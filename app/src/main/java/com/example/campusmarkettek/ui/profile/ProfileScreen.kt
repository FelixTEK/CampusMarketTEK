package com.example.campusmarkettek.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.FacultyDto
import com.example.campusmarkettek.data.remote.MeDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.AccountRepository
import com.example.campusmarkettek.domain.Validator
import com.example.campusmarkettek.ui.components.AppTextField
import com.example.campusmarkettek.ui.components.ScreenTitle
import com.example.campusmarkettek.ui.health.ConnectionStatusCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(val faculties: List<FacultyDto> = emptyList(), val saving: Boolean = false, val error: String? = null)

@HiltViewModel
class ProfileViewModel @Inject constructor(private val accounts: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _state.update { it.copy(faculties = accounts.faculties()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // เลือกคณะไม่ได้ชั่วคราว — ยังแก้ชื่อได้
            }
        }
    }

    fun save(displayName: String, facultyId: Int?, onSaved: () -> Unit) {
        Validator.displayName(displayName)?.let { msg -> return _state.update { it.copy(error = msg) } }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                accounts.updateProfile(displayName.trim(), facultyId)
                _state.update { it.copy(saving = false) }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.toUserMessage()) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}

/** แท็บฉัน: โปรไฟล์ (ตาราง users) · แก้ชื่อ/คณะ · เมนูผู้ดูแล · ออกจากระบบ */
@Composable
fun ProfileScreen(
    me: MeDto,
    onMeChanged: () -> Unit,
    onSignOut: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenMyListings: () -> Unit,
    onOpenDeals: () -> Unit,
    onOpenAdminReports: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAdminStats: () -> Unit,
    onOpenBanners: () -> Unit,
    onOpenAdminUsers: () -> Unit,
    onOpenPrivacy: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("ฉัน")

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(me.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    me.email ?: "บัญชีสถาบัน (KMITL SSO)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val faculty = s.faculties.firstOrNull { it.id == me.facultyId }?.nameTh
                Text(faculty ?: "ยังไม่ได้เลือกคณะ", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(onClick = {}, label = { Text(if (me.isKmitl) "สมาชิก สจล." else "ยืนยันตัวตนแล้ว") })
                    if (me.isAdmin) SuggestionChip(onClick = {}, label = { Text("ผู้ดูแล") })
                }
                TextButton(onClick = { editing = true }) { Text("แก้ไขโปรไฟล์") }
            }
        }

        OutlinedButton(
            onClick = onOpenMyListings,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        ) { Text("ประกาศของฉัน") }

        OutlinedButton(
            onClick = onOpenDeals,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        ) { Text("ดีลของฉัน") }

        OutlinedButton(
            onClick = onOpenStats,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        ) { Text("สถิติของฉัน") }

        OutlinedButton(
            onClick = onOpenPrivacy,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        ) { Text("ความเป็นส่วนตัวและข้อมูลของฉัน") }

        if (me.isAdmin) {
            OutlinedButton(
                onClick = onOpenAdmin,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) { Text("ตรวจยืนยันตัวตน (ผู้ดูแล)") }
            OutlinedButton(
                onClick = onOpenAdminReports,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) { Text("รายงาน (ผู้ดูแล)") }
            OutlinedButton(
                onClick = onOpenAdminStats,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) { Text("สถิติแพลตฟอร์ม (ผู้ดูแล)") }
            OutlinedButton(
                onClick = onOpenBanners,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) { Text("แบนเนอร์โฆษณา (ผู้ดูแล)") }
            OutlinedButton(
                onClick = onOpenAdminUsers,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) { Text("ผู้ใช้และสิทธิ์ (ผู้ดูแล)") }
        }

        // การ์ดสถานะเซิร์ฟเวอร์เป็นเครื่องมือของนักพัฒนา: ผู้ใช้จริงไม่ต้องเห็น
        if (com.example.campusmarkettek.BuildConfig.DEBUG) ConnectionStatusCard(modifier = Modifier.padding(16.dp))
        OutlinedButton(
            onClick = { confirmSignOut = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("ออกจากระบบ") }
    }

    if (confirmSignOut) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("ออกจากระบบ?") },
            text = { Text("แบบร่างประกาศที่ยังไม่ส่งจะยังอยู่ในเครื่อง") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("ออกจากระบบ") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("ยกเลิก") } },
        )
    }

    if (editing) {
        EditProfileDialog(
            me = me,
            state = s,
            onDismiss = { editing = false; viewModel.clearError() },
            onSave = { name, faculty -> viewModel.save(name, faculty) { editing = false; onMeChanged() } },
        )
    }
}

@Composable
private fun EditProfileDialog(
    me: MeDto,
    state: ProfileUiState,
    onDismiss: () -> Unit,
    onSave: (String, Int?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(me.displayName) }
    var facultyId by rememberSaveable { mutableStateOf(me.facultyId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("แก้ไขโปรไฟล์") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(name, { name = it }, "ชื่อที่แสดง", supportingText = "ไม่ต้องใช้ชื่อจริง")
                Text("คณะ (ใช้แสดงหนังสือสำหรับคณะของคุณ)", style = MaterialTheme.typography.labelMedium)
                state.faculties.forEach { f ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { facultyId = f.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = facultyId == f.id, onClick = { facultyId = f.id })
                        Text(f.nameTh)
                    }
                }
                if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = { onSave(name, facultyId) }, enabled = !state.saving) { Text("บันทึก") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } },
    )
}
