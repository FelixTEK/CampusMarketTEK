package com.example.campusmarkettek.data.auth

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** ผลที่ backend ส่งกลับมาทาง deep link `campusmarket://auth?code=… | error=…` หลังล็อกอิน SSO */
sealed interface SsoResult {
    /** รหัสครั้งเดียว (60 วินาที) ไว้แลกเป็น token */
    data class Code(val code: String) : SsoResult
    data class Failed(val reason: String) : SsoResult
}

/**
 * สะพานจาก MainActivity (รับ deep link) ไปหน้าเข้าสู่ระบบ
 * ใช้ Channel เพื่อให้ผลไม่หาย แม้ deep link มาถึงก่อนที่หน้าจะเริ่มฟัง (เช่นแอปถูกเปิดใหม่จากเบราว์เซอร์)
 */
@Singleton
class AuthEvents @Inject constructor() {
    private val channel = Channel<SsoResult>(Channel.BUFFERED)

    val results: Flow<SsoResult> = channel.receiveAsFlow()

    fun post(result: SsoResult) {
        channel.trySend(result)
    }
}
