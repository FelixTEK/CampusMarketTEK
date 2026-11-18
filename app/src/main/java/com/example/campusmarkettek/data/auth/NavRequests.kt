package com.example.campusmarkettek.data.auth

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * คำขอ "ไปหน้านี้" จากนอกแอป (แตะแจ้งเตือนระบบ) — MainActivity ส่งเข้ามา MainShell รับแล้ว navigate
 * ใช้ Channel เพื่อไม่ให้หายถ้ามาถึงก่อนที่หน้าหลักจะพร้อม (เช่นแอปถูกเปิดใหม่ หรือยังไม่ล็อกอิน)
 */
@Singleton
class NavRequests @Inject constructor() {
    private val channel = Channel<String>(Channel.BUFFERED)
    val routes: Flow<String> = channel.receiveAsFlow()
    fun post(route: String) { channel.trySend(route) }
}
