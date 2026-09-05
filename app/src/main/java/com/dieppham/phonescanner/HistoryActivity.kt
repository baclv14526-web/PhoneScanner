package com.dieppham.phonescanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dieppham.phonescanner.databinding.ActivityHistoryBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var dao: CallRecordDao

    private val dayFmt   = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi"))
    private val todayFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        dao = AppDatabase.get(this).callRecordDao()

        adapter = HistoryAdapter { phoneNumber ->
            // Bấm vào bản ghi → gọi lại số đó ngay
            try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")))
            } catch (e: SecurityException) {
                // Quyền CALL_PHONE đã được cấp ở MainActivity, trường hợp
                // hiếm gặp nếu người dùng thu hồi quyền sau khi cấp
            }
        }

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter

        // Kết hợp 2 Flow: danh sách bản ghi + thống kê số lần gọi
        // -> gom nhóm theo ngày rồi đưa vào adapter
        lifecycleScope.launch {
            combine(
                dao.getAllRecords(),
                dao.getCallStats()
            ) { records, stats ->
                val countMap = stats.associate { it.phoneNumber to it.callCount }
                buildHistoryItems(records, countMap)
            }.collect { items ->
                adapter.submitList(items)
                binding.layoutEmpty.visibility =
                    if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xóa toàn bộ lịch sử?")
                .setMessage("Không thể hoàn tác sau khi xóa.")
                .setPositiveButton("Xóa") { _, _ ->
                    lifecycleScope.launch { dao.deleteAll() }
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    /**
     * Chuyển danh sách CallRecord phẳng thành danh sách HistoryItem
     * có xen kẽ header ngày (Hôm nay / Hôm qua / Thứ X, dd/MM/yyyy).
     */
    private fun buildHistoryItems(
        records: List<CallRecord>,
        countMap: Map<String, Int>
    ): List<HistoryItem> {
        val result = mutableListOf<HistoryItem>()
        var lastDayKey = ""

        val todayKey      = todayFmt.format(Date())
        val yesterdayKey  = todayFmt.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        )

        for (record in records) {
            val dayKey = todayFmt.format(Date(record.timestamp))
            if (dayKey != lastDayKey) {
                val label = when (dayKey) {
                    todayKey     -> "HÔM NAY"
                    yesterdayKey -> "HÔM QUA"
                    else         -> dayFmt.format(Date(record.timestamp)).uppercase()
                }
                result += HistoryItem.Header(label)
                lastDayKey = dayKey
            }
            result += HistoryItem.Record(
                record    = record,
                callCount = countMap[record.phoneNumber] ?: 1
            )
        }
        return result
    }
}
