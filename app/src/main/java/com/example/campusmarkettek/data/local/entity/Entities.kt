package com.example.campusmarkettek.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Entity ของ Room = แคชในเครื่องของตารางใน PostgreSQL (backend/migrations/001_init.sql)
 * ชื่อคอลัมน์ตรงกับฐานข้อมูล (snake_case → camelCase) ไม่เพิ่ม/ลดข้อมูลของ server
 * เวลา (timestamptz) เก็บเป็น epoch millis
 *
 * คอลัมน์ที่มีเฉพาะในเครื่อง (ไม่มีใน PostgreSQL) มีไว้ซิงก์ตอนออฟไลน์เท่านั้น และระบุไว้ที่ตัวคอลัมน์
 */

/** categories */
@Entity(tableName = "categories", indices = [Index("parentId", "sort")])
data class CategoryEntity(
    @PrimaryKey val id: Int,
    val parentId: Int?,
    val nameTh: String,
    val sort: Int,
)

/** faculties */
@Entity(tableName = "faculties")
data class FacultyEntity(
    @PrimaryKey val id: Int,
    val nameTh: String,
)

/** listings (ไม่เก็บ search_text เพราะเป็นของ backend) */
@Entity(tableName = "listings", indices = [Index("status", "createdAt"), Index("categoryId")])
data class ListingEntity(
    @PrimaryKey val id: String,
    val sellerId: String,
    val categoryId: Int,
    val title: String,
    val description: String,
    val price: Int,
    val status: String,          // AVAILABLE | RESERVED | SOLD | CANCELLED | RETURNED | TAKEN_DOWN
    val statusReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** listing_images */
@Entity(tableName = "listing_images", primaryKeys = ["listingId", "position"])
data class ListingImageEntity(
    val listingId: String,
    val position: Int,           // 0–4, 0 = ปก
    val imageKey: String,
)

/** favorites (ของผู้ใช้ที่ล็อกอินอยู่ จึงไม่เก็บ user_id) */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val listingId: String,
    val createdAt: Long,
    /** เฉพาะในเครื่อง: กดหัวใจ/เอาออกตอนออฟไลน์แล้วยังไม่ได้ส่งไป server */
    val pendingSync: Boolean = false,
    /** เฉพาะในเครื่อง: pendingSync + pendingRemove = เอาออกจากโปรดแล้ว รอส่ง DELETE (ซ่อนจากหน้าจอทันที) */
    val pendingRemove: Boolean = false,
)

/** conversations */
@Entity(tableName = "conversations", indices = [Index("listingId")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val listingId: String,
    val buyerId: String,
    val createdAt: Long,
)

/** deals */
@Entity(tableName = "deals", indices = [Index("listingId", "status")])
data class DealEntity(
    @PrimaryKey val id: String,
    val listingId: String,
    val buyerId: String,
    val status: String,          // PENDING | ACCEPTED | HANDED_OVER | COMPLETED | REJECTED | CANCELLED
    val meetup: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

/**
 * messages
 * ข้อความที่พิมพ์ตอนออฟไลน์ยังไม่มี id จาก server จึงใช้ localId เป็นคีย์ (= client_id ที่จะส่งไป server กันส่งซ้ำ)
 * ข้อความจาก server (ไม่มี client_id เช่นข้อความระบบ) ใช้ localId = "srv-<id>"
 */
@Entity(tableName = "messages", indices = [Index("conversationId", "createdAt")])
data class MessageEntity(
    @PrimaryKey val localId: String,
    /** messages.id จาก server — ว่างจนกว่าจะส่งสำเร็จ */
    val serverId: Long?,
    val conversationId: String,
    val senderId: String?,       // null = ข้อความระบบ
    val body: String,
    val dealId: String?,         // มีค่า = การ์ดดีลในแชต
    val readAt: Long?,
    val createdAt: Long,
    /** เฉพาะในเครื่อง: SENT | PENDING | FAILED (เฟส 6) */
    val sendState: String = "SENT",
)

/** notifications */
@Entity(tableName = "notifications", indices = [Index("createdAt")])
data class NotificationEntity(
    @PrimaryKey val id: Long,
    val type: String,
    val body: String,
    val refId: String?,
    /** ห้องแชต/ประกาศที่เกี่ยวข้อง — ไว้แตะแล้วไปหน้านั้น (server หามาให้จากดีล) */
    val conversationId: String?,
    val listingId: String?,
    val isRead: Boolean,
    val createdAt: Long,
)

/**
 * แบบร่างประกาศ — มีเฉพาะในเครื่อง (ไม่มีตารางคู่กันใน PostgreSQL)
 * state: DRAFT = ผู้ใช้บันทึกไว้ยังไม่ส่ง · PENDING = รอส่ง (ส่งเมื่อมีเน็ต) · FAILED = server ปฏิเสธ (ดู error)
 * รูปเก็บเป็นไฟล์ใน filesDir/drafts/<id>/<ลำดับ>.jpg · ช่องที่ผู้ใช้พิมพ์เก็บเป็นข้อความตามที่พิมพ์
 */
@Entity(tableName = "listing_drafts")
data class DraftListingEntity(
    @PrimaryKey val id: String,
    /** ผู้ใช้ที่เป็นเจ้าของแบบร่าง — เครื่องเดียวหลายบัญชี ต้องไม่ส่งแบบร่างของคนหนึ่งในนามอีกคน */
    val ownerId: String,
    val categoryId: Int?,
    val title: String,
    val description: String,
    val price: String,
    val facultyIds: String,   // "1,2,3"
    val tagIds: String,
    val imageCount: Int,
    val state: String,
    val error: String?,
    val updatedAt: Long,
)

/** ประวัติค้นหา — เฉพาะในเครื่อง ไม่ขึ้น server */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val term: String,
    val usedAt: Long,
)

/**
 * แคชหน้าแรกของผลค้นหา/รายการโปรดไว้ดูตอนออฟไลน์ — เก็บทั้งหน้าเป็น JSON ตามที่ server ตอบ
 * (การ์ดมีข้อมูลจากหลายตาราง: listings + users + faculties จึงไม่แตกลงตารางแคชของ listings)
 * key: "home" = หน้าแรกไม่มีตัวกรอง · "favorites" = รายการโปรด
 */
@Entity(tableName = "browse_cache")
data class BrowseCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long,
)
