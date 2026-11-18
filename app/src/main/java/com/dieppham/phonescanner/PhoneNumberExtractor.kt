package com.dieppham.phonescanner

import com.google.mlkit.vision.text.Text

object PhoneNumberExtractor {

    private val RAW_CANDIDATE_REGEX = Regex("""[+]?[\d][\d\s.\-()]{7,16}\d""")
    private val MOBILE_REGEX  = Regex("""^(?:\+?84|0)(3|5|7|8|9)(\d{8})$""")
    private val LANDLINE_REGEX = Regex("""^(?:\+?84|0)(2\d)(\d{7,8})$""")

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
    // CHỈ DÙNG CHO ẢNH THƯ VIỆN (ảnh chụp chat nhiều dòng). Camera không dùng hàm này.
    //
    // Ý tưởng: bỏ qua mọi chữ, chỉ nhìn vào các "cụm số" (0912, 345, 678, +84...).
    // Các cụm số đứng liền nhau (chỉ cách bởi khoảng trắng, xuống dòng, . - ( ) )
    // được thử ghép lại thành một số điện thoại hợp lệ. Nhờ vậy:
    //  - dòng chữ thường (không chứa số) bị bỏ qua hoàn toàn;
    //  - dòng có số khác (giờ 10:45, ngày 15/11/2023, mã đơn, giá tiền) không ghép
    //    được thành SĐT hợp lệ nên bị loại;
    //  - SĐT bị ngắt xuống dòng trong bong bóng chat vẫn được ghép lại.
    // -------------------------------------------------------------------------
    private val DIGIT_CHUNK_REGEX = Regex("""\+?\d(?:[\d.\-()]*\d)?""")
    private val CHUNK_GAP_REGEX = Regex("""[\s().\-]*""")
    private const val MAX_DIGITS = 12          // +84 + 2 số vùng + 8 số = 12 chữ số là tối đa
    private const val MAX_CHUNKS_PER_NUMBER = 6

    fun extractForGallery(visionText: Text): List<String> {
        val results = LinkedHashSet<String>()
        for (block in visionText.textBlocks) {
            val text = block.lines.joinToString("\n") { it.text }
            results += extractNumbersFromText(text)
        }
        return results.toList()
    }

    private fun extractNumbersFromText(text: String): List<String> {
        val chunks = DIGIT_CHUNK_REGEX.findAll(text).toList()
        val found = mutableListOf<String>()
        var i = 0
        while (i < chunks.size) {
            val prefix = if (chunks[i].value.startsWith("+")) "+" else ""
            val digits = StringBuilder()
            var matchedEnd = -1
            var j = i
            while (j < chunks.size && j < i + MAX_CHUNKS_PER_NUMBER) {
                if (j > i) {
                    val gap = text.substring(chunks[j - 1].range.last + 1, chunks[j].range.first)
                    if (!CHUNK_GAP_REGEX.matches(gap)) break   // có chữ xen giữa → không phải cùng 1 số
                }
                digits.append(chunks[j].value.filter { it.isDigit() })
                if (digits.length > MAX_DIGITS) break
                val normalized = normalizeIfValid("$prefix$digits")
                if (normalized != null) {
                    found += normalized
                    matchedEnd = j
                    break
                }
                j++
            }
            i = if (matchedEnd >= 0) matchedEnd + 1 else i + 1
        }
        return found
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
