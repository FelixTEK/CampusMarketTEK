package com.example.campusmarkettek.ui.admin

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.AdminReportDto
import com.example.campusmarkettek.data.remote.AdminUserDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.ListingRepository
import com.example.campusmarkettek.data.repository.ModerationRepository
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.common.relativeThai
import com.example.campusmarkettek.ui.components.EmptyView
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.listing.ReasonDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun typeLabel(t: String) = when (t) { "LISTING" -> "ประกาศ"; "USER" -> "ผู้ใช้"; else -> "ข้อความ" }

private fun com.google.gson.JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString

// ── คิวรายงาน ───────────────────────────────────────────────────────────

data class AdminReportsUi(val status: String = "OPEN", val content: UiState<List<AdminReportDto>> = UiState.Loading)

@HiltViewModel
class AdminReportsViewModel @Inject constructor(private val repo: ModerationRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminReportsUi())
    val state: StateFlow<AdminReportsUi> = _state.asStateFlow()

    fun load(status: String = _state.value.status) {
        _state.value = AdminReportsUi(status, UiState.Loading)
        viewModelScope.launch {
            try {
                val list = repo.reports(status)
                _state.value = AdminReportsUi(status, if (list.isEmpty()) UiState.Empty else UiState.Success(list))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AdminReportsUi(status, UiState.Error(e.toUserMessage()))
            }
        }
    }
}

/** หน้า 11 ผู้ดูแล: คิวรายงาน (เปิดอยู่เก่าสุดก่อน · ปิดแล้วใหม่สุดก่อน) */
@Composable
fun AdminReportsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: AdminReportsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("รายงาน", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ui.status == "OPEN", onClick = { viewModel.load("OPEN") }, label = { Text("รอตรวจ") })
            FilterChip(selected = ui.status == "CLOSED", onClick = { viewModel.load("CLOSED") }, label = { Text("ปิดแล้ว") })
        }
        when (val c = ui.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = { viewModel.load() })
            UiState.Empty -> EmptyView(Icons.Outlined.Flag, "ไม่มีรายงาน", if (ui.status == "OPEN") "รายงานใหม่จะแสดงที่นี่" else "ยังไม่มีรายงานที่ปิดแล้ว")
            is UiState.Success -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(c.data, key = { it.id }) { r ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(r.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${typeLabel(r.targetType)} · ${r.reason}", fontWeight = FontWeight.SemiBold)
                            Text(
                                r.snapshot.str("title") ?: r.snapshot.str("displayName") ?: r.snapshot.str("listingTitle").orEmpty(),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            )
                            Text(
                                "โดย ${r.reporter.displayName} · ${relativeThai(r.createdAt)}" + if (r.otherReports > 0) " · มีรายงานอื่นอีก ${r.otherReports}" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── ตรวจรายงานหนึ่งเรื่อง ───────────────────────────────────────────────

data class AdminReportDetailUi(
    val content: UiState<AdminReportDto> = UiState.Loading,
    val target: AdminUserDto? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val closed: Boolean = false,
)

@HiltViewModel
class AdminReportDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ModerationRepository,
    private val listings: ListingRepository,
) : ViewModel() {
    private val id: String = checkNotNull(savedState["id"])
    private val _state = MutableStateFlow(AdminReportDetailUi())
    val state: StateFlow<AdminReportDetailUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                val r = repo.reportById(id)
                val target = r.snapshot.str("userId")?.let { runCatching { repo.adminUser(it) }.getOrNull() }
                _state.update { it.copy(content = UiState.Success(r), target = target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.content is UiState.Success) it.copy(notice = e.toUserMessage()) else it.copy(content = UiState.Error(e.toUserMessage())) }
            }
        }
    }

    private fun act(done: Boolean = false, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = false, closed = done) }
                if (!done) load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, notice = e.toUserMessage()) } // เช่น 403 ตัดสินเรื่องของตัวเอง · 409 ปิดไปแล้ว
            }
        }
    }

    fun close(note: String) = act(done = true) { repo.closeReport(id, note) }
    fun ban(userId: String, reason: String) = act { repo.ban(userId, reason) }
    fun unban(userId: String) = act { repo.unban(userId) }
    fun returnListing(listingId: String, reason: String) = act { listings.adminReturn(listingId, reason) }
    fun takedown(listingId: String, reason: String) = act { listings.adminTakedown(listingId, reason) }
}

private enum class Ask { Close, Ban, Return, Takedown }

