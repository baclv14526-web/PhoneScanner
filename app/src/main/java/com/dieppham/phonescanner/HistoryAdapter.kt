package com.dieppham.phonescanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onCallClick: (String) -> Unit,
    private val onPinToggle: (record: CallRecord) -> Unit
) : ListAdapter<HistoryItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        const val TYPE_HEADER        = 0
        const val TYPE_RECORD        = 1
        const val TYPE_PINNED_HEADER = 2

        private val DIFF = object : DiffUtil.ItemCallback<HistoryItem>() {
            override fun areItemsTheSame(a: HistoryItem, b: HistoryItem) = when {
                a is HistoryItem.Header       && b is HistoryItem.Header       -> a.label == b.label
                a is HistoryItem.PinnedHeader && b is HistoryItem.PinnedHeader -> true
                a is HistoryItem.Record       && b is HistoryItem.Record       -> a.record.id == b.record.id
                else -> false
            }
            override fun areContentsTheSame(a: HistoryItem, b: HistoryItem) = a == b
        }

        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    public override fun getItem(position: Int): HistoryItem = super.getItem(position)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is HistoryItem.PinnedHeader -> TYPE_PINNED_HEADER
        is HistoryItem.Header       -> TYPE_HEADER
        is HistoryItem.Record       -> TYPE_RECORD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PINNED_HEADER -> PinnedHeaderVH(inf.inflate(R.layout.item_pinned_header, parent, false))
            TYPE_HEADER        -> HeaderVH(inf.inflate(R.layout.item_day_header, parent, false))
            else               -> RecordVH(inf.inflate(R.layout.item_call_record, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HistoryItem.PinnedHeader -> { /* static */ }
            is HistoryItem.Header       -> (holder as HeaderVH).bind(item.label)
            is HistoryItem.Record       -> (holder as RecordVH).bind(
                record         = item.record,
                totalCallCount = item.totalCallCount,
                dailyCount     = item.dailyCount,
                lastCallTime   = item.lastCallTime,
                onCallClick    = onCallClick,
                onPinToggle    = onPinToggle
            )
        }
    }

    // --- ViewHolders ---

    class PinnedHeaderVH(view: View) : RecyclerView.ViewHolder(view)

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(label: String) { (itemView as TextView).text = label }
    }

    class RecordVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvNumber     = view.findViewById<TextView>(R.id.tvNumber)
        private val tvTime       = view.findViewById<TextView>(R.id.tvTime)
        private val tvCallCount  = view.findViewById<TextView>(R.id.tvCallCount)
        private val tvPinIcon    = view.findViewById<TextView>(R.id.tvPinIcon)

        fun bind(
            record: CallRecord,
            totalCallCount: Int,
            dailyCount: Int,
            lastCallTime: Long,
            onCallClick: (String) -> Unit,
            onPinToggle: (CallRecord) -> Unit
        ) {
            tvNumber.text = record.displayNumber
            tvPinIcon.visibility = if (record.isPinned) View.VISIBLE else View.GONE

            if (record.isPinned) {
                // Section ghim: hiện giờ gọi gần nhất + tổng lần gọi
                tvTime.text      = timeFmt.format(Date(record.timestamp))
                tvCallCount.text = if (totalCallCount > 1) "$totalCallCount lần" else "1 lần"
            } else {
                // Section lịch sử theo ngày: hiện giờ gọi cuối trong ngày + số lần trong ngày
                tvTime.text      = timeFmt.format(Date(lastCallTime))
                tvCallCount.text = if (dailyCount > 1) "$dailyCount lần hôm nay"
                                   else timeFmt.format(Date(lastCallTime))
                // Nếu gọi nhiều lần trong ngày thì hiện "x lần", nếu 1 lần thì hiện giờ
                // (badge "1 lần" không có nhiều ý nghĩa bằng giờ gọi)
                tvCallCount.text = when {
                    dailyCount > 1 -> "$dailyCount lần"
                    else           -> ""
                }
                tvCallCount.visibility = if (dailyCount > 1) View.VISIBLE else View.GONE
            }

            itemView.setOnClickListener { onCallClick(record.phoneNumber) }
            itemView.setOnLongClickListener { onPinToggle(record); true }
        }
    }
}

sealed class HistoryItem {
    object PinnedHeader : HistoryItem()
    data class Header(val label: String) : HistoryItem()
    data class Record(
        val record: CallRecord,
        val totalCallCount: Int,    // tổng từ trước đến nay (dùng cho badge section ghim)
        val dailyCount: Int,        // số lần gọi trong ngày này (dùng cho badge lịch sử)
        val lastCallTime: Long      // timestamp lần gọi cuối cùng trong nhóm ngày này
    ) : HistoryItem()
}
