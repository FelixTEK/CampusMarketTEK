package com.example.campusmarkettek.ui.listing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.auth.SessionManager
import com.example.campusmarkettek.data.remote.FacultyDto
import com.example.campusmarkettek.data.remote.TagDto
import com.example.campusmarkettek.data.remote.UpdateListingRequest
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.CatalogRepository
import com.example.campusmarkettek.data.repository.CategoryRepository
import com.example.campusmarkettek.data.repository.ListingRepository
import com.example.campusmarkettek.domain.Category
import com.example.campusmarkettek.domain.Validator
import com.example.campusmarkettek.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val MAX_PHOTOS = 5
const val MAX_TAGS = 5

data class ListingFormState(
    /** ไม่ว่าง = แก้ไขประกาศที่ลงแล้ว (ไม่แก้รูป) */
    val editId: String? = null,
    /** ไม่ว่าง = กำลังแก้แบบร่างในเครื่อง */
    val draftId: String? = null,
    val categoryId: Int? = null,
    val title: String = "",
    val description: String = "",
    val price: String = "",
    val facultyIds: Set<Int> = emptySet(),
    val tagIds: Set<Int> = emptySet(),
    val photos: List<ByteArray> = emptyList(),
    val categories: List<Category> = emptyList(),
    val faculties: List<FacultyDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val showErrors: Boolean = false,
    val error: String? = null,
) {
    val isEdit get() = editId != null
    private val byId get() = categories.associateBy { it.id }

    /** เส้นทางหมวดจากหมวดหลักถึงหมวดที่เลือก */
    val categoryPath: List<Category>
        get() {
            val out = ArrayList<Category>()
            var cur = categoryId?.let { byId[it] }
            while (cur != null) {
                out.add(0, cur)
                cur = cur.parentId?.let { byId[it] }
            }
            return out
        }

    val rootCategory get() = categoryPath.firstOrNull()
    val isBook get() = rootCategory?.nameTh?.startsWith("หนังสือ") == true
    val isLeaf get() = categoryId != null && categories.none { it.parentId == categoryId }

    /** แท็กที่ใช้ได้กับหมวดนี้: ทุกหมวด หรือของหมวดหลักเดียวกัน */
    val applicableTags get() = tags.filter { it.categoryId == null || it.categoryId == rootCategory?.id }

    val priceInt get() = price.trim().toIntOrNull()

    // ── ตรวจตามกติกาเดียวกับฐานข้อมูล (Validator) — null = ผ่าน ──
    val titleError get() = Validator.title(title)
    val priceError get() = Validator.price(priceInt)
    val descriptionError get() = Validator.description(description)
    val categoryError get() = when {
        categoryId == null -> "เลือกหมวดหมู่"
        !isLeaf -> "เลือกหมวดย่อยให้ละเอียดที่สุด"
        else -> null
    }
    val facultyError get() = if (isBook && facultyIds.isEmpty()) "หนังสือต้องเลือกคณะที่ใช้อย่างน้อย 1 คณะ" else null
    val photosError get() = if (isEdit) null else Validator.imageCount(photos.size)

    val firstError get() = listOfNotNull(photosError, categoryError, titleError, priceError, descriptionError, facultyError).firstOrNull()
}

/** ผลของปุ่ม: ไปหน้าประกาศของฉัน / กลับหน้ารายละเอียด */
sealed interface FormResult {
    data object Queued : FormResult
    data class Saved(val id: String) : FormResult
}

