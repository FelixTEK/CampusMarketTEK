package com.example.campusmarkettek.domain

/**
 * ตรวจข้อมูลฝั่งแอปก่อนส่ง — ตัวเลขทุกตัวตรงกับ CHECK ในฐานข้อมูล (backend/migrations/001_init.sql)
 * ถ้าฐานข้อมูลเปลี่ยน ต้องแก้ไฟล์นี้ให้ตรงกัน
 * คืนข้อความภาษาไทยเมื่อไม่ผ่าน และ null เมื่อผ่าน
 */
object Validator {

    // users.display_name: 1–50
    fun displayName(value: String): String? =
        if (value.trim().length in 1..50) null else "ชื่อที่แสดงต้องยาว 1–50 ตัวอักษร"

    // users.email: ≤ 254 และมีรูปแบบ x@y.z (ฐานข้อมูลเก็บเป็นตัวพิมพ์เล็ก)
    private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    fun email(value: String): String? {
        val v = value.trim()
        return if (v.length <= 254 && emailPattern.matches(v)) null else "รูปแบบอีเมลไม่ถูกต้อง"
    }

    // listings.title: 3–100
    fun title(value: String): String? =
        if (value.trim().length in 3..100) null else "ชื่อสินค้าต้องยาว 3–100 ตัวอักษร"

    // listings.description: ≤ 2000
    fun description(value: String): String? =
        if (value.length <= 2000) null else "รายละเอียดยาวได้ไม่เกิน 2,000 ตัวอักษร"

    // listings.price: > 0 (จำนวนเต็ม)
    fun price(value: Int?): String? =
        if (value != null && value > 0) null else "ราคาต้องมากกว่า 0 บาท"

    // listing_images.position 0–4 → 1–5 รูป
    fun imageCount(count: Int): String? =
        if (count in 1..5) null else "ต้องมีรูปสินค้า 1–5 รูป"

    // deals.meetup: ≤ 200
    fun meetup(value: String): String? =
        if (value.length <= 200) null else "สถานที่นัดรับยาวได้ไม่เกิน 200 ตัวอักษร"

    // messages.body: 1–1000
    fun messageBody(value: String): String? =
        if (value.trim().length in 1..1000) null else "ข้อความต้องยาว 1–1,000 ตัวอักษร"

    // reviews.rating: 1–5, reviews.comment: ≤ 300
    fun rating(value: Int): String? =
        if (value in 1..5) null else "ให้คะแนน 1–5 ดาว"

    fun reviewComment(value: String): String? =
        if (value.length <= 300) null else "ความเห็นยาวได้ไม่เกิน 300 ตัวอักษร"

    // reports.detail: ≤ 300
    fun reportDetail(value: String): String? =
        if (value.length <= 300) null else "รายละเอียดยาวได้ไม่เกิน 300 ตัวอักษร"
}
