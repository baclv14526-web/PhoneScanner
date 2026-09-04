package com.dieppham.phonescanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class PhoneNumberAnalyzer(
    // Giảm từ 3 xuống 2: phát hiện nhanh hơn, vẫn đủ tránh nhận nhầm
    private val requiredStableFrames: Int = 2,
    private val onStableNumberDetected: (String) -> Unit,
    private val onDebugInfo: ((rawText: String, error: String?) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastCandidate: String? = null
    private var stableCount: Int = 0
    private var paused = false

    // Tỉ lệ vùng khung quét so với chiều cao ảnh (khớp với ScannerOverlayView)
    // ScannerOverlayView đặt top = height * 0.38, frameHeight = 100dp / screenHeight
    // Ta crop rộng hơn một chút (±10%) để không bị cắt sát quá khi cầm hơi nghiêng
    private val CROP_TOP_RATIO    = 0.28f
    private val CROP_BOTTOM_RATIO = 0.62f

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
        val imageWidth  = imageProxy.width

        // --- CROP: chỉ đưa vùng khung quét vào ML Kit ---
        // Ít pixel hơn = OCR nhanh hơn + không bị nhiễu bởi chữ nằm
        // ngoài khung (tiêu đề trang, chú thích, v.v.)
        val cropTop    = (imageHeight * CROP_TOP_RATIO).toInt().coerceIn(0, imageHeight)
        val cropBottom = (imageHeight * CROP_BOTTOM_RATIO).toInt().coerceIn(0, imageHeight)
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )

        // ML Kit không hỗ trợ crop trực tiếp từ MediaImage, nhưng nó
        // trả về boundingBox theo tọa độ ảnh gốc — ta vẫn dùng toàn ảnh
        // cho OCR, nhưng khi chọn số dùng CROP_TOP/BOTTOM_RATIO để lọc
        // chỉ những dòng nằm trong vùng khung quét (thay vì dùng 0.5 cứng)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                // Trung tâm vùng crop theo tỉ lệ trong ảnh gốc
                val zoneCenterRatio = (CROP_TOP_RATIO + CROP_BOTTOM_RATIO) / 2f

                val candidates = PhoneNumberExtractor.extractCandidatesWithPosition(
                    visionText, imageHeight
                ).filter { c ->
                    // Chỉ giữ số nằm trong vùng khung quét (±biên)
                    c.centerYRatio in CROP_TOP_RATIO..CROP_BOTTOM_RATIO
                }

                val picked = if (candidates.isEmpty()) null
                else candidates.minByOrNull {
                    Math.abs(it.centerYRatio - zoneCenterRatio)
                }?.number

                val debugText = buildString {
                    if (visionText.text.isNotBlank())
                        append(visionText.text.take(100))
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
        if (found != lastCandidate) {
            lastCandidate = found
            stableCount = if (found != null) 1 else 0
            return
        }
        if (found != null) {
            stableCount++
            if (stableCount >= requiredStableFrames) {
                paused = true
                onStableNumberDetected(found)
            }
        }
    }
}
