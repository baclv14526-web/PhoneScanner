package com.dieppham.phonescanner

import com.google.mlkit.vision.text.Text

object PhoneNumberExtractor {

    private val RAW_CANDIDATE_REGEX = Regex("""[+]?[\d][\d\s.\-()]{7,16}\d""")
    private val MOBILE_REGEX  = Regex("""^(?:\+?84|0)(3|5|7|8|9)(\d{8})$""")
    private val LANDLINE_REGEX = Regex("""^(?:\+?84|0)(2\d)(\d{7,8})$""")

    // -------------------------------------------------------------------------
    // API cũ - vẫn giữ để tương thích debug label
    // -------------------------------------------------------------------------
    fun extractFirstValidNumber(ocrText: String): String? {
        for (match in RAW_CANDIDATE_REGEX.findAll(ocrText)) {
            val normalized = normalizeIfValid(cleanCandidate(match.value))
            if (normalized != null) return normalized
        }
        return null
    }

    // -------------------------------------------------------------------------
    // API mới: trả về số hợp lệ CÓ TỌA ĐỘ tâm (centerY tính theo tỉ lệ 0..1
    // trong ảnh) để caller chọn số nào gần tâm khung hình nhất.
    //
    // ML Kit trả kết quả theo cấu trúc: VisionText → Block → Line → Element.
    // Mỗi Line có boundingBox -> ta dùng centerY của Line để so sánh vị trí.
    // -------------------------------------------------------------------------
    data class NumberCandidate(val number: String, val centerYRatio: Float)

    fun extractCandidatesWithPosition(
        visionText: Text,
        imageHeight: Int
    ): List<NumberCandidate> {
        val results = mutableListOf<NumberCandidate>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                // Thử khớp toàn bộ text của dòng này
                val lineText = line.text
                for (match in RAW_CANDIDATE_REGEX.findAll(lineText)) {
                    val normalized = normalizeIfValid(cleanCandidate(match.value))
                    if (normalized != null) {
                        val box = line.boundingBox
                        val centerY = if (box != null && imageHeight > 0)
                            box.exactCenterY() / imageHeight
                        else
                            0.5f   // fallback: coi như ở giữa nếu không có bbox
                        results += NumberCandidate(normalized, centerY)
                        break   // chỉ lấy 1 số mỗi dòng, tránh đếm trùng
                    }
                }
            }
        }
        return results
    }

    // -------------------------------------------------------------------------
    // Chọn số gần tâm ảnh nhất (centerYRatio gần 0.5 nhất).
    // Khung xanh hướng dẫn nằm ở khoảng giữa màn hình, nên số nào có tọa độ
    // dọc gần 0.5 nhất chính là số đang nằm trong khung xanh.
    // -------------------------------------------------------------------------
    fun pickClosestToCenter(candidates: List<NumberCandidate>): String? {
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { Math.abs(it.centerYRatio - 0.5f) }?.number
    }

    // -------------------------------------------------------------------------
    private fun cleanCandidate(raw: String): String {
        val hasPlus = raw.trimStart().startsWith("+")
        val digitsOnly = raw.filter { it.isDigit() }
        return if (hasPlus) "+$digitsOnly" else digitsOnly
    }

    private fun normalizeIfValid(cleaned: String): String? {
        MOBILE_REGEX.find(cleaned)?.let { m ->
            return "0${m.groupValues[1]}${m.groupValues[2]}"
        }
        LANDLINE_REGEX.find(cleaned)?.let { m ->
            return "0${m.groupValues[1]}${m.groupValues[2]}"
        }
        return null
    }

    fun formatForDisplay(number: String): String {
        return when (number.length) {
            10, 11 -> "${number.substring(0, 4)} ${number.substring(4, 7)} ${number.substring(7)}"
            else   -> number
        }
    }
}
