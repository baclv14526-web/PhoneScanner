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
    private val onPinToggle: (record: CallRecord) -> Unit,
    private val onDayHeaderClick: (dayKey: String) -> Unit
) : ListAdapter<HistoryItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        const val TYPE_PINNED_HEADER = 0
        const val TYPE_DAY_HEADER    = 1
        const val TYPE_RECORD        = 2

        private val DIFF = object : DiffUtil.ItemCallback<HistoryItem>() {
            override fun areItemsTheSame(a: HistoryItem, b: HistoryItem) = when {
                a is HistoryItem.PinnedHeader && b is HistoryItem.PinnedHeader -> true
                a is HistoryItem.DayHeader    && b is HistoryItem.DayHeader    -> a.dayKey == b.dayKey
                a is HistoryItem.Record       && b is HistoryItem.Record       ->
                    a.record.id == b.record.id && a.record.phoneNumber == b.record.phoneNumber
                else -> false
            }
            override fun areContentsTheSame(a: HistoryItem, b: HistoryItem) = a == b
        }

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    public override fun getItem(position: Int): HistoryItem = super.getItem(position)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is HistoryItem.PinnedHeader -> TYPE_PINNED_HEADER
        is HistoryItem.DayHeader    -> TYPE_DAY_HEADER
        is HistoryItem.Record       -> TYPE_RECORD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PINNED_HEADER -> PinnedHeaderVH(inf.inflate(R.layout.item_pinned_header, parent, false))
            TYPE_DAY_HEADER    -> DayHeaderVH(inf.inflate(R.layout.item_day_header, parent, false))
            else               -> RecordVH(inf.inflate(R.layout.item_call_record, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HistoryItem.PinnedHeader -> { /* static */ }
            is HistoryItem.DayHeader    -> (holder as DayHeaderVH).bind(item, onDayHeaderClick)
            is HistoryItem.Record       -> (holder as RecordVH).bind(
                item, onCallClick, onPinToggle
            )
        }
    }

    // -------------------------------------------------------------------------
    // ViewHolders
    // -------------------------------------------------------------------------

    class PinnedHeaderVH(view: View) : RecyclerView.ViewHolder(view)

    class DayHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvChevron  = view.findViewById<TextView>(R.id.tvChevron)
        private val tvDayLabel = view.findViewById<TextView>(R.id.tvDayLabel)
        private val tvCount    = view.findViewById<TextView>(R.id.tvCount)

        fun bind(item: HistoryItem.DayHeader, onClick: (String) -> Unit) {
            tvDayLabel.text = item.label

            // Chevron xoay 0° (mở) hoặc -90° (đóng), không dùng animation ở đây
            // vì ListAdapter đã tự handle item changes
            tvChevron.rotation = if (item.expanded) 0f else -90f

            tvCount.text = if (item.expanded) "" else "${item.itemCount} số"

            itemView.setOnClickListener { onClick(item.dayKey) }
        }
    }

    class RecordVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvNumber    = view.findViewById<TextView>(R.id.tvNumber)
        private val tvTime      = view.findViewById<TextView>(R.id.tvTime)
        private val tvCallCount = view.findViewById<TextView>(R.id.tvCallCount)
        private val tvPinIcon   = view.findViewById<TextView>(R.id.tvPinIcon)

        fun bind(
            item: HistoryItem.Record,
            onCallClick: (String) -> Unit,
            onPinToggle: (CallRecord) -> Unit
        ) {
            val record = item.record
            tvNumber.text  = record.displayNumber
            tvPinIcon.visibility = if (record.isPinned) View.VISIBLE else View.GONE

            if (record.isPinned) {
                tvTime.text = timeFmt.format(Date(record.timestamp))
                tvCallCount.visibility = View.VISIBLE
                tvCallCount.text = if (item.totalCallCount > 1) "${item.totalCallCount} lần" else ""
            } else {
                tvTime.text = timeFmt.format(Date(item.lastCallTime))
                if (item.dailyCount > 1) {
                    tvCallCount.visibility = View.VISIBLE
                    tvCallCount.text = "${item.dailyCount} lần"
                } else {
                    tvCallCount.visibility = View.GONE
                }
            }

            itemView.setOnClickListener { onCallClick(record.phoneNumber) }
            itemView.setOnLongClickListener { onPinToggle(record); true }
        }
    }
}

// -------------------------------------------------------------------------
// HistoryItem sealed class
// -------------------------------------------------------------------------

sealed class HistoryItem {
    object PinnedHeader : HistoryItem()

    data class DayHeader(
        val dayKey: String,     // "yyyy-MM-dd" — dùng làm key toggle
        val label: String,      // "HÔM NAY", "HÔM QUA", "THỨ BA, 10/06/2025"
        val expanded: Boolean,
        val itemCount: Int      // số records trong ngày — hiện khi collapsed
    ) : HistoryItem()

    data class Record(
        val record: CallRecord,
        val totalCallCount: Int,
        val dailyCount: Int,
        val lastCallTime: Long
    ) : HistoryItem()
}
