package com.dieppham.phonescanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class SwipeToDeleteCallback(
    context: Context,
    private val adapter: HistoryAdapter,
    private val onDelete: (recordId: Long) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

    private val dp = context.resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BFC1515A")
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22 * dp
        textAlign = Paint.Align.CENTER
    }
    private val bgRect = RectF()
    private val cornerRadius = 16 * dp

    private fun positionOf(holder: RecyclerView.ViewHolder): Int = holder.layoutPosition

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val pos = positionOf(viewHolder)
        if (pos < 0 || pos >= adapter.itemCount) return 0
        val item = adapter.getItem(pos)
        return if (item is HistoryItem.Record && !item.record.isPinned)
            ItemTouchHelper.LEFT else 0
    }

    override fun onMove(
        rv: RecyclerView,
        vh: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ) = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = positionOf(viewHolder)
        if (pos < 0 || pos >= adapter.itemCount) return
        val item = adapter.getItem(pos)
        if (item is HistoryItem.Record) onDelete(item.record.id)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val iv = viewHolder.itemView
        val swipeFraction = abs(dX) / iv.width.toFloat()

        if (dX < 0) {
            val right  = iv.right.toFloat() - 8 * dp
            val left   = (right + dX).coerceAtLeast(iv.left.toFloat())
            val top    = iv.top.toFloat()  + 6 * dp
            val bottom = iv.bottom.toFloat() - 6 * dp

            bgRect.set(left, top, right, bottom)
            bgPaint.alpha = (swipeFraction * 220).toInt().coerceIn(0, 220)
            c.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

            if (swipeFraction > 0.20f) {
                iconPaint.alpha = ((swipeFraction - 0.20f) / 0.30f * 255)
                    .toInt().coerceIn(0, 255)
                val iconX = iv.right - 56 * dp
                val iconY = (top + bottom) / 2f -
                    (iconPaint.descent() + iconPaint.ascent()) / 2f
                c.drawText("\uD83D\uDDD1", iconX, iconY, iconPaint)
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
