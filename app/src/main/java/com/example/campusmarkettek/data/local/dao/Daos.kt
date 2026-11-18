package com.example.campusmarkettek.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.campusmarkettek.data.local.entity.CategoryEntity
import com.example.campusmarkettek.data.local.entity.ConversationEntity
import com.example.campusmarkettek.data.local.entity.BrowseCacheEntity
import com.example.campusmarkettek.data.local.entity.DealEntity
import com.example.campusmarkettek.data.local.entity.SearchHistoryEntity
import com.example.campusmarkettek.data.local.entity.DraftListingEntity
import com.example.campusmarkettek.data.local.entity.FacultyEntity
import com.example.campusmarkettek.data.local.entity.FavoriteEntity
import com.example.campusmarkettek.data.local.entity.ListingEntity
import com.example.campusmarkettek.data.local.entity.ListingImageEntity
import com.example.campusmarkettek.data.local.entity.MessageEntity
import com.example.campusmarkettek.data.local.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sort, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId IS NULL ORDER BY sort, id")
    fun observeTopLevel(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY sort, id")
    fun observeChildren(parentId: Int): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun clear()

    /** แทนที่ทั้งชุดในธุรกรรมเดียว (หมวดที่ server ลบแล้วหายจากแคชด้วย) */
    @Transaction
    suspend fun replaceAll(items: List<CategoryEntity>) {
        clear()
        upsertAll(items)
    }
}

@Dao
interface FacultyDao {
    @Transaction
    suspend fun replaceAll(items: List<FacultyEntity>) {
        clear()
        upsertAll(items)
    }

    @Query("DELETE FROM faculties")
    suspend fun clear()

    @Query("SELECT * FROM faculties ORDER BY id")
    fun observeAll(): Flow<List<FacultyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FacultyEntity>)
}

@Dao
interface ListingDao {
    @Query("SELECT * FROM listings WHERE status IN ('AVAILABLE', 'RESERVED') ORDER BY createdAt DESC")
    fun observeBrowsable(): Flow<List<ListingEntity>>

    @Query("SELECT * FROM listings WHERE sellerId = :sellerId ORDER BY createdAt DESC")
    fun observeBySeller(sellerId: String): Flow<List<ListingEntity>>

    @Query("SELECT * FROM listing_images WHERE listingId = :listingId ORDER BY position")
    fun observeImages(listingId: String): Flow<List<ListingImageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertListings(items: List<ListingEntity>)

    @Query("DELETE FROM listings WHERE sellerId = :sellerId")
    suspend fun clearBySeller(sellerId: String)

    @Query("SELECT * FROM listing_images WHERE position = 0")
    fun observeCovers(): Flow<List<ListingImageEntity>>

    /** แทนที่ประกาศของผู้ขายคนนี้ทั้งชุด (ประกาศที่หายไปจาก server หายจากแคชด้วย) */
    @Transaction
    suspend fun replaceMine(sellerId: String, listings: List<ListingEntity>, covers: List<ListingImageEntity>) {
        clearBySeller(sellerId)
        upsertListings(listings)
        upsertImages(covers)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImages(items: List<ListingImageEntity>)
}

@Dao
interface FavoriteDao {
    /** รายการโปรดที่แสดง (ไม่รวมที่เอาออกแล้วแต่รอส่ง DELETE) */
    @Query("SELECT * FROM favorites WHERE pendingRemove = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE pendingSync = 1")
    suspend fun pending(): List<FavoriteEntity>

    @Query("SELECT listingId FROM favorites")
    suspend fun allIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE listingId = :listingId")
    suspend fun remove(listingId: String)

    @Query("DELETE FROM favorites WHERE pendingSync = 0 AND listingId NOT IN (:keep)")
    suspend fun removeSyncedExcept(keep: List<String>)

    @Query("DELETE FROM favorites WHERE pendingSync = 0")
    suspend fun removeAllSynced()

    /** ใส่ค่าจาก server ทับ — รายการที่ยังรอส่งอยู่ (ผู้ใช้เพิ่งกด) ไม่ถูกแตะ */
    @Transaction
    suspend fun applyServer(items: List<FavoriteEntity>) {
        val pendingIds = pending().map { it.listingId }.toSet()
        val fresh = items.filter { it.listingId !in pendingIds }
        if (fresh.isEmpty()) removeAllSynced() else removeSyncedExcept(fresh.map { it.listingId })
        upsertAll(fresh)
    }
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY usedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE term = :term")
    suspend fun delete(term: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface BrowseCacheDao {
    @Query("SELECT * FROM browse_cache WHERE key = :key")
    suspend fun get(key: String): BrowseCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: BrowseCacheEntity)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ConversationEntity>)
}

@Dao
interface DealDao {
    @Query("SELECT * FROM deals ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DealEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DealEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DealEntity>)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, localId")
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sendState != 'SENT' ORDER BY createdAt")
    suspend fun unsent(): List<MessageEntity>

    /** ข้อความของฉันที่พิมพ์ตอนออฟไลน์และรอส่ง (FAILED ไม่ส่งซ้ำเอง — ผู้ใช้กดลองใหม่) */
    @Query("SELECT * FROM messages WHERE sendState = 'PENDING' AND senderId = :me ORDER BY createdAt")
    suspend fun pendingFrom(me: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE localId = :localId")
    suspend fun get(localId: String): MessageEntity?

    /** id ข้อความที่เก่าที่สุดที่ server ให้มาแล้วในห้องนี้ — ใช้เป็นจุดเริ่มของ "โหลดข้อความเก่ากว่า" */
    @Query("SELECT MIN(serverId) FROM messages WHERE conversationId = :conversationId")
    suspend fun oldestServerId(conversationId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MessageEntity>)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY id DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<NotificationEntity>)

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllRead()

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    /** id ล่าสุดที่เครื่องมี (null = ยังไม่เคยโหลด) — ใช้ถาม server เฉพาะที่ใหม่กว่า */
    @Query("SELECT MAX(id) FROM notifications")
    suspend fun maxId(): Long?
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM listing_drafts WHERE ownerId = :ownerId ORDER BY updatedAt DESC")
    fun observeAll(ownerId: String): Flow<List<DraftListingEntity>>

    /** ทุกบัญชี — ใช้เก็บกลับหลังล้างแคชตอนออกจากระบบ (แบบร่างต้องไม่หายเมื่อ token หมดอายุ) */
    @Query("SELECT * FROM listing_drafts")
    suspend fun all(): List<DraftListingEntity>

    @Query("SELECT * FROM listing_drafts WHERE id = :id")
    suspend fun get(id: String): DraftListingEntity?

    @Query("SELECT * FROM listing_drafts WHERE state = 'PENDING' AND ownerId = :ownerId ORDER BY updatedAt")
    suspend fun pending(ownerId: String): List<DraftListingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DraftListingEntity)

    @Query("UPDATE listing_drafts SET state = :state, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: String, state: String, error: String?, now: Long)

    @Query("DELETE FROM listing_drafts WHERE id = :id")
    suspend fun delete(id: String)
}
