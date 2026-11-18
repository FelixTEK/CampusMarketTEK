package com.example.campusmarkettek.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.auth.SessionManager
import com.example.campusmarkettek.data.remote.SellerProfileDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.ModerationRepository
import com.example.campusmarkettek.ui.chat.Stars
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.common.relativeThai
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.report.ReportDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SellerProfileViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ModerationRepository,
    session: SessionManager,
) : ViewModel() {
    val id: String = checkNotNull(savedState["id"])
    val isSelf: Boolean = session.userId() == id

    private val _state = MutableStateFlow<UiState<SellerProfileDto>>(UiState.Loading)
    val state: StateFlow<UiState<SellerProfileDto>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(repo.profile(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.toUserMessage())
            }
        }
    }
}

/** หน้า 08 โปรไฟล์ผู้ขาย: ตัวเลขความน่าเชื่อถือ (คำนวณจากดีลจริง) + รีวิวล่าสุด · รายงานผู้ใช้ได้ (ไม่ใช่ตัวเอง) */
@Composable
fun SellerProfileScreen(onBack: () -> Unit, viewModel: SellerProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var reporting by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var sent by remember { mutableStateOf(false) }
    LaunchedEffect(sent) { if (sent) { snackbar.showSnackbar("ส่งรายงานแล้ว ผู้ดูแลจะตรวจสอบ"); sent = false } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
                Box(Modifier.weight(1f))
                if (!viewModel.isSelf && state is UiState.Success) {
                    IconButton(onClick = { reporting = true }) { Icon(Icons.Outlined.Flag, contentDescription = "รายงานผู้ใช้") }
                }
            }
            when (val s = state) {
                UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, onRetry = viewModel::load)
                UiState.Empty -> Unit
                is UiState.Success -> ProfileBody(s.data)
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    if (reporting) {
        ReportDialog("USER", viewModel.id, "ผู้ใช้", onDismiss = { reporting = false }, onSent = { reporting = false; sent = true })
    }
}

@Composable
private fun ProfileBody(p: SellerProfileDto) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(p.displayName.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column {
                Text(p.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(p.faculty, if (p.isKmitl) "สมาชิก สจล." else "ยืนยันตัวตนแล้ว").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("ขายสำเร็จ", p.sold.toString())
                Stat("อัตราปิดการขาย", p.closeRate?.let { "$it%" } ?: "ผู้ขายใหม่")
                Stat("กำลังขาย", p.activeListings.toString())
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.rating != null) {
                Stars(Math.round(p.rating.average).toInt())
                Text("${p.rating.average} (${p.rating.count} รีวิว)", fontWeight = FontWeight.SemiBold)
            } else {
                Text("ยังไม่มีรีวิว", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("คะแนนมาจากผู้ซื้อของดีลที่สำเร็จจริงเท่านั้น", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        p.reviews.forEach { r ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stars(r.rating)
                        Text(relativeThai(r.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                    }
                    if (!r.comment.isNullOrBlank()) Text(r.comment)
                    Text("${r.buyerName} · ${r.listingTitle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box(Modifier.size(16.dp))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
