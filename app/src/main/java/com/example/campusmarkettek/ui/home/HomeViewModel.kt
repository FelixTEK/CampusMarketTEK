package com.example.campusmarkettek.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.FacultyDto
import com.example.campusmarkettek.data.remote.ListingCardDto
import com.example.campusmarkettek.data.remote.TagDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.BrowseFilters
import com.example.campusmarkettek.data.repository.BrowseRepository
import com.example.campusmarkettek.data.repository.CatalogRepository
import com.example.campusmarkettek.data.repository.CategoryRepository
import com.example.campusmarkettek.domain.Category
import com.example.campusmarkettek.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    /** ตัวกรองที่ใช้อยู่จริง (ผลที่เห็นตรงกับค่านี้) */
    val filters: BrowseFilters = BrowseFilters(),
    /** ข้อความในช่องค้นหา (พิมพ์อยู่ ยังไม่กดค้น) */
    val searchText: String = "",
    val items: List<ListingCardDto> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** กำลังโชว์หน้าแรกที่แคชไว้ เพราะเชื่อมต่อไม่ได้ */
    val offline: Boolean = false,
    val faculties: List<FacultyDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    /** จำนวนผลลัพธ์ของตัวกรองที่กำลังเลือกในแผ่นตัวกรอง (null = กำลังนับ/ยังไม่รู้) */
    val previewCount: Int? = null,
    val transientError: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val browse: BrowseRepository,
    private val categories: CategoryRepository,
    private val catalog: CatalogRepository,
    private val sync: SyncScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** หมวดหลัก (ชิป) จากตาราง categories — แสดงจากแคชทันที */
    val topCategories: StateFlow<List<Category>> = categories.observeTopLevel()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** รายการโปรดของฉัน (หัวใจที่เติมสี) — Room เป็นแหล่งความจริง */
    val favoriteIds: StateFlow<Set<String>> = browse.observeFavoriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val history: StateFlow<List<String>> = browse.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var loadJob: Job? = null
    private var countJob: Job? = null

    init {
        viewModelScope.launch { runCatching { categories.refresh() } }
        viewModelScope.launch {
            runCatching { catalog.refreshFaculties() }
            runCatching { _state.update { it.copy(tags = catalog.tags()) } }
        }
        viewModelScope.launch { catalog.observeFaculties().collect { f -> _state.update { it.copy(faculties = f) } } }
        // ดึงรายการโปรดจาก server มาใส่ Room (หัวใจในรายการจะถูกต้อง) และส่งรายการที่ค้างจากตอนออฟไลน์
        viewModelScope.launch { runCatching { browse.refreshFavorites() } }
        sync.enqueue()
        load(reset = true)
    }

    // ── ค้นหา / กรอง ───────────────────────────────────────────────────

    fun onSearchText(text: String) = _state.update { it.copy(searchText = text) }

    fun submitSearch() {
        val q = _state.value.searchText.trim()
        viewModelScope.launch { browse.addHistory(q) }
        apply(_state.value.filters.copy(query = q))
    }

    fun searchFromHistory(term: String) {
        _state.update { it.copy(searchText = term) }
        submitSearch()
    }

    fun clearSearch() {
        _state.update { it.copy(searchText = "") }
        apply(_state.value.filters.copy(query = ""))
    }

    fun selectCategory(id: Int?) = apply(_state.value.filters.copy(categoryId = id))

    fun removeHistory(term: String) = viewModelScope.launch { browse.removeHistory(term) }
    fun clearHistory() = viewModelScope.launch { browse.clearHistory() }

    /** ใช้ตัวกรองใหม่ (คงคำค้นและหมวดไว้) แล้วโหลดหน้าแรกใหม่ */
    fun apply(filters: BrowseFilters) {
        _state.update { it.copy(filters = filters) }
        load(reset = true)
    }

    fun retry() = load(reset = true)

    // ── โหลดและแบ่งหน้า ────────────────────────────────────────────────

    private fun load(reset: Boolean) {
        loadJob?.cancel()
        val f = _state.value.filters
        if (reset) _state.update { it.copy(loading = true, error = null, offline = false, items = emptyList(), page = 1, hasMore = false) }
        loadJob = viewModelScope.launch {
            try {
                val r = browse.search(f, page = 1)
                _state.update { it.copy(items = r.items, page = 1, hasMore = r.hasMore, loading = false, error = null, offline = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // เชื่อมต่อไม่ได้ + เป็นหน้าแรกเปล่า ๆ → โชว์ที่เคยโหลดไว้ (อ่านจาก Room)
                val cached = if (f.isDefault && e is java.io.IOException) browse.cachedHome() else null
                _state.update {
                    if (cached != null) it.copy(items = cached, page = 1, hasMore = false, loading = false, offline = true, error = null)
                    else it.copy(loading = false, error = e.toUserMessage())
                }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.loadingMore || s.loading || s.offline) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val r = browse.search(s.filters, page = s.page + 1)
                _state.update { cur ->
                    // กันซ้ำ: ถ้ามีประกาศใหม่แทรกระหว่างโหลดหน้า รายการเดิมจะเลื่อนลงมา
                    val known = cur.items.map { it.id }.toSet()
                    cur.copy(items = cur.items + r.items.filter { it.id !in known }, page = s.page + 1, hasMore = r.hasMore, loadingMore = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, transientError = e.toUserMessage()) }
            }
        }
    }

    // ── นับผลลัพธ์ในแผ่นตัวกรอง ("แสดง N รายการ") ───────────────────────

    fun previewCount(filters: BrowseFilters) {
        countJob?.cancel()
        _state.update { it.copy(previewCount = null) }
        countJob = viewModelScope.launch {
            delay(300) // รอผู้ใช้หยุดพิมพ์/แตะก่อน ไม่ยิงทุกครั้ง
            val n = runCatching { browse.count(filters) }.getOrNull()
            _state.update { it.copy(previewCount = n) }
        }
    }

    // ── หัวใจ ────────────────────────────────────────────────────────────

    fun toggleFavorite(id: String) {
        val now = id !in favoriteIds.value
        viewModelScope.launch {
            try {
                browse.toggleFavorite(id, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(transientError = e.toUserMessage()) }
            }
        }
    }

    fun consumeTransientError() = _state.update { it.copy(transientError = null) }
}
