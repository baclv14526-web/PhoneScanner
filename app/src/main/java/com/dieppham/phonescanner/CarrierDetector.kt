package com.dieppham.phonescanner

import android.graphics.Color

/**
 * Nhận diện nhà mạng Việt Nam theo đầu số (3 chữ số đầu của số dạng 0xxxxxxxxx).
 *
 * Lưu ý: đây là nhà mạng GỐC theo đầu số. Thuê bao đã chuyển mạng giữ số (MNP)
 * sẽ vẫn hiện theo đầu số cũ — không có cách nào biết chính xác nếu không tra cứu nhà mạng.
 */
object CarrierDetector {

    const val VIETTEL = "Viettel"
    const val VINAPHONE = "VinaPhone"
    const val MOBIFONE = "MobiFone"
    const val VIETNAMOBILE = "Vietnamobile"
    const val GMOBILE = "Gmobile"
    const val ITEL = "iTel"
    const val WINTEL = "Wintel"
    const val LANDLINE = "Điện thoại bàn"
    const val UNKNOWN = "Không xác định"

    private val PREFIXES: Map<String, String> = buildMap {
        listOf("032", "033", "034", "035", "036", "037", "038", "039", "086", "096", "097", "098")
            .forEach { put(it, VIETTEL) }
        listOf("081", "082", "083", "084", "085", "088", "091", "094")
            .forEach { put(it, VINAPHONE) }
        listOf("070", "076", "077", "078", "079", "089", "090", "093")
            .forEach { put(it, MOBIFONE) }
        listOf("052", "056", "058", "092")
            .forEach { put(it, VIETNAMOBILE) }
        listOf("059", "099")
            .forEach { put(it, GMOBILE) }
        put("087", ITEL)
        put("055", WINTEL)
    }

    /** Trả về tên nhà mạng; số bàn (02x) trả LANDLINE; không khớp trả UNKNOWN. */
    fun detect(phone: String): String {
        val local = toLocal(phone) ?: return UNKNOWN
        if (local.startsWith("02")) return LANDLINE
        return PREFIXES[local.take(3)] ?: UNKNOWN
    }

    /** Màu nhấn hiển thị cho từng nhà mạng (nền tối). */
    fun colorFor(carrier: String): Int = Color.parseColor(
        when (carrier) {
            VIETTEL      -> "#FF5252"
            VINAPHONE    -> "#40C4FF"
            MOBIFONE     -> "#FFAB40"
            VIETNAMOBILE -> "#FF80AB"
            GMOBILE      -> "#B388FF"
            ITEL         -> "#69F0AE"
            WINTEL       -> "#FFD740"
            LANDLINE     -> "#B0BEC5"
            else         -> "#78909C"
        }
    )

    // Chuẩn hóa về dạng 0xxxxxxxxx (chấp nhận +84 / 84 ở đầu)
    private fun toLocal(phone: String): String? {
        val d = phone.filter { it.isDigit() }
        return when {
            d.startsWith("84") && d.length >= 11 -> "0" + d.substring(2)
            d.startsWith("0") && d.length >= 10  -> d
            else -> null
        }
    }
}
