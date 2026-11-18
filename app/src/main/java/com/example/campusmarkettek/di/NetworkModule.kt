package com.example.campusmarkettek.di

import com.example.campusmarkettek.BuildConfig
import com.example.campusmarkettek.data.remote.AdminVerificationApi
import com.example.campusmarkettek.data.remote.AuthApi
import com.example.campusmarkettek.data.remote.BrowseApi
import com.example.campusmarkettek.data.remote.ChatApi
import com.example.campusmarkettek.data.remote.BannerApi
import com.example.campusmarkettek.data.remote.ModerationApi
import com.example.campusmarkettek.data.remote.StatsApi
import com.example.campusmarkettek.data.remote.NotificationApi
import com.example.campusmarkettek.data.remote.PrivacyApi
import com.example.campusmarkettek.data.remote.AuthInterceptor
import com.example.campusmarkettek.data.remote.CategoryApi
import com.example.campusmarkettek.data.remote.HealthApi
import com.example.campusmarkettek.data.remote.ListingApi
import com.example.campusmarkettek.data.remote.UserApi
import com.example.campusmarkettek.data.remote.VerificationApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)   // แนบ Bearer token + 401 → ออกจากระบบ
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideHealthApi(retrofit: Retrofit): HealthApi =
        retrofit.create(HealthApi::class.java)

    @Provides
    @Singleton
    fun provideCategoryApi(retrofit: Retrofit): CategoryApi =
        retrofit.create(CategoryApi::class.java)

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)

    @Provides
    @Singleton
    fun provideVerificationApi(retrofit: Retrofit): VerificationApi =
        retrofit.create(VerificationApi::class.java)

    @Provides
    @Singleton
    fun provideAdminVerificationApi(retrofit: Retrofit): AdminVerificationApi =
        retrofit.create(AdminVerificationApi::class.java)

    @Provides
    @Singleton
    fun provideListingApi(retrofit: Retrofit): ListingApi = retrofit.create(ListingApi::class.java)

    /**
     * client สำหรับโหลดรูปสินค้า (Coil): แนบ token เหมือน API แต่ไม่ log body (รูปเป็นไบนารีขนาดใหญ่)
     * รูปสินค้าต้องล็อกอินจึงจะดูได้ — จึงต้องผ่าน AuthInterceptor
     */
    @Provides
    @Singleton
    @Named("image")
    fun provideImageClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(authInterceptor).build()

    @Provides
    @Singleton
    fun provideWorkManager(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): androidx.work.WorkManager =
        androidx.work.WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideBrowseApi(retrofit: Retrofit): BrowseApi = retrofit.create(BrowseApi::class.java)

    @Provides
    @Singleton
    fun provideChatApi(retrofit: Retrofit): ChatApi = retrofit.create(ChatApi::class.java)

    @Provides
    @Singleton
    fun providePrivacyApi(retrofit: Retrofit): PrivacyApi = retrofit.create(PrivacyApi::class.java)

    @Provides
    @Singleton
    fun provideBannerApi(retrofit: Retrofit): BannerApi = retrofit.create(BannerApi::class.java)

    @Provides
    @Singleton
    fun provideStatsApi(retrofit: Retrofit): StatsApi = retrofit.create(StatsApi::class.java)

    @Provides
    @Singleton
    fun provideModerationApi(retrofit: Retrofit): ModerationApi = retrofit.create(ModerationApi::class.java)

    @Provides
    @Singleton
    fun provideNotificationApi(retrofit: Retrofit): NotificationApi = retrofit.create(NotificationApi::class.java)

    /**
     * client ของ WebSocket: ส่ง ping ทุก 25 วินาที (รู้ว่าเส้นเชื่อมตายแล้วต่อใหม่) และไม่มี timeout อ่าน
     * ใส่ token เองตอนเชื่อมต่อ จึงไม่ผ่าน AuthInterceptor (ที่จะออกจากระบบเมื่อได้ 401)
     */
    @Provides
    @Singleton
    @Named("ws")
    fun provideWsClient(): OkHttpClient =
        OkHttpClient.Builder().pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).build()
}
