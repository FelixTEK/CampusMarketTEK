package com.example.campusmarkettek.data

import com.example.campusmarkettek.data.repository.BrowseFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseFiltersTest {

    @Test fun empty_filters_are_the_default_home() {
        assertTrue(BrowseFilters().isDefault)
        assertTrue(BrowseFilters(query = "   ").isDefault)   // ช่องว่างล้วน = ไม่ได้ค้น
        assertEquals(0, BrowseFilters().activeCount)
    }

    @Test fun query_and_category_make_it_non_default_but_are_not_counted_as_filters() {
        val f = BrowseFilters(query = "g102", categoryId = 2)
        assertFalse(f.isDefault)
        assertEquals(0, f.activeCount) // คำค้นกับชิปหมวดมีที่แสดงของมันเอง ไม่นับบนปุ่มตัวกรอง
    }

    @Test fun each_filter_group_counts_once() {
        assertEquals(1, BrowseFilters(facultyId = 1).activeCount)
        assertEquals(1, BrowseFilters(tagIds = setOf(1, 2, 3)).activeCount) // หลายแท็ก = 1 กลุ่ม
        assertEquals(1, BrowseFilters(minPrice = 100, maxPrice = 500).activeCount) // ต่ำสุด+สูงสุด = 1 กลุ่ม
        assertEquals(1, BrowseFilters(sort = "price_asc").activeCount)
        assertEquals(4, BrowseFilters(facultyId = 1, tagIds = setOf(1), minPrice = 1, sort = "new").activeCount)
    }

    @Test fun any_filter_makes_it_non_default() {
        assertFalse(BrowseFilters(maxPrice = 100).isDefault)
        assertFalse(BrowseFilters(sort = "new").isDefault)
    }
}
