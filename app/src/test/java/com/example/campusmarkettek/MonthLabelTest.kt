package com.example.campusmarkettek

import com.example.campusmarkettek.ui.components.monthLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthLabelTest {
    @Test fun `เดือนแรกและเดือนสุดท้าย`() {
        assertEquals("ม.ค.", monthLabel("2026-01"))
        assertEquals("ธ.ค.", monthLabel("2026-12"))
    }

    @Test fun `พฤศจิกายน`() = assertEquals("พ.ย.", monthLabel("2026-11"))

    @Test fun `รูปแบบแปลกไม่ล้ม`() = assertEquals("xx", monthLabel("xx"))
}

class ShortMoneyTest {
    @Test fun `ย่อตัวเลขเหนือแท่ง`() {
        assertEquals("950", com.example.campusmarkettek.ui.stats.shortMoney(950))
        assertEquals("1.5k", com.example.campusmarkettek.ui.stats.shortMoney(1500))
        assertEquals("12k", com.example.campusmarkettek.ui.stats.shortMoney(12000))
    }
}
