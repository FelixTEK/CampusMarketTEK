package com.example.campusmarkettek.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.AdminStatsDto
import com.example.campusmarkettek.data.remote.MyStatsDto
import com.example.campusmarkettek.data.remote.StatsApi
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.common.formatPrice
import com.example.campusmarkettek.ui.common.statusLabel
import com.example.campusmarkettek.ui.components.Bar
import com.example.campusmarkettek.ui.components.BarChart
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.components.ShareBar
import com.example.campusmarkettek.ui.components.monthLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

val PERIODS = listOf(3, 6, 12)

/** ตัวเลขเหนือแท่งให้สั้น: 950 → "950" · 1500 → "1.5k" · 12000 → "12k" */
fun shortMoney(v: Int): String = when {
    v < 1000 -> v.toString()
    v % 1000 == 0 -> "${v / 1000}k"
    else -> "${"%.1f".format(java.util.Locale.US, v / 1000.0)}k"
}

data class StatsUi<T>(val months: Int = 6, val content: UiState<T> = UiState.Loading)

/** โหลดสถิติตามช่วงเดือน — คำนวณที่ server ด้วย COUNT/SUM … GROUP BY แอปแค่แสดง */
private fun <T> ViewModel.loader(state: MutableStateFlow<StatsUi<T>>, fetch: suspend (Int) -> T): (Int) -> Unit = { months ->
    state.value = StatsUi(months, UiState.Loading)
    viewModelScope.launch {
        try {
            state.value = StatsUi(months, UiState.Success(fetch(months)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = StatsUi(months, UiState.Error(e.toUserMessage()))
        }
    }
}

@HiltViewModel
class MyStatsViewModel @Inject constructor(private val api: StatsApi) : ViewModel() {
    private val _state = MutableStateFlow(StatsUi<MyStatsDto>())
    val state: StateFlow<StatsUi<MyStatsDto>> = _state.asStateFlow()
    private val doLoad = loader(_state) { api.mine(it) }
    fun load(months: Int = _state.value.months) = doLoad(months)
}

@HiltViewModel
class AdminStatsViewModel @Inject constructor(private val api: StatsApi) : ViewModel() {
    private val _state = MutableStateFlow(StatsUi<AdminStatsDto>())
    val state: StateFlow<StatsUi<AdminStatsDto>> = _state.asStateFlow()
    private val doLoad = loader(_state) { api.admin(it) }
    fun load(months: Int = _state.value.months) = doLoad(months)
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PeriodChips(months: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PERIODS.forEach { FilterChip(selected = months == it, onClick = { onPick(it) }, label = { Text("$it เดือน") }) }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private val STATUS_ORDER = listOf("AVAILABLE", "RESERVED", "SOLD", "CANCELLED", "RETURNED", "TAKEN_DOWN")

/** หน้า 10 สถิติของฉัน: แท็บผู้ขาย (ประกาศที่ลงขาย) / ผู้ซื้อ · เลือกช่วง 3/6/12 เดือน · กราฟแท่งรายเดือน */
@Composable
fun MyStatsScreen(onBack: () -> Unit, viewModel: MyStatsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var asSeller by rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }

    Column(Modifier.fillMaxSize()) {
        Header("สถิติของฉัน", onBack)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = asSeller, onClick = { asSeller = true }, label = { Text("ผู้ขาย") })
            FilterChip(selected = !asSeller, onClick = { asSeller = false }, label = { Text("ผู้ซื้อ") })
        }
        when (val c = ui.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = { viewModel.load() })
            UiState.Empty -> Unit
            is UiState.Success -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PeriodChips(ui.months) { viewModel.load(it) }
                if (asSeller) SellerBody(c.data, ui.months) else BuyerBody(c.data, ui.months)
            }
        }
    }
}

