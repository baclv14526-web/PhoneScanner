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

    // Toàn bộ dữ liệu gốc — filter được áp lên bản sao này, không query DB lại
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

        adapter = HistoryAdapter { phoneNumber ->
            try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")))
            } catch (_: SecurityException) {}
        }

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter

        setupSearch()

        lifecycleScope.launch {
            combine(
                dao.getAllRecords(),
                dao.getCallStats()
            ) { records, stats ->
                val countMap = stats.associate { it.phoneNumber to it.callCount }
                buildHistoryItems(records, countMap)
            }.collect { items ->
                allItems = items
                applyFilter()
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

        // Bấm Done/Search trên bàn phím → đóng keyboard
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }
    }

    private fun toggleSearch() {
        if (searchVisible) hideSearch() else showSearch()
    }

    private fun showSearch() {
        searchVisible = true
        binding.searchLayout.visibility = View.VISIBLE

        // Đo chiều cao thực tế rồi animate từ 0 lên
        binding.searchLayout.measure(
            View.MeasureSpec.makeMeasureSpec(binding.root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetH = binding.searchLayout.measuredHeight
            .coerceAtLeast((56 * resources.displayMetrics.density).toInt())

        binding.searchLayout.layoutParams.height = 0
        binding.searchLayout.requestLayout()
        binding.searchLayout.alpha = 0f

        binding.searchLayout.animate()
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Animate height
        val animator = android.animation.ValueAnimator.ofInt(0, targetH).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                binding.searchLayout.layoutParams.height = anim.animatedValue as Int
                binding.searchLayout.requestLayout()
            }
            doOnEnd {
                binding.searchLayout.layoutParams.height =
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        animator.start()

        // Mở keyboard tự động
        binding.etSearch.requestFocus()
        binding.etSearch.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 200)

        binding.btnSearch.text = "✕"
    }

    private fun hideSearch() {
        searchVisible = false
        searchQuery = ""
        binding.etSearch.text?.clear()
        hideKeyboard()

        val startH = binding.searchLayout.height
        val animator = android.animation.ValueAnimator.ofInt(startH, 0).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                binding.searchLayout.layoutParams.height = anim.animatedValue as Int
                binding.searchLayout.requestLayout()
            }
            doOnEnd {
                binding.searchLayout.visibility = View.GONE
                binding.searchLayout.layoutParams.height =
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        binding.searchLayout.animate()
            .alpha(0f).setDuration(200).start()
        animator.start()

        binding.btnSearch.text = "🔍"
        applyFilter()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    // -------------------------------------------------------------------------
    // Filter
    // -------------------------------------------------------------------------

    /**
     * Lọc allItems theo searchQuery:
     *  - Xóa khoảng trắng/dấu gạch trong query trước khi so sánh
     *    (người dùng hay gõ "0912 345" hay "0912-345")
     *  - So khớp với phoneNumber (dạng chuẩn) và displayNumber (dạng format)
     *  - Header ngày chỉ giữ lại nếu vẫn còn ít nhất 1 Record bên dưới
     */
    private fun applyFilter() {
        val filtered = if (searchQuery.isBlank()) {
            allItems
        } else {
            val q = searchQuery.filter { it.isDigit() }
                .ifEmpty { searchQuery.lowercase() }

            // Lọc Records khớp trước, giữ Headers còn Record bên dưới
            val keepIndices = mutableSetOf<Int>()
            var lastHeaderIdx = -1

            allItems.forEachIndexed { idx, item ->
                when (item) {
                    is HistoryItem.Header -> lastHeaderIdx = idx
                    is HistoryItem.Record -> {
                        val numDigits   = item.record.phoneNumber.filter { it.isDigit() }
                        val numDisplay  = item.record.displayNumber.lowercase()
                        val matches = if (q.all { it.isDigit() }) {
                            numDigits.contains(q)
                        } else {
                            numDisplay.contains(q)
                        }
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

        // Empty state: phân biệt "chưa có dữ liệu" vs "tìm không thấy"
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

    // -------------------------------------------------------------------------
    // Build items
    // -------------------------------------------------------------------------

    private fun buildHistoryItems(
        records: List<CallRecord>,
        countMap: Map<String, Int>
    ): List<HistoryItem> {
        val result = mutableListOf<HistoryItem>()
        var lastDayKey = ""
        val todayKey     = todayFmt.format(Date())
        val yesterdayKey = todayFmt.format(
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

    // Helper extension tránh import thêm
    private fun android.animation.Animator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    override fun onBackPressed() {
        if (searchVisible) hideSearch()
        else super.onBackPressed()
    }
}
