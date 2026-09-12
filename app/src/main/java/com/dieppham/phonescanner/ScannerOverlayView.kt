package com.dieppham.phonescanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
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

    val frameRect = RectF()
    private var scanLineY   = 0f
    private var flashAlpha  = 0
    private var isSuccess   = false
    private var scanAnimator:  ValueAnimator? = null
    private var flashAnimator: ValueAnimator? = null

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Đặt khung ở 40%-giữa màn hình — căn đúng trên A23 5G 20:9
        val top = h * 0.38f
        frameRect.set(frameMarginH, top, w - frameMarginH, top + frameHeight)
        startScanAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        // 1. Phủ tối toàn màn hình
        overlayPaint.color = COLOR_OVERLAY
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // 2. Khoét lỗ trong suốt vùng khung
        canvas.drawRoundRect(frameRect, frameRadius, frameRadius, clearPaint)

        // 3. Flash xanh lá khi nhận diện được
        if (flashAlpha > 0) {
            flashPaint.color  = COLOR_SUCCESS
            flashPaint.alpha  = flashAlpha
            canvas.drawRoundRect(frameRect, frameRadius, frameRadius, flashPaint)
        }

        // 4. Viền mỏng
        canvas.drawRoundRect(frameRect, frameRadius, frameRadius, framePaint)

        // 5. 4 góc nhấn mạnh
        cornerPaint.color = if (isSuccess) COLOR_SUCCESS else COLOR_FRAME
        drawCorners(canvas)

        // 6. Scan line chạy lên xuống
        if (!isSuccess) {
            val grad = LinearGradient(
                frameRect.left, scanLineY, frameRect.right, scanLineY + scanLineH * 6,
                intArrayOf(Color.TRANSPARENT, COLOR_FRAME, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            scanLinePaint.shader = grad
            canvas.drawRect(frameRect.left, scanLineY, frameRect.right, scanLineY + scanLineH, scanLinePaint)
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
