package com.example.campusmarkettek.data.remote

import com.google.gson.Gson
import retrofit2.HttpException
import java.io.IOException

/** รูปแบบ error จาก backend: { "code": "...", "message": "ข้อความภาษาไทย" } */
data class ApiErrorBody(val code: String?, val message: String?)

/**
 * แปลง exception ทุกชนิดเป็นข้อความภาษาไทยที่แสดงให้ผู้ใช้ได้
 * ใช้ message จาก backend ก่อน (เป็นภาษาไทยอยู่แล้ว) ถ้าไม่มีค่อยดูจากรหัส HTTP
 */
fun Throwable.toUserMessage(): String = when (this) {
    is HttpException -> serverMessage() ?: when (code()) {
        400, 422 -> "ข้อมูลไม่ถูกต้อง กรุณาตรวจสอบอีกครั้ง"
        401 -> "เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่"
        403 -> "คุณไม่มีสิทธิ์ทำรายการนี้"
        404 -> "ไม่พบข้อมูลที่ต้องการ"
        409 -> "ข้อมูลมีการเปลี่ยนแปลง กรุณาโหลดใหม่แล้วลองอีกครั้ง"
        429 -> "ทำรายการถี่เกินไป กรุณารอสักครู่"
        in 500..599 -> "เซิร์ฟเวอร์ขัดข้อง กรุณาลองใหม่ภายหลัง"
        else -> "เกิดข้อผิดพลาด (${code()})"
    }
    is IOException -> "เชื่อมต่อเซิร์ฟเวอร์ไม่ได้ ตรวจสอบอินเทอร์เน็ตแล้วลองใหม่"
    else -> "เกิดข้อผิดพลาดที่ไม่คาดคิด"
}

/** รหัส error จาก backend เช่น BANNED, NOT_VERIFIED (null ถ้าไม่ใช่ HttpException หรืออ่านไม่ได้) */
fun Throwable.apiErrorCode(): String? = (this as? HttpException)?.let { e ->
    try {
        Gson().fromJson(e.errorText(), ApiErrorBody::class.java)?.code
    } catch (ex: Exception) {
        null
    }
}

private fun HttpException.serverMessage(): String? = try {
    Gson().fromJson(errorText(), ApiErrorBody::class.java)?.message?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}

/** อ่านเนื้อ error โดยไม่ใช้ข้อมูลหมด (peek) — เรียกซ้ำได้หลายครั้งกับ exception เดียว */
private fun HttpException.errorText(): String? = response()?.errorBody()?.source()?.peek()?.readUtf8()
