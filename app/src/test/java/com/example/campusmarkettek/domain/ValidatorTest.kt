package com.example.campusmarkettek.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** ขอบเขตทุกตัวต้องตรงกับ CHECK ใน backend/migrations/001_init.sql */
class ValidatorTest {

    @Test fun title_boundaries() {   // listings.title 3–100
        assertNotNull(Validator.title("ab"))
        assertNull(Validator.title("abc"))
        assertNull(Validator.title("a".repeat(100)))
        assertNotNull(Validator.title("a".repeat(101)))
    }

    @Test fun description_max_2000() {
        assertNull(Validator.description("a".repeat(2000)))
        assertNotNull(Validator.description("a".repeat(2001)))
    }

    @Test fun price_must_be_positive() {
        assertNotNull(Validator.price(null))
        assertNotNull(Validator.price(0))
        assertNotNull(Validator.price(-5))
        assertNull(Validator.price(1))
    }

    @Test fun imageCount_1_to_5() {
        assertNotNull(Validator.imageCount(0))
        assertNull(Validator.imageCount(1))
        assertNull(Validator.imageCount(5))
        assertNotNull(Validator.imageCount(6))
    }

    @Test fun displayName_1_to_50() {
        assertNotNull(Validator.displayName("   "))
        assertNull(Validator.displayName("มิ้นท์"))
        assertNull(Validator.displayName("a".repeat(50)))
        assertNotNull(Validator.displayName("a".repeat(51)))
    }

    @Test fun email_format() {
        assertNull(Validator.email("a@b.co"))
        assertNotNull(Validator.email("a@b"))
        assertNotNull(Validator.email("a b@c.de"))
        assertNotNull(Validator.email("@b.co"))
    }

    @Test fun messageBody_1_to_1000() {
        assertNotNull(Validator.messageBody(""))
        assertNull(Validator.messageBody("a"))
        assertNull(Validator.messageBody("a".repeat(1000)))
        assertNotNull(Validator.messageBody("a".repeat(1001)))
    }

    @Test fun meetup_review_report_limits() {
        assertNull(Validator.meetup("a".repeat(200)))
        assertNotNull(Validator.meetup("a".repeat(201)))
        assertNull(Validator.rating(5))
        assertNotNull(Validator.rating(0))
        assertNotNull(Validator.rating(6))
        assertNull(Validator.reviewComment("a".repeat(300)))
        assertNotNull(Validator.reviewComment("a".repeat(301)))
        assertNull(Validator.reportDetail("a".repeat(300)))
        assertNotNull(Validator.reportDetail("a".repeat(301)))
    }
}
