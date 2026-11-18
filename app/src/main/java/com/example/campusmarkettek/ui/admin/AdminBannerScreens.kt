package com.example.campusmarkettek.ui.admin

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.example.campusmarkettek.data.remote.AdminBannerDto
import com.example.campusmarkettek.data.remote.AdminBannerListDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.BannerRepository
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.common.imageUrl
import com.example.campusmarkettek.ui.components.AppTextField
import com.example.campusmarkettek.ui.components.DraftBadge
import com.example.campusmarkettek.ui.components.EmptyView
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.verification.ImageCompressor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private fun statusLabel(s: String) = when (s) { "LIVE" -> "กำลังแสดง"; "SCHEDULED" -> "รอเริ่ม"; "EXPIRED" -> "หมดอายุ"; else -> "ปิดอยู่" }

data class AdminBannersUi(val content: UiState<AdminBannerListDto> = UiState.Loading, val notice: String? = null, val busy: Boolean = false)

@HiltViewModel
class AdminBannersViewModel @Inject constructor(private val repo: BannerRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminBannersUi())
    val state: StateFlow<AdminBannersUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(content = UiState.Success(repo.adminList())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.content is UiState.Success) it.copy(notice = e.toUserMessage()) else it.copy(content = UiState.Error(e.toUserMessage())) }
            }
        }
    }

    private fun act(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { block(); _state.update { it.copy(busy = false) }; load() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(busy = false, notice = e.toUserMessage()) } }
        }
    }

    fun setActive(id: Int, on: Boolean) = act { repo.setActive(id, on) }
    fun delete(id: Int) = act { repo.delete(id) }
}

/** หน้า 12 ผู้ดูแล: จัดการแบนเนอร์ — เปิด/ปิด ลบ เพิ่มใหม่ · แสดงพร้อมกันได้ไม่เกิน 6 ใบ (BR18) */
@Composable
fun AdminBannersScreen(onBack: () -> Unit, onAdd: () -> Unit, viewModel: AdminBannersViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var deleting by rememberSaveable { mutableStateOf<Int?>(null) }
    LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("แบนเนอร์โฆษณา", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onAdd) { Text("เพิ่ม") }
        }
        when (val c = ui.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = viewModel::load)
            UiState.Empty -> Unit
            is UiState.Success -> {
                Text(
                    "แสดงอยู่ ${c.data.live} / ${c.data.max} ใบ (เกินจะแสดงเฉพาะ ${c.data.max} ใบแรกตามลำดับ)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (ui.notice != null) Text(ui.notice!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                if (c.data.items.isEmpty()) {
                    EmptyView(Icons.Outlined.Image, "ยังไม่มีแบนเนอร์", "กด \"เพิ่ม\" เพื่ออัปโหลดรูป 2:1 หน้าแรกจะซ่อนแถบโฆษณาจนกว่าจะมี")
                } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(c.data.items, key = { it.id }) { b ->
                        BannerRow(b, busy = ui.busy, onToggle = { viewModel.setActive(b.id, it) }, onDelete = { deleting = b.id })
                    }
                }
            }
        }
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("ลบแบนเนอร์?") },
            text = { Text("ลบแล้วกู้คืนไม่ได้ (ถ้าแค่พักไว้ ให้ปิดสวิตช์แทน)") },
            confirmButton = { TextButton(onClick = { viewModel.delete(id); deleting = null }) { Text("ลบ") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("ยกเลิก") } },
        )
    }
}

@Composable
private fun BannerRow(b: AdminBannerDto, busy: Boolean, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncImage(imageUrl(b.imageUrl), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(10.dp)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(b.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                DraftBadge(statusLabel(b.status), isProblem = b.status == "EXPIRED")
            }
            Text(
                "${b.startsAt.take(10)} → ${b.endsAt.take(10)} · ลำดับ ${b.sort}" + (b.linkUrl?.let { "\nลิงก์: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = b.isActive, onCheckedChange = onToggle, enabled = !busy)
                Text(if (b.isActive) "เปิด" else "ปิด", Modifier.padding(start = 8.dp).weight(1f))
                IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.Delete, contentDescription = "ลบ") }
            }
        }
    }
}

