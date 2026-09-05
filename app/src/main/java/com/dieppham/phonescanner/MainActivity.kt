package com.dieppham.phonescanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

        // Nút mở lịch sử (góc trên phải)
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

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
            // Lưu lịch sử ngay khi bấm Gọi
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    init {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }
}
