package com.example.campusmarkettek.data.repository

import com.example.campusmarkettek.data.local.dao.CategoryDao
import com.example.campusmarkettek.data.local.entity.CategoryEntity
import com.example.campusmarkettek.data.remote.CategoryApi
import com.example.campusmarkettek.domain.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository = ทางเดียวที่ ViewModel ใช้ขอข้อมูล
 * UI อ่านจาก Room (Flow) เสมอ ส่วน refresh() ไปดึงจาก server (PostgreSQL) มาเขียนทับ Room
 * จึงเห็นข้อมูลล่าสุดที่เคยโหลดได้แม้ออฟไลน์
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val api: CategoryApi,
    private val dao: CategoryDao,
) {
    fun observeTopLevel(): Flow<List<Category>> =
        dao.observeTopLevel().map { list -> list.map { it.toDomain() } }

    /** โยน exception ถ้าเรียก server ไม่สำเร็จ — ผู้เรียกแปลงเป็นข้อความด้วย toUserMessage() */
    suspend fun refresh() {
        val remote = api.categories()
        dao.replaceAll(remote.map { CategoryEntity(it.id, it.parentId, it.nameTh, it.sort) })
    }

    private fun CategoryEntity.toDomain() = Category(id, parentId, nameTh, sort)
}
