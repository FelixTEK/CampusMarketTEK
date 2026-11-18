package com.example.campusmarkettek.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.auth.AuthEvents
import com.example.campusmarkettek.data.auth.SsoResult
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.AccountRepository
import com.example.campusmarkettek.domain.Validator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** ช่องทางที่ server เปิดอยู่ (null = ยังไม่รู้ ยังโหลดไม่เสร็จ) */
    val ssoAvailable: Boolean? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val accounts: AccountRepository,
    authEvents: AuthEvents,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        loadConfig()
        // ผลจาก deep link ของ SSO: ได้รหัสครั้งเดียว → แลกเป็น token · หรือได้เหตุผลที่ไม่ผ่าน
        viewModelScope.launch {
            authEvents.results.collect { result ->
                when (result) {
                    is SsoResult.Code -> exchange(result.code)
                    is SsoResult.Failed -> _state.update { it.copy(loading = false, error = ssoMessage(result.reason)) }
                }
            }
        }
    }

    fun loadConfig() {
        viewModelScope.launch {
            val available = try {
                accounts.config().kmitlSso
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _state.update { it.copy(ssoAvailable = available) }
        }
    }

    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun login() {
        val s = _state.value
        if (s.loading) return
        Validator.email(s.email)?.let { return fail(it) }
        if (s.password.isEmpty()) return fail("กรุณากรอกรหัสผ่าน")

        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                accounts.login(s.email.trim(), s.password) // สำเร็จ → SessionManager เปลี่ยนสถานะ แอปสลับหน้าเอง
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    private fun exchange(code: String) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                accounts.exchangeSsoCode(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    private fun ssoMessage(reason: String) = when (reason) {
        "not_active_student" ->
            "สถานภาพนักศึกษาของบัญชีนี้ไม่เข้าเงื่อนไข (เช่น จบการศึกษา พ้นสภาพ ลาออก) หากเป็นบุคคลภายนอกให้สมัครด้วยบัตรประชาชน"
        "banned" -> "บัญชีของคุณถูกระงับ — ${com.example.campusmarkettek.ui.APPEAL_HELP}"
        else -> "เข้าสู่ระบบด้วยบัญชี สจล. ไม่สำเร็จ กรุณาลองใหม่"
    }
}
