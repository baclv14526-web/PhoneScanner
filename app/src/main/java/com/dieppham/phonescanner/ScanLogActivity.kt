package com.dieppham.phonescanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.text.InputFilter
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dieppham.phonescanner.databinding.ActivityScanLogBinding
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Nhật ký số đã quét — tính năng độc lập với lịch sử cuộc gọi.
 *  - Mỗi lượt quét: số điện thoại, nhà mạng, thời gian quét.
 *  - Gom nhóm theo ngày (mới nhất trước).
 *  - Lọc theo mốc thời gian (hôm nay, hôm qua, 7/30 ngày, tháng này, khoảng ngày tùy chọn).
 *  - Tìm kiếm theo số, nhà mạng, ghi chú, ngày (vd 15/11) hoặc giờ (vd 09:30).
 *  - Cùng số quét lại trong vòng 1 phút được gộp thành 1 dòng "×N".
 *  - Sắp xếp trong mỗi ngày: theo giờ quét (mặc định) hoặc theo số điện thoại.
 *  - Mỗi số có thể có 1 ghi chú (nút 📝/✏️ cạnh số).
 */
class ScanLogActivity : AppCompatActivity() {

    private enum class Preset { ALL, TODAY, YESTERDAY, LAST_7, LAST_30, THIS_MONTH, CUSTOM }

    private lateinit var binding: ActivityScanLogBinding
    private lateinit var adapter: ScanLogAdapter
    private lateinit var dao: ScanRecordDao

    private var preset = Preset.ALL
    private var customFrom = 0L
    private var customTo = 0L
    private var query = ""
    private var sortByPhone = false
    private var loadJob: Job? = null

    // Dữ liệu mới nhất từ DB cho khoảng thời gian đang chọn (chưa lọc theo ô tìm kiếm)
    private var latestRecords: List<ScanRecord> = emptyList()
    private var latestCounts: Map<String, Int> = emptyMap()
    private var latestNotes: Map<String, String> = emptyMap()

    private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayLabelFmt = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi"))
    private val dateTimeFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
    private val shortDateFmt = SimpleDateFormat("dd/MM", Locale.US)
    private val fullDateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    private val customChipDefaultText = "📅 Chọn ngày…"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        dao = AppDatabase.get(this).scanRecordDao()

        adapter = ScanLogAdapter(
            onRowClick = { showRowActions(it) },
            onNoteClick = { phone, note -> showNoteDialog(phone, note) }
        )
        binding.recyclerScanLog.layoutManager = LinearLayoutManager(this)
        binding.recyclerScanLog.adapter = adapter

        setupSearch()
        setupChips()
        binding.sortGroup.setOnCheckedStateChangeListener { _, ids ->
            val byPhone = ids.firstOrNull() == R.id.chipSortPhone
            if (byPhone != sortByPhone) { sortByPhone = byPhone; render() }
        }

        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xóa toàn bộ nhật ký quét?")
                .setMessage("Lịch sử cuộc gọi không bị ảnh hưởng. Không thể hoàn tác.")
                .setPositiveButton("Xóa") { _, _ -> lifecycleScope.launch { dao.deleteAll() } }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    // Tính lại khoảng thời gian mỗi lần mở lại màn hình (để "Hôm nay" luôn đúng khi qua nửa đêm)
    override fun onResume() {
        super.onResume()
        reload()
    }

    // -------------------------------------------------------------------------
    // Nạp dữ liệu theo mốc thời gian
    // -------------------------------------------------------------------------

    private fun reload() {
        loadJob?.cancel()
        val (from, to) = currentRange()
        loadJob = lifecycleScope.launch {
            combine(dao.observeRange(from, to), dao.observeCounts(), dao.observeNotes()) { recs, counts, notes ->
                Triple(recs, counts, notes)
            }
                .collect { (recs, counts, notes) ->
                    latestRecords = recs
                    latestCounts = counts.associate { it.phoneNumber to it.cnt }
                    latestNotes = notes.associate { it.phoneNumber to it.note }
                    render()
                }
        }
    }

    private fun currentRange(): Pair<Long, Long> {
        val todayStart = startOfDay(0)
        return when (preset) {
            Preset.ALL        -> 0L to Long.MAX_VALUE
            Preset.TODAY      -> todayStart to endOfDay(todayStart)
            Preset.YESTERDAY  -> startOfDay(1) to endOfDay(startOfDay(1))
            Preset.LAST_7     -> startOfDay(6) to endOfDay(todayStart)
            Preset.LAST_30    -> startOfDay(29) to endOfDay(todayStart)
            Preset.THIS_MONTH -> startOfMonth() to endOfDay(todayStart)
            Preset.CUSTOM     -> customFrom to customTo
        }
    }

