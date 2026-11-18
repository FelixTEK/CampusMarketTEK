package com.example.campusmarkettek.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.example.campusmarkettek.data.remote.BannerDto
import com.example.campusmarkettek.data.repository.BannerRepository
import com.example.campusmarkettek.ui.common.imageUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BannersViewModel @Inject constructor(private val repo: BannerRepository) : ViewModel() {
    private val _banners = MutableStateFlow<List<BannerDto>>(emptyList())
    val banners: StateFlow<List<BannerDto>> = _banners.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _banners.value = repo.live() }
    }
}

private const val AUTO_MS = 5_000L
private const val LOOP_PAGES = 10_000

/** ลิงก์ในแบนเนอร์: campusmarket://listing/<id> เปิดประกาศในแอป · https:// เปิดในเบราว์เซอร์ · อื่นไม่ทำอะไร (BR18) */
fun openBannerLink(link: String?, context: android.content.Context, onOpenListing: (String) -> Unit) {
    when {
        link == null -> Unit
        link.startsWith("campusmarket://listing/") -> link.removePrefix("campusmarket://listing/").takeIf { it.isNotBlank() }?.let(onOpenListing)
        link.startsWith("https://") -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Carousel โฆษณา 2:1: เลื่อนเอง 5 วินาที · หยุดเมื่อแตะ/ลากค้าง · วนไม่สิ้นสุด · จุดบอกหน้า · ป้าย "โฆษณา" ทุกใบ
 * ใบเดียว = ไม่เลื่อน ไม่ต้องมีจุด · ไม่มีเลย = ไม่วาดอะไร (เรียกจากข้างนอกเมื่อมีของ)
 */
@Composable
fun BannerCarousel(banners: List<BannerDto>, onOpenListing: (String) -> Unit, modifier: Modifier = Modifier) {
    if (banners.isEmpty()) return
    val n = banners.size
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = if (n > 1) LOOP_PAGES / 2 - (LOOP_PAGES / 2) % n else 0) { if (n > 1) LOOP_PAGES else 1 }
    var pressed by remember { mutableStateOf(false) }
    val dragged by pager.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(n, pressed, dragged) {
        if (n <= 1 || pressed || dragged) return@LaunchedEffect
        while (true) {
            delay(AUTO_MS)
            pager.animateScrollToPage(pager.currentPage + 1)
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(16.dp))) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                val b = banners[page % n]
                AsyncImage(
                    model = imageUrl(b.imageUrl), contentDescription = "โฆษณา", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f).pointerInput(b.id) {
                        detectTapGestures(
                            onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                            onTap = { openBannerLink(b.linkUrl, context, onOpenListing) },
                        )
                    },
                )
            }
            Text(
                "โฆษณา", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (n > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(n) { i ->
                    val on = pager.currentPage % n == i
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(if (on) 8.dp else 6.dp).clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
    }
}
