package com.example.campusmarkettek.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.auth.MeCache
import com.example.campusmarkettek.data.auth.SessionManager
import com.example.campusmarkettek.data.local.AppDatabase
import com.example.campusmarkettek.data.remote.MeDto
import com.example.campusmarkettek.data.remote.apiErrorCode
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** ผู้ใช้ที่ล็อกอินอยู่ตอนนี้ (จาก GET /users/me) ใช้ตัดสินว่าจะเห็นหน้าไหน */
sealed interface MeState {
    data object Loading : MeState
    data class Ready(val me: MeDto) : MeState
    data object Banned : MeState
    data class Failed(val message: String) : MeState
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    val session: SessionManager,
    private val accounts: AccountRepository,
    private val db: AppDatabase,
    private val meCache: MeCache,
) : ViewModel() {

    private val _me = MutableStateFlow<MeState>(MeState.Loading)
    val me: StateFlow<MeState> = _me.asStateFlow()

    init {
        viewModelScope.launch {
            session.isLoggedIn.collect { loggedIn ->
                if (loggedIn) {
                    refreshMe()
                } else {
                    _me.value = MeState.Loading
                    meCache.clear()
                    // ออกจากระบบ (หรือ token หมดอายุ) → ล้างแคชในเครื่อง ไม่ให้ข้อมูลของคนเดิมค้างอยู่
                    launch(Dispatchers.IO) {
                        // แบบร่างประกาศที่ยังไม่ได้ส่งต้องไม่หายเมื่อ token หมดอายุ — เก็บไว้แล้วใส่กลับ (ผูกกับเจ้าของ แสดงเฉพาะบัญชีนั้น)
                        val drafts = db.draftDao().all()
                        db.clearAllTables()
                        drafts.forEach { db.draftDao().upsert(it) }
                    }
                }
            }
        }
    }

    /** โหลดโปรไฟล์ใหม่ — เรียกหลังส่งบัตร/กดตรวจสถานะ/แก้โปรไฟล์ */
    fun refreshMe() {
        viewModelScope.launch {
            // ถ้ามีข้อมูลเดิมอยู่แล้วไม่สลับเป็น Loading (กันหน้าจอกระพริบ)
            if (_me.value !is MeState.Ready) _me.value = MeState.Loading
            _me.value = try {
                accounts.me().also { meCache.save(it) }.let { MeState.Ready(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    e.apiErrorCode() == "BANNED" -> MeState.Banned
                    // เชื่อมต่อไม่ได้ (ออฟไลน์ / server ล่ม) แต่เคยล็อกอินบนเครื่องนี้ → ใช้โปรไฟล์ที่จำไว้ ให้ดูข้อมูลและลงแบบร่างได้
                    // (คำตอบจาก server เช่น 401/403 ยังยึดตาม server เสมอ)
                    e is java.io.IOException -> meCache.load(session.userId())?.let { MeState.Ready(it) }
                        ?: MeState.Failed(e.toUserMessage())
                    else -> MeState.Failed(e.toUserMessage())
                }
            }
        }
    }

    fun signOut() = session.signOut()
}