@Composable
fun AdminReportDetailScreen(
    onBack: () -> Unit,
    onOpenListing: (String) -> Unit,
    viewModel: AdminReportDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var ask by rememberSaveable { mutableStateOf<Ask?>(null) }
    androidx.compose.runtime.LaunchedEffect(ui.closed) { if (ui.closed) onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("ตรวจรายงาน", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        when (val c = ui.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = viewModel::load)
            UiState.Empty -> Unit
            is UiState.Success -> {
                val r = c.data
                val userId = r.snapshot.str("userId")
                val listingId = r.snapshot.str("listingId")
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${typeLabel(r.targetType)} · ${r.reason}", fontWeight = FontWeight.Bold)
                            if (!r.detail.isNullOrBlank()) Text(r.detail)
                            Text("ผู้รายงาน: ${r.reporter.displayName} · ${relativeThai(r.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r.otherReports > 0) Text("มีรายงานอื่นต่อเป้าหมายเดียวกันอีก ${r.otherReports} เรื่อง", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if (r.status == "CLOSED") Text("ปิดแล้ว: ${r.adminNote}", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // สำเนาหลักฐาน ณ เวลารายงาน
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("สำเนา ณ เวลารายงาน", style = MaterialTheme.typography.labelLarge)
                            when (r.targetType) {
                                "LISTING" -> {
                                    Text(r.snapshot.str("title").orEmpty(), fontWeight = FontWeight.SemiBold)
                                    Text("฿${r.snapshot.str("price")} · ${r.snapshot.str("status")} · ผู้ขาย ${r.snapshot.str("sellerName")}", style = MaterialTheme.typography.bodySmall)
                                    Text(r.snapshot.str("description").orEmpty())
                                }
                                "USER" -> Text("${r.snapshot.str("displayName")} ${r.snapshot.str("faculty").orEmpty()}")
                                else -> {
                                    Text("ในแชตประกาศ: ${r.snapshot.str("listingTitle")}", style = MaterialTheme.typography.bodySmall)
                                    val reported = r.snapshot.get("messageId").asString
                                    r.snapshot.getAsJsonArray("messages").forEach { m ->
                                        val o = m.asJsonObject
                                        val mine = o.str("id") == reported
                                        Text(
                                            (if (mine) "▶ " else "") + (when (o.str("senderId")) { null -> "ระบบ: "; userId -> "ผู้ถูกรายงาน: "; else -> "อีกฝ่าย: " }) + o.str("body"),
                                            fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                                            color = if (mine) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (r.repeatPairs.isNotEmpty()) {
                        Text(
                            "เบาะแสปั่นตัวเลข: " + r.repeatPairs.joinToString(", ") { "${it.buyerName} ซื้อกับผู้ขายนี้ ${it.deals} ครั้ง" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }

                    ui.target?.let {
                        Text("ผู้ถูกรายงาน: ${it.displayName}" + if (it.isBanned) " (ถูกระงับอยู่)" else "", fontWeight = FontWeight.SemiBold)
                    }
                    if (ui.notice != null) Text(ui.notice!!, color = MaterialTheme.colorScheme.error)

                    if (r.status == "OPEN") {
                        if (listingId != null) OutlinedButton(onClick = { onOpenListing(listingId) }, modifier = Modifier.fillMaxWidth()) { Text("ดูประกาศ") }
                        if (r.targetType == "LISTING" && listingId != null) {
                            OutlinedButton(onClick = { ask = Ask.Return }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("ตีกลับประกาศ (ผู้ขายแก้ได้)") }
                            OutlinedButton(onClick = { ask = Ask.Takedown }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("ถอดประกาศ") }
                        }
                        val target = ui.target
                        if (userId != null && target != null && !target.isAdmin) {
                            if (target.isBanned) OutlinedButton(onClick = { viewModel.unban(userId) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("ปลดระงับผู้ใช้") }
                            else OutlinedButton(onClick = { ask = Ask.Ban }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("ระงับผู้ใช้") }
                        }
                        Button(onClick = { ask = Ask.Close }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("ปิดรายงาน") }
                    }
                }

                when (ask) {
                    Ask.Close -> ReasonDialog("ปิดรายงาน", "หมายเหตุ", listOf("ตรวจแล้ว ไม่พบความผิด", "ดำเนินการแล้ว"), "ปิดรายงาน", { viewModel.close(it); ask = null }, { ask = null })
                    Ask.Ban -> ReasonDialog("ระงับผู้ใช้", "เหตุผล", listOf("หลอกลวง", "คุกคามผู้อื่น", "ฝ่าฝืนกติกาซ้ำ"), "ระงับ", { viewModel.ban(userId!!, it); ask = null }, { ask = null })
                    Ask.Return -> ReasonDialog("ตีกลับประกาศ", "เหตุผล", listOf("ข้อมูลไม่ตรงความจริง", "รูปไม่เหมาะสม"), "ตีกลับ", { viewModel.returnListing(listingId!!, it); ask = null }, { ask = null })
                    Ask.Takedown -> ReasonDialog("ถอดประกาศ", "เหตุผล", listOf("สินค้าผิดกฎหมาย", "หลอกลวง"), "ถอด", { viewModel.takedown(listingId!!, it); ask = null }, { ask = null })
                    null -> Unit
                }
            }
        }
    }
}
