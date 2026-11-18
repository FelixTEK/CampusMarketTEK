package com.example.campusmarkettek.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.DealListItemDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.ChatRepository
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.common.formatPrice
import com.example.campusmarkettek.ui.components.DraftBadge
import com.example.campusmarkettek.ui.components.EmptyView
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DealsUi(val role: String = "buyer", val content: UiState<List<DealListItemDto>> = UiState.Loading)

@HiltViewModel
class DealsViewModel @Inject constructor(private val chat: ChatRepository) : ViewModel() {
    private val _state = MutableStateFlow(DealsUi())
    val state: StateFlow<DealsUi> = _state.asStateFlow()

    init { load("buyer") }

    fun load(role: String) {
        _state.value = DealsUi(role, UiState.Loading)
        viewModelScope.launch {
            try {
                val list = chat.deals(role)
                _state.value = DealsUi(role, if (list.isEmpty()) UiState.Empty else UiState.Success(list))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DealsUi(role, UiState.Error(e.toUserMessage()))
            }
        }
    }
}

/** หน้า 06 ดีลของฉัน: แยกที่ฉันซื้อ / ที่ฉันขาย แตะแล้วเข้าห้องแชตของดีลนั้น */
@Composable
fun DealsScreen(onBack: () -> Unit, onOpenRoom: (String) -> Unit, viewModel: DealsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("ดีลของฉัน", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ui.role == "buyer", onClick = { viewModel.load("buyer") }, label = { Text("ที่ฉันซื้อ") })
            FilterChip(selected = ui.role == "seller", onClick = { viewModel.load("seller") }, label = { Text("ที่ฉันขาย") })
        }
        when (val c = ui.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = { viewModel.load(ui.role) })
            UiState.Empty -> EmptyView(Icons.Outlined.Handshake, "ยังไม่มีดีล", "ดีลจะแสดงที่นี่เมื่อมีการเสนอซื้อ")
            is UiState.Success -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(c.data, key = { it.id }) { d ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = d.conversationId != null) { d.conversationId?.let(onOpenRoom) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.listing.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                DraftBadge(dealStatusLabel(d.status), isProblem = d.status == "REJECTED" || d.status == "CANCELLED")
                            }
                            Text(formatPrice(d.listing.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text((if (ui.role == "buyer") "ผู้ขาย: " else "ผู้ซื้อ: ") + d.other.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!d.meetup.isNullOrBlank()) Text("นัดรับ: ${d.meetup}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