// ── เพิ่มแบนเนอร์ ───────────────────────────────────────────────────────

data class BannerFormUi(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)

@HiltViewModel
class BannerFormViewModel @Inject constructor(private val repo: BannerRepository) : ViewModel() {
    private val _state = MutableStateFlow(BannerFormUi())
    val state: StateFlow<BannerFormUi> = _state.asStateFlow()

    fun submit(title: String, link: String, days: Int, sort: Int, source: ByteArray) {
        if (_state.value.busy) return
        _state.value = BannerFormUi(busy = true)
        viewModelScope.launch {
            try {
                // ย่อให้ด้านยาว ≤ 1600 px (ได้ 1600×800 พอสำหรับหน้าจอมือถือ) — สัดส่วนเดิมไม่เปลี่ยน
                val jpeg = withContext(Dispatchers.Default) { ImageCompressor.compress(source) }
                repo.create(title, link, days, sort, jpeg)
                _state.value = BannerFormUi(done = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = BannerFormUi(error = e.toUserMessage()) // เช่น "รูปต้องมีสัดส่วน 2:1 (รูปนี้ 1000×1000)"
            }
        }
    }
}

@Composable
fun AdminBannerFormScreen(onBack: () -> Unit, onAdded: () -> Unit, viewModel: BannerFormViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf("14") }
    var sort by rememberSaveable { mutableStateOf("100") }
    var picked by rememberSaveable { mutableStateOf<Uri?>(null) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(ui.done) { if (ui.done) onAdded() } // เปิดรายการใหม่ (ไม่พึ่งการรีเฟรชตอนกลับมา)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { // ยกเลิกตัวเลือกรูป = คงรูปเดิมไว้
            picked = uri
            localError = null
            // ตรวจสัดส่วนก่อนอัปโหลด (server ตรวจซ้ำเสมอ)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val ratio = bounds.outWidth.toFloat() / bounds.outHeight.coerceAtLeast(1)
            if (ratio < 1.9f || ratio > 2.1f) localError = "รูปต้องมีสัดส่วน 2:1 (รูปนี้ ${bounds.outWidth}×${bounds.outHeight})"
            else if (bounds.outWidth < 600) localError = "รูปต้องกว้างอย่างน้อย 600 px"
        }
    }

    val daysInt = days.toIntOrNull()
    val valid = picked != null && localError == null && title.isNotBlank() && daysInt != null && daysInt in 1..365 && sort.toIntOrNull() != null

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("เพิ่มแบนเนอร์", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (picked == null) "เลือกรูป (สัดส่วน 2:1 กว้าง ≥ 600 px)" else "เปลี่ยนรูป")
            }
            if (picked != null) {
                AsyncImage(picked, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(10.dp)))
            }
            val err = localError ?: ui.error
            if (err != null) Text(err, color = MaterialTheme.colorScheme.error)

            AppTextField(title, { title = it.take(100) }, "ชื่อแบนเนอร์ (ไว้ให้ผู้ดูแลจำ ไม่แสดงผู้ใช้)")
            AppTextField(link, { link = it.take(500) }, "ลิงก์ (ไม่บังคับ) https://… หรือ campusmarket://listing/<id>", keyboardType = KeyboardType.Uri)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(days, { days = it.filter(Char::isDigit).take(3) }, "แสดงกี่วัน (เริ่มทันที)", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                AppTextField(sort, { sort = it.filter(Char::isDigit).take(5) }, "ลำดับ (น้อยก่อน)", Modifier.weight(1f), keyboardType = KeyboardType.Number)
            }
            Button(
                onClick = {
                    val bytes = picked?.let { u -> context.contentResolver.openInputStream(u)?.use { it.readBytes() } } ?: return@Button
                    viewModel.submit(title, link, daysInt!!, sort.toInt(), bytes)
                },
                enabled = valid && !ui.busy, modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ui.busy) "กำลังอัปโหลด…" else "เพิ่มแบนเนอร์") }
        }
    }
}
