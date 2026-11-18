package com.example.campusmarkettek.data.repository

import com.example.campusmarkettek.data.auth.SessionManager
import com.example.campusmarkettek.data.local.dao.BrowseCacheDao
import com.example.campusmarkettek.data.local.dao.DealDao
import com.example.campusmarkettek.data.local.dao.MessageDao
import com.example.campusmarkettek.data.local.entity.BrowseCacheEntity
import com.example.campusmarkettek.data.local.entity.DealEntity
import com.example.campusmarkettek.data.local.entity.MessageEntity
import com.example.campusmarkettek.data.remote.ChatApi
import com.example.campusmarkettek.data.remote.ConversationItemDto
import com.example.campusmarkettek.data.remote.DealDto
import com.example.campusmarkettek.data.remote.DealListItemDto
import com.example.campusmarkettek.data.remote.MeetupRequest
import com.example.campusmarkettek.data.remote.MessageDto
import com.example.campusmarkettek.data.remote.RealtimeEvent
import com.example.campusmarkettek.data.remote.RoomDto
import com.example.campusmarkettek.data.remote.SendMessageRequest
import com.example.campusmarkettek.work.SyncScheduler
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** ข้อความหนึ่งบรรทัดในห้อง + ดีลที่แนบ (ถ้าเป็นการ์ดดีล) */
data class ChatItem(val message: MessageEntity, val deal: DealEntity?)

/**
 * แชตและดีล: Room เป็นแหล่งที่หน้าจออ่าน (ออฟไลน์ก็เปิดห้องดูข้อความเก่าได้)
 * ข้อมูลใหม่มาจาก REST (ตอนเปิดห้อง) และ WebSocket (ตอนมีคนส่ง/ดีลเปลี่ยน) แล้วเขียนลง Room
 * ข้อความที่ฉันพิมพ์ลงห้องทันทีเป็น PENDING พร้อม client_id แล้วค่อยส่ง — ส่งซ้ำกี่ครั้ง server ก็ไม่เบิ้ล
 */
