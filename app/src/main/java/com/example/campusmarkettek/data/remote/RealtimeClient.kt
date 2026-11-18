package com.example.campusmarkettek.data.remote

import com.example.campusmarkettek.BuildConfig
import com.example.campusmarkettek.data.auth.SessionManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** เหตุการณ์สดจาก server (/ws) */
sealed interface RealtimeEvent {
    data class Message(val message: MessageDto) : RealtimeEvent
    data class Deal(val conversationId: String, val deal: DealDto) : RealtimeEvent

    /** เชื่อมต่อ (หรือเชื่อมต่อใหม่) สำเร็จ — ช่วงที่หลุดอาจพลาดข้อความ หน้าจอควรโหลดซ้ำ */
    data object Connected : RealtimeEvent
}

/**
 * ตัวเชื่อม WebSocket ไปที่ backend: เปิดไว้ตอนแอปอยู่หน้าจอ (start/stop ตามวงจรชีวิต)
 * หลุดแล้วต่อใหม่เอง (รอ 1, 2, 4 ... สูงสุด 30 วินาที) · ping ทุก 25 วินาทีเพื่อรู้ว่าเส้นตายแล้ว
 * ส่งข้อความผ่าน REST เท่านั้น (ตรวจสิทธิ์/กันซ้ำที่เดียว) — ช่องนี้รับอย่างเดียว
 */
@Singleton
class RealtimeClient @Inject constructor(
    @Named("ws") private val client: OkHttpClient,
    private val session: SessionManager,
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var socket: WebSocket? = null

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                val token = session.token()
                if (token == null) { delay(2_000); continue } // ยังไม่ล็อกอิน
                val closed = CompletableDeferred<Boolean>()
                val request = Request.Builder().url(wsUrl()).header("Authorization", "Bearer $token").build()
                socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        _connected.value = true
                        backoff = 1_000L
                        _events.tryEmit(RealtimeEvent.Connected)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        parse(text)?.let { _events.tryEmit(it) }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete(code == 4401 || code == 4403) // ถูกปฏิเสธเรื่องสิทธิ์ → ไม่ต้องรีบต่อใหม่
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.complete(false)
                    }
                })
                val denied = closed.await()
                _connected.value = false
                socket = null
                delay(if (denied) 30_000L else backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
    }

    private fun wsUrl(): String =
        BuildConfig.API_BASE_URL.trimEnd('/').replaceFirst("http", "ws") + "/ws"

    private fun parse(text: String): RealtimeEvent? = try {
        val o = gson.fromJson(text, JsonObject::class.java)
        when (o.get("type")?.asString) {
            "message" -> RealtimeEvent.Message(gson.fromJson(o.get("message"), MessageDto::class.java))
            "deal" -> RealtimeEvent.Deal(o.get("conversationId").asString, gson.fromJson(o.get("deal"), DealDto::class.java))
            else -> null // hello และชนิดที่ยังไม่รู้จัก
        }
    } catch (e: Exception) {
        null
    }
}
