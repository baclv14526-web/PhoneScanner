package com.dieppham.phonescanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dieppham.phonescanner.databinding.ItemScanDayHeaderBinding
import com.dieppham.phonescanner.databinding.ItemScanRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phần tử hiển thị trong nhật ký quét: tiêu đề ngày hoặc một lượt quét. */
sealed class ScanLogItem {
    data class DayHeader(val dayKey: String, val label: String, val count: Int) : ScanLogItem()
    data class Row(val record: ScanRecord, val note: String?) : ScanLogItem()
}

class ScanLogAdapter(
    private val onRowClick: (ScanRecord) -> Unit,
    private val onNoteClick: (phone: String, currentNote: String?) -> Unit
) : ListAdapter<ScanLogItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1

        private val DIFF = object : DiffUtil.ItemCallback<ScanLogItem>() {
            override fun areItemsTheSame(a: ScanLogItem, b: ScanLogItem): Boolean = when {
                a is ScanLogItem.DayHeader && b is ScanLogItem.DayHeader -> a.dayKey == b.dayKey
                a is ScanLogItem.Row && b is ScanLogItem.Row -> a.record.id == b.record.id
                else -> false
            }

            override fun areContentsTheSame(a: ScanLogItem, b: ScanLogItem): Boolean = a == b
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ScanLogItem.DayHeader -> TYPE_HEADER
        is ScanLogItem.Row -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemScanDayHeaderBinding.inflate(inflater, parent, false))
        } else {
            RowVH(ItemScanRecordBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ScanLogItem.DayHeader -> (holder as HeaderVH).bind(item)
            is ScanLogItem.Row -> (holder as RowVH).bind(item)
        }
    }

    private class HeaderVH(private val b: ItemScanDayHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ScanLogItem.DayHeader) {
            b.tvDayLabel.text = item.label
            b.tvCount.text = "${item.count} lượt quét"
        }
    }

    private inner class RowVH(private val b: ItemScanRecordBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ScanLogItem.Row) {
            val r = item.record
            b.tvNumber.text = PhoneNumberExtractor.formatForDisplay(r.phoneNumber)
            b.tvCarrier.text = r.carrier
            b.tvCarrier.setTextColor(CarrierDetector.colorFor(r.carrier))
            b.tvTime.text = timeFmt.format(Date(r.timestamp))
            b.tvIcon.text = if (r.carrier == CarrierDetector.LANDLINE) "☎" else "📱"
            if (r.scanCount > 1) {
                b.tvScanCount.visibility = View.VISIBLE
                b.tvScanCount.text = "×${r.scanCount}"
            } else {
                b.tvScanCount.visibility = View.GONE
            }
            val note = item.note
            if (note.isNullOrBlank()) {
                b.tvNote.visibility = View.GONE
                b.btnNote.text = "📝"
            } else {
                b.tvNote.visibility = View.VISIBLE
                b.tvNote.text = "📝 $note"
                b.btnNote.text = "✏️"
            }
            b.btnNote.setOnClickListener { onNoteClick(r.phoneNumber, note) }
            b.root.setOnClickListener { onRowClick(r) }
        }
    }
}
