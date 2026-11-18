package com.example.campusmarkettek.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.campusmarkettek.data.auth.SessionManager
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.BrowseRepository
import com.example.campusmarkettek.data.repository.ChatRepository
import com.example.campusmarkettek.data.repository.ListingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * งานเบื้องหลังที่รันเมื่อมีเน็ตเท่านั้น (WorkManager): ส่งรายการโปรดที่ค้าง แล้วส่งแบบร่างประกาศที่รอส่ง (PENDING) ทีละรายการ
 *   สำเร็จ                  → ลบแบบร่าง · ประกาศขึ้นทันที
 *   เน็ตหลุด / server ล่ม   → ลองใหม่เอง (backoff)
 *   server ปฏิเสธ (4xx)     → แบบร่างเป็น FAILED พร้อมเหตุผล ให้ผู้ใช้แก้แล้วส่งใหม่ (ไม่ลองซ้ำเอง)
 * ส่งเฉพาะแบบร่างของผู้ใช้ที่ล็อกอินอยู่ (เครื่องเดียวหลายบัญชีต้องไม่ส่งข้ามคน)
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repo: ListingRepository,
    private val browse: BrowseRepository,
    private val chat: ChatRepository,
    private val session: SessionManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val owner = session.userId() ?: return Result.success() // ยังไม่ล็อกอิน: รอไว้ ส่งตอนล็อกอินครั้งถัดไป

        // ของเล็กและเร็วก่อน: ข้อความแชตที่พิมพ์ตอนออฟไลน์ แล้วรายการโปรดที่กดตอนออฟไลน์
        guarded { chat.syncPendingMessages() }?.let { return it }
        guarded { browse.syncPendingFavorites() }?.let { return it }

        for (draft in repo.pendingDrafts(owner)) {
            try {
                repo.uploadDraft(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                when (e.code()) {
                    401 -> return Result.success() // token หมดอายุ: SessionManager ออกจากระบบให้แล้ว แบบร่างยังรออยู่
                    408, 429, in 500..599 -> return Result.retry()
                    else -> repo.markDraft(draft.id, "FAILED", e.toUserMessage())
                }
            } catch (e: java.io.IOException) {
                return Result.retry()
            } catch (e: Exception) {
                repo.markDraft(draft.id, "FAILED", e.toUserMessage())
            }
        }
        runCatching { repo.refreshMine(owner) }
        return Result.success()
    }

    /**
     * รันขั้นตอนหนึ่งของการ sync · null = สำเร็จ ไปต่อได้ · ไม่ใช่ null = หยุดงานรอบนี้ด้วยผลนั้น
     *   เน็ตหลุด/server ล่ม/ส่งถี่ → ลองใหม่ทีหลัง · 401 → ออกจากระบบไปแล้ว หยุดโดยไม่ลองซ้ำ
     */
    private suspend fun guarded(block: suspend () -> Unit): Result? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: java.io.IOException) {
        Result.retry()
    } catch (e: HttpException) {
        when (e.code()) {
            401 -> Result.success()
            408, 429, in 500..599 -> Result.retry()
            else -> null // ข้อมูลของรายการนั้นไม่ผ่านกติกา — ข้ามไป ไม่ลากทั้งงาน
        }
    }
}

/** สั่งเริ่มงานส่งแบบร่าง (ทำงานเมื่อมีเน็ต) */
@Singleton
class SyncScheduler @Inject constructor(private val workManager: WorkManager) {
    fun enqueue() {
        workManager.enqueueUniqueWork(
            "background-sync",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
