package com.example.campusmarkettek.data.remote

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** แถวจากตาราง notifications · conversationId/listingId = ปลายทางที่แตะแล้วไป (ถ้ามี) */
data class NotificationDto(
    val id: Long,
    val type: String,
    val body: String,
    val refId: String?,
    val conversationId: String?,
    val listingId: String?,
    val isRead: Boolean,
    val createdAt: String,
)

/** unread = จำนวนที่ยังไม่อ่านทั้งหมดของฉันบน server */
data class NotificationPageDto(val items: List<NotificationDto>, val unread: Int)

data class UnreadDto(val unread: Int)

interface NotificationApi {
    /** after = id ล่าสุดที่เครื่องมี → ได้เฉพาะที่ใหม่กว่า (ไม่ส่ง = 50 รายการล่าสุด) */
    @GET("notifications")
    suspend fun list(@Query("after") after: Long? = null): NotificationPageDto

    @POST("notifications/{id}/read")
    suspend fun read(@Path("id") id: Long): UnreadDto

    @POST("notifications/read-all")
    suspend fun readAll(): UnreadDto
}
