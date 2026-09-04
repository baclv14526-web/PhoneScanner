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

/**
 * Custom view vẽ toàn bộ overlay lên camera preview:
 *  - Nền tối mờ xung quanh (vignette), vùng khung quét trong suốt
 *  - Khung bo tròn với 4 góc nhấn mạnh kiểu máy quét chuyên nghiệp
 *  - Đường quét (scan line) chạy lên xuống liên tục
 *  - Flash xanh khi phát hiện số thành công
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // --- Màu sắc ---
    private val COLOR_FRAME   = Color.parseColor("#00E5FF")   // cyan sáng
    private val COLOR_CORNER  = Color.parseColor("#00E5FF")
    private val COLOR_SUCCESS = Color.parseColor("#69F0AE")   // xanh lá flash
    private val COLOR_OVERLAY = Color.parseColor("#99000000") // nền tối mờ

    // --- Kích thước tính theo dp ---
    private val dp = context.resources.displayMetrics.density
    private val frameMarginH = 28 * dp
    private val frameHeight  = 100 * dp
    private val cornerLen    = 28 * dp
    private val cornerStroke = 4 * dp
    private val frameRadius  = 18 * dp
    private val scanLineH    = 2.5f * dp

    // --- Paint objects ---
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

    // --- State ---
    val frameRect = RectF()   // expose ra ngoài để MainActivity tính focus point
    private var scanLineY = 0f
    private var flashAlpha = 0
    private var isSuccess = false

    // --- Animators ---
    private var scanAnimator: ValueAnimator? = null
    private var flashAnimator: ValueAnimator? = null

    init {
        // Hardware layer: cho phép CLEAR xfermode hoạt động đúng
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = h * 0.38f
        frameRect.set(frameMarginH, top, w - frameMarginH, top + frameHeight)
        startScanAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Phủ nền tối toàn màn hình
        overlayPaint.color = COLOR_OVERLAY
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // 2. Khoét lỗ trong suốt đúng vùng khung quét
        canvas.drawRoundRect(frameRect, frameRadius, frameRadius, clearPaint)

        // 3. Flash xanh khi nhận diện thành công
        if (flashAlpha > 0) {
            flashPaint.color = COLOR_SUCCESS
            flashPaint.alpha = flashAlpha
            canvas.drawRoundRect(frameRect, frameRadius, frameRadius, flashPaint)
        }

        // 4. Đường viền mỏng bao quanh khung
        canvas.drawRoundRect(frameRect, frameRadius, frameRadius, framePaint)

        // 5. 4 góc nhấn mạnh (kiểu máy quét QR chuyên nghiệp)
        val c = cornerPaint.color
        cornerPaint.color = if (isSuccess) COLOR_SUCCESS else COLOR_CORNER
        drawCorners(canvas)
        cornerPaint.color = c

        // 6. Đường quét chạy nội bộ khung (chỉ hiện khi chưa nhận được số)
        if (!isSuccess && frameRect.height() > 0) {
            val lineGradient = LinearGradient(
                frameRect.left, scanLineY,
                frameRect.right, scanLineY + scanLineH * 4,
                intArrayOf(Color.TRANSPARENT, COLOR_FRAME, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            scanLinePaint.shader = lineGradient
            canvas.drawRect(frameRect.left, scanLineY, frameRect.right, scanLineY + scanLineH, scanLinePaint)
        }
    }

    private fun drawCorners(canvas: Canvas) {
        val l = frameRect.left
        val t = frameRect.top
        val r = frameRect.right
        val b = frameRect.bottom
        val cl = cornerLen
        val cr = frameRadius * 0.6f   // offset vào trong một chút cho căn chỉnh góc bo

        // Trên-trái
        canvas.drawLine(l + cr, t, l + cr + cl, t, cornerPaint)
        canvas.drawLine(l, t + cr, l, t + cr + cl, cornerPaint)
        // Trên-phải
        canvas.drawLine(r - cr - cl, t, r - cr, t, cornerPaint)
        canvas.drawLine(r, t + cr, r, t + cr + cl, cornerPaint)
        // Dưới-trái
        canvas.drawLine(l + cr, b, l + cr + cl, b, cornerPaint)
        canvas.drawLine(l, b - cr - cl, l, b - cr, cornerPaint)
        // Dưới-phải
        canvas.drawLine(r - cr - cl, b, r - cr, b, cornerPaint)
        canvas.drawLine(r, b - cr - cl, r, b - cr, cornerPaint)
    }

    // --- Animations ---

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

    /** Gọi khi phát hiện số thành công — flash xanh rồi tắt */
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
        cornerPaint.color = COLOR_SUCCESS
        invalidate()
    }

    /** Gọi khi người dùng bấm Quét lại */
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
