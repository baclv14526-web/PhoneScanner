package com.dieppham.phonescanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnRepeat

class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val COLOR_FRAME   = Color.parseColor("#00E5FF")
    private val COLOR_SUCCESS = Color.parseColor("#69F0AE")
    private val COLOR_OVERLAY = Color.parseColor("#AA000000")

    private val dp = context.resources.displayMetrics.density

    // Khung rất rộng: margin 10dp, cao 200dp — đủ chứa 3-4 dòng số điện thoại
    private val frameMarginH  = 10 * dp
    private val frameHeight   = 200 * dp
    private val cornerLen     = 36 * dp
    private val cornerStroke  = 5f * dp
    private val frameRadius   = 14 * dp
    private val scanLineH     = 3f * dp

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = COLOR_FRAME
        alpha = 100
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = cornerStroke
        color = COLOR_FRAME
    }
    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Gradient + Matrix cho scan line: tạo 1 LẦN duy nhất (khi biết frameRect),
    // sau đó chỉ TRANSLATE shader theo scanLineY mỗi frame — tránh alloc
    // LinearGradient/int[]/float[] mới ~60 lần/giây trong onDraw(), vốn gây
    // GC pressure và có thể làm giật animation trên chip tầm trung như A23 5G.
    private val scanGradientMatrix = Matrix()
    private var scanGradient: LinearGradient? = null

    val frameRect = RectF()
    // Path "khoét lỗ": viền ngoài = toàn màn hình, viền trong = frameRect,
    // dùng FillType.EVEN_ODD -> vẽ 1 lần duy nhất ra đúng vùng tối xung
    // quanh khung, không cần PorterDuff.CLEAR + hardware layer như trước.
    private val overlayPath = Path()
    private var scanLineY   = 0f
    private var flashAlpha  = 0
    private var isSuccess   = false
    private var scanAnimator:  ValueAnimator? = null
    private var flashAnimator: ValueAnimator? = null

    // Không còn cần LAYER_TYPE_HARDWARE — kỹ thuật Path EVEN_ODD hoạt động
    // đúng trên layer mặc định (NONE), không cần ép GPU giữ offscreen buffer

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = h * 0.38f
        frameRect.set(frameMarginH, top, w - frameMarginH, top + frameHeight)

        // Build lại path khoét lỗ — chỉ cần làm khi kích thước/frameRect
        // đổi (onSizeChanged), KHÔNG build lại trong onDraw()
        overlayPath.reset()
        overlayPath.fillType = Path.FillType.EVEN_ODD
        overlayPath.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        overlayPath.addRoundRect(frameRect, frameRadius, frameRadius, Path.Direction.CW)

        // Gradient chỉ phụ thuộc frameRect.left/right (cố định) — tạo 1 lần
        // ở đây; vị trí dọc (scanLineY) sau này chỉ cần translate Matrix
        scanGradient = LinearGradient(
            frameRect.left, 0f, frameRect.right, scanLineH * 6,
            intArrayOf(Color.TRANSPARENT, COLOR_FRAME, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        scanLinePaint.shader = scanGradient

        startScanAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Phủ tối xung quanh khung — 1 draw call duy nhất nhờ Path
        // EVEN_ODD (thay vì draw full rect rồi CLEAR khoét lỗ như trước)
        overlayPaint.color = COLOR_OVERLAY
        canvas.drawPath(overlayPath, overlayPaint)

        // 2. Flash xanh lá khi nhận diện được
        if (flashAlpha > 0) {
            flashPaint.color  = COLOR_SUCCESS
            flashPaint.alpha  = flashAlpha
            canvas.drawRoundRect(frameRect, frameRadius, frameRadius, flashPaint)
        }

        // 3. Viền mỏng
        canvas.drawRoundRect(frameRect, frameRadius, frameRadius, framePaint)

        // 4. 4 góc nhấn mạnh
        cornerPaint.color = if (isSuccess) COLOR_SUCCESS else COLOR_FRAME
        drawCorners(canvas)

        // 5. Scan line chạy lên xuống — dùng lại gradient đã cache, chỉ
        // translate theo scanLineY thay vì tạo LinearGradient mới mỗi frame
        if (!isSuccess) {
            scanGradientMatrix.setTranslate(0f, scanLineY)
            scanGradient?.setLocalMatrix(scanGradientMatrix)
            canvas.drawRect(
                frameRect.left, scanLineY, frameRect.right, scanLineY + scanLineH,
                scanLinePaint
            )
        }
    }

    private fun drawCorners(c: Canvas) {
        val l = frameRect.left; val t = frameRect.top
        val r = frameRect.right; val b = frameRect.bottom
        val cl = cornerLen; val cr = frameRadius * 0.55f
        c.drawLine(l + cr, t, l + cr + cl, t, cornerPaint)
        c.drawLine(l, t + cr, l, t + cr + cl, cornerPaint)
        c.drawLine(r - cr - cl, t, r - cr, t, cornerPaint)
        c.drawLine(r, t + cr, r, t + cr + cl, cornerPaint)
        c.drawLine(l + cr, b, l + cr + cl, b, cornerPaint)
        c.drawLine(l, b - cr - cl, l, b - cr, cornerPaint)
        c.drawLine(r - cr - cl, b, r - cr, b, cornerPaint)
        c.drawLine(r, b - cr - cl, r, b - cr, cornerPaint)
    }

    private fun startScanAnimation() {
        scanAnimator?.cancel()
        scanAnimator = ValueAnimator.ofFloat(frameRect.top, frameRect.bottom - scanLineH).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator(1.2f)
            doOnRepeat { it.interpolator = DecelerateInterpolator(1.2f) }
            addUpdateListener { scanLineY = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun flashSuccess() {
        isSuccess = true
        scanAnimator?.pause()
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofInt(180, 0).apply {
            duration = 700; interpolator = LinearInterpolator()
            addUpdateListener { flashAlpha = it.animatedValue as Int; invalidate() }
            start()
        }
        invalidate()
    }

    fun resetToScanning() {
        isSuccess = false; flashAlpha = 0
        scanAnimator?.resume() ?: startScanAnimation()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scanAnimator?.cancel(); flashAnimator?.cancel()
    }
}
