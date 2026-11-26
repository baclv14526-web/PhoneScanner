package com.dieppham.phonescanner

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Xuất/nhập nhật ký quét dạng file .txt (UTF-8). Mỗi dòng, các cột cách nhau bằng TAB:
 *
 *   SĐT <TAB> yyyy-MM-dd HH:mm:ss <TAB> số lần quét <TAB> nhà mạng <TAB> ghi chú      (v2)
 *
 * File v1 cũ (không có cột nhà mạng: SĐT, thời gian, số lần, ghi chú) vẫn nhập được; phiên bản
 * được nhận biết qua dòng tiêu đề "# ... v2". Khi nhập, nhà mạng được nhận lại theo đầu số.
 *
 * - Dòng bắt đầu bằng '#' là chú thích, bỏ qua khi nhập.
 * - Ghi chú của số được lặp ở mọi dòng của số đó; xuống dòng trong ghi chú ghi là "\n".
 * - Số chỉ có ghi chú (đã xóa nhật ký) ghi với cột thời gian để trống.
 */
object ScanLogTransfer {

    data class Entry(val phone: String, val timestamp: Long?, val scanCount: Int, val note: String)
    data class Parsed(val entries: List<Entry>, val skippedLines: Int)

    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private val PHONE_REGEX = Regex("""^0\d{9,10}$""")

    private fun fmt() = SimpleDateFormat(TIME_PATTERN, Locale.US).apply { isLenient = false }

    private fun escapeNote(s: String): String =
        s.replace("\\", "\\\\").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n").replace("\t", " ")

    private fun unescapeNote(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    fun buildExport(records: List<ScanRecord>, notes: List<PhoneNote>): String {
        val f = fmt()
        val noteMap = notes.associate { it.phoneNumber to it.note }
        val sb = StringBuilder()
        sb.append("# Nhat ky quet - PhoneScanner v2\n")
        sb.append("# Cot (cach nhau bang TAB): SDT | Thoi gian quet (yyyy-MM-dd HH:mm:ss) | So lan quet | Nha mang | Ghi chu\n")
        records.sortedWith(compareBy<ScanRecord> { it.timestamp }.thenBy { it.id }).forEach { r ->
            sb.append(r.phoneNumber).append('\t')
                .append(f.format(Date(r.timestamp))).append('\t')
                .append(r.scanCount).append('\t')
                .append(r.carrier.replace('\t', ' ')).append('\t')
                .append(escapeNote(noteMap[r.phoneNumber] ?: "")).append('\n')
        }
        val withRecords = records.map { it.phoneNumber }.toSet()
        notes.filter { it.phoneNumber !in withRecords }.forEach { n ->
            sb.append(n.phoneNumber).append("\t\t\t")
                .append(CarrierDetector.detect(n.phoneNumber)).append('\t')
                .append(escapeNote(n.note)).append('\n')
        }
        return sb.toString()
    }

    private fun normalizePhone(raw: String): String? {
        var d = raw.trim().filter { it.isDigit() }
        if (d.startsWith("84") && d.length in 11..12) d = "0" + d.drop(2)
        return if (PHONE_REGEX.matches(d)) d else null
    }

    fun parse(text: String): Parsed {
        val f = fmt()
        val entries = mutableListOf<Entry>()
        var skipped = 0
        val v2 = text.lineSequence().takeWhile { it.isBlank() || it.trimStart().startsWith("#") }
            .any { it.contains("PhoneScanner v2") }
        val noteIdx = if (v2) 4 else 3
        text.removePrefix("\uFEFF").lineSequence().forEach { line0 ->
            val line = line0.trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
            val cols = line.split('\t', limit = noteIdx + 1)
            val phone = normalizePhone(cols[0])
            if (phone == null) { skipped++; return@forEach }
            val timeStr = cols.getOrNull(1)?.trim().orEmpty()
            val ts = if (timeStr.isEmpty()) null else runCatching { f.parse(timeStr)?.time }.getOrNull()
            if (timeStr.isNotEmpty() && ts == null) { skipped++; return@forEach }
            val count = cols.getOrNull(2)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val note = unescapeNote(cols.getOrNull(noteIdx).orEmpty()).trim().take(200)
            entries += Entry(phone, ts, count, note)
        }
        return Parsed(entries, skipped)
    }
}
