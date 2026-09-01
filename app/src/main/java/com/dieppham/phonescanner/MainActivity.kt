package com.dieppham.phonescanner

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.MotionEvent
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
import com.dieppham.phonescanner.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var analyzer: PhoneNumberAnalyzer? = null
    private var pendingNumberToCall: String? = null
    private var camera: Camera? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.permission_camera_denied), Toast.LENGTH_LONG).show()
            }
        }

    private val requestCallPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingNumberToCall?.let { placeCall(it) }
            } else {
                Toast.makeText(this, getString(R.string.permission_call_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnCall.setOnClickListener {
            binding.tvDetectedNumber.text.toString()
                .filter { it.isDigit() }
                .let { number -> requestCallWithPermission(number) }
        }

        binding.btnRescan.setOnClickListener {
            hideConfirmationCard()
            analyzer?.resume()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // Truoc day khong chi dinh do phan giai -> CameraX tu chon muc
            // kha thap, du cho preview nhung khong du net de OCR doc chu
            // nho. Ep do phan giai cao hon han (~1280x720) de tang do net
            // khi doc so dien thoai, danh doi mot chut CPU/pin.
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val phoneAnalyzer = PhoneNumberAnalyzer(
                requiredStableFrames = 3,
                onStableNumberDetected = { number ->
                    runOnUiThread { showConfirmationCard(number) }
                },
                onDebugInfo = { rawText, error ->
                    runOnUiThread { updateDebugLabel(rawText, error) }
                }
            )
            analyzer = phoneAnalyzer
            imageAnalysis.setAnalyzer(cameraExecutor, phoneAnalyzer)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                camera = boundCamera

                // Ep lay net lien tuc vao dung giua khung huong dan mau
                // xanh, thay vi de camera tu doan lay net trung binh ca
                // khung hinh - day la nguyen nhan chinh khien phai nghieng
                // may moi vo tinh lay net trung so dien thoai.
                binding.scanFrame.post {
                    focusOnScanFrame(boundCamera)
                }

                // Cham vao man hinh de tu lay net lai bat cu luc nao, phong
                // khi lay net tu dong chua bat trung.
                binding.previewView.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val point = binding.previewView.meteringPointFactory
                            .createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
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

    private fun focusOnScanFrame(camera: Camera) {
        val cx = binding.scanFrame.x + binding.scanFrame.width / 2f
        val cy = binding.scanFrame.y + binding.scanFrame.height / 2f
        val point = binding.previewView.meteringPointFactory.createPoint(cx, cy)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .disableAutoCancel() // giu lay net lien tuc, khong tu huy sau vai giay
            .build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    private fun updateDebugLabel(rawText: String, error: String?) {
        binding.tvDebug.text = when {
            error != null -> "LỖI: $error"
            rawText.isBlank() -> "OCR: (không đọc được chữ nào trong khung hình)"
            else -> "OCR đọc được:\n${rawText.take(200)}"
        }
    }

    private fun showConfirmationCard(number: String) {
        binding.tvDetectedNumber.text = PhoneNumberExtractor.formatForDisplay(number)
        binding.cardConfirm.visibility = android.view.View.VISIBLE
        binding.tvHint.visibility = android.view.View.GONE
    }

    private fun hideConfirmationCard() {
        binding.cardConfirm.visibility = android.view.View.GONE
        binding.tvHint.visibility = android.view.View.VISIBLE
    }

    private fun requestCallWithPermission(number: String) {
        pendingNumberToCall = number
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            placeCall(number)
        } else {
            requestCallPermission.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun placeCall(number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        try {
            startActivity(intent)
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.permission_call_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
