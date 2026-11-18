package com.example.campusmarkettek.data.repository

import android.content.Context
import com.example.campusmarkettek.data.local.dao.DraftDao
import com.example.campusmarkettek.data.local.dao.ListingDao
import com.example.campusmarkettek.data.local.entity.DraftListingEntity
import com.example.campusmarkettek.data.local.entity.ListingEntity
import com.example.campusmarkettek.data.local.entity.ListingImageEntity
import com.example.campusmarkettek.data.remote.ListingApi
import com.example.campusmarkettek.data.remote.ListingDetailDto
import com.example.campusmarkettek.data.remote.ReasonRequest
import com.example.campusmarkettek.data.remote.UpdateListingRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** ประกาศของฉันในรายการ (หน้า 18) */
data class MyListing(
    val id: String,
    val title: String,
    val price: Int,
    val status: String,
    val statusReason: String?,
    /** URL สัมพัทธ์ของรูปปก */
    val cover: String?,
    val updatedAt: Long,
)

/** แบบร่างประกาศที่ยังอยู่ในเครื่อง: state = DRAFT | PENDING | FAILED */
data class Draft(
    val id: String,
    val categoryId: Int?,
    val title: String,
    val description: String,
    val price: String,
    val facultyIds: Set<Int>,
    val tagIds: Set<Int>,
    val imageCount: Int,
    val state: String,
    val error: String?,
    val updatedAt: Long,
)

private fun DraftListingEntity.toDomain() = Draft(
    id, categoryId, title, description, price,
    facultyIds.toIdSet(), tagIds.toIdSet(), imageCount, state, error, updatedAt,
)

private fun String.toIdSet(): Set<Int> = split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

@Singleton
class ListingRepository @Inject constructor(
    private val api: ListingApi,
    private val listingDao: ListingDao,
    private val draftDao: DraftDao,
    @ApplicationContext private val context: Context,
) {
    // ── ประกาศของฉัน (อ่านจาก Room · refresh จาก server) ──────────────────

    fun observeMine(sellerId: String): Flow<List<MyListing>> =
        combine(listingDao.observeBySeller(sellerId), listingDao.observeCovers()) { listings, covers ->
            val coverById = covers.associate { it.listingId to it.imageKey }
            listings.map { MyListing(it.id, it.title, it.price, it.status, it.statusReason, coverById[it.id], it.updatedAt) }
        }

    /** ดึงประกาศของฉันจาก server (GET /users/me/listings) แล้วแทนที่แคช */
    suspend fun refreshMine(sellerId: String) {
        val remote = api.mine()
        listingDao.replaceMine(
            sellerId = sellerId,
            listings = remote.map {
                ListingEntity(
                    id = it.id, sellerId = sellerId, categoryId = it.categoryId, title = it.title,
                    description = "", // รายการไม่ส่งรายละเอียดมา (ดูในหน้ารายละเอียด)
                    price = it.price, status = it.status, statusReason = it.statusReason,
                    createdAt = Instant.parse(it.createdAt).toEpochMilli(),
                    updatedAt = Instant.parse(it.updatedAt).toEpochMilli(),
                )
            },
            covers = remote.mapNotNull { l -> l.cover?.let { ListingImageEntity(l.id, 0, it) } },
        )
    }

    // ── รายละเอียดและการเปลี่ยนสถานะ (ต้องออนไลน์ — server เป็นผู้ตัดสิน) ──

    suspend fun detail(id: String): ListingDetailDto = api.detail(id)
    suspend fun update(id: String, req: UpdateListingRequest): ListingDetailDto = api.update(id, req)
    suspend fun cancel(id: String, reason: String) = api.cancel(id, ReasonRequest(reason))
    suspend fun relist(id: String) = api.relist(id)
    suspend fun resubmit(id: String) = api.resubmit(id)
    suspend fun adminReturn(id: String, reason: String) = api.adminReturn(id, ReasonRequest(reason))
    suspend fun adminTakedown(id: String, reason: String) = api.adminTakedown(id, ReasonRequest(reason))
    suspend fun adminRestore(id: String) = api.adminRestore(id)

    // ── แบบร่าง (เก็บในเครื่อง · ลงประกาศ = เก็บแบบร่างก่อนแล้วส่งเมื่อมีเน็ต) ──

    fun observeDrafts(ownerId: String): Flow<List<Draft>> =
        draftDao.observeAll(ownerId).map { list -> list.map { it.toDomain() } }

    suspend fun getDraft(id: String): Draft? = draftDao.get(id)?.toDomain()

    private fun draftDir(id: String) = File(context.filesDir, "drafts/$id")

    /** บันทึกแบบร่าง + รูป (JPEG ที่ย่อแล้ว) ลงเครื่อง · id เดิม = แก้ทับ · คืน id */
    suspend fun saveDraft(
        id: String?, ownerId: String, categoryId: Int?, title: String, description: String, price: String,
        facultyIds: Set<Int>, tagIds: Set<Int>, photos: List<ByteArray>, state: String,
    ): String = withContext(Dispatchers.IO) {
        val draftId = id ?: UUID.randomUUID().toString()
        val dir = draftDir(draftId)
        dir.deleteRecursively()
        dir.mkdirs()
        photos.forEachIndexed { i, bytes -> File(dir, "$i.jpg").writeBytes(bytes) }
        draftDao.upsert(
            DraftListingEntity(
                id = draftId, ownerId = ownerId, categoryId = categoryId, title = title, description = description,
                price = price, facultyIds = facultyIds.joinToString(","), tagIds = tagIds.joinToString(","),
                imageCount = photos.size, state = state, error = null, updatedAt = System.currentTimeMillis(),
            ),
        )
        draftId
    }

    suspend fun loadDraftPhotos(id: String): List<ByteArray> = withContext(Dispatchers.IO) {
        val dir = draftDir(id)
        (0 until (draftDao.get(id)?.imageCount ?: 0)).mapNotNull { i -> File(dir, "$i.jpg").takeIf { it.exists() }?.readBytes() }
    }

    suspend fun deleteDraft(id: String) = withContext(Dispatchers.IO) {
        draftDao.delete(id)
        draftDir(id).deleteRecursively()
    }

    suspend fun markDraft(id: String, state: String, error: String? = null) =
        draftDao.setState(id, state, error, System.currentTimeMillis())

    suspend fun pendingDrafts(ownerId: String): List<Draft> = draftDao.pending(ownerId).map { it.toDomain() }

    /** ส่งแบบร่างขึ้น server (POST /listings) — โยน exception ถ้าไม่สำเร็จ · สำเร็จแล้วลบแบบร่าง */
    suspend fun uploadDraft(draft: Draft) {
        val photos = loadDraftPhotos(draft.id)
        val text = "text/plain; charset=utf-8".toMediaType()
        fun field(value: String): RequestBody = value.toRequestBody(text)
        api.create(
            categoryId = field((draft.categoryId ?: 0).toString()),
            title = field(draft.title),
            description = field(draft.description),
            price = field(draft.price.trim()),
            facultyIds = field(draft.facultyIds.joinToString(",")),
            tagIds = field(draft.tagIds.joinToString(",")),
            images = photos.mapIndexed { i, bytes ->
                MultipartBody.Part.createFormData("images", "photo$i.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
            },
        )
        deleteDraft(draft.id)
    }
}
