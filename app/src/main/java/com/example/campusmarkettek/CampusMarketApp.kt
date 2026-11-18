package com.example.campusmarkettek

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named

/**
 * จุดเริ่มต้นของ Hilt — ทุก dependency ที่ inject ได้มีอายุผูกกับ Application นี้
 * และตั้งค่า 2 อย่างที่ต้องทำที่นี่:
 *   - WorkManager ให้สร้าง Worker ผ่าน Hilt (inject repository ได้)
 *   - Coil โหลดรูปผ่าน client ที่แนบ token (รูปสินค้าต้องล็อกอินจึงจะดูได้)
 */
@HiltAndroidApp
class CampusMarketApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject @Named("image") lateinit var imageClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        com.example.campusmarkettek.work.ensureNotificationChannel(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient })) }
            .build()
}
