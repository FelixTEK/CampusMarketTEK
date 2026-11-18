package com.example.campusmarkettek.data.repository

import com.example.campusmarkettek.data.local.dao.BrowseCacheDao
import com.example.campusmarkettek.data.local.dao.FavoriteDao
import com.example.campusmarkettek.data.local.dao.SearchHistoryDao
import com.example.campusmarkettek.data.local.entity.BrowseCacheEntity
import com.example.campusmarkettek.data.local.entity.FavoriteEntity
import com.example.campusmarkettek.data.local.entity.SearchHistoryEntity
import com.example.campusmarkettek.data.remote.BrowseApi
import com.example.campusmarkettek.data.remote.ListingCardDto
import com.example.campusmarkettek.data.remote.ListingPageDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** ตัวกรองของหน้าแรก (ตรงกับพารามิเตอร์ของ GET /listings) */
data class BrowseFilters(
    val query: String = "",
    val categoryId: Int? = null,
    val facultyId: Int? = null,
    val tagIds: Set<Int> = emptySet(),
    val minPrice: Int? = null,
    val maxPrice: Int? = null,
    /** relevant | new | price_asc | price_desc — null = ค่าเริ่มต้นของ server (มีคำค้น: ตรงที่สุด · ไม่มี: ใหม่สุด) */
    val sort: String? = null,
) {
    /** จำนวนตัวกรองที่เปิดอยู่ (ไม่นับคำค้นและหมวดหลักที่เป็นชิป) — ไว้โชว์ตัวเลขบนปุ่มตัวกรอง */
    val activeCount: Int
        get() = listOf(facultyId != null, tagIds.isNotEmpty(), minPrice != null || maxPrice != null, sort != null).count { it }

    /** หน้าแรกเปล่า ๆ ไม่มีตัวกรองเลย (ใช้แคชสำหรับดูออฟไลน์) */
    val isDefault: Boolean get() = query.isBlank() && categoryId == null && activeCount == 0
}

