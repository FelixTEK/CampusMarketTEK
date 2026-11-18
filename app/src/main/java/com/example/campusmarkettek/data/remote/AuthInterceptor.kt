package com.example.campusmarkettek.data.remote

import com.example.campusmarkettek.data.auth.SessionManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** แนบ Bearer token ทุกคำขอ และถ้า server ตอบ 401 ทั้งที่แนบ token ไปแล้ว ให้ออกจากระบบ */
@Singleton
class AuthInterceptor @Inject constructor(private val session: SessionManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = session.token()
        val request = if (token != null) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        val response = chain.proceed(request)
        // 401 จากหน้าเข้าสู่ระบบ (ไม่ได้แนบ token) ไม่ใช่เรื่อง token หมดอายุ จึงไม่ออกจากระบบ
        if (response.code == 401 && token != null) session.signOut()
        return response
    }
}
