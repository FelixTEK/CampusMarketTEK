package com.example.campusmarkettek.data.repository

import com.example.campusmarkettek.data.auth.SessionManager
import com.example.campusmarkettek.data.remote.AdminVerificationApi
import com.example.campusmarkettek.data.remote.ApproveRequest
import com.example.campusmarkettek.data.remote.AuthApi
import com.example.campusmarkettek.data.remote.AuthConfigDto
import com.example.campusmarkettek.data.remote.ExchangeRequest
import com.example.campusmarkettek.data.remote.FacultyDto
import com.example.campusmarkettek.data.remote.LoginRequest
import com.example.campusmarkettek.data.remote.MeDto
import com.example.campusmarkettek.data.remote.PendingVerificationDto
import com.example.campusmarkettek.data.remote.RegisterRequest
import com.example.campusmarkettek.data.remote.RejectRequest
import com.example.campusmarkettek.data.remote.SessionDto
import com.example.campusmarkettek.data.remote.UpdateMeRequest
import com.example.campusmarkettek.data.remote.UserApi
import com.example.campusmarkettek.data.remote.VerificationApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * บัญชีผู้ใช้: เข้าสู่ระบบ สมัคร โปรไฟล์ และการยืนยันตัวตน
 * ทุกเมธอดโยน exception เมื่อ server ปฏิเสธ — ผู้เรียกแปลงเป็นข้อความด้วย toUserMessage()
 */
@Singleton
class AccountRepository @Inject constructor(
    private val auth: AuthApi,
    private val users: UserApi,
    private val verifications: VerificationApi,
    private val session: SessionManager,
) {
    suspend fun config(): AuthConfigDto = auth.config()

    suspend fun login(email: String, password: String) = signIn(auth.login(LoginRequest(email, password)))

    suspend fun register(email: String, password: String, displayName: String) =
        signIn(auth.register(RegisterRequest(email, password, displayName)))

    /** แลกรหัสครั้งเดียวจาก deep link ของ SSO เป็น token */
    suspend fun exchangeSsoCode(code: String) = signIn(auth.exchange(ExchangeRequest(code)))

    private fun signIn(result: SessionDto) = session.signIn(result.token)

    suspend fun me(): MeDto = users.me()

    suspend fun updateProfile(displayName: String?, facultyId: Int?): MeDto =
        users.updateMe(UpdateMeRequest(displayName, facultyId))

    suspend fun faculties(): List<FacultyDto> = users.faculties()

    suspend fun thaidMock(): MeDto = auth.thaidMock()

    /** ส่งรูปบัตร + เซลฟี (JPEG ที่บีบอัดแล้ว) */
    suspend fun submitIdCard(card: ByteArray, selfie: ByteArray) {
        verifications.submit(jpegPart("card", card), jpegPart("selfie", selfie))
    }

    private fun jpegPart(name: String, bytes: ByteArray) = MultipartBody.Part.createFormData(
        name,
        "$name.jpg",
        bytes.toRequestBody("image/jpeg".toMediaType()),
    )

    fun signOut() = session.signOut()
}

/** งานของผู้ดูแล: ตรวจคำขอยืนยันตัวตนของคนนอก */
@Singleton
class AdminVerificationRepository @Inject constructor(private val api: AdminVerificationApi) {
    suspend fun queue(): List<PendingVerificationDto> = api.queue()

    suspend fun image(id: String, which: String): ByteArray = api.image(id, which).bytes()

    suspend fun approve(id: String, nationalId: String) {
        api.approve(id, ApproveRequest(nationalId))
    }

    suspend fun reject(id: String, reason: String) {
        api.reject(id, RejectRequest(reason))
    }
}
