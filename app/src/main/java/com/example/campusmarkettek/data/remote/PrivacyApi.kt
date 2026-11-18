package com.example.campusmarkettek.data.remote

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Streaming

data class DeleteMeRequest(val confirm: Boolean = true)

interface PrivacyApi {
    /** ข้อมูลทั้งหมดของฉันเป็น JSON (ส่งเป็นสตรีม — แอปเขียนลงไฟล์ที่ผู้ใช้เลือกเลย ไม่ค้างในหน่วยความจำ) */
    @Streaming
    @GET("me/export")
    suspend fun export(): ResponseBody

    /** DELETE ที่มี body (confirm = true) — Retrofit ต้องใช้ @HTTP */
    @HTTP(method = "DELETE", path = "me", hasBody = true)
    suspend fun deleteMe(@Body body: DeleteMeRequest): OkDto
}
