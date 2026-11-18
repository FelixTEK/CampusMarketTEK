package com.example.campusmarkettek.data.remote

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

// ── DTO: รูปเดียวกับ JSON ที่ backend ตอบ (ฟิลด์มาจากตาราง users / id_card_requests) ──

/** คำขอยืนยันตัวตนล่าสุด: status = PENDING | APPROVED | REJECTED */
data class VerificationDto(val id: String, val status: String, val rejectReason: String?)

data class MeDto(
    val id: String,
    val displayName: String,
    val email: String?,
    val facultyId: Int?,
    val isVerified: Boolean,
    val isAdmin: Boolean,
    /** เข้าด้วย KMITL SSO (บัญชี สจล. มีอีเมลสถาบันได้ จึงดูจาก email อย่างเดียวไม่ได้) */
    val isKmitl: Boolean = false,
    val verification: VerificationDto?,
)

data class SessionDto(val token: String, val user: MeDto)

data class AuthConfigDto(val kmitlSso: Boolean, val thaidMock: Boolean)

data class LoginRequest(val email: String, val password: String)
data class RegisterRequest(val email: String, val password: String, val displayName: String)
data class ExchangeRequest(val code: String)
data class UpdateMeRequest(val displayName: String? = null, val facultyId: Int? = null)

data class FacultyDto(val id: Int, val nameTh: String)

data class PendingVerificationDto(val id: String, val displayName: String, val status: String, val createdAt: String)
data class ApproveRequest(val nationalId: String)
data class RejectRequest(val reason: String)
data class OkDto(val ok: Boolean)

interface AuthApi {
    @GET("auth/config")
    suspend fun config(): AuthConfigDto

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): SessionDto

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): SessionDto

    @POST("auth/exchange")
    suspend fun exchange(@Body body: ExchangeRequest): SessionDto

    @POST("auth/thaid/mock")
    suspend fun thaidMock(): MeDto
}

interface UserApi {
    @GET("users/me")
    suspend fun me(): MeDto

    @PATCH("users/me")
    suspend fun updateMe(@Body body: UpdateMeRequest): MeDto

    @GET("faculties")
    suspend fun faculties(): List<FacultyDto>
}

interface VerificationApi {
    @Multipart
    @POST("verifications")
    suspend fun submit(@Part card: MultipartBody.Part, @Part selfie: MultipartBody.Part): VerificationDto
}

interface AdminVerificationApi {
    @GET("admin/verifications")
    suspend fun queue(@Query("status") status: String = "PENDING"): List<PendingVerificationDto>

    /** ไบต์รูปที่ถอดรหัสแล้ว (which = card | selfie) */
    @GET("admin/verifications/{id}/images/{which}")
    suspend fun image(@Path("id") id: String, @Path("which") which: String): ResponseBody

    @POST("admin/verifications/{id}/approve")
    suspend fun approve(@Path("id") id: String, @Body body: ApproveRequest): OkDto

    @POST("admin/verifications/{id}/reject")
    suspend fun reject(@Path("id") id: String, @Body body: RejectRequest): OkDto
}
