package com.dieppham.phonescanner

/**
 * Trích xuất số điện thoại kiểu Việt Nam từ text do OCR đọc được.
 *
 * Hỗ trợ:
 *  - Di động 10 số: 03x/05x/07x/08x/09x + 7 số  (vd: 0912345678)
 *  - Dạng +84 hoặc 84 thay cho số 0 đầu          (vd: +84912345678)
 *  - Cố định có mã vùng 2 số: 02x + 8 số          (vd: 02438123456)
 *
 * OCR hay chèn khoảng trắng/dấu chấm/gạch ngang giữa các cụm số
 * (vd: "091.234.5678" hay "0912 345 678") nên ta xoá hết ký tự phân
 * cách trước khi so khớp regex.
 */
object PhoneNumberExtractor {

    // Cho phép số, khoảng trắng, dấu chấm, gạch ngang, gạch chéo, ngoặc, dấu +
    // giữa các nhóm chữ số khi quét thô từ text gốc.
    private val RAW_CANDIDATE_REGEX = Regex("""[+]?[\d][\d\s.\-()]{7,16}\d""")

    // Sau khi đã làm sạch (chỉ còn chữ số, có thể có dấu + ở đầu)
    private val MOBILE_REGEX = Regex("""^(?:\+?84|0)(3|5|7|8|9)(\d{8})$""")
    private val LANDLINE_REGEX = Regex("""^(?:\+?84|0)(2\d)(\d{7,8})$""")

    /**
     * Quét toàn bộ text OCR, trả về số điện thoại VN hợp lệ đầu tiên tìm thấy
     * (đã chuẩn hoá về dạng bắt đầu bằng 0), hoặc null nếu không có.
     */
    fun extractFirstValidNumber(ocrText: String): String? {
        for (match in RAW_CANDIDATE_REGEX.findAll(ocrText)) {
            val cleaned = cleanCandidate(match.value)
            val normalized = normalizeIfValid(cleaned)
            if (normalized != null) return normalized
        }
        return null
    }

    private fun cleanCandidate(raw: String): String {
        val hasPlus = raw.trimStart().startsWith("+")
        val digitsOnly = raw.filter { it.isDigit() }
        return if (hasPlus) "+$digitsOnly" else digitsOnly
    }

    private fun normalizeIfValid(cleaned: String): String? {
        MOBILE_REGEX.find(cleaned)?.let { m ->
            val head = m.groupValues[1]
            val tail = m.groupValues[2]
            return "0$head$tail"
        }
        LANDLINE_REGEX.find(cleaned)?.let { m ->
            val area = m.groupValues[1]
            val tail = m.groupValues[2]
            return "0$area$tail"
        }
        return null
    }

    /** Định dạng đẹp để hiển thị, vd: 0912345678 -> 0912 345 678 */
    fun formatForDisplay(number: String): String {
        return when (number.length) {
            10 -> "${number.substring(0, 4)} ${number.substring(4, 7)} ${number.substring(7)}"
            11 -> "${number.substring(0, 4)} ${number.substring(4, 7)} ${number.substring(7)}"
            else -> number
        }
    }
}
