package com.dieppham.phonescanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class PhoneNumberAnalyzer(
    private val requiredStableFrames: Int = 3,
    private val onStableNumberDetected: (String) -> Unit,
    private val onDebugInfo: ((rawText: String, error: String?) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastCandidate: String? = null
    private var stableCount: Int = 0
    private var paused = false

    fun pause() { paused = true }

    fun resume() {
        paused = false
        lastCandidate = null
        stableCount = 0
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (paused) { imageProxy.close(); return }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            onDebugInfo?.invoke("", "imageProxy.image = null")
            imageProxy.close()
            return
        }

        val imageHeight = imageProxy.height
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                // --- THAY ĐỔI CHÍNH ---
                // Trước: lấy số đầu tiên trong toàn bộ text -> luôn bị ghim
                //        vào số cũ dù bạn đã di khung xanh sang số khác.
                // Sau:   lấy TẤT CẢ số tìm thấy kèm vị trí dọc, rồi chọn
                //        số có centerY gần giữa ảnh nhất (= gần khung xanh
                //        nhất) -> tự động đổi sang số mới khi bạn di máy.
                val candidates = PhoneNumberExtractor.extractCandidatesWithPosition(
                    visionText, imageHeight
                )
                val picked = PhoneNumberExtractor.pickClosestToCenter(candidates)

                // Debug: hiện tất cả số tìm thấy + số nào được chọn
                val debugText = buildString {
                    append(visionText.text.take(120))
                    if (candidates.isNotEmpty()) {
                        append("\n---")
                        candidates.forEach { c ->
                            val marker = if (c.number == picked) " ✓" else ""
                            append("\n${c.number} (y=${String.format("%.2f", c.centerYRatio)})$marker")
                        }
                    }
                }
                onDebugInfo?.invoke(debugText, null)

                handleCandidate(picked)
            }
            .addOnFailureListener { e ->
                onDebugInfo?.invoke("", "Lỗi OCR: ${e.javaClass.simpleName}: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleCandidate(found: String?) {
        // Nếu số được chọn đổi khác (người dùng di khung sang số mới)
        // -> reset counter ngay lập tức, không giữ lại count của số cũ.
        if (found != lastCandidate) {
            lastCandidate = found
            stableCount = if (found != null) 1 else 0
            return
        }
        // Cùng số: tăng count
        if (found != null) {
            stableCount++
            if (stableCount >= requiredStableFrames) {
                paused = true
                onStableNumberDetected(found)
            }
        }
    }
}
