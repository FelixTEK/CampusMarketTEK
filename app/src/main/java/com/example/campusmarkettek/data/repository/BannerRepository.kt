package com.example.campusmarkettek.data.repository

import com.example.campusmarkettek.data.local.dao.BrowseCacheDao
import com.example.campusmarkettek.data.local.entity.BrowseCacheEntity
import com.example.campusmarkettek.data.remote.AdminBannerDto
import com.example.campusmarkettek.data.remote.AdminBannerListDto
import com.example.campusmarkettek.data.remote.BannerApi
import com.example.campusmarkettek.data.remote.BannerDto
import com.example.campusmarkettek.data.remote.BannerPatch
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * แบนเนอร์โฆษณา (EX02): หน้าแรกแสดงเฉพาะที่ server บอกว่าแสดงอยู่ · เก็บล่าสุดใน Room ไว้แสดงตอนออฟไลน์
 * ไม่มีแบนเนอร์/โหลดไม่ได้และไม่มีแคช → คืนลิสต์ว่าง (หน้าแรกซ่อนทั้งแถบ)
 */
@Singleton
class BannerRepository @Inject constructor(private val api: BannerApi, private val cache: BrowseCacheDao) {
    private val gson = Gson()
    private val type = object : TypeToken<List<BannerDto>>() {}.type

    suspend fun live(): List<BannerDto> = try {
        api.live().items.also { cache.put(BrowseCacheEntity("banners", gson.toJson(it), System.currentTimeMillis())) }
    } catch (e: IOException) {
        cache.get("banners")?.let { runCatching { gson.fromJson<List<BannerDto>>(it.json, type) }.getOrNull() } ?: emptyList()
    } catch (e: Exception) {
        emptyList() // server ตอบผิดพลาด: โฆษณาไม่ใช่ของสำคัญ ซ่อนแถบไว้เฉย ๆ
    }

    // ── ผู้ดูแล ──
    suspend fun adminList(): AdminBannerListDto = api.adminList()

    suspend fun setActive(id: Int, active: Boolean): AdminBannerDto = api.update(id, BannerPatch(isActive = active))

    suspend fun delete(id: Int) { api.delete(id) }

    /** เริ่มแสดงทันที นาน [days] วัน */
    suspend fun create(title: String, link: String, days: Int, sort: Int, jpeg: ByteArray): AdminBannerDto {
        val text = "text/plain".toMediaType()
        val now = Instant.now()
        return api.create(
            title.trim().toRequestBody(text),
            link.trim().ifEmpty { null }?.toRequestBody(text),
            now.toString().toRequestBody(text),
            now.plusSeconds(days * 86_400L).toString().toRequestBody(text),
            sort.toString().toRequestBody(text),
            MultipartBody.Part.createFormData("image", "banner.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())),
        )
    }
}
