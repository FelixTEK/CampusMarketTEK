package com.example.campusmarkettek.ui.admin

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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.AdminUserListDto
import com.example.campusmarkettek.data.remote.AdminUserRowDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.ModerationRepository
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.components.AppTextField
import com.example.campusmarkettek.ui.components.EmptyView
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.listing.ReasonDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdminUsersUi(
    val content: UiState<AdminUserListDto> = UiState.Loading,
    val notice: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class AdminUsersViewModel @Inject constructor(private val repo: ModerationRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminUsersUi())
    val state: StateFlow<AdminUsersUi> = _state.asStateFlow()
    private var job: Job? = null
    private var q = ""
    private var filter = "all"

    /** ค้นใหม่ (ยกเลิกคำค้นเก่าที่ยังค้างอยู่) · พิมพ์ติดกันรอ 300 ms ก่อนยิง */
    fun search(query: String, filter: String, debounce: Boolean = true) {
        q = query; this.filter = filter
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(300)
            try {
                _state.update { it.copy(content = UiState.Success(repo.users(q, this@AdminUsersViewModel.filter))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.content is UiState.Success) it.copy(notice = e.toUserMessage()) else it.copy(content = UiState.Error(e.toUserMessage())) }
            }
        }
    }

    fun reload() = search(q, filter, debounce = false)

    private fun act(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { block(); _state.update { it.copy(busy = false) }; reload() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(busy = false, notice = e.toUserMessage()) } }
        }
    }

    fun promote(id: String) = act { repo.promote(id) }
    fun demote(id: String) = act { repo.demote(id) }
    fun ban(id: String, reason: String) = act { repo.ban(id, reason) }
    fun unban(id: String) = act { repo.unban(id) }
}

private enum class UserAction { Promote, Demote, Ban, Unban }

private val FILTERS = listOf("all" to "ทั้งหมด", "banned" to "ถูกระงับ", "admin" to "ผู้ดูแล")

/** ผู้ดูแล: ค้นหาผู้ใช้ ดูคนที่ถูกระงับ ตั้ง/ถอนผู้ดูแล (เพิ่มสตาฟ) ระงับ/ปลดระงับ — ทุกกฎตัดสินที่ server */
@Composable
fun AdminUsersScreen(meId: String, onBack: () -> Unit, viewModel: AdminUsersViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var pending by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) } // (userId, action name)
    LaunchedEffect(query, filter) { viewModel.search(query, filter) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("ผู้ใช้และสิทธิ์", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        AppTextField(query, { query = it.take(100) }, "ค้นหาชื่อที่แสดงหรืออีเมล", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FILTERS.forEach { (key, label) -> FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) }) }
        }
        if (ui.notice != null) Text(ui.notice!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))

        when (val c = ui.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = viewModel::reload)
            UiState.Empty -> Unit
            is UiState.Success -> {
                if (c.data.items.isEmpty()) {
                    EmptyView(Icons.Outlined.Person, "ไม่พบผู้ใช้", "ลองเปลี่ยนคำค้นหรือตัวกรอง")
                } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(c.data.items, key = { it.id }) { u ->
                        UserRow(u, isMe = u.id == meId, busy = ui.busy) { action -> pending = u.id to action.name }
                    }
                    if (c.data.hasMore) item {
                        Text("แสดง 50 รายการแรก — พิมพ์ค้นหาเพื่อกรองให้แคบลง", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    val target = pending?.let { (id, a) -> (ui.content as? UiState.Success)?.data?.items?.firstOrNull { it.id == id }?.let { it to UserAction.valueOf(a) } }
    if (pending != null && target == null) pending = null
    target?.let { (u, action) ->
        val close = { pending = null }
        when (action) {
            UserAction.Ban -> ReasonDialog("ระงับผู้ใช้", "เหตุผล", listOf("หลอกลวง", "คุกคามผู้อื่น", "ฝ่าฝืนกติกาซ้ำ"), "ระงับ", { viewModel.ban(u.id, it); close() }, close)
            else -> AlertDialog(
                onDismissRequest = close,
                title = { Text(when (action) { UserAction.Promote -> "ตั้งเป็นผู้ดูแล?"; UserAction.Demote -> "ถอนสิทธิ์ผู้ดูแล?"; else -> "ปลดระงับ?" }) },
                text = {
                    Text(when (action) {
                        UserAction.Promote -> "${u.displayName} จะเห็นและทำเมนูผู้ดูแลได้ทันที (ตรวจตัวตน รายงาน ระงับผู้ใช้ แบนเนอร์ สถิติ)"
                        UserAction.Demote -> "${u.displayName} จะเสียสิทธิ์ผู้ดูแลทันที แต่ยังใช้แอปเป็นสมาชิกทั่วไปได้"
                        else -> "${u.displayName} จะกลับมาใช้งานได้ ดีลที่ถูกยกเลิกไปแล้วไม่กลับมา"
                    })
                },
                confirmButton = {
                    TextButton(onClick = {
                        when (action) {
                            UserAction.Promote -> viewModel.promote(u.id)
                            UserAction.Demote -> viewModel.demote(u.id)
                            else -> viewModel.unban(u.id)
                        }
                        close()
                    }) { Text("ยืนยัน") }
                },
                dismissButton = { TextButton(onClick = close) { Text("ยกเลิก") } },
            )
        }
    }
}

@Composable
private fun UserRow(u: AdminUserRowDto, isMe: Boolean, busy: Boolean, onAction: (UserAction) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(u.displayName + if (isMe) " (คุณ)" else "", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            val tags = buildList {
                add(if (u.isKmitl) "สมาชิก สจล." else "บุคคลภายนอก")
                if (u.isAdmin) add("ผู้ดูแล")
                if (u.isBanned) add("ถูกระงับ")
                if (!u.isVerified) add("รอยืนยันตัวตน")
            }
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = if (u.isBanned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!isMe) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (u.isAdmin) {
                    OutlinedButton(onClick = { onAction(UserAction.Demote) }, enabled = !busy) { Text("ถอนสิทธิ์ผู้ดูแล") }
                } else {
                    if (u.isVerified && !u.isBanned) OutlinedButton(onClick = { onAction(UserAction.Promote) }, enabled = !busy) { Text("ตั้งเป็นผู้ดูแล") }
                    if (u.isBanned) OutlinedButton(onClick = { onAction(UserAction.Unban) }, enabled = !busy) { Text("ปลดระงับ") }
                    else OutlinedButton(onClick = { onAction(UserAction.Ban) }, enabled = !busy) { Text("ระงับ") }
                }
            }
        }
    }
}
