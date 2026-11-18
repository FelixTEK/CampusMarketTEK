package com.example.campusmarkettek.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.campusmarkettek.data.repository.BrowseFilters
import com.example.campusmarkettek.ui.components.AppTextField
import com.example.campusmarkettek.ui.components.EmptyView
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.ListingCard
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.theme.Rust

/** หน้า 02 หน้าแรก: ค้นหา · ชิปหมวด · ตัวกรอง (หน้า 03) · รายการสินค้าแบ่งหน้า · หัวใจ */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenListing: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    bannersViewModel: BannersViewModel = hiltViewModel(),
) {
    val banners by bannersViewModel.banners.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { bannersViewModel.refresh(); onPauseOrDispose { } }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.topCategories.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(s.transientError) {
        s.transientError?.let { snackbar.showSnackbar(it); viewModel.consumeTransientError() }
    }
    // เลื่อนใกล้ท้ายรายการ → โหลดหน้าถัดไป
    LaunchedEffect(listState, s.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (s.items.isNotEmpty() && last >= s.items.size - 3) viewModel.loadMore() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Campus Market", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                IconButton(onClick = onOpenFavorites) {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = "รายการโปรด")
                }
            }

            // ── ช่องค้นหา + ปุ่มตัวกรอง ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = s.searchText,
                    onValueChange = viewModel::onSearchText,
                    modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused },
                    placeholder = { Text("ค้นหา ชื่อสินค้า รหัสวิชา ยี่ห้อ") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (s.searchText.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearSearch() }) { Icon(Icons.Outlined.Close, "ล้างคำค้น") }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch(); focusManager.clearFocus() }),
                )
                Surface(
                    modifier = Modifier.size(56.dp).clickable { focusManager.clearFocus(); sheetOpen = true },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        BadgedBox(badge = { if (s.filters.activeCount > 0) Badge(containerColor = Rust) { Text("${s.filters.activeCount}") } }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "ตัวกรอง")
                        }
                    }
                }
            }

            if (searchFocused && s.searchText.isBlank() && history.isNotEmpty()) {
                SearchHistory(
                    terms = history,
                    onPick = { viewModel.searchFromHistory(it); focusManager.clearFocus() },
                    onRemove = viewModel::removeHistory,
                    onClear = viewModel::clearHistory,
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(selected = s.filters.categoryId == null, onClick = { viewModel.selectCategory(null) }, label = { Text("ทั้งหมด") }) }
                    items(categories, key = { it.id }) { c ->
                        FilterChip(selected = s.filters.categoryId == c.id, onClick = { viewModel.selectCategory(c.id) }, label = { Text(c.nameTh) })
                    }
                }

                if (s.offline) {
                    Text(
                        "เชื่อมต่อไม่ได้ กำลังแสดงรายการที่เคยโหลดไว้",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }

                when {
                    s.loading -> LoadingView()
                    s.error != null -> ErrorView(s.error!!, onRetry = viewModel::retry)
                    s.items.isEmpty() -> EmptyView(
                        Icons.Outlined.Inventory2,
                        if (s.filters.isDefault) "ยังไม่มีสินค้าให้แสดง" else "ไม่พบสินค้าที่ตรงกับที่ค้นหา",
                        if (s.filters.isDefault) "รายการสินค้าจะแสดงที่นี่เมื่อมีผู้ลงขาย" else "ลองเปลี่ยนคำค้นหรือล้างตัวกรอง",
                    )
                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // โฆษณาอยู่บนสุดของรายการ (เลื่อนหายไปกับรายการ) · ซ่อนตอนกำลังค้นหาหรือกรอง · ไม่มีแบนเนอร์ = ไม่มีแถบ
                        if (banners.isNotEmpty() && s.filters.isDefault && s.searchText.isEmpty()) {
                            item(key = "banners") { BannerCarousel(banners, onOpenListing = onOpenListing) }
                        }
                        items(s.items, key = { it.id }) { item ->
                            ListingCard(
                                item = item,
                                isFavorite = item.id in favorites,
                                onClick = { onOpenListing(item.id) },
                                onToggleFavorite = { viewModel.toggleFavorite(item.id) },
                            )
                        }
                        if (s.loadingMore) {
                            item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (sheetOpen) {
        FilterSheet(
            state = s,
            onPreview = viewModel::previewCount,
            onApply = { viewModel.apply(it); sheetOpen = false; focusManager.clearFocus() },
            onDismiss = { sheetOpen = false; focusManager.clearFocus() },
        )
    }
}

@Composable
private fun SearchHistory(terms: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ค้นหาล่าสุด", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("ล้างทั้งหมด") }
        }
        terms.forEach { term ->
            Row(Modifier.fillMaxWidth().clickable { onPick(term) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(term, Modifier.weight(1f).padding(start = 12.dp))
                IconButton(onClick = { onRemove(term) }) { Icon(Icons.Outlined.Close, "ลบ $term", Modifier.size(18.dp)) }
            }
        }
    }
}

/** หน้า 03 ตัวกรอง: เรียงตาม · ช่วงราคา · คณะ · แท็ก — ปุ่มล่างบอกจำนวนผลลัพธ์ก่อนกดใช้ */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    state: HomeUiState,
    onPreview: (BrowseFilters) -> Unit,
    onApply: (BrowseFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var temp by remember { mutableStateOf(state.filters) }
    var minText by remember { mutableStateOf(state.filters.minPrice?.toString().orEmpty()) }
    var maxText by remember { mutableStateOf(state.filters.maxPrice?.toString().orEmpty()) }
    val hasQuery = temp.query.isNotBlank()
    val defaultSort = if (hasQuery) "relevant" else "new"

    LaunchedEffect(temp) { onPreview(temp) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ตัวกรอง", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Text("เรียงตาม", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = buildList {
                    if (hasQuery) add("relevant" to "ตรงที่สุด")
                    add("new" to "ใหม่ล่าสุด"); add("price_asc" to "ราคาน้อย → มาก"); add("price_desc" to "ราคามาก → น้อย")
                }
                options.forEach { (key, label) ->
                    FilterChip(
                        selected = (temp.sort ?: defaultSort) == key,
                        onClick = { temp = temp.copy(sort = if (key == defaultSort) null else key) },
                        label = { Text(label) },
                    )
                }
            }

            Text("ช่วงราคา (บาท)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    minText, { v -> minText = v.filter(Char::isDigit).take(9); temp = temp.copy(minPrice = minText.toIntOrNull()) },
                    "ต่ำสุด", Modifier.weight(1f), keyboardType = KeyboardType.Number,
                )
                AppTextField(
                    maxText, { v -> maxText = v.filter(Char::isDigit).take(9); temp = temp.copy(maxPrice = maxText.toIntOrNull()) },
                    "สูงสุด", Modifier.weight(1f), keyboardType = KeyboardType.Number,
                )
            }
            if (temp.minPrice != null && temp.maxPrice != null && temp.minPrice!! > temp.maxPrice!!) {
                Text("ราคาต่ำสุดต้องไม่เกินราคาสูงสุด", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (state.faculties.isNotEmpty()) {
                Text("คณะ (หนังสือที่ใช้ในคณะนั้น)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.faculties.forEach { f ->
                        FilterChip(
                            selected = temp.facultyId == f.id,
                            onClick = { temp = temp.copy(facultyId = if (temp.facultyId == f.id) null else f.id) },
                            label = { Text(f.nameTh.removePrefix("คณะ")) },
                        )
                    }
                }
            }

            if (state.tags.isNotEmpty()) {
                Text("แท็ก (ต้องมีครบทุกแท็กที่เลือก)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.tags.forEach { t ->
                        FilterChip(
                            selected = t.id in temp.tagIds,
                            onClick = {
                                temp = temp.copy(tagIds = if (t.id in temp.tagIds) temp.tagIds - t.id else if (temp.tagIds.size < 5) temp.tagIds + t.id else temp.tagIds)
                            },
                            label = { Text(t.nameTh) },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(
                    onClick = { temp = BrowseFilters(query = temp.query, categoryId = temp.categoryId); minText = ""; maxText = "" },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("ล้างตัวกรอง") }
                Button(
                    onClick = { onApply(temp) },
                    enabled = !(temp.minPrice != null && temp.maxPrice != null && temp.minPrice!! > temp.maxPrice!!),
                    modifier = Modifier.weight(1.4f).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(state.previewCount?.let { "แสดง $it รายการ" } ?: "แสดงผล", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
