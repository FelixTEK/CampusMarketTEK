package com.example.campusmarkettek.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.REPORT_REASONS
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.ModerationRepository
import com.example.campusmarkettek.ui.components.AppTextField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReportUi(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)

@HiltViewModel
class ReportViewModel @Inject constructor(private val repo: ModerationRepository) : ViewModel() {
    private val _state = MutableStateFlow(ReportUi())
    val state: StateFlow<ReportUi> = _state.asStateFlow()

    /** เปิดกล่องใหม่ต้องเริ่มสะอาด (ViewModel ถูกเก็บไว้ตามหน้าจอ ไม่ใช่ตามกล่อง) */
    fun reset() { _state.value = ReportUi() }

    fun submit(type: String, id: String, reason: String, detail: String) {
        if (_state.value.busy) return
        _state.update { ReportUi(busy = true) }
        viewModelScope.launch {
            try {
                repo.report(type, id, reason, detail)
                _state.value = ReportUi(done = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 409 "คุณรายงานเรื่องนี้ไปแล้ว" ฯลฯ — ข้อความจาก server ตรงอยู่แล้ว
                _state.value = ReportUi(error = e.toUserMessage())
            }
        }
    }
}

/**
 * หน้า 09 รายงาน: เลือกเหตุผล (บังคับ) + รายละเอียดไม่เกิน 300 ตัว · targetType = LISTING | USER | MESSAGE
 * ส่งแล้วแจ้งผ่าน onSent แล้วปิดเอง · ผู้ดูแลเห็นสำเนาของเป้าหมาย ณ เวลานี้ (กันผู้ถูกรายงานลบ/แก้ทีหลัง)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportDialog(
    targetType: String,
    targetId: String,
    label: String,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
    viewModel: ReportViewModel = hiltViewModel(key = "report:$targetType:$targetId"),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(ui.done) { if (ui.done) { viewModel.reset(); onSent() } }

    AlertDialog(
        onDismissRequest = { if (!ui.busy) { viewModel.reset(); onDismiss() } },
        title = { Text("รายงาน$label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("เลือกเหตุผล", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REPORT_REASONS.forEach { r -> FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(r) }) }
                }
                AppTextField(detail, { detail = it.take(300) }, "รายละเอียดเพิ่มเติม (ไม่บังคับ)", singleLine = false, supportingText = "${detail.length} / 300")
                if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.submit(targetType, targetId, reason!!, detail) }, enabled = reason != null && !ui.busy) {
                Text(if (ui.busy) "กำลังส่ง…" else "ส่งรายงาน")
            }
        },
        dismissButton = { TextButton(onClick = { viewModel.reset(); onDismiss() }, enabled = !ui.busy) { Text("ยกเลิก") } },
    )
}
