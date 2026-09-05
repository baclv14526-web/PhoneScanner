package com.dieppham.phonescanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dieppham.phonescanner.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var dao: CallRecordDao
    private var analyzer: PhoneNumberAnalyzer? = null
    private var pendingNumberToCall: String? = null
    private var camera: Camera? = null

    // Số hiện đang hiển thị trên card xác nhận (dạng chuẩn, không format)
    private var confirmedNumber: String = ""

    // --- Đèn flash / ánh sáng ---
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var torchOn = false
    private var torchManualOverride = false   // true = người dùng tự bật/tắt thủ công
    private var lastLux = Float.MAX_VALUE

    companion object {
        // Ngưỡng lux: dưới đây thì coi là tối, bật torch tự động.
        // ~10 lux = phòng rất tối; ~50 lux = phòng đèn yếu; ~200 lux = đủ sáng trong nhà
        // Dùng 2 ngưỡng (hysteresis) để tránh nhấp nháy torch khi lux dao động sát ngưỡng.
        private const val LUX_DARK_THRESHOLD   = 50f   // tối hơn này → bật torch tự động
        private const val LUX_BRIGHT_THRESHOLD = 80f   // sáng hơn này → tắt torch tự động
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, getString(R.string.permission_camera_denied), Toast.LENGTH_LONG).show()
        }

    private val requestCallPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingNumberToCall?.let { doCall(it) }
            else Toast.makeText(this, getString(R.string.permission_call_denied), Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dao = AppDatabase.get(this).callRecordDao()

        setupDebugLabel()
        setupLightSensor()

        // Nút mở lịch sử (góc trên phải)
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Nút torch thủ công
        binding.btnTorch.setOnClickListener { toggleTorchManual() }

        binding.btnCall.setOnClickListener {
            requestCallWithPermission(confirmedNumber)
        }

        binding.btnRescan.setOnClickListener {
            hideConfirmationCard()
            binding.scanOverlay.resetToScanning()
            analyzer?.resume()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    // -------------------------------------------------------------------------
    // Debug label: tự ẩn sau 5 giây, bật lại bằng nút ⚙
    // -------------------------------------------------------------------------

    private val hideDebugRunnable = Runnable { hideDebug() }

    private fun setupDebugLabel() {
        // Tự ẩn sau 5 giây kể từ lúc mở app
        binding.tvDebug.postDelayed(hideDebugRunnable, 5_000)

        // Bấm × để ẩn ngay lập tức
        binding.btnDebugToggle.setOnClickListener {
            binding.tvDebug.removeCallbacks(hideDebugRunnable)
            hideDebug()
        }

        // Bấm ⚙ để bật lại (ẩn lại sau 10 giây)
        binding.btnDebugShow.setOnClickListener {
            showDebug()
            binding.tvDebug.postDelayed(hideDebugRunnable, 10_000)
        }
    }

    private fun hideDebug() {
        binding.tvDebug.animate().alpha(0f).setDuration(400)
            .withEndAction { binding.tvDebug.visibility = View.GONE }
            .start()
        binding.btnDebugToggle.animate().alpha(0f).setDuration(400)
            .withEndAction { binding.btnDebugToggle.visibility = View.GONE }
            .start()
        binding.btnDebugShow.visibility = View.VISIBLE
        binding.btnDebugShow.alpha = 0f
        binding.btnDebugShow.animate().alpha(1f).setDuration(400).start()
    }

    private fun showDebug() {
        binding.tvDebug.visibility = View.VISIBLE
        binding.tvDebug.animate().alpha(1f).setDuration(300).start()
        binding.btnDebugToggle.visibility = View.VISIBLE
        binding.btnDebugToggle.animate().alpha(1f).setDuration(300).start()
        binding.btnDebugShow.animate().alpha(0f).setDuration(300)
            .withEndAction { binding.btnDebugShow.visibility = View.GONE }
            .start()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                ).build()
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val phoneAnalyzer = PhoneNumberAnalyzer(
                requiredStableFrames = 2,
                onStableNumberDetected = { number ->
                    runOnUiThread {
                        binding.scanOverlay.flashSuccess()
                        showConfirmationCard(number)
                    }
                },
                onDebugInfo = { rawText, error ->
                    runOnUiThread { updateDebugLabel(rawText, error) }
                }
            )
            analyzer = phoneAnalyzer
            imageAnalysis.setAnalyzer(cameraExecutor, phoneAnalyzer)

            try {
                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
                camera = boundCamera
                binding.scanOverlay.post { focusOnScanZone(boundCamera) }
                binding.previewView.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val pt = binding.previewView.meteringPointFactory
                            .createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                        view.performClick()
                    }
                    true
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focusOnScanZone(camera: Camera) {
        val frame = binding.scanOverlay.frameRect
        if (frame.isEmpty) return
        val pt = binding.previewView.meteringPointFactory
            .createPoint(frame.centerX(), frame.centerY())
        val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF)
            .disableAutoCancel().build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    private fun updateDebugLabel(rawText: String, error: String?) {
        binding.tvDebug.text = when {
            error != null     -> "LỖI: $error"
            rawText.isBlank() -> "OCR: (không đọc được chữ)"
            else              -> "OCR:\n${rawText.take(180)}"
        }
    }

    private fun showConfirmationCard(number: String) {
        confirmedNumber = number
        binding.tvDetectedNumber.text = PhoneNumberExtractor.formatForDisplay(number)
        binding.tvHint.visibility = View.GONE
        binding.cardConfirm.visibility = View.VISIBLE
        binding.cardConfirm.translationY = 80f * resources.displayMetrics.density
        binding.cardConfirm.alpha = 0f
        binding.cardConfirm.animate()
            .translationY(0f).alpha(1f)
            .setDuration(320).setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideConfirmationCard() {
        binding.cardConfirm.animate()
            .translationY(80f * resources.displayMetrics.density).alpha(0f)
            .setDuration(200).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.cardConfirm.visibility = View.GONE
                binding.tvHint.visibility = View.VISIBLE
            }.start()
    }

    private fun requestCallWithPermission(number: String) {
        if (number.isBlank()) return
        pendingNumberToCall = number
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) doCall(number)
        else requestCallPermission.launch(Manifest.permission.CALL_PHONE)
    }

    private fun doCall(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
            lifecycleScope.launch {
                dao.insert(
                    CallRecord(
                        phoneNumber   = number,
                        displayNumber = PhoneNumberExtractor.formatForDisplay(number),
                        timestamp     = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.permission_call_denied), Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------
    // Đèn flash tự động theo độ sáng môi trường
    // -------------------------------------------------------------------------

    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val lux = event.values[0]
            lastLux = lux
            if (torchManualOverride) return   // người dùng đang tự điều khiển, không can thiệp

            when {
                lux < LUX_DARK_THRESHOLD   && !torchOn -> setTorch(true,  auto = true)
                lux > LUX_BRIGHT_THRESHOLD && torchOn  -> setTorch(false, auto = true)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun setupLightSensor() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        if (lightSensor == null) {
            // Thiết bị không có cảm biến ánh sáng — chỉ dùng nút thủ công
            binding.btnTorch.visibility = View.VISIBLE
            updateTorchButton()
        }
        // Nếu có sensor, nút torch vẫn hiện nhưng nhỏ hơn (dùng để override)
        binding.btnTorch.visibility = View.VISIBLE
        updateTorchButton()
    }

    private fun setTorch(on: Boolean, auto: Boolean = false) {
        if (torchOn == on) return
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
        if (!auto) {
            // Khi người dùng bấm thủ công: set override, tự tắt override sau 60s
            torchManualOverride = true
            binding.btnTorch.removeCallbacks(clearOverrideRunnable)
            binding.btnTorch.postDelayed(clearOverrideRunnable, 60_000)
        }
        updateTorchButton()
    }

    private val clearOverrideRunnable = Runnable {
        // Sau 60 giây tự trả quyền điều khiển về cho cảm biến ánh sáng
        torchManualOverride = false
    }

    private fun toggleTorchManual() {
        setTorch(!torchOn, auto = false)
    }

    private fun updateTorchButton() {
        binding.btnTorch.text = if (torchOn) "🔦" else "🔦"
        binding.btnTorch.alpha = if (torchOn) 1f else 0.45f
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let {
            sensorManager.registerListener(
                lightSensorListener, it,
                SensorManager.SENSOR_DELAY_NORMAL   // ~5 lần/giây, đủ dùng, không tốn pin
            )
        }
    }

    override fun onPause() {
        super.onPause()
        // Tắt torch và dừng cảm biến khi app ra nền
        sensorManager.unregisterListener(lightSensorListener)
        if (torchOn) setTorch(false, auto = true)
        torchManualOverride = false
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.tvDebug.removeCallbacks(hideDebugRunnable)
        binding.btnTorch.removeCallbacks(clearOverrideRunnable)
        cameraExecutor.shutdown()
    }

    init {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }
}
