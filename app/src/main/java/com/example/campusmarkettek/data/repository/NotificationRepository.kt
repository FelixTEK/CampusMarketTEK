package com.example.campusmarkettek.data.repository

import com.example.campusmarkettek.data.local.dao.NotificationDao
import com.example.campusmarkettek.data.local.entity.NotificationEntity
import com.example.campusmarkettek.data.remote.NotificationApi
import com.example.campusmarkettek.data.remote.NotificationDto
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * แจ้งเตือน: Room เป็นแหล่งที่หน้าจอและ badge อ่าน (ออฟไลน์ก็ดูของเก่าได้)
 * ดึงจาก server เฉพาะแถวที่ใหม่กว่า id ล่าสุดที่เครื่องมี — กดอ่านแล้วเปลี่ยนในเครื่องทันที แล้วค่อยบอก server
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val api: NotificationApi,
    private val dao: NotificationDao,
) {
    fun observeAll(): Flow<List<NotificationEntity>> = dao.observeAll()

    /**
     * ดึงแถวใหม่ลง Room · คืนเฉพาะแถวใหม่ที่ยังไม่อ่าน (ไว้ให้ NotificationWorker ขึ้นแจ้งเตือนระบบ)
     * ครั้งแรกหลังล็อกอิน (เครื่องยังว่าง) คืนลิสต์ว่าง — เป็นประวัติเก่า ไม่ใช่ของใหม่ที่ควรเด้ง
     */
    suspend fun refresh(): List<NotificationEntity> {
        val last = dao.maxId()
        val page = api.list(after = last)
        val rows = page.items.map { it.toEntity() }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
        return if (last == null) emptyList() else rows.filter { !it.isRead }
    }

    suspend fun markRead(n: NotificationEntity) {
        if (n.isRead) return
        dao.markRead(n.id)
        runCatching { api.read(n.id) } // บอก server ไม่ได้ตอนออฟไลน์ก็ไม่เป็นไร: ในเครื่องอ่านแล้ว
    }

    suspend fun markAllRead() {
        dao.markAllRead()
        runCatching { api.readAll() }
    }
}

private fun NotificationDto.toEntity() = NotificationEntity(
    id = id, type = type, body = body, refId = refId, conversationId = conversationId, listingId = listingId,
    isRead = isRead, createdAt = Instant.parse(createdAt).toEpochMilli(),
)

/** ปลายทางตอนแตะแจ้งเตือน: ห้องแชตของดีล · ไม่มีก็หน้าประกาศ · ไม่มีก็ไม่ไปไหน */
fun NotificationEntity.targetRoute(): String? = when {
    conversationId != null -> "chat/$conversationId"
    listingId != null -> "listing/$listingId"
    else -> null
}
