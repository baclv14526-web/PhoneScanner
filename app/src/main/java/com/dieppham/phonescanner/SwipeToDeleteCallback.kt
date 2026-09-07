package com.dieppham.phonescanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Vuốt sang trái để xóa bản ghi.
 * - Chỉ cho phép vuốt TYPE_RECORD (vị trí có HistoryItem.Record), không vuốt Header.
 * - Vẽ nền đỏ + icon 🗑 khi người dùng đang vuốt.
 * - Gọi [onDelete] với record id khi vuốt hoàn tất.
 */
class SwipeToDeleteCallback(
    context: Context,
    private val adapter: HistoryAdapter,
    private val onDelete: (recordId: Long) -> Unit
) : ItemTouchHelper.SimpleCallback(
    0,                          // drag directions: không cho kéo thả sắp xếp
    ItemTouchHelper.LEFT        // swipe directions: chỉ vuốt trái
) {
    private val dp = context.resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BFC1515A")   // đỏ mờ
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22 * dp
        textAlign = Paint.Align.CENTER
    }
    private val bgRect = RectF()
    private val cornerRadius = 16 * dp

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val pos = viewHolder.absoluteAdapterPosition
        if (pos == RecyclerView.NO_ID.toInt()) return 0
        val item = adapter.getItem(pos)
        return if (item is HistoryItem.Record && !item.record.isPinned &&
                   adapter.getItemViewType(pos) == HistoryAdapter.TYPE_RECORD)
            ItemTouchHelper.LEFT else 0
    }

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder) = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = viewHolder.absoluteAdapterPosition
        if (pos == RecyclerView.NO_ID.toInt()) return
        val item = adapter.getItem(pos)
        if (item is HistoryItem.Record) {
            onDelete(item.record.id)
        }
    }

    override fun onChildDraw(
        c: Canvas, recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float,
        actionState: Int, isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        val swipeFraction = abs(dX) / itemView.width.toFloat()

        if (dX < 0) {   // vuốt sang trái
            val right  = itemView.right.toFloat() - 8 * dp
            val left   = right + dX             // dX âm → left < right
            val top    = itemView.top.toFloat()  + 6 * dp
            val bottom = itemView.bottom.toFloat() - 6 * dp

            bgRect.set(left.coerceAtLeast(itemView.left.toFloat()), top, right, bottom)

            // Nền đỏ mờ dần ra sau icon, đậm dần khi vuốt xa hơn
            bgPaint.alpha = (swipeFraction * 220).toInt().coerceIn(0, 220)
            c.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

            // Icon thùng rác — hiện khi đã vuốt > 20% chiều rộng item
            if (swipeFraction > 0.20f) {
                iconPaint.alpha = ((swipeFraction - 0.20f) / 0.30f * 255)
                    .toInt().coerceIn(0, 255)
                val iconX = itemView.right - 56 * dp
                val iconY = (top + bottom) / 2f - (iconPaint.descent() + iconPaint.ascent()) / 2f
                c.drawText("🗑", iconX, iconY, iconPaint)
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
