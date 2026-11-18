package com.example.campusmarkettek.ui.common

import com.example.campusmarkettek.BuildConfig
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

/** ราคาเป็นบาทมีจุลภาค เช่น ฿4,900 */
fun formatPrice(price: Int): String = "฿" + NumberFormat.getIntegerInstance(Locale.US).format(price)

/** URL เต็มของรูปจากค่าสัมพัทธ์ที่ backend ส่งมา เช่น /uploads/listings/<id>/0.jpg */
fun imageUrl(path: String): String = BuildConfig.API_BASE_URL.trimEnd('/') + path

/** "เมื่อสักครู่ / 5 นาทีที่แล้ว / 3 วันที่แล้ว" จากเวลา epoch millis */
fun relativeThai(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = (now - epochMillis) / 1000
    return when {
        seconds < 60 -> "เมื่อสักครู่"
        seconds < 3600 -> "${seconds / 60} นาทีที่แล้ว"
        seconds < 86_400 -> "${seconds / 3600} ชั่วโมงที่แล้ว"
        seconds < 86_400 * 30 -> "${seconds / 86_400} วันที่แล้ว"
        else -> "${seconds / (86_400 * 30)} เดือนที่แล้ว"
    }
}

fun relativeThai(iso: String): String = relativeThai(Instant.parse(iso).toEpochMilli())

/** ป้ายสถานะประกาศ (ข้อ 2.10): AVAILABLE | RESERVED | SOLD | CANCELLED | RETURNED | TAKEN_DOWN */
fun statusLabel(status: String): String = when (status) {
    "AVAILABLE" -> "กำลังขาย"
    "RESERVED" -> "ถูกจอง"
    "SOLD" -> "ขายแล้ว"
    "CANCELLED" -> "ยกเลิกแล้ว"
    "RETURNED" -> "ถูกตีกลับ"
    "TAKEN_DOWN" -> "ถูกถอด"
    else -> status
}
