package com.example.campusmarkettek.data

import com.example.campusmarkettek.data.remote.apiErrorCode
import com.example.campusmarkettek.data.remote.toUserMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ErrorMessagesTest {

    private fun http(code: Int, body: String) =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Test fun uses_thai_message_from_backend() {
        val e = http(401, """{"code":"INVALID_CREDENTIALS","message":"อีเมลหรือรหัสผ่านไม่ถูกต้อง"}""")
        assertEquals("อีเมลหรือรหัสผ่านไม่ถูกต้อง", e.toUserMessage())
    }

    @Test fun error_body_can_be_read_more_than_once() {
        val e = http(403, """{"code":"BANNED","message":"บัญชีของคุณถูกระงับ"}""")
        assertEquals("BANNED", e.apiErrorCode())
        assertEquals("BANNED", e.apiErrorCode())
        assertEquals("บัญชีของคุณถูกระงับ", e.toUserMessage())
    }

    @Test fun falls_back_to_status_text_when_body_is_not_json() {
        assertEquals("คุณไม่มีสิทธิ์ทำรายการนี้", http(403, "oops").toUserMessage())
        assertEquals("เซิร์ฟเวอร์ขัดข้อง กรุณาลองใหม่ภายหลัง", http(503, "").toUserMessage())
        assertNull(http(500, "<html>").apiErrorCode())
    }

    @Test fun network_failure_gets_connection_message() {
        assertEquals("เชื่อมต่อเซิร์ฟเวอร์ไม่ได้ ตรวจสอบอินเทอร์เน็ตแล้วลองใหม่", IOException("timeout").toUserMessage())
    }

    @Test fun unknown_exception_is_generic() {
        assertEquals("เกิดข้อผิดพลาดที่ไม่คาดคิด", IllegalStateException("boom").toUserMessage())
    }
}
