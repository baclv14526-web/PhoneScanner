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

/**
 * Adapter hiển thị lịch sử gom nhóm theo ngày.
 * Danh sách item gồm 2 loại: HEADER (ngày) và RECORD (bản ghi cuộc gọi).
 */
class HistoryAdapter(
    private val onCallClick: (String) -> Unit
) : ListAdapter<HistoryItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_RECORD = 1

        private val DIFF = object : DiffUtil.ItemCallback<HistoryItem>() {
            override fun areItemsTheSame(a: HistoryItem, b: HistoryItem) = when {
                a is HistoryItem.Header && b is HistoryItem.Header -> a.label == b.label
                a is HistoryItem.Record && b is HistoryItem.Record -> a.record.id == b.record.id
                else -> false
            }
            override fun areContentsTheSame(a: HistoryItem, b: HistoryItem) = a == b
        }

        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is HistoryItem.Header -> TYPE_HEADER
        is HistoryItem.Record -> TYPE_RECORD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(
                inflater.inflate(R.layout.item_day_header, parent, false)
            )
            else -> RecordVH(
                inflater.inflate(R.layout.item_call_record, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HistoryItem.Header -> (holder as HeaderVH).bind(item.label)
            is HistoryItem.Record -> (holder as RecordVH).bind(item.record, item.callCount, onCallClick)
        }
    }

    // --- ViewHolders ---

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(label: String) { (itemView as TextView).text = label }
    }

    class RecordVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvNumber   = view.findViewById<TextView>(R.id.tvNumber)
        private val tvTime     = view.findViewById<TextView>(R.id.tvTime)
        private val tvCallCount = view.findViewById<TextView>(R.id.tvCallCount)

        fun bind(record: CallRecord, callCount: Int, onCallClick: (String) -> Unit) {
            tvNumber.text = record.displayNumber
            tvTime.text   = timeFmt.format(Date(record.timestamp))
            tvCallCount.text = if (callCount > 1) "${callCount} lần" else "1 lần"
            itemView.setOnClickListener { onCallClick(record.phoneNumber) }
        }
    }
}

/** Sealed class đại diện cho 1 item trong RecyclerView */
sealed class HistoryItem {
    data class Header(val label: String) : HistoryItem()
    data class Record(val record: CallRecord, val callCount: Int) : HistoryItem()
}
