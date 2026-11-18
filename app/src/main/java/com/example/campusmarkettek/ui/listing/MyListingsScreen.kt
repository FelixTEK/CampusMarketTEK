package com.example.campusmarkettek.ui.listing

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.example.campusmarkettek.data.auth.SessionManager
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.Draft
import com.example.campusmarkettek.data.repository.ListingRepository
import com.example.campusmarkettek.data.repository.MyListing
import com.example.campusmarkettek.ui.common.formatPrice
import com.example.campusmarkettek.ui.common.imageUrl
import com.example.campusmarkettek.ui.common.relativeThai
import com.example.campusmarkettek.ui.common.statusLabel
import com.example.campusmarkettek.ui.components.DraftBadge
import com.example.campusmarkettek.ui.components.EmptyView
import com.example.campusmarkettek.ui.components.StatusBadge
import com.example.campusmarkettek.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MyListingsUi(
    val listings: List<MyListing> = emptyList(),
    val drafts: List<Draft> = emptyList(),
    val refreshError: String? = null,
)

@HiltViewModel
class MyListingsViewModel @Inject constructor(
    private val repo: ListingRepository,
    private val session: SessionManager,
    private val draftSync: SyncScheduler,
) : ViewModel() {

    private val owner = session.userId().orEmpty()
    private val refreshError = MutableStateFlow<String?>(null)

    /** แสดงจาก Room ทันที (ออฟไลน์ก็เห็น) · แบบร่างอยู่ด้านบน */
    val state: StateFlow<MyListingsUi> = combine(repo.observeMine(owner), repo.observeDrafts(owner), refreshError) { l, d, e ->
        MyListingsUi(l, d, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyListingsUi())

    init {
        // ตัวส่งแบบร่างเบื้องหลังทำให้รายการเปลี่ยน = เน็ตกลับมาแล้ว → เอาข้อความ "เชื่อมต่อไม่ได้" เก่าออก
        viewModelScope.launch {
            repo.observeMine(owner).drop(1).collect { refreshError.value = null }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                repo.refreshMine(owner)
                refreshError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refreshError.value = e.toUserMessage()
            }
        }
    }

    fun deleteDraft(id: String) = viewModelScope.launch { repo.deleteDraft(id) }

    /** ส่งแบบร่างที่ส่งไม่สำเร็จ/ที่บันทึกไว้อีกครั้ง */
    fun sendDraft(id: String) {
        viewModelScope.launch {
            repo.markDraft(id, "PENDING")
            draftSync.enqueue()
        }
    }
}

private val FILTERS = listOf(
    "ทั้งหมด" to null,
    "กำลังขาย" to "AVAILABLE",
    "ถูกจอง" to "RESERVED",
    "ต้องแก้ไข" to "RETURNED",
    "ขายแล้ว" to "SOLD",
    "ยกเลิก" to "CANCELLED",
)

/** หน้า 18 ประกาศของฉัน: แบบร่างที่ยังไม่ส่ง + ประกาศทุกสถานะ กรองตามสถานะได้ */
@Composable
fun MyListingsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenDraft: (String) -> Unit,
    viewModel: MyListingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }

    // กลับมาหน้านี้ทีไรโหลดจาก server ใหม่ (สถานะอาจเปลี่ยนจากผู้ดูแล/ดีล)
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val shown = ui.listings.filter { filter == null || it.status == filter }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("ประกาศของฉัน", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FILTERS) { (label, status) ->
                val count = if (status == null) ui.listings.size else ui.listings.count { it.status == status }
                FilterChip(selected = filter == status, onClick = { filter = status }, label = { Text("$label $count") })
            }
        }
        if (ui.refreshError != null) {
            Text("${ui.refreshError} (แสดงข้อมูลที่เคยโหลดไว้)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        if (ui.drafts.isEmpty() && shown.isEmpty()) {
            EmptyView(
                Icons.Outlined.Sell,
                if (filter == null) "ยังไม่มีประกาศ" else "ไม่มีประกาศในสถานะนี้",
                if (filter == null) "กดแท็บ ลงขาย เพื่อลงประกาศแรกของคุณ" else null,
            )
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filter == null && ui.drafts.isNotEmpty()) {
                item { Text("แบบร่างในเครื่อง (${ui.drafts.size})", style = MaterialTheme.typography.titleSmall) }
                items(ui.drafts, key = { "d-" + it.id }) { d ->
                    DraftRow(d, onEdit = { onOpenDraft(d.id) }, onSend = { viewModel.sendDraft(d.id) }, onDelete = { viewModel.deleteDraft(d.id) })
                }
                item { Text("ประกาศที่ลงแล้ว", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
            }
            items(shown, key = { it.id }) { l -> ListingRow(l, onClick = { onOpen(l.id) }) }
        }
    }
}

@Composable
private fun ListingRow(l: MyListing, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).let { it }) {
                if (l.cover != null) {
                    AsyncImage(model = imageUrl(l.cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(l.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(
                    formatPrice(l.price) + " · อัปเดต " + relativeThai(l.updatedAt),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusBadge(l.status)
                if (l.statusReason != null && l.status in setOf("RETURNED", "TAKEN_DOWN", "CANCELLED")) {
                    Text(
                        (if (l.status == "CANCELLED") "เหตุผล: " else "เหตุผลจากผู้ดูแล: ") + l.statusReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (l.status == "CANCELLED") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftRow(d: Draft, onEdit: () -> Unit, onSend: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.title.ifBlank { "(ยังไม่ตั้งชื่อ)" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                when (d.state) {
                    "PENDING" -> DraftBadge("รอส่ง")
                    "FAILED" -> DraftBadge("ส่งไม่สำเร็จ", isProblem = true)
                    else -> DraftBadge("แบบร่าง")
                }
            }
            Text(
                listOfNotNull(d.price.takeIf { it.isNotBlank() }?.let { "฿$it" }, "${d.imageCount} รูป", relativeThai(d.updatedAt)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                d.state == "PENDING" -> Text("จะส่งอัตโนมัติเมื่อมีอินเทอร์เน็ต", style = MaterialTheme.typography.bodySmall)
                d.state == "FAILED" && d.error != null -> Text(d.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row {
                TextButton(onClick = onEdit) { Text("แก้ไข") }
                if (d.state != "PENDING") TextButton(onClick = onSend) { Text("ส่งตอนนี้") }
                else TextButton(onClick = onSend) { Text("ลองส่งเลย") }
                TextButton(onClick = onDelete) { Text("ลบ") }
            }
        }
    }
}