    // 00:00:00.000 của ngày cách hôm nay [daysAgo] ngày (giờ địa phương)
    private fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    private fun startOfMonth(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // 23:59:59.999 của ngày bắt đầu tại [dayStart]
    private fun endOfDay(dayStart: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis - 1

    // MaterialDatePicker trả về nửa đêm UTC của ngày được chọn → đổi sang nửa đêm giờ địa phương
    // của đúng ngày đó (nếu không, người dùng ở UTC+7 sẽ bị lệch ngày).
    private fun utcDayToLocalStart(utcMillis: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    // -------------------------------------------------------------------------
    // Chip mốc thời gian
    // -------------------------------------------------------------------------

    private fun setupChips() {
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newPreset = when (id) {
                R.id.chipAll       -> Preset.ALL
                R.id.chipToday     -> Preset.TODAY
                R.id.chipYesterday -> Preset.YESTERDAY
                R.id.chipLast7     -> Preset.LAST_7
                R.id.chipLast30    -> Preset.LAST_30
                R.id.chipThisMonth -> Preset.THIS_MONTH
                else -> return@setOnCheckedStateChangeListener   // chipCustom: xử lý ở sự kiện click
            }
            preset = newPreset
            binding.chipCustom.text = customChipDefaultText
            reload()
        }
        binding.chipCustom.setOnClickListener { showDateRangePicker() }
    }

    private fun chipIdFor(p: Preset): Int = when (p) {
        Preset.ALL        -> R.id.chipAll
        Preset.TODAY      -> R.id.chipToday
        Preset.YESTERDAY  -> R.id.chipYesterday
        Preset.LAST_7     -> R.id.chipLast7
        Preset.LAST_30    -> R.id.chipLast30
        Preset.THIS_MONTH -> R.id.chipThisMonth
        Preset.CUSTOM     -> R.id.chipCustom
    }

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Chọn khoảng ngày")
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            val from = utcDayToLocalStart(sel.first)
            val to = endOfDay(utcDayToLocalStart(sel.second))
            customFrom = from
            customTo = to
            preset = Preset.CUSTOM
            binding.chipCustom.text = rangeLabel(from, to)
            reload()
        }
        // Hủy/đóng hộp thoại: trả dấu chọn về mốc đang áp dụng (chip "Chọn ngày" vừa bị tick khi bấm)
        picker.addOnDismissListener { binding.chipGroup.check(chipIdFor(preset)) }
        picker.show(supportFragmentManager, "scan_log_date_range")
    }

    private fun rangeLabel(from: Long, to: Long): String {
        val sameDay = dayKeyFmt.format(Date(from)) == dayKeyFmt.format(Date(to))
        return if (sameDay) "📅 ${fullDateFmt.format(Date(from))}"
        else "📅 ${shortDateFmt.format(Date(from))} – ${shortDateFmt.format(Date(to))}"
    }

