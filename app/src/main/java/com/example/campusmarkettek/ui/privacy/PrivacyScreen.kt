package com.example.campusmarkettek.ui.privacy

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.DeleteMeRequest
import com.example.campusmarkettek.data.remote.PrivacyApi
import com.example.campusmarkettek.data.remote.toUserMessage
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

data class PrivacyUi(val busy: Boolean = false, val message: String? = null, val error: String? = null, val deleted: Boolean = false)

@HiltViewModel
class PrivacyViewModel @Inject constructor(private val api: PrivacyApi) : ViewModel() {
    private val _state = MutableStateFlow(PrivacyUi())
    val state: StateFlow<PrivacyUi> = _state.asStateFlow()

    /** ดึงข้อมูลทั้งหมดแล้วเขียนลงไฟล์ที่ผู้ใช้เลือก (Storage Access Framework) */
    fun export(context: Context, target: Uri) {
        if (_state.value.busy) return
        _state.value = PrivacyUi(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    api.export().byteStream().use { input ->
                        context.contentResolver.openOutputStream(target)?.use { out -> input.copyTo(out) } ?: error("เขียนไฟล์ไม่ได้")
                    }
                }
                _state.value = PrivacyUi(message = "บันทึกไฟล์ข้อมูลของคุณแล้ว")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PrivacyUi(error = e.toUserMessage())
            }
        }
    }

    fun deleteAccount() {
        if (_state.value.busy) return
        _state.value = PrivacyUi(busy = true)
        viewModelScope.launch {
            try {
                api.deleteMe(DeleteMeRequest(confirm = true))
                _state.value = PrivacyUi(deleted = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PrivacyUi(error = e.toUserMessage()) // เช่น "ลบบัญชีไม่ได้ เพราะยังมีดีลที่ไม่จบ 1 รายการ"
            }
        }
    }
}

/** หน้า 17 ศูนย์ความเป็นส่วนตัว: ประกาศ + ดาวน์โหลดข้อมูลของฉัน + ลบบัญชี (ยืนยัน 2 ชั้น) */
@Composable
fun PrivacyScreen(onBack: () -> Unit, onAccountDeleted: () -> Unit, viewModel: PrivacyViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirming by rememberSaveable { mutableStateOf(false) }
    var understood by rememberSaveable { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(context, uri)
    }
    androidx.compose.runtime.LaunchedEffect(ui.deleted) { if (ui.deleted) onAccountDeleted() }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("ความเป็นส่วนตัว", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp)) { PrivacyNoticeContent() }
            }

            if (ui.message != null) Text(ui.message!!, color = MaterialTheme.colorScheme.primary)
            if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error)

            OutlinedButton(onClick = { saver.launch("campus-market-my-data.json") }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.busy) "กำลังทำงาน…" else "ดาวน์โหลดข้อมูลของฉัน (JSON)")
            }
            Button(
                onClick = { understood = false; confirming = true }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("ลบบัญชีของฉัน") }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("ลบบัญชีถาวร?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ล้างข้อมูลระบุตัวตนของคุณ ยกเลิกประกาศที่ยังไม่ขาย และออกจากระบบทันที ย้อนกลับไม่ได้ (ถ้ายังมีดีลที่ไม่จบ ระบบจะไม่ให้ลบ)")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text("ฉันเข้าใจและต้องการลบบัญชี")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; viewModel.deleteAccount() }, enabled = understood) {
                    Text("ลบบัญชี", color = if (understood) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("ยกเลิก") } },
        )
    }
}
