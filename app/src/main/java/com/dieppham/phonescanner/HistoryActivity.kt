package com.dieppham.phonescanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
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

    private val dayLabelFmt = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi"))
    private val displayDayFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val dbDayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // Nguồn dữ liệu gốc — records gom nhóm theo ngày
    private var allGroups: List<DayGroup> = emptyList()

    // Trạng thái expand/collapse: dayKey -> expanded (mặc định hôm nay = true)
    private val expandedState = mutableMapOf<String, Boolean>()

    private var searchQuery = ""
    private var searchVisible = false

    // Dữ liệu gom nhóm nội bộ
    data class DayGroup(
        val dayKey: String,
        val label: String,
        val records: List<HistoryItem.Record>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        dao = AppDatabase.get(this).callRecordDao()

        adapter = HistoryAdapter(
            onCallClick = { number ->
                try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))) }
                catch (_: SecurityException) {}
            },
            onPinToggle = { record -> showPinDialog(record) },
            onDayHeaderClick = { dayKey ->
                // Toggle trạng thái expand/collapse của ngày đó
                expandedState[dayKey] = !(expandedState[dayKey] ?: true)
                rebuildAndSubmit()
            }
        )

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter

        ItemTouchHelper(
            SwipeToDeleteCallback(this, adapter) { recordId ->
                lifecycleScope.launch { dao.deleteById(recordId) }
            }
        ).attachToRecyclerView(binding.recyclerHistory)

        setupSearch()

        lifecycleScope.launch {
            combine(
                dao.getAllRecords(),
                dao.getCallStats()
            ) { records, totalStats ->
                records to totalStats.associate { it.phoneNumber to it.callCount }
            }.collect { (records, totalMap) ->
                val dailyStats = dao.getDailyStatsList()
                val dailyMap = dailyStats.associate { "${it.phoneNumber}|${it.dayKey}" to it }
                allGroups = buildGroups(records, totalMap, dailyMap)
                // Hôm nay mặc định mở, các ngày cũ mặc định đóng
                val todayKey = dbDayFmt.format(Date())
                allGroups.forEach { g ->
                    if (!expandedState.containsKey(g.dayKey)) {
                        expandedState[g.dayKey] = (g.dayKey == todayKey)
                    }
                }
                rebuildAndSubmit()
            }
        }

        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xóa toàn bộ lịch sử?")
                .setMessage("Các số đã ghim cũng sẽ bị xóa. Không thể hoàn tác.")
                .setPositiveButton("Xóa") { _, _ ->
                    lifecycleScope.launch { dao.deleteAll() }
                    expandedState.clear()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    // -------------------------------------------------------------------------
    // Xây dựng danh sách flat từ groups + trạng thái expand
    // -------------------------------------------------------------------------

    private fun rebuildAndSubmit() {
        val flat = buildFlatList(
            if (searchQuery.isBlank()) allGroups else filterGroups(allGroups, searchQuery)
        )
        adapter.submitList(flat)

        val isEmpty = flat.none { it is HistoryItem.Record || it is HistoryItem.PinnedHeader }
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) {
            if (allGroups.isEmpty()) {
                binding.tvEmptyIcon.text = "📋"
                binding.tvEmptyText.text = "Chưa có lịch sử gọi"
            } else {
                binding.tvEmptyIcon.text = "🔍"
                binding.tvEmptyText.text = "Không tìm thấy số \"$searchQuery\""
            }
        }
    }

    /**
     * Flat list = section ghim (nếu có) + các DayHeader + Records (nếu expanded)
     * Sắp xếp theo thời gian mới nhất lên đầu (đã được đảm bảo bởi allGroups)
     */
    private fun buildFlatList(groups: List<DayGroup>): List<HistoryItem> {
        val result = mutableListOf<HistoryItem>()

        // Section ghim — luôn mở, không có header toggle
        val pinned = allGroups.flatMap { g -> g.records.filter { it.record.isPinned } }
            .distinctBy { it.record.phoneNumber }
        if (pinned.isNotEmpty() && searchQuery.isBlank()) {
            result += HistoryItem.PinnedHeader
            result += pinned
        }

        // Lịch sử theo ngày
        groups.forEach { group ->
            val expanded = expandedState[group.dayKey] ?: true
            result += HistoryItem.DayHeader(
                dayKey    = group.dayKey,
                label     = group.label,
                expanded  = expanded,
                itemCount = group.records.size
            )
            if (expanded) result += group.records
        }

        return result
    }

    // -------------------------------------------------------------------------
    // Xây dựng DayGroup từ records DB
    // -------------------------------------------------------------------------

    private fun buildGroups(
        records: List<CallRecord>,
        totalMap: Map<String, Int>,
        dailyMap: Map<String, DailyCallStats>
    ): List<DayGroup> {
        val todayDisplay     = displayDayFmt.format(Date())
        val yesterdayDisplay = displayDayFmt.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        )

        val unpinned = records.filter { !it.isPinned }

        // Gom nhóm theo ngày, giữ thứ tự mới nhất trước (records đã sort DESC từ DB)
        val groupMap = linkedMapOf<String, MutableList<CallRecord>>()
        unpinned.forEach { record ->
            val dk = dbDayFmt.format(Date(record.timestamp))
            groupMap.getOrPut(dk) { mutableListOf() }.add(record)
        }

        return groupMap.map { (dayKey, recs) ->
            val displayKey = displayDayFmt.format(dbDayFmt.parse(dayKey) ?: Date())
            val label = when (displayKey) {
                todayDisplay     -> "HÔM NAY"
                yesterdayDisplay -> "HÔM QUA"
                else             -> dayLabelFmt.format(dbDayFmt.parse(dayKey) ?: Date()).uppercase()
            }

            // Dedup: mỗi số chỉ 1 dòng/ngày (giữ timestamp lớn nhất = mới nhất)
            val seen = mutableSetOf<String>()
            val dedupRecs = recs.filter { seen.add(it.phoneNumber) }

            val historyRecords = dedupRecs.map { record ->
                val dk2 = dbDayFmt.format(Date(record.timestamp))
                val daily = dailyMap["${record.phoneNumber}|$dk2"]
                HistoryItem.Record(
                    record         = record,
                    totalCallCount = totalMap[record.phoneNumber] ?: 1,
                    dailyCount     = daily?.callCount ?: 1,
                    lastCallTime   = daily?.lastCall ?: record.timestamp
                )
            }

            DayGroup(dayKey = dayKey, label = label, records = historyRecords)
        }
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    private fun filterGroups(groups: List<DayGroup>, query: String): List<DayGroup> {
        val q = query.filter { it.isDigit() }.ifEmpty { query.lowercase() }
        return groups.mapNotNull { group ->
            val filtered = group.records.filter { item ->
                if (q.all { it.isDigit() })
                    item.record.phoneNumber.filter { it.isDigit() }.contains(q)
                else
                    item.record.displayNumber.lowercase().contains(q)
            }
            if (filtered.isEmpty()) null
            else group.copy(records = filtered)
        }
    }

    // -------------------------------------------------------------------------
    // Pin dialog
    // -------------------------------------------------------------------------

    private fun showPinDialog(record: CallRecord) {
        val msg     = if (record.isPinned) "Bỏ ghim số này?" else "Ghim số này lên đầu?"
        val btnText = if (record.isPinned) "Bỏ ghim" else "📌 Ghim"
        AlertDialog.Builder(this)
            .setTitle(record.displayNumber)
            .setMessage(msg)
            .setPositiveButton(btnText) { _, _ ->
                lifecycleScope.launch {
                    if (record.isPinned) dao.unpinRecord(record.id)
                    else dao.pinRecord(record.id, System.currentTimeMillis())
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Search bar
    // -------------------------------------------------------------------------

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener { toggleSearch() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                rebuildAndSubmit()
            }
        })
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
    }

    private fun toggleSearch() { if (searchVisible) hideSearch() else showSearch() }

    private fun showSearch() {
        searchVisible = true
        binding.searchLayout.visibility = View.VISIBLE
        binding.searchLayout.measure(
            View.MeasureSpec.makeMeasureSpec(binding.root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetH = binding.searchLayout.measuredHeight
            .coerceAtLeast((56 * resources.displayMetrics.density).toInt())
        binding.searchLayout.layoutParams.height = 0
        binding.searchLayout.requestLayout()
        binding.searchLayout.alpha = 0f
        binding.searchLayout.animate().alpha(1f).setDuration(250)
            .setInterpolator(DecelerateInterpolator()).start()
        android.animation.ValueAnimator.ofInt(0, targetH).apply {
            duration = 250; interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                binding.searchLayout.layoutParams.height = anim.animatedValue as Int
                binding.searchLayout.requestLayout()
            }
            doOnEnd { binding.searchLayout.layoutParams.height =
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT }
        }.start()
        binding.etSearch.requestFocus()
        binding.etSearch.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
        binding.btnSearch.text = "✕"
    }

    private fun hideSearch() {
        searchVisible = false; searchQuery = ""
        binding.etSearch.text?.clear()
        hideKeyboard()
        val startH = binding.searchLayout.height
        android.animation.ValueAnimator.ofInt(startH, 0).apply {
            duration = 200; interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                binding.searchLayout.layoutParams.height = anim.animatedValue as Int
                binding.searchLayout.requestLayout()
            }
            doOnEnd {
                binding.searchLayout.visibility = View.GONE
                binding.searchLayout.layoutParams.height =
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }.start()
        binding.searchLayout.animate().alpha(0f).setDuration(200).start()
        binding.btnSearch.text = "🔍"
        rebuildAndSubmit()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    private fun android.animation.Animator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    override fun onBackPressed() {
        if (searchVisible) hideSearch() else super.onBackPressed()
    }
}
