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

    private val candidateCounts = mutableMapOf<String, Int>()
    private var paused = false

    // Cho phép MainActivity tắt việc build debugText khi label debug đã ẩn
    // (tự ẩn sau 5s) — tránh String.format() + buildString() chạy vô ích
    // mỗi frame suốt phần còn lại của phiên quét khi không ai xem nó.
    var debugEnabled: Boolean = true

    // Vùng lọc kết quả — khớp ScannerOverlayView top=0.38, height=200dp
    // Dùng tỉ lệ rộng hơn để không miss khi cầm hơi nghiêng
    private val ZONE_TOP    = 0.28f
    private val ZONE_BOTTOM = 0.78f

    fun pause()  { paused = true }
    fun resume() { paused = false; candidateCounts.clear() }

    /**
     * Giải phóng TextRecognizer client (native resources).
     * BẮT BUỘC gọi khi thay thế analyzer này bằng 1 instance mới (mỗi lần
     * startCamera() chạy lại), nếu không sẽ leak dần qua mỗi lần "Quét lại".
     */
    fun close() {
        recognizer.close()
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

        // InputImage.fromMediaImage là cách duy nhất đúng cho CameraX —
        // ML Kit tự xử lý YUV_420_888, pixelStride, rowStride và rotation.
        // KHÔNG tự crop YUV thủ công vì pixelStride/rowStride khác nhau
        // theo thiết bị (Samsung A23: UV pixelStride=2, interleaved).
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        val imageHeight = imageProxy.height

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val candidates = PhoneNumberExtractor
                    .extractCandidatesWithPosition(visionText, imageHeight)
                    .filter { it.centerYRatio in ZONE_TOP..ZONE_BOTTOM }

                if (debugEnabled) {
                    val debugText = buildString {
                        if (visionText.text.isNotBlank())
                            append(visionText.text.take(150))
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
                onDebugInfo?.invoke("", "Lỗi OCR: ${e.javaClass.simpleName}: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleCandidates(found: List<String>) {
        // QUAN TRỌNG: dedupe trước khi xử lý. Nếu cùng 1 số xuất hiện 2 lần
        // trong CÙNG 1 khung hình (in trùng 2 dòng trên card, hoặc OCR
        // detect trùng do bounding box chồng lấn), vòng lặp bên dưới sẽ
        // tăng candidateCounts[number] TỚI 2 LẦN chỉ trong 1 lần gọi hàm
        // này — phá vỡ mục đích "yêu cầu N khung hình liên tiếp ổn định"
        // (có thể đủ ngưỡng chỉ từ 1 frame duy nhất), và khiến `ready`
        // chứa cùng 1 số 2 lần → MainActivity hiểu nhầm là có 2 số khác
        // nhau, hiện dialog "Chọn số điện thoại" với 2 dòng trùng lặp.
        val uniqueFound = found.distinct()

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