    // -------------------------------------------------------------------------
    // Tìm kiếm + dựng danh sách
    // -------------------------------------------------------------------------

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                render()
            }
        })
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
                true
            } else false
        }
    }

    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd')
            .replace('Đ', 'D')

    /**
     * Ô tìm kiếm khớp theo 3 kiểu:
     *  - số điện thoại: gõ toàn chữ số (cho phép khoảng trắng . - +), khớp 1 phần, hiểu cả +84
     *  - nhà mạng: gõ tên, không phân biệt hoa thường/dấu ("viettel", "ban" → Điện thoại bàn)
     *  - thời gian: có dấu / hoặc : → khớp với "dd/MM/yyyy HH:mm" (vd "15/11", "09:30")
     */
    private fun filterRecords(records: List<ScanRecord>): List<ScanRecord> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return records

        val qDigits = q.filter { it.isDigit() }
        val numericLike = qDigits.isNotEmpty() && q.all { it.isDigit() || it == ' ' || it == '.' || it == '-' || it == '+' }
        // +84912... / 84912... → 0912...
        val qDigitsLocal = if (qDigits.startsWith("84") && qDigits.length > 2) "0" + qDigits.drop(2) else null
        val dateTimeQuery = q.contains('/') || q.contains(':')
        val qNorm = stripAccents(q)

        return records.filter { r ->
            (numericLike && (r.phoneNumber.contains(qDigits) ||
                (qDigitsLocal != null && r.phoneNumber.contains(qDigitsLocal)))) ||
                (dateTimeQuery && dateTimeFmt.format(Date(r.timestamp)).contains(q)) ||
                stripAccents(r.carrier.lowercase(Locale.ROOT)).contains(qNorm) ||
                (latestNotes[r.phoneNumber]?.let { stripAccents(it.lowercase(Locale.ROOT)).contains(qNorm) } ?: false)
        }
    }

    private fun render() {
        val filtered = filterRecords(latestRecords)

        val todayKey = dayKeyFmt.format(Date())
        val yesterdayKey = dayKeyFmt.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        )

        // Gom theo ngày, giữ thứ tự mới nhất trước (DB đã sắp xếp giảm dần theo thời gian)
        val groups = linkedMapOf<String, MutableList<ScanRecord>>()
        filtered.forEach { r ->
            groups.getOrPut(dayKeyFmt.format(Date(r.timestamp))) { mutableListOf() }.add(r)
        }

        val items = mutableListOf<ScanLogItem>()
        groups.forEach { (dayKey, recs) ->
            val label = when (dayKey) {
                todayKey     -> "HÔM NAY"
                yesterdayKey -> "HÔM QUA"
                else         -> dayLabelFmt.format(Date(recs.first().timestamp)).uppercase()
            }
            items += ScanLogItem.DayHeader(dayKey, label, recs.sumOf { it.scanCount })
            // Mặc định: mới nhất trước (đã sắp từ DB). Theo số: cùng số đứng liền nhau,
            // trong cùng số vẫn mới nhất trước (sortedWith ổn định, giữ thứ tự thời gian).
            val ordered = if (sortByPhone) recs.sortedBy { it.phoneNumber } else recs
            ordered.forEach { items += ScanLogItem.Row(it, latestNotes[it.phoneNumber]) }
        }
        adapter.submitList(items)

        val empty = filtered.isEmpty()
        binding.layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            if (latestCounts.isEmpty()) {
                binding.tvEmptyIcon.text = "📖"
                binding.tvEmptyText.text = "Chưa có số nào được quét"
            } else {
                binding.tvEmptyIcon.text = "🔍"
                binding.tvEmptyText.text = "Không có kết quả phù hợp"
            }
            binding.tvSummary.visibility = View.GONE
        } else {
            val distinct = filtered.map { it.phoneNumber }.distinct().size
            binding.tvSummary.text = "${filtered.sumOf { it.scanCount }} lượt quét · $distinct số khác nhau"
            binding.tvSummary.visibility = View.VISIBLE
        }
    }

    // -------------------------------------------------------------------------
    // Thao tác trên một dòng
    // -------------------------------------------------------------------------

    private fun showNoteDialog(phone: String, currentNote: String?) {
        val display = PhoneNumberExtractor.formatForDisplay(phone)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(currentNote ?: "")
            setSelection(text.length)
            hint = "Ví dụ: Anh Nam – giao hàng"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 5
            filters = arrayOf(InputFilter.LengthFilter(200))
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (currentNote.isNullOrBlank()) "Thêm ghi chú · $display" else "Sửa ghi chú · $display")
            .setView(container)
            .setPositiveButton("Lưu") { _, _ ->
                val text = input.text.toString().trim()
                lifecycleScope.launch {
                    if (text.isEmpty()) dao.deleteNote(phone)
                    else dao.upsertNote(PhoneNote(phone, text, System.currentTimeMillis()))
                }
            }
            .setNegativeButton("Hủy", null)
        if (!currentNote.isNullOrBlank()) {
            builder.setNeutralButton("Xóa ghi chú") { _, _ ->
                lifecycleScope.launch { dao.deleteNote(phone) }
            }
        }
        builder.show()
        input.requestFocus()
    }

    private fun showRowActions(r: ScanRecord) {
        val display = PhoneNumberExtractor.formatForDisplay(r.phoneNumber)
        val actions = arrayOf("📞 Mở bàn phím gọi", "📋 Sao chép số", "🗑 Xóa bản ghi này")
        AlertDialog.Builder(this)
            .setTitle("$display · ${r.carrier}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${r.phoneNumber}")))
                    1 -> {
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("phone", r.phoneNumber))
                        Toast.makeText(this, "Đã sao chép $display", Toast.LENGTH_SHORT).show()
                    }
                    2 -> lifecycleScope.launch { dao.deleteById(r.id) }
                }
            }
            .show()
    }
}
