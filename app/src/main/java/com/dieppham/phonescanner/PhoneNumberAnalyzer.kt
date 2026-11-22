package com.dieppham.phonescanner

import android.graphics.RectF
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

    // Vùng quét = đúng khung xanh trên màn hình. Lọc kết quả OCR thay vì cắt ảnh để giữ nguyên
    // đường YUV -> ML Kit chuẩn của InputImage.fromMediaImage.
    //
    // Trước đây vùng lọc là dải cố định 30%-75% chiều cao ảnh, rộng hơn nhiều so với khung xanh
    // (~30%-53%), nên số nằm ngoài khung vẫn bị quét. Giờ khung (toạ độ View) được quy đổi sang
    // toạ độ ảnh tại mỗi frame, nên số được quét chắc chắn đang nằm trong vùng xanh.
    private class ScanZone(
        val viewW: Float, val viewH: Float,
        val left: Float, val top: Float, val right: Float, val bottom: Float
    )

    @Volatile private var zone: ScanZone? = null

    // Dự phòng khi chưa biết khung (chưa bố cục xong): dải cũ theo tỉ lệ chiều cao ảnh
    private val fallbackTop    = 0.30f
    private val fallbackBottom = 0.75f

    /** Đặt vùng quét: kích thước View xem camera + khung xanh (px, toạ độ View). */
    fun setScanZone(viewW: Int, viewH: Int, frame: RectF) {
        if (viewW <= 0 || viewH <= 0 || frame.isEmpty) return
        zone = ScanZone(
            viewW.toFloat(), viewH.toFloat(),
            frame.left, frame.top, frame.right, frame.bottom
        )
    }

    /**
     * Đổi khung từ toạ độ View sang tỉ lệ 0..1 trong ảnh phân tích (đã xoay thẳng đứng).
     * PreviewView dùng FILL_CENTER: ảnh được phóng cho phủ kín View rồi cắt phần thừa ở giữa
     * (cắt hai bên khi View "cao hơn" ảnh, như điện thoại 20:9) nên:
     *   scale = max(viewW/imgW, viewH/imgH);  phần bị cắt mỗi phía = (img*scale - view)/2
     */
    private fun zoneInImage(imgW: Int, imgH: Int): RectF? {
        val z = zone ?: return null
        if (imgW <= 0 || imgH <= 0) return null
        val scale = maxOf(z.viewW / imgW, z.viewH / imgH)
        val offX = (imgW * scale - z.viewW) / 2f
        val offY = (imgH * scale - z.viewH) / 2f
        return RectF(
            (z.left + offX) / scale / imgW,
            (z.top + offY) / scale / imgH,
            (z.right + offX) / scale / imgW,
            (z.bottom + offY) / scale / imgH
        )
    }

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
        // Kích thước ảnh sau khi xoay — dùng để tính tỉ lệ vị trí dòng chữ
        val rotated = imageProxy.imageInfo.rotationDegrees % 180 != 0
        val imageWidth  = if (rotated) imageProxy.height else imageProxy.width
        val imageHeight = if (rotated) imageProxy.width else imageProxy.height
        val zoneRect = zoneInImage(imageWidth, imageHeight)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val candidates = PhoneNumberExtractor
                    .extractCandidatesWithPosition(visionText, imageHeight, imageWidth)
                    .filter { c ->
                        if (zoneRect != null) {
                            c.centerYRatio in zoneRect.top..zoneRect.bottom &&
                                c.centerXRatio in zoneRect.left..zoneRect.right
                        } else {
                            c.centerYRatio in fallbackTop..fallbackBottom
                        }
                    }

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
