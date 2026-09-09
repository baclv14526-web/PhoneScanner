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
    private val COLOR_CORNER  = Color.parseColor("#00E5FF")
    private val COLOR_SUCCESS = Color.parseColor("#69F0AE")
    private val COLOR_OVERLAY = Color.parseColor("#99000000")

    private val dp = context.resources.displayMetrics.density

    // Khung rộng hơn: margin 16dp (thay vì 28dp), cao 130dp (thay vì 100dp)
    // Đặt ở 0.42f thay vì 0.38f — trên A23 5G (20:9) camera preview bắt đầu
    // thấp hơn do status bar + title bar, nên cần dịch xuống
    private val frameMarginH  = 16 * dp
    private val frameHeight   = 130 * dp
    private val cornerLen     = 32 * dp
    private val cornerStroke  = 4.5f * dp
    private val frameRadius   = 16 * dp
    private val scanLineH     = 2.5f * dp

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
        alpha = 120
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = cornerStroke
        color = COLOR_CORNER
    }
    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val frameRect = RectF()
    private var scanLineY = 0f
    private var flashAlpha = 0
    private var isSuccess = false
    private var scanAnimator: ValueAnimator? = null
    private var flashAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 0.42f: dịch xuống so với 0.38f cũ để căn đúng giữa vùng camera
        // trên màn hình tỉ lệ 20:9 của Samsung A23 5G
        val top = h * 0.42f
        frameRect.set(frameMarginH, top, w - frameMarginH, top + frameHeight)
        startScanAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        overlayPaint.color = COLOR_OVERLAY
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        canvas.drawRoundRect(frameRect, frameRadius, frameRadius, clearPaint)

        if (flashAlpha > 0) {
            flashPaint.color = COLOR_SUCCESS
            flashPaint.alpha = flashAlpha
            canvas.drawRoundRect(frameRect, frameRadius, frameRadius, flashPaint)
        }

        canvas.drawRoundRect(frameRect, frameRadius, frameRadius, framePaint)

        cornerPaint.color = if (isSuccess) COLOR_SUCCESS else COLOR_CORNER
        drawCorners(canvas)

        if (!isSuccess && frameRect.height() > 0) {
            val lineGradient = LinearGradient(
                frameRect.left, scanLineY,
                frameRect.right, scanLineY + scanLineH * 4,
                intArrayOf(Color.TRANSPARENT, COLOR_FRAME, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            scanLinePaint.shader = lineGradient
            canvas.drawRect(
                frameRect.left, scanLineY,
                frameRect.right, scanLineY + scanLineH,
                scanLinePaint
            )
        }
    }

    private fun drawCorners(canvas: Canvas) {
        val l  = frameRect.left
        val t  = frameRect.top
        val r  = frameRect.right
        val b  = frameRect.bottom
        val cl = cornerLen
        val cr = frameRadius * 0.6f

        canvas.drawLine(l + cr, t, l + cr + cl, t, cornerPaint)
        canvas.drawLine(l, t + cr, l, t + cr + cl, cornerPaint)
        canvas.drawLine(r - cr - cl, t, r - cr, t, cornerPaint)
        canvas.drawLine(r, t + cr, r, t + cr + cl, cornerPaint)
        canvas.drawLine(l + cr, b, l + cr + cl, b, cornerPaint)
        canvas.drawLine(l, b - cr - cl, l, b - cr, cornerPaint)
        canvas.drawLine(r - cr - cl, b, r - cr, b, cornerPaint)
        canvas.drawLine(r, b - cr - cl, r, b - cr, cornerPaint)
    }

    private fun startScanAnimation() {
        scanAnimator?.cancel()
        scanAnimator = ValueAnimator.ofFloat(frameRect.top, frameRect.bottom - scanLineH).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator(1.2f)
            doOnRepeat { it.interpolator = DecelerateInterpolator(1.2f) }
            addUpdateListener { anim ->
                scanLineY = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun flashSuccess() {
        isSuccess = true
        scanAnimator?.pause()
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofInt(160, 0).apply {
            duration = 600
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                flashAlpha = anim.animatedValue as Int
                invalidate()
            }
            start()
        }
        invalidate()
    }

    fun resetToScanning() {
        isSuccess = false
        flashAlpha = 0
        scanAnimator?.resume() ?: startScanAnimation()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scanAnimator?.cancel()
        flashAnimator?.cancel()
    }
}
