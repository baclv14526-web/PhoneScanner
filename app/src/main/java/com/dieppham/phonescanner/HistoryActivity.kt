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

    private val dayFmt   = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi"))
    private val todayFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    private var allItems: List<HistoryItem> = emptyList()
    private var searchQuery: String = ""
    private var searchVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        dao = AppDatabase.get(this).callRecordDao()

        adapter = HistoryAdapter(
            onCallClick = { phoneNumber ->
                try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))) }
                catch (_: SecurityException) {}
            },
            onPinToggle = { record ->
                // Long-press → hiện dialog xác nhận ghim/bỏ ghim
                showPinDialog(record)
            }
        )

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter

        val swipeCallback = SwipeToDeleteCallback(this, adapter) { recordId ->
            lifecycleScope.launch { dao.deleteById(recordId) }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerHistory)

        setupSearch()

        lifecycleScope.launch {
            combine(
                dao.getAllRecords(),
                dao.getCallStats(),
                dao.getDailyStats()
            ) { records, totalStats, dailyStats ->
                val totalMap = totalStats.associate { it.phoneNumber to it.callCount }
                // Map: "phoneNumber|dayKey" -> DailyCallStats
                val dailyMap = dailyStats.associate { "${it.phoneNumber}|${it.dayKey}" to it }
                buildHistoryItems(records, totalMap, dailyMap)
            }.collect { items ->
                allItems = items
                applyFilter()
            }
        }

        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xóa toàn bộ lịch sử?")
                .setMessage("Các số đã ghim cũng sẽ bị xóa. Không thể hoàn tác.")
                .setPositiveButton("Xóa") { _, _ ->
                    lifecycleScope.launch { dao.deleteAll() }
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    // -------------------------------------------------------------------------
    // Pin / Unpin
    // -------------------------------------------------------------------------

    private fun showPinDialog(record: CallRecord) {
        val title   = record.displayNumber
        val msg     = if (record.isPinned) "Bỏ ghim số này?" else "Ghim số này lên đầu danh sách?"
        val btnText = if (record.isPinned) "Bỏ ghim" else "📌 Ghim"

        AlertDialog.Builder(this)
            .setTitle(title)
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
    // Build items: section "ĐÃ GHIM" trên cùng, sau đó lịch sử theo ngày
    // -------------------------------------------------------------------------

    private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun buildHistoryItems(
        records: List<CallRecord>,
        totalMap: Map<String, Int>,
        dailyMap: Map<String, DailyCallStats>
    ): List<HistoryItem> {
        val result = mutableListOf<HistoryItem>()

        val pinned   = records.filter { it.isPinned }
        val unpinned = records.filter { !it.isPinned }

        // --- Section ghim: mỗi số 1 dòng, hiện tổng lần gọi ---
        if (pinned.isNotEmpty()) {
            result += HistoryItem.PinnedHeader
            // Dedup: chỉ lấy bản ghi mới nhất của mỗi số trong danh sách ghim
            pinned.distinctBy { it.phoneNumber }.forEach { record ->
                result += HistoryItem.Record(
                    record         = record,
                    totalCallCount = totalMap[record.phoneNumber] ?: 1,
                    dailyCount     = 1,
                    lastCallTime   = record.timestamp
                )
            }
        }

        // --- Section lịch sử: gom theo ngày, mỗi số chỉ 1 dòng/ngày ---
        val todayKey     = todayFmt.format(Date())
        val yesterdayKey = todayFmt.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        )
        var lastDayKey = ""

        // Chỉ lấy 1 bản ghi đại diện mỗi cặp (phoneNumber, ngày)
        // → dùng distinctBy trên cặp key, giữ bản ghi có timestamp lớn nhất (đã sort DESC)
        val seen = mutableSetOf<String>()
        unpinned.forEach { record ->
            val dk = dayKeyFmt.format(Date(record.timestamp))
            val key = "${record.phoneNumber}|$dk"
            if (seen.contains(key)) return@forEach
            seen += key

            val displayDayKey = todayFmt.format(Date(record.timestamp))
            if (displayDayKey != lastDayKey) {
                val label = when (displayDayKey) {
                    todayKey     -> "HÔM NAY"
                    yesterdayKey -> "HÔM QUA"
                    else         -> dayFmt.format(Date(record.timestamp)).uppercase()
                }
                result += HistoryItem.Header(label)
                lastDayKey = displayDayKey
            }

            val daily = dailyMap[key]
            result += HistoryItem.Record(
                record         = record,
                totalCallCount = totalMap[record.phoneNumber] ?: 1,
                dailyCount     = daily?.callCount ?: 1,
                lastCallTime   = daily?.lastCall ?: record.timestamp
            )
        }

        return result
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener { toggleSearch() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilter()
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
            doOnEnd {
                binding.searchLayout.layoutParams.height =
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }.start()
        binding.etSearch.requestFocus()
        binding.etSearch.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
        binding.btnSearch.text = "✕"
    }

    private fun hideSearch() {
        searchVisible = false
        searchQuery = ""
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
        applyFilter()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    private fun applyFilter() {
        val filtered = if (searchQuery.isBlank()) {
            allItems
        } else {
            val q = searchQuery.filter { it.isDigit() }
                .ifEmpty { searchQuery.lowercase() }
            val keepIndices = mutableSetOf<Int>()
            var lastHeaderIdx = -1
            allItems.forEachIndexed { idx, item ->
                when (item) {
                    is HistoryItem.PinnedHeader -> lastHeaderIdx = idx
                    is HistoryItem.Header       -> lastHeaderIdx = idx
                    is HistoryItem.Record -> {
                        val matches = if (q.all { it.isDigit() })
                            item.record.phoneNumber.filter { it.isDigit() }.contains(q)
                        else
                            item.record.displayNumber.lowercase().contains(q)
                        if (matches) {
                            keepIndices += idx
                            if (lastHeaderIdx >= 0) keepIndices += lastHeaderIdx
                        }
                    }
                }
            }
            allItems.filterIndexed { idx, _ -> idx in keepIndices }
        }

        adapter.submitList(filtered)

        val isEmpty = filtered.isEmpty()
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) {
            if (allItems.isEmpty()) {
                binding.tvEmptyIcon.text = "📋"
                binding.tvEmptyText.text = "Chưa có lịch sử gọi"
            } else {
                binding.tvEmptyIcon.text = "🔍"
                binding.tvEmptyText.text = "Không tìm thấy số \"$searchQuery\""
            }
        }
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
