package com.example.campusmarkettek.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.campusmarkettek.MainActivity
import com.example.campusmarkettek.R
import com.example.campusmarkettek.data.auth.SessionManager
import com.example.campusmarkettek.data.repository.NotificationRepository
import com.example.campusmarkettek.data.repository.targetRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val DEAL_CHANNEL_ID = "deals"
const val EXTRA_ROUTE = "open_route"

/** ช่องแจ้งเตือนของระบบ (Android 8+ ต้องมี) — เรียกซ้ำได้ */
fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(DEAL_CHANNEL_ID, "ดีลและข้อเสนอ", NotificationManager.IMPORTANCE_DEFAULT)
        .apply { description = "ข้อเสนอซื้อและความเคลื่อนไหวของดีล" }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/**
 * ทุก ≥ 15 นาที (ขั้นต่ำของ WorkManager) ตอนมีเน็ต: ดึงแจ้งเตือนใหม่แล้วขึ้นแจ้งเตือนของระบบ
 * แตะแล้วเปิดแอปไปหน้าที่เกี่ยวข้อง (ห้องแชตของดีล) · ไม่ได้ให้สิทธิ์ POST_NOTIFICATIONS ก็ยังอัปเดต badge ในแอปได้
 */
@HiltWorker
class NotificationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val repo: NotificationRepository,
    private val session: SessionManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (session.userId() == null) return Result.success()
        val fresh = try {
            repo.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            return Result.retry()
        } catch (e: HttpException) {
            return if (e.code() >= 500 || e.code() == 429) Result.retry() else Result.success()
        }

        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return Result.success()

        ensureNotificationChannel(context)
        val manager = NotificationManagerCompat.from(context)
        for (n in fresh) {
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                n.targetRoute()?.let { putExtra(EXTRA_ROUTE, it) }
            }
            val pending = PendingIntent.getActivity(context, n.id.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val built = NotificationCompat.Builder(context, DEAL_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Campus Market")
                .setContentText(n.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            @Suppress("MissingPermission") // เช็กสิทธิ์ด้านบนแล้ว
            manager.notify(n.id.toInt(), built)
        }
        return Result.success()
    }
}

@Singleton
class NotificationScheduler @Inject constructor(private val workManager: WorkManager) {
    /** เรียกทุกครั้งที่เข้าแอปได้: KEEP = มีงานอยู่แล้วไม่ตั้งซ้ำ */
    fun schedule() {
        workManager.enqueueUniquePeriodicWork(
            "notification-poll",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
