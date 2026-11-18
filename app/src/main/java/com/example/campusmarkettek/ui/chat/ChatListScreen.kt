package com.example.campusmarkettek.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.example.campusmarkettek.data.remote.ConversationItemDto
import com.example.campusmarkettek.data.remote.RealtimeClient
import com.example.campusmarkettek.data.remote.RealtimeEvent
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.ChatRepository
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.common.imageUrl
import com.example.campusmarkettek.ui.common.relativeThai
import com.example.campusmarkettek.ui.components.EmptyView
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.components.ScreenTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class ChatListUi(
    val content: UiState<List<ConversationItemDto>> = UiState.Loading,
    /** กำลังโชว์รายการที่เคยโหลดไว้ เพราะเชื่อมต่อไม่ได้ */
    val offline: Boolean = false,
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chat: ChatRepository,
    realtime: RealtimeClient,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatListUi())
    val state: StateFlow<ChatListUi> = _state.asStateFlow()

    init {
        load(showSpinner = true)
        // มีข้อความใหม่/ดีลเปลี่ยน/ต่อใหม่ → โหลดรายการห้องใหม่เงียบ ๆ (ลำดับห้อง ข้อความล่าสุด จำนวนที่ยังไม่อ่าน)
        viewModelScope.launch {
            realtime.events.collect { if (it is RealtimeEvent.Message || it is RealtimeEvent.Deal || it is RealtimeEvent.Connected) load(showSpinner = false) }
        }
    }

    fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner && _state.value.content !is UiState.Success) _state.value = ChatListUi(UiState.Loading)
            try {
                val list = chat.conversations()
                _state.value = ChatListUi(if (list.isEmpty()) UiState.Empty else UiState.Success(list))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cached = if (e is java.io.IOException) chat.cachedConversations() else null
                _state.value = when {
                    cached != null -> ChatListUi(if (cached.isEmpty()) UiState.Empty else UiState.Success(cached), offline = true)
                    _state.value.content is UiState.Success -> _state.value.copy(offline = e is java.io.IOException)
                    else -> ChatListUi(UiState.Error(e.toUserMessage()))
                }
            }
        }
    }
}

/** แท็บแชต: ห้องแชตทั้งที่ฉันซื้อและที่ฉันขาย เรียงตามข้อความล่าสุด · ตัวเลขแดง = ข้อความที่ยังไม่อ่าน */
@Composable
fun ChatListScreen(onOpen: (String) -> Unit, viewModel: ChatListViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.load(showSpinner = false) // กลับมาหน้านี้ (เช่นจากห้องแชต) → ตัวเลขที่ยังไม่อ่านต้องอัปเดต
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("แชต")
        if (ui.offline) {
            Text(
                "เชื่อมต่อไม่ได้ กำลังแสดงรายการที่เคยโหลดไว้",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        when (val c = ui.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = { viewModel.load(showSpinner = true) })
            UiState.Empty -> EmptyView(Icons.Outlined.Forum, "ยังไม่มีแชต", "กดแชตจากหน้าสินค้าเพื่อเริ่มคุยกับผู้ขาย")
            is UiState.Success -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(c.data, key = { it.id }) { room -> RoomRow(room, onClick = { onOpen(room.id) }) }
            }
        }
    }
}

@Composable
private fun RoomRow(room: ConversationItemDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))) {
                if (room.listing.cover != null) AsyncImage(imageUrl(room.listing.cover), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(room.other.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    room.lastMessage?.let {
                        Text(relativeThai(Instant.parse(it.createdAt).toEpochMilli()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    (if (room.role == "BUYER") "ผู้ขาย · " else "ผู้ซื้อ · ") + room.listing.title,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    room.lastMessage?.body ?: "ยังไม่มีข้อความ",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (room.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (room.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            if (room.unread > 0) Badge(containerColor = MaterialTheme.colorScheme.tertiary) { Text(if (room.unread > 99) "99+" else room.unread.toString()) }
        }
    }
}
