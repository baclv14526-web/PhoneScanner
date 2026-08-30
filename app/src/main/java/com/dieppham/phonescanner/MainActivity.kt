package com.dieppham.phonescanner

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val phoneAnalyzer = PhoneNumberAnalyzer(requiredStableFrames = 3) { number ->
                runOnUiThread { showConfirmationCard(number) }
            }
            analyzer = phoneAnalyzer
            imageAnalysis.setAnalyzer(cameraExecutor, phoneAnalyzer)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
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