@Composable
private fun SellerBody(d: MyStatsDto, months: Int) {
    val s = d.seller
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("ขายสำเร็จ (ทั้งหมด)", s.sold.toString(), Modifier.weight(1f))
        Tile("ยอดขาย (ตามราคาประกาศ)", formatPrice(s.soldValue), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("อัตราปิดการขาย", s.closeRate?.let { "$it%" } ?: "ผู้ขายใหม่", Modifier.weight(1f))
        Tile("คะแนนรีวิว", s.rating?.let { "${it.average} (${it.count})" } ?: "ยังไม่มี", Modifier.weight(1f))
    }
    Section("ยอดขายรายเดือน ($months เดือนล่าสุด)") {
        BarChart(s.monthly.map { Bar(monthLabel(it.month), it.value) }, format = ::shortMoney)
        Text(
            "ช่วงนี้ขายได้ ${s.monthly.sumOf { it.count }} ชิ้น · ${formatPrice(s.monthly.sumOf { it.value })}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Section("ประกาศของฉันตามสถานะ") {
        val max = s.byStatus.values.maxOrNull() ?: 0
        STATUS_ORDER.forEach { ShareBar(statusLabel(it), s.byStatus[it] ?: 0, max) }
    }
    Text("ยอดขายเป็นค่าประมาณจากราคาประกาศ (ราคาต่อรองจริงอยู่นอกระบบ) และนับตามเดือนที่ผู้ซื้อยืนยันรับของ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BuyerBody(d: MyStatsDto, months: Int) {
    val b = d.buyer
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("ซื้อสำเร็จ (ทั้งหมด)", b.bought.toString(), Modifier.weight(1f))
        Tile("ยอดซื้อ (ตามราคาประกาศ)", formatPrice(b.spent), Modifier.weight(1f))
    }
    Section("การซื้อรายเดือน ($months เดือนล่าสุด)") {
        BarChart(b.monthly.map { Bar(monthLabel(it.month), it.count) })
        Text("ช่วงนี้ซื้อ ${b.monthly.sumOf { it.count }} ชิ้น · ${formatPrice(b.monthly.sumOf { it.value })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Dashboard ผู้ดูแล (UC24): ภาพรวมแพลตฟอร์ม — ผู้ใช้ ประกาศตามสถานะ ดีล ยอดรวม และงานที่รอ */
@Composable
fun AdminStatsScreen(onBack: () -> Unit, viewModel: AdminStatsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }

    Column(Modifier.fillMaxSize()) {
        Header("สถิติแพลตฟอร์ม", onBack)
        when (val c = ui.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = { viewModel.load() })
            UiState.Empty -> Unit
            is UiState.Success -> {
                val d = c.data
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PeriodChips(ui.months) { viewModel.load(it) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile("ผู้ใช้ทั้งหมด", d.users.total.toString(), Modifier.weight(1f))
                        Tile("ดีลสำเร็จ", (d.deals["COMPLETED"] ?: 0).toString(), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile("มูลค่ารวม (ตามราคาประกาศ)", formatPrice(d.totalValue), Modifier.weight(1f))
                        Tile("รอผู้ดูแล: ${d.openReports} รายงาน · ${d.pendingVerifications} คำขอยืนยัน", (d.openReports + d.pendingVerifications).toString(), Modifier.weight(1f))
                    }
                    Section("ดีลที่สำเร็จรายเดือน") { BarChart(d.monthly.map { Bar(monthLabel(it.month), it.count) }) }
                    Section("ผู้ใช้ใหม่รายเดือน") { BarChart(d.newUsers.map { Bar(monthLabel(it.month), it.count) }) }
                    Section("ประกาศตามสถานะ") {
                        val max = d.listings.values.maxOrNull() ?: 0
                        STATUS_ORDER.forEach { ShareBar(statusLabel(it), d.listings[it] ?: 0, max) }
                    }
                    Section("ผู้ใช้") {
                        Text("ยืนยันตัวตนแล้ว ${d.users.verified} · คน สจล. ${d.users.kmitl} · คนนอก ${d.users.outsiders} · ถูกระงับ ${d.users.banned}")
                    }
                }
            }
        }
    }
}
