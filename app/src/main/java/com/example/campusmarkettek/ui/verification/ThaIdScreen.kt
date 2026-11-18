package com.example.campusmarkettek.ui.verification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThaIdUiState(val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class ThaIdViewModel @Inject constructor(private val accounts: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(ThaIdUiState())
    val state: StateFlow<ThaIdUiState> = _state.asStateFlow()

    fun confirm(onDone: () -> Unit) {
        if (_state.value.loading) return
        _state.update { ThaIdUiState(loading = true) }
        viewModelScope.launch {
            try {
                accounts.thaidMock()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { ThaIdUiState(error = e.toUserMessage()) }
            }
        }
    }
}

/**
 * หน้า 15 ThaID (จำลอง) — mockup เท่านั้น ไม่ใช้โลโก้/ระบบจริงของกรมการปกครอง
 * การเชื่อมจริงต้องทำข้อตกลงกับหน่วยงานเจ้าของระบบ (เฟส 14)
 */
@Composable
fun ThaIdScreen(onBack: () -> Unit, onVerified: () -> Unit, viewModel: ThaIdViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
                Text("ยืนยันด้วย ThaID (จำลอง)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("หน้านี้เป็นตัวอย่างจำลอง", style = MaterialTheme.typography.titleSmall)
                    Text("ในระบบจริง แอป ThaID จะถามคุณก่อนว่ายินยอมให้ส่งข้อมูลอะไรบ้าง")
                    Text("ที่เราขอ: ชื่อ (ใช้เป็นชื่อที่แสดงเริ่มต้น) และวันเกิด (ตรวจว่าอายุ 20 ปีขึ้นไป แล้วไม่เก็บ)")
                    Text("ที่เราไม่ขอ: รูปถ่าย ที่อยู่")
                }
            }
            if (s.error != null) Text(s.error!!, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = { viewModel.confirm(onVerified) },
                enabled = !s.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (s.loading) "กำลังยืนยัน..." else "ยืนยัน (จำลอง)") }
        }
    }
}
