package com.example.campusmarkettek.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.ListingCardDto
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.BrowseRepository
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.components.EmptyView
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.ListingCard
import com.example.campusmarkettek.ui.components.LoadingView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUi(
    val content: UiState<List<ListingCardDto>> = UiState.Loading,
    /** กำลังโชว์รายการที่เคยโหลดไว้ เพราะเชื่อมต่อไม่ได้ */
    val offline: Boolean = false,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(private val browse: BrowseRepository) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUi())
    val state: StateFlow<FavoritesUi> = _state.asStateFlow()

    /** หัวใจ: Room เป็นแหล่งความจริง — กดเอาออกแล้วการ์ดหายจากหน้านี้ทันที */
    val favoriteIds: StateFlow<Set<String>> = browse.observeFavoriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = FavoritesUi(UiState.Loading)
            _state.value = try {
                FavoritesUi(browse.refreshFavorites().let { if (it.isEmpty()) UiState.Empty else UiState.Success(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cached = if (e is java.io.IOException) browse.cachedFavorites() else null
                if (cached != null) FavoritesUi(if (cached.isEmpty()) UiState.Empty else UiState.Success(cached), offline = true)
                else FavoritesUi(UiState.Error(e.toUserMessage()))
            }
        }
    }

    fun toggle(id: String, nowFavorite: Boolean) {
        viewModelScope.launch { runCatching { browse.toggleFavorite(id, nowFavorite) } }
    }
}

/** หน้า 20 รายการโปรด — ขายแล้วยังเห็นได้ (BR16) ยกเลิก/ถอดแล้วจะไม่แสดง */
@Composable
fun FavoritesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: FavoritesViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val ids by viewModel.favoriteIds.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("รายการโปรด", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (ui.offline) {
            Text(
                "เชื่อมต่อไม่ได้ กำลังแสดงรายการที่เคยโหลดไว้",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        when (val c = ui.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = viewModel::load)
            UiState.Empty -> EmptyView(Icons.Outlined.FavoriteBorder, "ยังไม่มีรายการโปรด", "แตะรูปหัวใจที่สินค้าเพื่อบันทึกไว้ดูทีหลัง")
            is UiState.Success -> {
                val shown = c.data.filter { it.id in ids }
                if (shown.isEmpty()) {
                    EmptyView(Icons.Outlined.FavoriteBorder, "ยังไม่มีรายการโปรด", "แตะรูปหัวใจที่สินค้าเพื่อบันทึกไว้ดูทีหลัง")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(shown, key = { it.id }) { item ->
                            ListingCard(item, isFavorite = true, onClick = { onOpen(item.id) }, onToggleFavorite = { viewModel.toggle(item.id, false) })
                        }
                    }
                }
            }
        }
    }
}
