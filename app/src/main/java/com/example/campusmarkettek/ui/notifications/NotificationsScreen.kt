package com.example.campusmarkettek.ui.notifications

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.local.entity.NotificationEntity
import com.example.campusmarkettek.data.repository.NotificationRepository
import com.example.campusmarkettek.data.repository.targetRoute
import com.example.campusmarkettek.ui.common.relativeThai
import com.example.campusmarkettek.ui.components.EmptyView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(private val repo: NotificationRepository) : ViewModel() {

    val items: StateFlow<List<NotificationEntity>?> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** โหลดไม่ได้ (เช่นออฟไลน์) — ยังโชว์ของที่เก็บไว้ในเครื่อง แค่บอกให้รู้ */
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            try {
                repo.refresh()
                _offline.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _offline.value = true
            }
        }
    }

    fun open(n: NotificationEntity) {
        viewModelScope.launch { repo.markRead(n) }
    }

    fun markAllRead() {
        viewModelScope.launch { repo.markAllRead() }
    }
}

private fun iconFor(type: String): ImageVector = when (type) {
    "DEAL_OFFER" -> Icons.Outlined.ShoppingBag
    "DEAL_ACCEPTED" -> Icons.Outlined.Handshake
    "DEAL_HANDED_OVER" -> Icons.Outlined.LocalShipping
    "DEAL_COMPLETED", "DEAL_AUTO_COMPLETED" -> Icons.Outlined.CheckCircleOutline
    "DEAL_REJECTED", "DEAL_CANCELLED" -> Icons.Outlined.Cancel
    "DEAL_REMINDER" -> Icons.Outlined.Timer
    "REVIEW_PROMPT" -> Icons.Outlined.StarBorder
    "SPAM_WARN" -> Icons.Outlined.WarningAmber
    else -> Icons.Outlined.NotificationsNone
}

/** แท็บแจ้งเตือน (หน้า 07): ข้อเสนอและความเคลื่อนไหวของดีล · จุดสีบอกว่ายังไม่อ่าน · แตะแล้วไปห้องแชตของดีล */
@Composable
fun NotificationsScreen(onOpenRoute: (String) -> Unit, viewModel: NotificationsViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "แจ้งเตือน", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            if (items.orEmpty().any { !it.isRead }) TextButton(onClick = viewModel::markAllRead) { Text("อ่านทั้งหมด") }
        }
        if (offline) {
            Text(
                "เชื่อมต่อไม่ได้ กำลังแสดงรายการที่เคยโหลดไว้",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        val list = items
        if (list == null) {
            com.example.campusmarkettek.ui.components.LoadingView() // Room ยังไม่ตอบ (ครั้งแรกที่เปิด)
        } else if (list.isEmpty()) {
            EmptyView(Icons.Outlined.NotificationsNone, "ยังไม่มีการแจ้งเตือน", "ข้อเสนอซื้อและความเคลื่อนไหวของดีลจะแสดงที่นี่")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { n ->
                    NotificationRow(n, onClick = {
                        viewModel.open(n)
                        n.targetRoute()?.let(onOpenRoute)
                    })
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: NotificationEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (n.isRead) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(iconFor(n.type), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(n.body, fontWeight = if (n.isRead) FontWeight.Normal else FontWeight.SemiBold)
                Text(relativeThai(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!n.isRead) Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
        }
    }
}
