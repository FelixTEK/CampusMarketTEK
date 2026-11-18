package com.example.campusmarkettek.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.auth.SessionManager
import com.example.campusmarkettek.data.remote.RealtimeClient
import com.example.campusmarkettek.data.remote.RealtimeEvent
import com.example.campusmarkettek.data.remote.RoomDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.ChatItem
import com.example.campusmarkettek.data.repository.ChatRepository
import com.example.campusmarkettek.data.repository.ModerationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatRoomUi(
    val room: RoomDto? = null,
    val loading: Boolean = true,
    /** โหลดห้องไม่ได้และไม่มีแคช (เช่นเปิดจากแจ้งเตือนตอนออฟไลน์) */
    val error: String? = null,
    val input: String = "",
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    /** กำลังรอ server ตอบการกดปุ่มดีล (กันกดซ้ำ) */
    val busy: Boolean = false,
    /** ข้อความแจ้งสั้น ๆ เช่น "ประกาศนี้ถูกจองไปแล้ว" (409) */
    val notice: String? = null,
    /** ดาวที่ให้แต่ละดีล (dealId → ดาว) · checkedReviews = ดีลที่ถาม server แล้ว (ไม่มีดาว = ยังไม่รีวิว) */
    val ratings: Map<String, Int> = emptyMap(),
    val checkedReviews: Set<String> = emptySet(),
)

@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val chat: ChatRepository,
    private val realtime: RealtimeClient,
    private val moderation: ModerationRepository,
    session: SessionManager,
) : ViewModel() {

    private val id: String = checkNotNull(savedState["id"])
    val me: String = session.userId().orEmpty()

    private val _state = MutableStateFlow(ChatRoomUi())
    val state: StateFlow<ChatRoomUi> = _state.asStateFlow()

    /** ข้อความทั้งหมดในห้องจาก Room (ออฟไลน์ก็เห็นของเก่า) */
    val items: StateFlow<List<ChatItem>> = chat.observeItems(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        load()
        // ข้อความ/ดีลของห้องนี้มาทาง WebSocket (Room อัปเดตให้เองใน ChatRepository) — ที่นี่แค่ทำเครื่องหมายอ่านและรีเฟรชหัวห้อง
        viewModelScope.launch {
            realtime.events.collect { e ->
                when {
                    e is RealtimeEvent.Message && e.message.conversationId == id -> {
                        if (e.message.senderId != me) chat.markRead(id)
                    }
                    e is RealtimeEvent.Deal && e.conversationId == id -> refreshRoom()
                    e is RealtimeEvent.Connected -> { runCatching { chat.refreshLatest(id) }; chat.markRead(id) }
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val cached = chat.cachedRoom(id)
            if (cached != null) _state.update { it.copy(room = cached, loading = false) } // เปิดห้องได้ทันทีจากแคช
            try {
                val room = chat.room(id)
                _state.update { it.copy(room = room, loading = false) }
                val more = chat.refreshLatest(id)
                _state.update { it.copy(hasMore = more) }
                chat.markRead(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = if (it.room == null) e.toUserMessage() else null) }
            }
        }
    }

    private suspend fun refreshRoom() {
        runCatching { chat.room(id) }.onSuccess { r -> _state.update { it.copy(room = r) } }
    }

    fun onInput(v: String) = _state.update { it.copy(input = v.take(1000)) }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(input = "") }
        viewModelScope.launch { chat.send(id, text) }
    }

    fun retry(localId: String) {
        viewModelScope.launch { chat.retry(localId) }
    }

    fun loadOlder() {
        val s = _state.value
        if (!s.hasMore || s.loadingOlder) return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            val more = runCatching { chat.loadOlder(id) }.getOrDefault(false)
            _state.update { it.copy(loadingOlder = false, hasMore = more) }
        }
    }

    // ── ปุ่มบนการ์ดดีล: server เป็นผู้ตัดสิน (409 → แจ้งเหตุผลแล้วโหลดห้องใหม่ให้เห็นสถานะจริง) ──

    fun offer() = act { chat.offer(id) }
    fun accept(dealId: String) = act { chat.accept(dealId, id) }
    fun reject(dealId: String) = act { chat.reject(dealId, id) }
    fun cancel(dealId: String) = act { chat.cancel(dealId, id) }
    fun handover(dealId: String) = act { chat.handover(dealId, id) }
    fun receive(dealId: String) = act { chat.receive(dealId, id) }
    fun setMeetup(dealId: String, text: String) = act { chat.setMeetup(dealId, id, text) }

    private fun act(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = false) }
                refreshRoom() // สถานะประกาศ (กำลังขาย/ถูกจอง/ขายแล้ว) เปลี่ยนตามดีล
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, notice = e.toUserMessage()) }
                runCatching { chat.refreshLatest(id) }
                refreshRoom()
            }
        }
    }

    /** ถาม server ว่าดีลที่สำเร็จเหล่านี้รีวิวแล้วหรือยัง (ครั้งเดียวต่อดีล) */
    fun loadReviews(dealIds: Collection<String>) {
        val todo = dealIds.filter { it !in _state.value.checkedReviews }
        if (todo.isEmpty()) return
        _state.update { it.copy(checkedReviews = it.checkedReviews + todo) }
        viewModelScope.launch {
            for (d in todo) {
                runCatching { moderation.reviewOf(d) }.onSuccess { r ->
                    if (r != null) _state.update { it.copy(ratings = it.ratings + (d to r.rating)) }
                }.onFailure { _state.update { it.copy(checkedReviews = it.checkedReviews - d) } } // ออฟไลน์ → ถามใหม่ครั้งหน้า
            }
        }
    }

    fun review(dealId: String, rating: Int, comment: String) = act {
        try {
            val r = moderation.review(dealId, rating, comment)
            _state.update { it.copy(ratings = it.ratings + (dealId to r.rating), notice = "ขอบคุณสำหรับรีวิว") }
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 409) loadReviewsAgain(dealId)
            throw e
        }
    }

    private fun loadReviewsAgain(dealId: String) {
        _state.update { it.copy(checkedReviews = it.checkedReviews - dealId) }
        loadReviews(listOf(dealId))
    }

    fun notify(text: String) = _state.update { it.copy(notice = text) }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
}
