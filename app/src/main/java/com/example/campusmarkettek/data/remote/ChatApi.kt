package com.example.campusmarkettek.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ── DTO: รูปเดียวกับ JSON ที่ backend ตอบ (ตาราง conversations / messages / deals) ──

/** สถานะดีล: PENDING | ACCEPTED | HANDED_OVER | COMPLETED | REJECTED | CANCELLED */
data class DealDto(
    val id: String,
    val status: String,
    val meetup: String?,
    val listingId: String,
    val buyerId: String,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String?,
)

data class RoomListingDto(val id: String, val title: String, val price: Int, val status: String, val cover: String?)
data class PersonDto(val id: String, val displayName: String)

/** ห้องแชตมองจากมุมของฉัน: role = BUYER | SELLER */
data class RoomDto(
    val id: String,
    val role: String,
    val listing: RoomListingDto,
    val other: PersonDto,
    val activeDeal: DealDto?,
    val createdAt: String,
)

data class LastMessageDto(val id: Long, val body: String, val senderId: String?, val createdAt: String)

data class ConversationItemDto(
    val id: String,
    val role: String,
    val listing: RoomListingDto,
    val other: PersonDto,
    val activeDeal: DealDto?,
    val createdAt: String,
    val lastMessage: LastMessageDto?,
    val unread: Int,
)

/** senderId = null คือข้อความระบบ · deal ไม่ว่าง = การ์ดดีลในแชต */
data class MessageDto(
    val id: Long,
    val conversationId: String,
    val senderId: String?,
    val body: String,
    val dealId: String?,
    val deal: DealDto?,
    val clientId: String?,
    val readAt: String?,
    val createdAt: String,
)

data class MessagePageDto(val items: List<MessageDto>, val hasMore: Boolean)

data class SendMessageRequest(val body: String, val clientId: String)
data class MeetupRequest(val meetup: String)
data class ReadDto(val read: Int)

data class DealListItemDto(
    val id: String,
    val status: String,
    val meetup: String?,
    val conversationId: String?,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String?,
    val listing: RoomListingDto,
    val other: PersonDto,
)

interface ChatApi {
    @POST("listings/{id}/conversations")
    suspend fun openRoom(@Path("id") listingId: String): RoomDto

    @GET("conversations")
    suspend fun conversations(): List<ConversationItemDto>

    @GET("conversations/{id}")
    suspend fun room(@Path("id") id: String): RoomDto

    @GET("conversations/{id}/messages")
    suspend fun messages(@Path("id") id: String, @Query("before") before: Long? = null): MessagePageDto

    @POST("conversations/{id}/messages")
    suspend fun send(@Path("id") id: String, @Body body: SendMessageRequest): MessageDto

    @POST("conversations/{id}/read")
    suspend fun markRead(@Path("id") id: String): ReadDto

    @POST("conversations/{id}/deals")
    suspend fun offer(@Path("id") conversationId: String): DealDto

    @POST("deals/{id}/accept")
    suspend fun accept(@Path("id") id: String): DealDto

    @POST("deals/{id}/reject")
    suspend fun reject(@Path("id") id: String): DealDto

    @POST("deals/{id}/cancel")
    suspend fun cancel(@Path("id") id: String): DealDto

    @POST("deals/{id}/handover")
    suspend fun handover(@Path("id") id: String): DealDto

    @POST("deals/{id}/receive")
    suspend fun receive(@Path("id") id: String): DealDto

    @PATCH("deals/{id}/meetup")
    suspend fun meetup(@Path("id") id: String, @Body body: MeetupRequest): DealDto

    @GET("deals")
    suspend fun deals(@Query("role") role: String): List<DealListItemDto>
}
