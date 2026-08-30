package com.dieppham.phonescanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Phân tích từng khung hình camera bằng ML Kit OCR, tìm số điện thoại VN.
 *
 * Để tránh gọi nhầm do OCR đọc sai 1 khung hình thoáng qua, chỉ báo
 * "đã phát hiện" khi CÙNG MỘT số xuất hiện ổn định trong [requiredStableFrames]
 * khung hình liên tiếp.
 */
class PhoneNumberAnalyzer(
    private val requiredStableFrames: Int = 3,
    private val onStableNumberDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastCandidate: String? = null
    private var stableCount: Int = 0
    private var paused = false

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
        lastCandidate = null
        stableCount = 0
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (paused) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val found = PhoneNumberExtractor.extractFirstValidNumber(visionText.text)
                handleCandidate(found)
            }
            .addOnCompleteListener {
                // Luôn đóng imageProxy dù thành công hay lỗi, nếu không camera sẽ bị đơ
                imageProxy.close()
            }
    }

    private fun handleCandidate(found: String?) {
        if (found == null) {
            lastCandidate = null
            stableCount = 0
            return
        }

        if (found == lastCandidate) {
            stableCount++
        } else {
            lastCandidate = found
            stableCount = 1
        }

        if (stableCount >= requiredStableFrames) {
            paused = true // tạm dừng phân tích cho tới khi người dùng xác nhận/quét lại
            onStableNumberDetected(found)
        }
    }
}