@Singleton
class ChatRepository @Inject constructor(
    private val api: ChatApi,
    private val messageDao: MessageDao,
    private val dealDao: DealDao,
    private val cacheDao: BrowseCacheDao,
    private val session: SessionManager,
    private val sync: SyncScheduler,
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _unreadTotal = MutableStateFlow(0)
    /** จำนวนข้อความที่ยังไม่อ่านทุกห้อง — ตัวเลขบนแท็บแชต (BR28: ไม่ผ่านตารางแจ้งเตือน) */
    val unreadTotal: StateFlow<Int> = _unreadTotal.asStateFlow()

    // ── ห้องและรายการห้อง ─────────────────────────────────────────────

    /** เปิดห้องจากหน้าประกาศ (มีห้องเดิมก็ใช้ต่อ) */
    suspend fun openRoom(listingId: String): RoomDto = api.openRoom(listingId).also { cache("room:${it.id}", it) }

    suspend fun room(id: String): RoomDto = api.room(id).also { cache("room:$id", it) }
    suspend fun cachedRoom(id: String): RoomDto? = readCache("room:$id", RoomDto::class.java)

    suspend fun conversations(): List<ConversationItemDto> {
        val list = api.conversations()
        _unreadTotal.value = list.sumOf { it.unread }
        cache("conversations", list)
        return list
    }

    suspend fun cachedConversations(): List<ConversationItemDto>? {
        val json = cacheDao.get("conversations")?.json ?: return null
        return runCatching { gson.fromJson<List<ConversationItemDto>>(json, object : TypeToken<List<ConversationItemDto>>() {}.type) }.getOrNull()
    }

    private suspend fun cache(key: String, value: Any) = cacheDao.put(BrowseCacheEntity(key, gson.toJson(value), System.currentTimeMillis()))

    private suspend fun <T> readCache(key: String, type: Class<T>): T? =
        cacheDao.get(key)?.let { runCatching { gson.fromJson(it.json, type) }.getOrNull() }

    // ── ข้อความ ──────────────────────────────────────────────────────

    fun observeItems(conversationId: String): Flow<List<ChatItem>> =
        combine(messageDao.observeConversation(conversationId), dealDao.observeAll()) { messages, deals ->
            val byId = deals.associateBy { it.id }
            messages.map { ChatItem(it, it.dealId?.let(byId::get)) }
        }

    /** ดึงข้อความล่าสุดจาก server ใส่ Room · คืน true ถ้ายังมีข้อความเก่ากว่านี้ให้โหลดอีก */
    suspend fun refreshLatest(conversationId: String): Boolean {
        val page = api.messages(conversationId)
        upsertMessages(page.items)
        return page.hasMore
    }

    suspend fun loadOlder(conversationId: String): Boolean {
        val oldest = messageDao.oldestServerId(conversationId) ?: return false
        val page = api.messages(conversationId, before = oldest)
        upsertMessages(page.items)
        return page.hasMore
    }

    private suspend fun upsertMessages(list: List<MessageDto>) {
        if (list.isEmpty()) return
        messageDao.upsertAll(list.map { it.toEntity() })
        list.mapNotNull { it.deal }.distinctBy { it.id }.forEach { dealDao.upsert(it.toEntity()) }
    }

    /** พิมพ์ส่ง: ขึ้นในห้องทันที (กำลังส่ง) แล้วส่งขึ้น server — ออฟไลน์ก็ค้างไว้ให้ SyncWorker ส่งเมื่อมีเน็ต */
    suspend fun send(conversationId: String, text: String) {
        val me = session.userId() ?: return
        val entity = MessageEntity(
            localId = UUID.randomUUID().toString(), serverId = null, conversationId = conversationId, senderId = me,
            body = text.trim(), dealId = null, readAt = null, createdAt = System.currentTimeMillis(), sendState = "PENDING",
        )
        messageDao.upsertAll(listOf(entity))
        push(entity)
    }

    /** กดลองส่งใหม่ที่ข้อความ "ส่งไม่สำเร็จ" */
    suspend fun retry(localId: String) {
        val e = messageDao.get(localId) ?: return
        val pending = e.copy(sendState = "PENDING")
        messageDao.upsertAll(listOf(pending))
        push(pending)
    }

    private suspend fun push(e: MessageEntity) {
        try {
            upsertMessages(listOf(api.send(e.conversationId, SendMessageRequest(e.body, e.localId))))
        } catch (io: IOException) {
            sync.enqueue() // ออฟไลน์ — รอส่งตอนมีเน็ต
        } catch (http: HttpException) {
            // 429 (ส่งถี่) หรือ server ล่ม = ลองใหม่ทีหลัง · อย่างอื่น (สิทธิ์/ข้อความผิดกติกา) = ส่งไม่ได้ ให้ผู้ใช้เห็น
            if (http.code() == 429 || http.code() >= 500) sync.enqueue() else messageDao.upsertAll(listOf(e.copy(sendState = "FAILED")))
        }
    }

    /** ส่งข้อความที่ค้าง (เรียกจาก SyncWorker) — โยน IOException ถ้ายังไม่มีเน็ต */
    suspend fun syncPendingMessages() {
        val me = session.userId() ?: return
        for (e in messageDao.pendingFrom(me)) {
            try {
                upsertMessages(listOf(api.send(e.conversationId, SendMessageRequest(e.body, e.localId))))
            } catch (http: HttpException) {
                if (http.code() == 429 || http.code() >= 500 || http.code() == 401) throw http
                messageDao.upsertAll(listOf(e.copy(sendState = "FAILED")))
            }
        }
    }

    /** ทำเครื่องหมายอ่านแล้ว (ตอนเปิดห้อง/มีข้อความใหม่ขณะเปิดห้อง) แล้วรีเฟรชตัวเลขที่ยังไม่อ่าน */
    suspend fun markRead(conversationId: String) {
        runCatching { api.markRead(conversationId) }
        refreshUnread()
    }

    suspend fun refreshUnread() {
        runCatching { conversations() }
    }

    // ── เหตุการณ์สดจาก WebSocket → Room ───────────────────────────────

    suspend fun onEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.Message -> {
                upsertMessages(listOf(event.message))
                if (event.message.senderId != session.userId()) scope.launch { refreshUnread() }
            }
            is RealtimeEvent.Deal -> dealDao.upsert(event.deal.toEntity())
            RealtimeEvent.Connected -> scope.launch { refreshUnread() } // กลับมาออนไลน์: ตัวเลขอาจเปลี่ยนระหว่างที่หลุด
        }
    }

    // ── ดีล ──────────────────────────────────────────────────────────
    // server เป็นผู้ตัดสินเสมอ (ไม่เดาผลเอง) — ผลที่ได้เขียนลง Room ทันที และข้อความระบบตามมาทาง WebSocket/รีเฟรช

    suspend fun offer(conversationId: String): DealDto = saved(api.offer(conversationId), conversationId)
    suspend fun accept(dealId: String, conversationId: String) = saved(api.accept(dealId), conversationId)
    suspend fun reject(dealId: String, conversationId: String) = saved(api.reject(dealId), conversationId)
    suspend fun cancel(dealId: String, conversationId: String) = saved(api.cancel(dealId), conversationId)
    suspend fun handover(dealId: String, conversationId: String) = saved(api.handover(dealId), conversationId)
    suspend fun receive(dealId: String, conversationId: String) = saved(api.receive(dealId), conversationId)
    suspend fun setMeetup(dealId: String, conversationId: String, text: String) = saved(api.meetup(dealId, MeetupRequest(text)), conversationId)

    private suspend fun saved(deal: DealDto, conversationId: String): DealDto {
        dealDao.upsert(deal.toEntity())
        runCatching { refreshLatest(conversationId) } // ได้ข้อความระบบ/การ์ดใหม่แม้ WebSocket ไม่ได้เชื่อมอยู่
        return deal
    }

    suspend fun deals(role: String): List<DealListItemDto> = api.deals(role)
}

private fun String.ms() = Instant.parse(this).toEpochMilli()

private fun MessageDto.toEntity() = MessageEntity(
    // ข้อความจากคนที่ส่งมี client_id → ใช้เป็นคีย์ (ตรงกับข้อความ PENDING ของฉัน จึงทับกันพอดี) · ข้อความระบบไม่มี
    localId = clientId ?: "srv-$id", serverId = id, conversationId = conversationId, senderId = senderId, body = body,
    dealId = dealId, readAt = readAt?.ms(), createdAt = createdAt.ms(), sendState = "SENT",
)

private fun DealDto.toEntity() = DealEntity(
    id = id, listingId = listingId, buyerId = buyerId, status = status, meetup = meetup,
    createdAt = createdAt.ms(), updatedAt = updatedAt.ms(), completedAt = completedAt?.ms(),
)
