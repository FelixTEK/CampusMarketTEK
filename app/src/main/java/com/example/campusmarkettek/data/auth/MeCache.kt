package com.example.campusmarkettek.data.auth

import android.content.Context
import com.example.campusmarkettek.data.remote.MeDto
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * โปรไฟล์ล่าสุดของผู้ใช้ที่ล็อกอินอยู่ — ไว้เปิดแอปตอนออฟไลน์ได้ (ไม่ต้องรอ server ถามว่ายืนยันตัวตนแล้วหรือยัง)
 * เก็บเฉพาะข้อมูลที่ GET /users/me ส่งมา (ไม่มีรหัสผ่าน/แฮช/เลขบัตร) และผูกกับ id ผู้ใช้
 * ใช้เฉพาะตอน "เชื่อมต่อไม่ได้" เท่านั้น — server ตอบอะไรมา (401/403 รวมถึงถูกระงับ) ยึดตาม server เสมอ
 */
@Singleton
class MeCache @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("me_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(me: MeDto) {
        prefs.edit().putString("me", gson.toJson(me)).apply()
    }

    /** คืนโปรไฟล์ที่เคยเก็บไว้ ถ้าเป็นของผู้ใช้คนนี้ (กันเครื่องเดียวหลายบัญชี) */
    fun load(userId: String?): MeDto? {
        val json = prefs.getString("me", null) ?: return null
        return try {
            gson.fromJson(json, MeDto::class.java)?.takeIf { it.id == userId }
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
