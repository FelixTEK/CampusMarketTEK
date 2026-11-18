package com.example.campusmarkettek.data.repository

import com.example.campusmarkettek.data.local.dao.CategoryDao
import com.example.campusmarkettek.data.local.dao.FacultyDao
import com.example.campusmarkettek.data.local.entity.FacultyEntity
import com.example.campusmarkettek.data.remote.FacultyDto
import com.example.campusmarkettek.data.remote.ListingApi
import com.example.campusmarkettek.data.remote.TagDto
import com.example.campusmarkettek.data.remote.UserApi
import com.example.campusmarkettek.domain.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** ข้อมูลอ้างอิงที่ฟอร์มลงขายใช้: หมวด (ต้นไม้ทั้งหมด) คณะ แท็ก — อ่านจาก Room ก่อน แล้วค่อยรีเฟรชจาก server */
@Singleton
class CatalogRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val facultyDao: FacultyDao,
    private val users: UserApi,
    private val listings: ListingApi,
) {
    /** หมวดทุกระดับ (หมวดถูกโหลดลง Room แล้วโดย CategoryRepository.refresh ตอนเปิดหน้าแรก) */
    fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { list -> list.map { Category(it.id, it.parentId, it.nameTh, it.sort) } }

    fun observeFaculties(): Flow<List<FacultyDto>> =
        facultyDao.observeAll().map { list -> list.map { FacultyDto(it.id, it.nameTh) } }

    suspend fun refreshFaculties() {
        facultyDao.replaceAll(users.faculties().map { FacultyEntity(it.id, it.nameTh) })
    }

    @Volatile
    private var tagCache: List<TagDto>? = null

    /** แท็กมี 14 รายการ ไม่เปลี่ยนบ่อย — เก็บในหน่วยความจำ ไม่ลง Room */
    suspend fun tags(): List<TagDto> = tagCache ?: listings.tags().also { tagCache = it }
}
