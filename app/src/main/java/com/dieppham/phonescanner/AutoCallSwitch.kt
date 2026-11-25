package com.dieppham.phonescanner

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * Công tắc gạt "Tự gọi": thanh trượt hình viên thuốc, núm tròn có tia sét trượt trái/phải.
 * Chạm để đổi trạng thái hoặc kéo núm sang bên kia; màu nền chuyển mượt xám → xanh.
 */
class AutoCallSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val dp = context.resources.displayMetrics.density

    private val offTrack = Color.parseColor("#33FFFFFF")
    private val onTrack = Color.parseColor("#E600E5FF")
    private val offText = Color.parseColor("#B3FFFFFF")
    private val onText = Color.parseColor("#FF00262B")
    private val offBolt = Color.parseColor("#FF6B6B78")
    private val onBolt = Color.parseColor("#FF00B8D4")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = Color.parseColor("#33FFFFFF")
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A000000") }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * dp; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER; letterSpacing = 0.06f
    }

    private val argb = ArgbEvaluator()
    private val rect = RectF()
    private val boltPath = Path()
    private val pad = 4f * dp
    private val label = "TỰ GỌI"

    private var progress = 0f           // 0 = tắt (núm bên trái), 1 = bật (núm bên phải)
    private var animator: ValueAnimator? = null
    private var checked = false

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private var downX = 0f
    private var startProgress = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Tự động gọi"
    }

    fun isChecked() = checked

    /** Đặt trạng thái; [animate]=false khi khôi phục lúc mở app (không kích hoạt callback). */
    fun setChecked(value: Boolean, animate: Boolean = true) {
        checked = value
        animateTo(if (value) 1f else 0f, animate)
    }

    private fun animateTo(target: Float, animate: Boolean) {
        animator?.cancel()
        if (!animate) { progress = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun commit(value: Boolean) {
        if (value != checked) {
            checked = value
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onCheckedChange?.invoke(value)
        }
        animateTo(if (value) 1f else 0f, true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((128 * dp).toInt(), widthMeasureSpec),
            resolveSize((44 * dp).toInt(), heightMeasureSpec)
        )
    }

    private val travel get() = width - 2 * pad - thumbDiameter
    private val thumbDiameter get() = height - 2 * pad

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = h / 2f

        // Nền
        trackPaint.color = argb.evaluate(progress, offTrack, onTrack) as Int
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        borderPaint.alpha = ((1f - progress) * 255).toInt()
        rect.set(0.5f * dp, 0.5f * dp, w - 0.5f * dp, h - 0.5f * dp)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        // Chữ nằm ở phía đối diện núm
        val td = thumbDiameter
        val thumbCx = pad + td / 2f + progress * travel
        val textCx = (1f - progress) * ((pad + td + w) / 2f) + progress * ((w - pad - td) / 2f)
        textPaint.color = argb.evaluate(progress, offText, onText) as Int
        val ty = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, textCx, ty, textPaint)

        // Núm
        val cy = h / 2f
        canvas.drawCircle(thumbCx, cy + 1f * dp, td / 2f + 0.5f * dp, glowPaint)
        canvas.drawCircle(thumbCx, cy, td / 2f, thumbPaint)

        // Tia sét trong núm
        boltPaint.color = argb.evaluate(progress, offBolt, onBolt) as Int
        val s = td * 0.5f
        val x0 = thumbCx - s / 2f; val y0 = cy - s / 2f
        boltPath.reset()
        boltPath.moveTo(x0 + s * 0.58f, y0)
        boltPath.lineTo(x0 + s * 0.15f, y0 + s * 0.56f)
        boltPath.lineTo(x0 + s * 0.46f, y0 + s * 0.56f)
        boltPath.lineTo(x0 + s * 0.38f, y0 + s)
        boltPath.lineTo(x0 + s * 0.85f, y0 + s * 0.40f)
        boltPath.lineTo(x0 + s * 0.54f, y0 + s * 0.40f)
        boltPath.close()
        canvas.drawPath(boltPath, boltPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; startProgress = progress; dragging = false
                animator?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) dragging = true
                if (dragging && travel > 0f) {
                    progress = (startProgress + (e.x - downX) / travel).coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) commit(progress >= 0.5f)
                else { commit(!checked); performClick() }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                animateTo(if (checked) 1f else 0f, true)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
