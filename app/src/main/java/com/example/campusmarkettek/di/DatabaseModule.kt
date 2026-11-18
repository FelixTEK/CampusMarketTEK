package com.example.campusmarkettek.di

import android.content.Context
import androidx.room.Room
import com.example.campusmarkettek.data.local.AppDatabase
import com.example.campusmarkettek.data.local.dao.BrowseCacheDao
import com.example.campusmarkettek.data.local.dao.CategoryDao
import com.example.campusmarkettek.data.local.dao.DealDao
import com.example.campusmarkettek.data.local.dao.MessageDao
import com.example.campusmarkettek.data.local.dao.FavoriteDao
import com.example.campusmarkettek.data.local.dao.SearchHistoryDao
import com.example.campusmarkettek.data.local.dao.DraftDao
import com.example.campusmarkettek.data.local.dao.FacultyDao
import com.example.campusmarkettek.data.local.dao.ListingDao
import com.example.campusmarkettek.data.local.dao.NotificationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "campus_market_cache.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    // DAO อื่นจะเพิ่มที่นี่เมื่อเฟสที่ใช้เริ่มทำ
    @Provides
    fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideNotificationDao(db: AppDatabase): NotificationDao = db.notificationDao()

    @Provides
    fun provideListingDao(db: AppDatabase): ListingDao = db.listingDao()

    @Provides
    fun provideDraftDao(db: AppDatabase): DraftDao = db.draftDao()

    @Provides
    fun provideFacultyDao(db: AppDatabase): FacultyDao = db.facultyDao()

    @Provides
    fun provideFavoriteDao(db: AppDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideSearchHistoryDao(db: AppDatabase): SearchHistoryDao = db.searchHistoryDao()

    @Provides
    fun provideBrowseCacheDao(db: AppDatabase): BrowseCacheDao = db.browseCacheDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideDealDao(db: AppDatabase): DealDao = db.dealDao()
}
