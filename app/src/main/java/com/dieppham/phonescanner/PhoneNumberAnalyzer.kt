package com.dieppham.phonescanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class PhoneNumberAnalyzer(
    private val requiredStableFrames: Int = 2,
    private val onStableNumberDetected: (List<String>) -> Unit,
    private val onDebugInfo: ((rawText: String, error: String?) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Mỗi số được đếm riêng — reset nếu không còn xuất hiện trong frame
    private val candidateCounts = mutableMapOf<String, Int>()
    @Volatile private var paused = false

    // Chỉ dựng chuỗi debug khi nhãn debug đang hiển thị (tránh tốn CPU mỗi frame)
    @Volatile var debugEnabled: Boolean = true

    // Vùng lọc kết quả OCR theo tỉ lệ chiều cao ảnh — khớp với ScannerOverlayView
    // (top = 0.38, cao 200dp). Lọc kết quả thay vì cắt ảnh để giữ nguyên đường
    // YUV -> ML Kit chuẩn của InputImage.fromMediaImage.
    private val zoneTop    = 0.30f
    private val zoneBottom = 0.75f

    fun pause()  { paused = true }
    fun resume() { paused = false; candidateCounts.clear() }
    fun close()  { recognizer.close() }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (paused) { imageProxy.close(); return }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            if (debugEnabled) onDebugInfo?.invoke("", "imageProxy.image = null")
            imageProxy.close()
            return
        }

        // ML Kit tự xử lý YUV + rotation: cách đúng và ổn định trên mọi máy
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        // Chiều cao ảnh sau khi xoay — dùng để tính tỉ lệ vị trí dòng chữ
        val rotated = imageProxy.imageInfo.rotationDegrees % 180 != 0
        val imageHeight = if (rotated) imageProxy.width else imageProxy.height

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val candidates = PhoneNumberExtractor
                    .extractCandidatesWithPosition(visionText, imageHeight)
                    .filter { it.centerYRatio in zoneTop..zoneBottom }

                if (debugEnabled) {
                    val debugText = buildString {
                        if (visionText.text.isNotBlank()) append(visionText.text.take(150))
                        if (candidates.isNotEmpty()) {
                            append("\n---")
                            candidates.forEach {
                                append("\n${it.number} (y=${String.format("%.2f", it.centerYRatio)})")
                            }
                        }
                    }
                    onDebugInfo?.invoke(debugText, null)
                }

                handleCandidates(candidates.map { it.number })
            }
            .addOnFailureListener { e ->
                if (debugEnabled) {
                    onDebugInfo?.invoke("", "Lỗi OCR: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleCandidates(found: List<String>) {
        // Một số có thể bị phát hiện 2 lần trong cùng 1 frame -> chỉ đếm 1 lần
        val uniqueFound = found.distinct()

        // Số không còn trong frame này thì reset bộ đếm
        candidateCounts.keys.filter { it !in uniqueFound }.forEach { candidateCounts.remove(it) }

        val ready = mutableListOf<String>()
        for (number in uniqueFound) {
            val count = (candidateCounts[number] ?: 0) + 1
            candidateCounts[number] = count
            if (count >= requiredStableFrames) ready += number
        }

        if (ready.isNotEmpty()) {
            paused = true
            onStableNumberDetected(ready)
        }
    }
}
