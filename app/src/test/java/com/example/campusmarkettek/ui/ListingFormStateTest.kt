package com.example.campusmarkettek.ui

import com.example.campusmarkettek.data.remote.TagDto
import com.example.campusmarkettek.domain.Category
import com.example.campusmarkettek.ui.common.formatPrice
import com.example.campusmarkettek.ui.common.relativeThai
import com.example.campusmarkettek.ui.common.statusLabel
import com.example.campusmarkettek.ui.listing.ListingFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** กติกาของฟอร์มลงขายต้องตรงกับ BR04 และ CHECK ในฐานข้อมูล */
class ListingFormStateTest {

    // ต้นไม้หมดจำลองตามข้อมูลจริง: 1 หนังสือเรียน › 10 (000) › 11 (004) · 2 อุปกรณ์คอมพิวเตอร์ › 20 (อื่น ๆ)
    private val categories = listOf(
        Category(1, null, "หนังสือเรียน", 1),
        Category(10, 1, "000 คอมพิวเตอร์และความรู้ทั่วไป", 1),
        Category(11, 10, "004 วิทยาการคอมพิวเตอร์", 1),
        Category(2, null, "อุปกรณ์คอมพิวเตอร์", 2),
        Category(20, 2, "อื่น ๆ", 1),
    )
    private val tags = listOf(
        TagDto(1, "มีเฉลย", 1), TagDto(5, "ใช้เล่นเกม", 2), TagDto(11, "ขายด่วน", null),
    )

    private fun valid() = ListingFormState(
        categories = categories, tags = tags, categoryId = 11, title = "แคลคูลัส 1",
        price = "250", facultyIds = setOf(1), photos = listOf(ByteArray(1)), loading = false,
    )

    @Test fun complete_book_form_has_no_error() {
        assertNull(valid().firstError)
    }

    @Test fun category_must_be_a_leaf() {
        assertNotNull(valid().copy(categoryId = 10).categoryError) // หมวดกลาง (ยังมีหมวดย่อย)
        assertNotNull(valid().copy(categoryId = null).categoryError)
        assertNull(valid().copy(categoryId = 11).categoryError)
    }

    @Test fun book_needs_a_faculty_but_other_categories_do_not() {
        assertNotNull(valid().copy(facultyIds = emptySet()).facultyError)
        assertNull(valid().copy(categoryId = 20, facultyIds = emptySet()).facultyError)
    }

    @Test fun book_detection_and_path() {
        val s = valid()
        assertTrue(s.isBook)
        assertEquals(listOf(1, 10, 11), s.categoryPath.map { it.id })
        assertFalse(s.copy(categoryId = 20).isBook)
    }

    @Test fun applicable_tags_follow_root_category() {
        assertEquals(listOf(1, 11), valid().applicableTags.map { it.id })                 // หนังสือ: แท็กหนังสือ + ทุกหมวด
        assertEquals(listOf(5, 11), valid().copy(categoryId = 20).applicableTags.map { it.id })
    }

    @Test fun price_title_photos_follow_database_checks() {
        assertNotNull(valid().copy(price = "0").priceError)
        assertNotNull(valid().copy(price = "").priceError)
        assertNotNull(valid().copy(title = "ab").titleError)
        assertNotNull(valid().copy(photos = emptyList()).photosError)
        assertNotNull(valid().copy(photos = List(6) { ByteArray(1) }).photosError)
        // ตอนแก้ไขไม่แก้รูป จึงไม่ตรวจรูป
        assertNull(valid().copy(editId = "x", photos = emptyList()).photosError)
    }

    @Test fun formatting_helpers() {
        assertEquals("฿4,900", formatPrice(4900))
        assertEquals("฿250", formatPrice(250))
        assertEquals("กำลังขาย", statusLabel("AVAILABLE"))
        assertEquals("ถูกตีกลับ", statusLabel("RETURNED"))
        val now = 1_000_000_000_000L
        assertEquals("เมื่อสักครู่", relativeThai(now - 10_000, now))
        assertEquals("5 นาทีที่แล้ว", relativeThai(now - 5 * 60_000, now))
        assertEquals("2 ชั่วโมงที่แล้ว", relativeThai(now - 2 * 3_600_000, now))
        assertEquals("3 วันที่แล้ว", relativeThai(now - 3 * 86_400_000, now))
    }
}
