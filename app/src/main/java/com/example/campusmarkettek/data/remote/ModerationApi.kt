package com.example.campusmarkettek.data.remote

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ── โปรไฟล์ผู้ขาย + รีวิว (หน้า 08) ──

data class RatingDto(val average: Double, val count: Int)
data class ReviewItemDto(val rating: Int, val comment: String?, val createdAt: String, val buyerName: String, val listingTitle: String)

/** closeRate = null → "ผู้ขายใหม่" (ปิดแล้วไม่ถึง 3 ประกาศ) · rating = null → ยังไม่มีรีวิว */
data class SellerProfileDto(
    val id: String,
    val displayName: String,
    val faculty: String?,
    val isKmitl: Boolean,
    val sold: Int,
    val activeListings: Int,
    val closeRate: Int?,
    val rating: RatingDto?,
    val reviews: List<ReviewItemDto>,
)

data class ReviewRequest(val rating: Int, val comment: String?)
data class ReviewDto(val rating: Int, val comment: String?, val createdAt: String)
data class ReviewStateDto(val review: ReviewDto?)

// ── รายงาน (หน้า 09) และฝั่งผู้ดูแล (หน้า 11) ──

data class ReportRequest(val targetType: String, val targetId: String, val reason: String, val detail: String?)
data class ReportCreatedDto(val id: String)

data class RepeatPairDto(val buyerName: String, val deals: Int)

/** snapshot = สำเนาเป้าหมาย ณ เวลารายงาน (รูปร่างต่างกันตาม targetType · userId = ผู้ถูกรายงาน เสมอ) */
data class AdminReportDto(
    val id: String,
    val targetType: String,
    val targetId: String,
    val reason: String,
    val detail: String?,
    val status: String,
    val adminNote: String?,
    val createdAt: String,
    val reporter: PersonDto,
    val otherReports: Int,
    val snapshot: JsonObject,
    val repeatPairs: List<RepeatPairDto>,
)

data class AdminReportPageDto(val items: List<AdminReportDto>)
data class CloseReportRequest(val note: String)
data class BanRequest(val reason: String)
data class AdminUserDto(val id: String, val displayName: String, val isBanned: Boolean, val isAdmin: Boolean)

/** แถวในรายชื่อผู้ใช้ของผู้ดูแล (ไม่มีอีเมล) */
data class AdminUserRowDto(
    val id: String,
    val displayName: String,
    val isVerified: Boolean,
    val isAdmin: Boolean,
    val isBanned: Boolean,
    val isKmitl: Boolean,
)
data class AdminUserListDto(val items: List<AdminUserRowDto>, val hasMore: Boolean)

interface ModerationApi {
    @GET("users/{id}")
    suspend fun profile(@Path("id") id: String): SellerProfileDto

    @POST("deals/{id}/review")
    suspend fun review(@Path("id") dealId: String, @Body body: ReviewRequest): ReviewDto

    @GET("deals/{id}/review")
    suspend fun reviewOf(@Path("id") dealId: String): ReviewStateDto

    @POST("reports")
    suspend fun report(@Body body: ReportRequest): ReportCreatedDto

    @GET("admin/reports")
    suspend fun adminReports(@Query("status") status: String): AdminReportPageDto

    @GET("admin/reports/{id}")
    suspend fun adminReport(@Path("id") id: String): AdminReportDto

    @POST("admin/reports/{id}/close")
    suspend fun closeReport(@Path("id") id: String, @Body body: CloseReportRequest): OkDto

    @GET("admin/users")
    suspend fun adminUsers(@Query("q") q: String, @Query("filter") filter: String): AdminUserListDto

    @POST("admin/users/{id}/promote")
    suspend fun promote(@Path("id") id: String): OkDto

    @POST("admin/users/{id}/demote")
    suspend fun demote(@Path("id") id: String): OkDto

    @GET("admin/users/{id}")
    suspend fun adminUser(@Path("id") id: String): AdminUserDto

    @POST("admin/users/{id}/ban")
    suspend fun ban(@Path("id") id: String, @Body body: BanRequest): OkDto

    @POST("admin/users/{id}/unban")
    suspend fun unban(@Path("id") id: String): OkDto
}

/** เหตุผลรายงานที่เลือกได้ — ต้องตรงกับ REPORT_REASONS ฝั่ง backend (server ตรวจซ้ำ) */
val REPORT_REASONS = listOf(
    "หลอกลวง / สงสัยเป็นมิจฉาชีพ",
    "สินค้าผิดกฎหมายหรือไม่เหมาะสม",
    "ข้อมูลไม่ตรงความจริง",
    "คุกคามหรือใช้ถ้อยคำไม่เหมาะสม",
    "สแปม",
    "อื่น ๆ",
)
