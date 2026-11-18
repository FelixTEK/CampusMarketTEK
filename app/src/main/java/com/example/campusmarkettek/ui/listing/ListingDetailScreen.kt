package com.example.campusmarkettek.ui.listing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.campusmarkettek.data.remote.ListingDetailDto
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.common.formatPrice
import com.example.campusmarkettek.ui.common.imageUrl
import com.example.campusmarkettek.ui.common.relativeThai
import com.example.campusmarkettek.ui.components.AppTextField
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.components.StatusBadge
import com.example.campusmarkettek.ui.theme.Rust

private enum class Dialog { Cancel, AdminReturn, AdminTakedown }

/** หน้า 04 รายละเอียดสินค้า — ปุ่มด้านล่างเปลี่ยนตามผู้ดูและสถานะ (เจ้าของ / ผู้ซื้อ / ผู้ดูแล) */
@Composable
fun ListingDetailScreen(
    isAdmin: Boolean,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenSeller: (String) -> Unit,
    viewModel: ListingDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
    var reporting by rememberSaveable { mutableStateOf(false) }
    var reportSent by rememberSaveable { mutableStateOf(false) }

    // กลับมาหน้านี้ (เช่นจากหน้าแก้ไข) → โหลดใหม่ ไม่ให้เห็นข้อมูลเก่า
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Box(Modifier.weight(1f))
            // หัวใจ: เฉพาะประกาศของคนอื่น (ของตัวเองไม่ต้องบันทึกโปรด)
            val loaded = (state.content as? UiState.Success)?.data
            if (loaded != null && !loaded.isOwner) {
                IconButton(onClick = { reporting = true }) {
                    Icon(Icons.Outlined.Flag, contentDescription = "รายงานประกาศ", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = viewModel::toggleFavorite) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (favorite) "เอาออกจากรายการโปรด" else "บันทึกเป็นรายการโปรด",
                        tint = if (favorite) Rust else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when (val c = state.content) {
            UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(c.message, onRetry = viewModel::load)
            UiState.Empty -> Unit
            is UiState.Success -> DetailBody(
                d = c.data,
                isAdmin = isAdmin,
                busy = state.busy,
                actionError = state.actionError,
                onEdit = { onEdit(c.data.id) },
                onCancel = { dialog = Dialog.Cancel },
                onRelist = viewModel::relist,
                onResubmit = viewModel::resubmit,
                onReturn = { dialog = Dialog.AdminReturn },
                onTakedown = { dialog = Dialog.AdminTakedown },
                onRestore = viewModel::adminRestore,
                onChat = { viewModel.openChat(onOpenChat) },
                onOpenSeller = onOpenSeller,
                reportSent = reportSent,
            )
        }
    }

    if (reporting) {
        com.example.campusmarkettek.ui.report.ReportDialog(
            "LISTING", viewModel.listingId, "ประกาศ",
            onDismiss = { reporting = false }, onSent = { reporting = false; reportSent = true },
        )
    }

    when (dialog) {
        Dialog.Cancel -> ReasonDialog(
            title = "ยกเลิกประกาศ",
            label = "เหตุผล",
            suggestions = listOf("ขายได้แล้วที่อื่น", "เปลี่ยนใจ", "ของเสีย"),
            confirm = "ยกเลิกประกาศ",
            onConfirm = { viewModel.cancel(it); dialog = null },
            onDismiss = { dialog = null },
        )
        Dialog.AdminReturn -> ReasonDialog(
            title = "ตีกลับประกาศ", label = "เหตุผล (ผู้ขายจะเห็น)", suggestions = emptyList(), confirm = "ตีกลับ",
            onConfirm = { viewModel.adminReturn(it); dialog = null }, onDismiss = { dialog = null },
        )
        Dialog.AdminTakedown -> ReasonDialog(
            title = "ถอดประกาศ", label = "เหตุผล (ผู้ขายจะเห็น)", suggestions = emptyList(), confirm = "ถอด",
            onConfirm = { viewModel.adminTakedown(it); dialog = null }, onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    d: ListingDetailDto,
    isAdmin: Boolean,
    busy: Boolean,
    actionError: String?,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onRelist: () -> Unit,
    onResubmit: () -> Unit,
    onReturn: () -> Unit,
    onTakedown: () -> Unit,
    onRestore: () -> Unit,
    onChat: () -> Unit,
    onOpenSeller: (String) -> Unit,
    reportSent: Boolean,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── รูป (ปัดซ้าย–ขวา) ──
        if (d.images.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ImageNotSupported, null, tint = MaterialTheme.colorScheme.outline)
            }
        } else {
            val pager = rememberPagerState(pageCount = { d.images.size })
            Box {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(300.dp)) { page ->
                    AsyncImage(
                        model = imageUrl(d.images[page]),
                        contentDescription = "รูปที่ ${page + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    "${pager.currentPage + 1} / ${d.images.size}",
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0x991B1F1E)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(d.status)
                d.tags.forEach { SuggestionChip(onClick = {}, label = { Text(it.nameTh) }) }
            }
            Text(d.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(formatPrice(d.price), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                d.categoryPath.joinToString(" › ") { it.nameTh } + " · ลงประกาศ ${relativeThai(d.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // เหตุผลจากผู้ดูแล/ผู้ขาย (เห็นเฉพาะเจ้าของและผู้ดูแล)
            if (d.statusReason != null) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (d.status == "CANCELLED") "เหตุผลที่ยกเลิก" else "เหตุผลจากผู้ดูแล",
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(d.statusReason, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            if (d.description.isNotBlank()) {
                Text("รายละเอียด", style = MaterialTheme.typography.titleSmall)
                Text(d.description)
            }
            if (d.faculties.isNotEmpty()) {
                Text("คณะที่ใช้เล่มนี้", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.faculties.forEach { AssistChip(onClick = {}, label = { Text(it.nameTh.removePrefix("คณะ")) }) }
                }
            }

            // ── ผู้ขาย ──
            Card(
                modifier = Modifier.clickable { onOpenSeller(d.seller.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(d.seller.displayName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    Column(Modifier.weight(1f)) {
                        Text(d.seller.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(d.seller.faculty, if (d.seller.isKmitl) "สมาชิก สจล." else "ยืนยันตัวตนแล้ว").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (actionError != null) Text(actionError, color = MaterialTheme.colorScheme.error)
            if (reportSent) Text("ส่งรายงานแล้ว ผู้ดูแลจะตรวจสอบ", color = MaterialTheme.colorScheme.primary)

            ActionButtons(d, isAdmin, busy, onEdit, onCancel, onRelist, onResubmit, onReturn, onTakedown, onRestore, onChat)
            Box(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActionButtons(
    d: ListingDetailDto, isAdmin: Boolean, busy: Boolean,
    onEdit: () -> Unit, onCancel: () -> Unit, onRelist: () -> Unit, onResubmit: () -> Unit,
    onReturn: () -> Unit, onTakedown: () -> Unit, onRestore: () -> Unit, onChat: () -> Unit,
) {
    @Composable fun primary(text: String, onClick: () -> Unit) =
        Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) { Text(text, fontWeight = FontWeight.SemiBold) }

    @Composable fun secondary(text: String, onClick: () -> Unit) =
        OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) { Text(text) }

    if (d.isOwner) {
        when (d.status) {
            "AVAILABLE" -> { secondary("แก้ไข", onEdit); secondary("ยกเลิกประกาศ", onCancel) }
            "RESERVED" -> Text("ประกาศถูกจองแล้ว แก้ไขหรือยกเลิกไม่ได้ ต้องยกเลิกการจองในหน้าดีลก่อน", color = MaterialTheme.colorScheme.onSurfaceVariant)
            "RETURNED" -> { primary("แก้ไขและส่งใหม่", onResubmit); secondary("แก้ไข", onEdit) }
            "CANCELLED" -> primary("ลงขายอีกครั้ง", onRelist)
            "SOLD" -> Text("ขายแล้ว — แก้ไขไม่ได้", color = MaterialTheme.colorScheme.onSurfaceVariant)
            "TAKEN_DOWN" -> Text("ประกาศถูกถอด ติดต่อผู้ดูแลถ้าไม่เห็นด้วย", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    if (isAdmin) {
        Text("เมนูผู้ดูแล", style = MaterialTheme.typography.titleSmall)
        when (d.status) {
            "AVAILABLE", "RESERVED" -> { secondary("ตีกลับ (ผู้ขายแก้ได้)", onReturn); secondary("ถอดประกาศ", onTakedown) }
            "RETURNED" -> secondary("ถอดประกาศ", onTakedown)
            "TAKEN_DOWN" -> primary("กู้คืนประกาศ", onRestore)
            else -> Unit
        }
    }
    if (d.status == "AVAILABLE" || d.status == "RESERVED") {
        // เสนอซื้ออยู่ในห้องแชต (ผู้ซื้อกด "เสนอซื้อ" ที่นั่น)
        primary("แชตกับผู้ขาย", onChat)
    }
}

/** กล่องกรอกเหตุผล (ยกเลิก / ตีกลับ / ถอด) — บังคับกรอก และมีคำตอบสำเร็จรูปให้แตะ */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReasonDialog(
    title: String,
    label: String,
    suggestions: List<String>,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { AssistChip(onClick = { reason = it }, label = { Text(it) }) }
                    }
                }
                AppTextField(reason, { reason = it.take(200) }, label, singleLine = false)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(reason.trim()) }, enabled = reason.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
    )
}
