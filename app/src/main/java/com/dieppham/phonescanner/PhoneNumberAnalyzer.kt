package com.dieppham.phonescanner

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream

class PhoneNumberAnalyzer(
    // 1 frame ổn định là đủ khi đã crop đúng vùng — nhanh gấp đôi so với 2 frame
    private val requiredStableFrames: Int = 1,
    private val onStableNumberDetected: (List<String>) -> Unit,  // trả về DANH SÁCH số
    private val onDebugInfo: ((rawText: String, error: String?) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Map<number, stableCount> — theo dõi từng số riêng, không chỉ 1 số
    private val candidateCounts = mutableMapOf<String, Int>()
    private var paused = false

    // Khớp với ScannerOverlayView: top=0.38, frameHeight=200dp
    // Biên ±0.05 để không cắt sát khi cầm hơi nghiêng
    private val CROP_TOP_RATIO    = 0.33f
    private val CROP_BOTTOM_RATIO = 0.72f

    fun pause()  { paused = true }
    fun resume() { paused = false; candidateCounts.clear() }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (paused) { imageProxy.close(); return }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            onDebugInfo?.invoke("", "imageProxy.image = null")
            imageProxy.close()
            return
        }

        val imgH    = imageProxy.height
        val imgW    = imageProxy.width
        val rotDeg  = imageProxy.imageInfo.rotationDegrees

        // Crop Bitmap thực sự trước khi đưa vào ML Kit:
        // Ít pixel hơn = OCR chạy nhanh hơn đáng kể (không phải filter sau)
        val cropBitmap = try {
            cropToBitmap(imageProxy, imgW, imgH, rotDeg)
        } catch (_: Exception) { null }

        val inputImage = if (cropBitmap != null) {
            InputImage.fromBitmap(cropBitmap, 0)   // đã rotate khi crop
        } else {
            // Fallback: dùng toàn ảnh nếu crop thất bại
            InputImage.fromMediaImage(mediaImage, rotDeg)
        }

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                // Chiều cao ảnh đưa vào recognizer (sau crop nếu có)
                val analysisH = cropBitmap?.height ?: imgH

                val candidates = PhoneNumberExtractor.extractCandidatesWithPosition(
                    visionText, analysisH
                ).let { list ->
                    if (cropBitmap != null) list   // đã crop rồi, không cần lọc thêm
                    else list.filter { c ->        // fallback: lọc theo vùng
                        c.centerYRatio in CROP_TOP_RATIO..CROP_BOTTOM_RATIO
                    }
                }

                val debugText = buildString {
                    if (visionText.text.isNotBlank()) append(visionText.text.take(120))
                    if (candidates.isNotEmpty()) {
                        append("\n---")
                        candidates.forEach { append("\n${it.number} (y=${String.format("%.2f", it.centerYRatio)})") }
                    }
                }
                onDebugInfo?.invoke(debugText, null)

                handleCandidates(candidates.map { it.number })
            }
            .addOnFailureListener { e ->
                onDebugInfo?.invoke("", "Lỗi OCR: ${e.javaClass.simpleName}: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * Crop vùng khung quét từ YUV ImageProxy → Bitmap RGB đã rotate đúng chiều.
     * Chạy trên luồng phân tích (không phải main thread) nên an toàn.
     */
    private fun cropToBitmap(proxy: ImageProxy, w: Int, h: Int, rotDeg: Int): Bitmap? {
        val yBuffer = proxy.planes[0].buffer
        val uBuffer = proxy.planes[1].buffer
        val vBuffer = proxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // Xác định vùng crop TRƯỚC KHI rotate (ảnh YUV chưa rotate)
        // rotDeg=90 trên A23: chiều dọc màn hình = chiều ngang ảnh
        val (cropL, cropT, cropR, cropB) = if (rotDeg == 90 || rotDeg == 270) {
            // Ảnh nằm ngang: chiều cao màn hình ánh xạ sang chiều rộng ảnh
            val cLeft  = (w * CROP_TOP_RATIO).toInt().coerceIn(0, w)
            val cRight = (w * CROP_BOTTOM_RATIO).toInt().coerceIn(0, w)
            listOf(cLeft, 0, cRight, h)
        } else {
            val cTop    = (h * CROP_TOP_RATIO).toInt().coerceIn(0, h)
            val cBottom = (h * CROP_BOTTOM_RATIO).toInt().coerceIn(0, h)
            listOf(0, cTop, w, cBottom)
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(cropL, cropT, cropR, cropB), 90, out)
        val jpegBytes = out.toByteArray()

        val raw = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return null

        // Rotate Bitmap về đúng chiều dọc
        return if (rotDeg != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotDeg.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                .also { if (it != raw) raw.recycle() }
        } else raw
    }

    /**
     * Xử lý danh sách số tìm được trong frame hiện tại.
     * Mỗi số được đếm riêng — số nào đủ stable thì báo ngay,
     * không cần chờ tất cả cùng stable.
     */
    private fun handleCandidates(found: List<String>) {
        // Xóa số không còn xuất hiện trong frame này
        val toRemove = candidateCounts.keys.filter { it !in found }
        toRemove.forEach { candidateCounts.remove(it) }

        val ready = mutableListOf<String>()
        for (number in found) {
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
