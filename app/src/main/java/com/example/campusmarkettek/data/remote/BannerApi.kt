package com.example.campusmarkettek.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/** แบนเนอร์ที่แสดงอยู่ตอนนี้ (ไม่มีชื่อ — ชื่อไว้ให้ผู้ดูแลจำ) · linkUrl = https:// หรือ campusmarket:// หรือไม่มี */
data class BannerDto(val id: Int, val imageUrl: String, val linkUrl: String?)
data class BannerPageDto(val items: List<BannerDto>)

/** status: LIVE | SCHEDULED | EXPIRED | OFF */
data class AdminBannerDto(
    val id: Int,
    val title: String,
    val imageUrl: String,
    val linkUrl: String?,
    val startsAt: String,
    val endsAt: String,
    val isActive: Boolean,
    val sort: Int,
    val status: String,
)

data class AdminBannerListDto(val items: List<AdminBannerDto>, val live: Int, val max: Int)

/** ฟิลด์ที่เป็น null ไม่ถูกส่ง (Gson) = ไม่แก้ */
data class BannerPatch(val isActive: Boolean? = null)

interface BannerApi {
    @GET("banners")
    suspend fun live(): BannerPageDto

    @GET("admin/banners")
    suspend fun adminList(): AdminBannerListDto

    @Multipart
    @POST("admin/banners")
    suspend fun create(
        @Part("title") title: RequestBody,
        @Part("linkUrl") linkUrl: RequestBody?,
        @Part("startsAt") startsAt: RequestBody,
        @Part("endsAt") endsAt: RequestBody,
        @Part("sort") sort: RequestBody,
        @Part image: MultipartBody.Part,
    ): AdminBannerDto

    @PATCH("admin/banners/{id}")
    suspend fun update(@Path("id") id: Int, @Body body: BannerPatch): AdminBannerDto

    @DELETE("admin/banners/{id}")
    suspend fun delete(@Path("id") id: Int): OkDto
}
