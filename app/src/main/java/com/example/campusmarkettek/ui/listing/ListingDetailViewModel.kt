package com.example.campusmarkettek.ui.listing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.ListingDetailDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.BrowseRepository
import com.example.campusmarkettek.data.repository.ChatRepository
import com.example.campusmarkettek.data.repository.ListingRepository
import com.example.campusmarkettek.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val content: UiState<ListingDetailDto> = UiState.Loading,
    val busy: Boolean = false,
    /** error จากการกดปุ่ม (เช่น 409 ถูกจองไปแล้ว) แสดงเหนือปุ่ม ไม่ทับตัวประกาศ */
    val actionError: String? = null,
)

@HiltViewModel
class ListingDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ListingRepository,
    private val browse: BrowseRepository,
    private val chat: ChatRepository,
): ViewModel() {

    private val id: String = checkNotNull(savedState["id"])
    val listingId: String get() = id

    /** หัวใจของประกาศนี้ (Room เป็นแหล่งความจริง — เปลี่ยนทันที แล้วค่อยส่ง server) */
    val isFavorite: StateFlow<Boolean> = browse.observeFavoriteIds()
        .map { id in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavorite() {
        viewModelScope.launch {
            try {
                browse.toggleFavorite(id, !isFavorite.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(actionError = e.toUserMessage()) }
            }
        }
    }

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            if (_state.value.content !is UiState.Success) _state.update { it.copy(content = UiState.Loading) }
            try {
                _state.update { it.copy(content = UiState.Success(repo.detail(id))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // มีข้อมูลที่แสดงอยู่แล้ว (โหลดซ้ำแล้วเน็ตหลุด) → คงไว้และแจ้งเตือนเฉย ๆ ไม่ทิ้งทั้งหน้า
                _state.update {
                    if (it.content is UiState.Success) it.copy(actionError = e.toUserMessage())
                    else it.copy(content = UiState.Error(e.toUserMessage()))
                }
            }
        }
    }

    /** เปิดห้องแชตกับผู้ขาย (มีห้องเดิมก็ใช้ต่อ) แล้วส่ง id ห้องกลับไปให้หน้าจอพาไป */
    fun openChat(onRoom: (String) -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val room = chat.openRoom(id)
                _state.update { it.copy(busy = false) }
                onRoom(room.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, actionError = e.toUserMessage()) }
            }
        }
    }

    // ── เจ้าของ ──
    fun cancel(reason: String) = act { repo.cancel(id, reason) }
    fun relist() = act { repo.relist(id) }
    fun resubmit() = act { repo.resubmit(id) }

    // ── ผู้ดูแล ──
    fun adminReturn(reason: String) = act { repo.adminReturn(id, reason) }
    fun adminTakedown(reason: String) = act { repo.adminTakedown(id, reason) }
    fun adminRestore() = act { repo.adminRestore(id) }

    /** ทำการเปลี่ยนสถานะที่ server แล้วโหลดรายละเอียดใหม่ (server เป็นผู้ตัดสิน — ไม่เดาผลเอง) */
    private fun act(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = false) }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, actionError = e.toUserMessage()) }
                load() // สถานะอาจเปลี่ยนไปแล้ว (เช่น 409) โหลดล่าสุดให้เห็น
            }
        }
    }
}
