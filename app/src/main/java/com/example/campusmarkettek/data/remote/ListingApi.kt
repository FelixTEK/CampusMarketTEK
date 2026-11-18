package com.example.campusmarkettek.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

// ── DTO: รูปเดียวกับ JSON ที่ backend ตอบ (ฟิลด์มาจากตาราง listings และตารางที่เกี่ยวข้อง) ──

/** แท็ก: categoryId = หมวดหลักที่ใช้ได้ (null = ใช้ได้ทุกหมวด) */
data class TagDto(val id: Int, val nameTh: String, val categoryId: Int?)

data class NamedDto(val id: Int, val nameTh: String)

data class SellerDto(val id: String, val displayName: String, val faculty: String?, val isKmitl: Boolean)

/** สถานะ: AVAILABLE | RESERVED | SOLD | CANCELLED | RETURNED | TAKEN_DOWN */
data class ListingDetailDto(
    val id: String,
    val title: String,
    val description: String,
    val price: Int,
    val status: String,
    val statusReason: String?,
    val createdAt: String,
    val updatedAt: String,
    val categoryId: Int,
    val categoryPath: List<NamedDto>,
    /** URL สัมพัทธ์ เช่น /uploads/listings/<id>/0.jpg */
    val images: List<String>,
    val faculties: List<NamedDto>,
    val tags: List<NamedDto>,
    val seller: SellerDto,
    val isOwner: Boolean,
)

data class ListingSummaryDto(
    val id: String,
    val title: String,
    val price: Int,
    val status: String,
    val statusReason: String?,
    val categoryId: Int,
    val cover: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class CreatedListingDto(val id: String, val status: String)

data class UpdateListingRequest(
    val title: String? = null,
    val description: String? = null,
    val price: Int? = null,
    val categoryId: Int? = null,
    val facultyIds: List<Int>? = null,
    val tagIds: List<Int>? = null,
)

data class ReasonRequest(val reason: String)

interface ListingApi {
    @GET("tags")
    suspend fun tags(): List<TagDto>

    @Multipart
    @POST("listings")
    suspend fun create(
        @Part("categoryId") categoryId: RequestBody,
        @Part("title") title: RequestBody,
        @Part("description") description: RequestBody,
        @Part("price") price: RequestBody,
        @Part("facultyIds") facultyIds: RequestBody,
        @Part("tagIds") tagIds: RequestBody,
        @Part images: List<MultipartBody.Part>,
    ): CreatedListingDto

    @GET("users/me/listings")
    suspend fun mine(@Query("status") status: String? = null): List<ListingSummaryDto>

    @GET("listings/{id}")
    suspend fun detail(@Path("id") id: String): ListingDetailDto

    @PATCH("listings/{id}")
    suspend fun update(@Path("id") id: String, @Body body: UpdateListingRequest): ListingDetailDto

    @POST("listings/{id}/cancel")
    suspend fun cancel(@Path("id") id: String, @Body body: ReasonRequest): ListingDetailDto

    @POST("listings/{id}/relist")
    suspend fun relist(@Path("id") id: String): ListingDetailDto

    @POST("listings/{id}/resubmit")
    suspend fun resubmit(@Path("id") id: String): ListingDetailDto

    @POST("admin/listings/{id}/return")
    suspend fun adminReturn(@Path("id") id: String, @Body body: ReasonRequest): OkDto

    @POST("admin/listings/{id}/takedown")
    suspend fun adminTakedown(@Path("id") id: String, @Body body: ReasonRequest): OkDto

    @POST("admin/listings/{id}/restore")
    suspend fun adminRestore(@Path("id") id: String): OkDto
}
