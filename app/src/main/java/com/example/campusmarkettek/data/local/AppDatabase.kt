package com.example.campusmarkettek.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.campusmarkettek.data.local.dao.BrowseCacheDao
import com.example.campusmarkettek.data.local.dao.CategoryDao
import com.example.campusmarkettek.data.local.dao.ConversationDao
import com.example.campusmarkettek.data.local.dao.DealDao
import com.example.campusmarkettek.data.local.dao.DraftDao
import com.example.campusmarkettek.data.local.dao.FacultyDao
import com.example.campusmarkettek.data.local.dao.FavoriteDao
import com.example.campusmarkettek.data.local.dao.ListingDao
import com.example.campusmarkettek.data.local.dao.MessageDao
import com.example.campusmarkettek.data.local.dao.NotificationDao
import com.example.campusmarkettek.data.local.dao.SearchHistoryDao
import com.example.campusmarkettek.data.local.entity.BrowseCacheEntity
import com.example.campusmarkettek.data.local.entity.CategoryEntity
import com.example.campusmarkettek.data.local.entity.ConversationEntity
import com.example.campusmarkettek.data.local.entity.DealEntity
import com.example.campusmarkettek.data.local.entity.DraftListingEntity
import com.example.campusmarkettek.data.local.entity.FacultyEntity
import com.example.campusmarkettek.data.local.entity.FavoriteEntity
import com.example.campusmarkettek.data.local.entity.ListingEntity
import com.example.campusmarkettek.data.local.entity.ListingImageEntity
import com.example.campusmarkettek.data.local.entity.MessageEntity
import com.example.campusmarkettek.data.local.entity.NotificationEntity
import com.example.campusmarkettek.data.local.entity.SearchHistoryEntity

/**
 * ฐานข้อมูลในเครื่อง = แคชของข้อมูลบน PostgreSQL ไว้ดูตอนออฟไลน์ (ไม่ใช่ข้อมูลจริง)
 * ข้อมูลจริงอยู่ที่ server เสมอ จึงใช้ fallbackToDestructiveMigration ได้ (เปลี่ยนโครงสร้างแล้วโหลดใหม่จาก server)
 */
@Database(
    entities = [
        CategoryEntity::class,
        FacultyEntity::class,
        ListingEntity::class,
        ListingImageEntity::class,
        FavoriteEntity::class,
        ConversationEntity::class,
        DealEntity::class,
        MessageEntity::class,
        NotificationEntity::class,
        DraftListingEntity::class,
        SearchHistoryEntity::class,
        BrowseCacheEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun facultyDao(): FacultyDao
    abstract fun listingDao(): ListingDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun conversationDao(): ConversationDao
    abstract fun dealDao(): DealDao
    abstract fun messageDao(): MessageDao
    abstract fun notificationDao(): NotificationDao
    abstract fun draftDao(): DraftDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun browseCacheDao(): BrowseCacheDao
}