@HiltViewModel
class ListingFormViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val categoryRepo: CategoryRepository,
    private val listings: ListingRepository,
    private val session: SessionManager,
    private val draftSync: SyncScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(ListingFormState(editId = savedState["id"], draftId = savedState["draftId"]))
    val state: StateFlow<ListingFormState> = _state.asStateFlow()

    init {
        viewModelScope.launch { catalog.observeCategories().collect { c -> _state.update { it.copy(categories = c) } } }
        viewModelScope.launch { catalog.observeFaculties().collect { f -> _state.update { it.copy(faculties = f) } } }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // หมวด คณะ แท็ก โหลดไม่ได้ (ออฟไลน์) ก็ยังกรอกแบบร่างได้ — ใช้ที่ cache ใน Room
            runCatching { categoryRepo.refresh() }
            runCatching { catalog.refreshFaculties() }
            val tags = runCatching { catalog.tags() }.getOrDefault(emptyList())
            _state.update { it.copy(tags = tags) }

            try {
                val s = _state.value
                if (s.editId != null) {
                    val d = listings.detail(s.editId)
                    _state.update {
                        it.copy(
                            categoryId = d.categoryId, title = d.title, description = d.description,
                            price = d.price.toString(), facultyIds = d.faculties.map { f -> f.id }.toSet(),
                            tagIds = d.tags.map { t -> t.id }.toSet(),
                        )
                    }
                } else if (s.draftId != null) {
                    listings.getDraft(s.draftId)?.let { d ->
                        val photos = listings.loadDraftPhotos(d.id)
                        _state.update {
                            it.copy(
                                categoryId = d.categoryId, title = d.title, description = d.description,
                                price = d.price, facultyIds = d.facultyIds, tagIds = d.tagIds, photos = photos,
                            )
                        }
                    }
                }
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    /** ล้างฟอร์มหลังลงประกาศแล้ว (แท็บลงขายค้างสถานะไว้ จึงต้องล้างเอง) */
    fun reset() = _state.update {
        it.copy(
            draftId = null, categoryId = null, title = "", description = "", price = "", facultyIds = emptySet(),
            tagIds = emptySet(), photos = emptyList(), saving = false, showErrors = false, error = null,
        )
    }

    fun onTitle(v: String) = _state.update { it.copy(title = v.take(100), error = null) }
    fun onDescription(v: String) = _state.update { it.copy(description = v.take(2000), error = null) }
    fun onPrice(v: String) = _state.update { it.copy(price = v.filter(Char::isDigit).take(9), error = null) }

    fun onCategory(id: Int) = _state.update { s ->
        val next = s.copy(categoryId = id, error = null)
        // เปลี่ยนหมวดแล้ว ล้างคณะ/แท็กที่ไม่เข้ากับหมวดใหม่
        next.copy(
            facultyIds = if (next.isBook) next.facultyIds else emptySet(),
            tagIds = next.tagIds.filter { t -> next.applicableTags.any { it.id == t } }.toSet(),
        )
    }

    fun setAllFaculties(selected: Boolean) = _state.update {
        it.copy(facultyIds = if (selected) it.faculties.map { f -> f.id }.toSet() else emptySet(), error = null)
    }

    fun toggleFaculty(id: Int) = _state.update { it.copy(facultyIds = it.facultyIds.toggle(id), error = null) }

    fun toggleTag(id: Int) = _state.update {
        if (id !in it.tagIds && it.tagIds.size >= MAX_TAGS) it.copy(error = "เลือกแท็กได้ไม่เกิน $MAX_TAGS แท็ก")
        else it.copy(tagIds = it.tagIds.toggle(id), error = null)
    }

    fun addPhoto(bytes: ByteArray) = _state.update {
        if (it.photos.size >= MAX_PHOTOS) it.copy(error = "ใส่รูปได้ไม่เกิน $MAX_PHOTOS รูป") else it.copy(photos = it.photos + bytes, error = null)
    }

    fun removePhoto(index: Int) = _state.update { it.copy(photos = it.photos.filterIndexed { i, _ -> i != index }) }

    fun onError(message: String) = _state.update { it.copy(error = message) }

    /** กดลงประกาศ: แก้ไข = ส่ง PATCH ทันที · ลงใหม่ = เก็บแบบร่างแล้วส่งเมื่อมีเน็ต */
    fun submit(onResult: (FormResult) -> Unit) {
        val s = _state.value
        if (s.saving) return
        s.firstError?.let { return _state.update { it.copy(showErrors = true, error = null) } }
        _state.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            try {
                if (s.editId != null) {
                    listings.update(
                        s.editId,
                        UpdateListingRequest(
                            title = s.title.trim(), description = s.description, price = s.priceInt,
                            categoryId = s.categoryId, facultyIds = s.facultyIds.toList(), tagIds = s.tagIds.toList(),
                        ),
                    )
                    onResult(FormResult.Saved(s.editId))
                } else {
                    saveDraft(s, state = "PENDING")
                    draftSync.enqueue()
                    onResult(FormResult.Queued)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.toUserMessage()) }
            }
        }
    }

    /** บันทึกแบบร่างไว้ในเครื่องโดยไม่ส่ง (ไม่ต้องกรอกครบ) */
    fun saveAsDraft(onResult: (FormResult) -> Unit) {
        val s = _state.value
        if (s.saving || s.isEdit) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                saveDraft(s, state = "DRAFT")
                onResult(FormResult.Queued)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.toUserMessage()) }
            }
        }
    }

    private suspend fun saveDraft(s: ListingFormState, state: String) {
        val owner = checkNotNull(session.userId()) { "ยังไม่ได้เข้าสู่ระบบ" }
        listings.saveDraft(
            id = s.draftId, ownerId = owner, categoryId = s.categoryId, title = s.title.trim(),
            description = s.description, price = s.price, facultyIds = s.facultyIds, tagIds = s.tagIds,
            photos = s.photos, state = state,
        )
    }
}

private fun Set<Int>.toggle(id: Int) = if (id in this) this - id else this + id
