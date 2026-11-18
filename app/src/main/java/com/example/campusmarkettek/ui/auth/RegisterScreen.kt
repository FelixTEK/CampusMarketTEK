package com.example.campusmarkettek.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.AccountRepository
import com.example.campusmarkettek.domain.Validator
import com.example.campusmarkettek.ui.components.AppTextField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterUiState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RegisterViewModel @Inject constructor(private val accounts: AccountRepository) : ViewModel() {

    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onName(v: String) = _state.update { it.copy(displayName = v, error = null) }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        // ตรวจฝั่งแอปด้วยกติกาเดียวกับฐานข้อมูล (Validator) แล้ว server ตรวจซ้ำอีกชั้น
        val problem = Validator.email(s.email)
            ?: if (s.password.length !in 8..128) "รหัสผ่านต้องยาว 8–128 ตัวอักษร" else null
            ?: Validator.displayName(s.displayName)
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // สำเร็จ → ล็อกอินให้ทันที (ยังไม่ยืนยันตัวตน) แอปพาไปหน้าส่งบัตรเอง
                accounts.register(s.email.trim(), s.password, s.displayName.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }
}

/** สมัครบุคคลภายนอก: อีเมล + รหัสผ่าน + ชื่อที่แสดง แล้วไปยืนยันตัวตนต่อ */
@Composable
fun RegisterScreen(onBack: () -> Unit, viewModel: RegisterViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
                Text("สมัครสมาชิกบุคคลภายนอก", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                "สร้างบัญชีก่อน แล้วยืนยันตัวตนด้วยบัตรประชาชน (ผู้ดูแลตรวจ 1–2 วันทำการ) ระหว่างรอจะเข้าใช้งานแอปไม่ได้",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTextField(s.email, viewModel::onEmail, "อีเมล (ใช้เข้าสู่ระบบ)", keyboardType = KeyboardType.Email)
            AppTextField(
                s.password, viewModel::onPassword, "รหัสผ่าน", password = true,
                supportingText = "อย่างน้อย 8 ตัวอักษร (เก็บแบบเข้ารหัส ไม่มีใครเห็นรหัสจริง)",
            )
            AppTextField(
                s.displayName, viewModel::onName, "ชื่อที่แสดง",
                supportingText = "ชื่อเล่นก็ได้ ไม่ต้องใช้ชื่อจริง (1–50 ตัวอักษร)",
            )
            if (s.error != null) Text(s.error!!, color = MaterialTheme.colorScheme.error)

            Button(
                onClick = viewModel::submit,
                enabled = !s.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (s.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("สร้างบัญชี", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