@Singleton
class BrowseRepository @Inject constructor(
    private val api: BrowseApi,
    private val favoriteDao: FavoriteDao,
    private val historyDao: SearchHistoryDao,
    private val cacheDao: BrowseCacheDao,
    private val sync: com.example.campusmarkettek.work.SyncScheduler,
) {
    private val gson = Gson()
    private val cardsType = object : TypeToken<List<ListingCardDto>>() {}.type

    // ── ค้นหา ──────────────────────────────────────────────────────────

    suspend fun search(f: BrowseFilters, page: Int): ListingPageDto {
        val result = api.search(
            q = f.query.trim().ifBlank { null }, category = f.categoryId, faculty = f.facultyId,
            tags = f.tagIds.takeIf { it.isNotEmpty() }?.joinToString(","),
            minPrice = f.minPrice, maxPrice = f.maxPrice, sort = f.sort, page = page,
        )
        // หน้าแรกของหน้าแรก (ไม่มีตัวกรอง) เก็บไว้ดูตอนออฟไลน์
        if (f.isDefault && page == 1) saveCache("home", result.items)
        return result
    }

    suspend fun count(f: BrowseFilters): Int = api.count(
        q = f.query.trim().ifBlank { null }, category = f.categoryId, faculty = f.facultyId,
        tags = f.tagIds.takeIf { it.isNotEmpty() }?.joinToString(","), minPrice = f.minPrice, maxPrice = f.maxPrice,
    ).count

    /** หน้าแรกที่เคยโหลดไว้ (null = ยังไม่เคย) */
    suspend fun cachedHome(): List<ListingCardDto>? = loadCache("home")

    private suspend fun saveCache(key: String, items: List<ListingCardDto>) =
        cacheDao.put(BrowseCacheEntity(key, gson.toJson(items), System.currentTimeMillis()))

    private suspend fun loadCache(key: String): List<ListingCardDto>? =
        cacheDao.get(key)?.let { runCatching { gson.fromJson<List<ListingCardDto>>(it.json, cardsType) }.getOrNull() }

    // ── ประวัติค้นหา (ในเครื่อง) ─────────────────────────────────────────

    fun observeHistory(): Flow<List<String>> = historyDao.observeRecent(8).map { list -> list.map { it.term } }

    suspend fun addHistory(term: String) {
        val t = term.trim()
        if (t.length >= 2) historyDao.upsert(SearchHistoryEntity(t, System.currentTimeMillis()))
    }

    suspend fun removeHistory(term: String) = historyDao.delete(term)
    suspend fun clearHistory() = historyDao.clear()

    // ── รายการโปรด: เปลี่ยนในเครื่องก่อน (หัวใจเปลี่ยนทันที) แล้วค่อยส่ง server ─────────

    /** id ที่อยู่ในรายการโปรดตอนนี้ (หัวใจที่เติมสี) */
    fun observeFavoriteIds(): Flow<Set<String>> = favoriteDao.observeAll().map { list -> list.map { it.listingId }.toSet() }

    /**
     * กลับค่าหัวใจ: เขียน Room ทันที (หน้าจอเปลี่ยนเลย) แล้วลองส่ง server
     * ส่งไม่สำเร็จ (ออฟไลน์) → ค้าง pendingSync ไว้ ให้ SyncWorker ส่งให้เมื่อมีเน็ต · ทำซ้ำได้ผลเท่าเดิมทั้งสองฝั่ง
     * คืน true ถ้าส่งถึง server แล้ว
     */
    suspend fun toggleFavorite(listingId: String, nowFavorite: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (nowFavorite) {
            favoriteDao.upsert(FavoriteEntity(listingId, now, pendingSync = true))
        } else {
            favoriteDao.upsert(FavoriteEntity(listingId, now, pendingSync = true, pendingRemove = true))
        }
        return try {
            if (nowFavorite) api.addFavorite(listingId) else api.removeFavorite(listingId)
            if (nowFavorite) favoriteDao.upsert(FavoriteEntity(listingId, now)) else favoriteDao.remove(listingId)
            true
        } catch (e: java.io.IOException) {
            // ออฟไลน์ — ค้างไว้ แล้วสั่งตัวส่งเบื้องหลัง: มันรอจนมีเน็ตแล้วส่งให้เอง (ไม่ต้องรอผู้ใช้เปิดแอปใหม่)
            sync.enqueue()
            false
        } catch (e: retrofit2.HttpException) {
            // server ปฏิเสธ (เช่น 404 ประกาศถูกซ่อนแล้ว) → ย้อนค่าในเครื่องให้ตรงกับความจริง
            favoriteDao.remove(listingId)
            throw e
        }
    }

    /** ส่งรายการที่ค้างอยู่ขึ้น server (เรียกจาก SyncWorker) — โยน IOException ถ้ายังไม่มีเน็ต */
    suspend fun syncPendingFavorites() {
        for (fav in favoriteDao.pending()) {
            try {
                if (fav.pendingRemove) api.removeFavorite(fav.listingId) else api.addFavorite(fav.listingId)
                if (fav.pendingRemove) favoriteDao.remove(fav.listingId) else favoriteDao.upsert(fav.copy(pendingSync = false))
            } catch (e: retrofit2.HttpException) {
                if (e.code() in 400..499 && e.code() != 401) favoriteDao.remove(fav.listingId) else throw e
            }
        }
    }

    /**
     * ดึงรายการโปรดทั้งหมดจาก server มาใส่ Room (ตอนเปิดแอป/เปิดหน้ารายการโปรด) และเก็บการ์ดไว้ดูออฟไลน์
     * รายการที่ผู้ใช้เพิ่งกดแล้วยังรอส่ง ไม่ถูกทับ
     */
    suspend fun refreshFavorites(): List<ListingCardDto> {
        val cards = api.favorites()
        favoriteDao.applyServer(cards.map { FavoriteEntity(it.id, Instant.parse(it.createdAt).toEpochMilli()) })
        saveCache("favorites", cards)
        return cards
    }

    suspend fun cachedFavorites(): List<ListingCardDto>? = loadCache("favorites")
}
