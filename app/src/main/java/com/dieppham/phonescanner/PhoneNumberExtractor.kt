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
    // centerXRatio: vị trí ngang của dòng (0..1 theo chiều rộng ảnh); mặc định 0.5 khi caller không
    // truyền imageWidth (ảnh thư viện không lọc theo khung nên không cần).
    data class NumberCandidate(
        val number: String,
        val centerYRatio: Float,
        val centerXRatio: Float = 0.5f
    )

    fun extractCandidatesWithPosition(
        visionText: Text,
        imageHeight: Int,
        imageWidth: Int = 0
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
                        val centerX = if (box != null && imageWidth > 0)
                            box.exactCenterX() / imageWidth
                        else
                            0.5f
                        results += NumberCandidate(normalized, centerY, centerX)
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

    // Ký tự OCR hay đọc nhầm từ chữ số (O↔0, l/I/|↔1, S↔5, B↔8, Z↔2, G↔6, g/q↔9)
    private val CONFUSABLE_DIGITS = mapOf(
        'O' to '0', 'o' to '0',
        'I' to '1', 'l' to '1', '|' to '1',
        'Z' to '2', 'z' to '2',
        'S' to '5', 's' to '5',
        'G' to '6',
        'B' to '8',
        'g' to '9', 'q' to '9'
    )
    // Tách văn bản thành các "từ" (chữ, số, và . - ( ) + |); dấu câu khác như ':' ',' là ranh giới
    private val WORD_TOKEN_REGEX = Regex("""[\p{L}\p{N}.\-()+|]+""")

    fun extractForGallery(visionText: Text): List<String> {
        val results = LinkedHashSet<String>()
        for (block in visionText.textBlocks) {
            val text = block.lines.joinToString("\n") { it.text }
            // Lượt 1: đọc đúng như OCR trả về
            results += extractNumbersFromText(text)
            // Lượt 2: sửa các ký tự bị đọc nhầm thành chữ (O→0, l→1...) rồi đọc lại.
            // Số chỉ được nhận nếu sau khi sửa vẫn là SĐT hợp lệ, nên không sinh số bừa.
            val corrected = correctOcrDigitConfusions(text)
            if (corrected != text) results += extractNumbersFromText(corrected)
        }
        return results.toList()
    }

    /**
     * Thay ký tự dễ nhầm bằng chữ số, nhưng CHỈ trong những "từ" thật sự giống số:
     * có ít nhất 1 chữ số thật và không chứa chữ cái nào khác (vd "O912345678", "09l2", "0g12345678").
     * Từ thuần chữ ("SOS", "Zalo", "BOB") hoặc lẫn chữ khác ("SĐT") giữ nguyên.
     * Thay 1-1 theo ký tự nên vị trí trong chuỗi không đổi.
     */
    private fun correctOcrDigitConfusions(text: String): String {
        val sb = StringBuilder(text)
        for (m in WORD_TOKEN_REGEX.findAll(text)) {
            val token = m.value
            var realDigits = 0
            var confusables = 0
            var otherLetters = 0
            for (c in token) {
                when {
                    c in '0'..'9' -> realDigits++
                    CONFUSABLE_DIGITS.containsKey(c) -> confusables++
                    c.isLetter() -> otherLetters++
                }
            }
            if (otherLetters == 0 && confusables > 0 && realDigits >= 1) {
                for (k in token.indices) {
                    CONFUSABLE_DIGITS[token[k]]?.let { sb.setCharAt(m.range.first + k, it) }
                }
            }
        }
        return sb.toString()
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
