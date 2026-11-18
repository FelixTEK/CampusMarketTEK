package com.example.campusmarkettek.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * สถานะการเข้าสู่ระบบของทั้งแอป (ต้องล็อกอินก่อนใช้งาน — BR01)
 * เก็บ token ไว้ในหน่วยความจำด้วย เพื่อไม่ต้องถอดรหัสจาก Keystore ทุกครั้งที่เรียก API
 */
@Singleton
class SessionManager @Inject constructor(private val storage: TokenStorage) {

    @Volatile
    private var token: String? = storage.read()

    private val _isLoggedIn = MutableStateFlow(token != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun token(): String? = token

    /** id ผู้ใช้ (claim sub ใน JWT) — อ่านจากตัว token เอง ไม่ต้องเก็บเพิ่ม · ไม่ได้ตรวจลายเซ็น (server ตรวจอยู่แล้ว) */
    fun userId(): String? {
        val payload = token?.split('.')?.getOrNull(1) ?: return null
        return try {
            val json = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))
            org.json.JSONObject(json).optString("sub").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    fun signIn(newToken: String) {
        storage.save(newToken)
        token = newToken
        _isLoggedIn.value = true
    }

    /** ออกจากระบบ หรือ server ตอบ 401 (token หมดอายุ) — แอปจะกลับหน้าเข้าสู่ระบบเอง */
    fun signOut() {
        storage.clear()
        token = null
        _isLoggedIn.value = false
    }
}
