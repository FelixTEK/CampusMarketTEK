package com.example.campusmarkettek.ui.admin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.PendingVerificationDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.AdminVerificationRepository
import com.example.campusmarkettek.ui.common.UiState
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

/** คิวคำขอที่รอตรวจ (เก่าสุดก่อน) */
@HiltViewModel
class AdminQueueViewModel @Inject constructor(private val repo: AdminVerificationRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<PendingVerificationDto>>>(UiState.Loading)
    val state: StateFlow<UiState<List<PendingVerificationDto>>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                repo.queue().let { if (it.isEmpty()) UiState.Empty else UiState.Success(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error(e.toUserMessage())
            }
        }
    }
}

data class ReviewUiState(
    val loading: Boolean = true,
    val card: Bitmap? = null,
    val selfie: Bitmap? = null,
    val nationalId: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

/** ตรวจคำขอ 1 รายการ: ดูรูป → กรอกเลขบัตร → อนุมัติ หรือปฏิเสธพร้อมเหตุผล */
@HiltViewModel
class AdminReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: AdminVerificationRepository,
) : ViewModel() {

    private val requestId: String = checkNotNull(savedState["id"])

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // รูปถูกถอดรหัสที่ server แล้วส่งมาเฉพาะผู้ดูแล — ถือไว้ในหน่วยความจำ ไม่บันทึกลงเครื่อง
                val card = decode(repo.image(requestId, "card"))
                val selfie = decode(repo.image(requestId, "selfie"))
                _state.update { it.copy(loading = false, card = card, selfie = selfie) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    private suspend fun decode(bytes: ByteArray): Bitmap? =
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }

    fun onNationalId(v: String) = _state.update { it.copy(nationalId = v.filter(Char::isDigit).take(13), error = null) }

    fun approve(onDone: () -> Unit) {
        val id = _state.value.nationalId
        if (id.length != 13) return _state.update { it.copy(error = "กรอกเลขบัตรประชาชน 13 หลักที่อ่านจากรูป") }
        act(onDone) { repo.approve(requestId, id) }
    }

    fun reject(reason: String, onDone: () -> Unit) {
        if (reason.isBlank()) return _state.update { it.copy(error = "ต้องระบุเหตุผล") }
        act(onDone) { repo.reject(requestId, reason.trim()) }
    }

    private fun act(onDone: () -> Unit, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.toUserMessage()) }
            }
        }
    }
}
