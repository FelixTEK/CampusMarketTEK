package com.example.campusmarkettek.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.campusmarkettek.data.local.entity.DealEntity
import com.example.campusmarkettek.data.repository.ChatItem
import com.example.campusmarkettek.ui.common.formatPrice
import com.example.campusmarkettek.ui.common.imageUrl
import com.example.campusmarkettek.ui.components.AppTextField
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.components.StatusBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ACTIVE_DEAL = setOf("PENDING", "ACCEPTED", "HANDED_OVER")
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** หน้า 19 ห้องแชต: ข้อความ + การ์ดดีล + ปุ่มเสนอซื้อ (ผู้ซื้อ) — ข้อความที่พิมพ์ขึ้นห้องทันที ส่งไม่ถึงก็ส่งใหม่ให้เอง */
@Composable
fun ChatRoomScreen(
    onBack: () -> Unit,
    onOpenListing: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    viewModel: ChatRoomViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // หมุนจอแล้วกล่องที่เปิดอยู่ต้องไม่หาย: เก็บเป็นค่าที่ saveable (id/ชื่อปุ่ม) แล้วค่อยหาดีลจากรายการ
    var confirmKey by rememberSaveable { mutableStateOf<String?>(null) }   // "Accept:<dealId>"
    var meetupId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var reportMessageId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); viewModel.consumeNotice() } }

    val room = ui.room
    val deals = items.mapNotNull { it.deal }
    val dealById = deals.associateBy { it.id }
    val confirm = confirmKey?.split(":")?.let { p -> dealById[p.getOrNull(1)]?.let { d -> DealButton.valueOf(p[0]) to d } }
    val meetupFor = meetupId?.let(dealById::get)
    val reviewFor = reviewId?.let(dealById::get)
    // ดีลที่สำเร็จ → ถาม server ว่ารีวิวแล้วหรือยัง (ผู้ซื้อเห็นปุ่มให้คะแนน ผู้ขายเห็นดาวที่ได้)
    val completedIds = deals.filter { it.status == "COMPLETED" }.map { it.id }
    LaunchedEffect(completedIds) { viewModel.loadReviews(completedIds) }
    val canOffer = room != null && room.role == "BUYER" && room.listing.status == "AVAILABLE" && deals.none { it.status in ACTIVE_DEAL }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
                Column(Modifier.weight(1f).clickable(enabled = room != null) { onOpenProfile(room!!.other.id) }) {
                    Text(room?.other?.displayName ?: "แชต", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (room != null) Text(if (room.role == "BUYER") "ผู้ขาย" else "ผู้ซื้อ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            when {
                room == null && ui.loading -> LoadingView()
                room == null -> ErrorView(ui.error ?: "เปิดห้องไม่ได้", onRetry = viewModel::load, modifier = Modifier.weight(1f))
                else -> {
                    // การ์ดประกาศ (แตะเพื่อดูรายละเอียด)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onOpenListing(room.listing.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
                                if (room.listing.cover != null) AsyncImage(imageUrl(room.listing.cover), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f)) {
                                Text(room.listing.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(formatPrice(room.listing.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            StatusBadge(room.listing.status)
                        }
                    }

                    if (items.isEmpty() && !ui.hasMore) {
                        Text("ยังไม่มีข้อความ ทักทายได้เลย", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
                    }
                    // รายการข้อความ: ใหม่สุดอยู่ล่าง (reverseLayout) · เลื่อนขึ้นสุดแล้วโหลดของเก่ากว่า
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        items(items.asReversed(), key = { it.message.localId }) { item ->
                            MessageRow(
                                item = item, me = viewModel.me, myRole = room.role, busy = ui.busy,
                                ratings = ui.ratings, reviewChecked = ui.checkedReviews,
                                onReport = { reportMessageId = it },
                                onDealButton = { b, d -> if (b == DealButton.Meetup) meetupId = d.id else if (b == DealButton.Review) reviewId = d.id else if (needsConfirm(b)) confirmKey = "${b.name}:${d.id}" else runDeal(viewModel, b, d) },
                                onRetry = viewModel::retry,
                            )
                        }
                        if (ui.hasMore) {
                            item(key = "older") {
                                LaunchedEffect(Unit) { viewModel.loadOlder() }
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                            }
                        }
                    }

                    if (canOffer) {
                        Button(
                            onClick = viewModel::offer, enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) { Text("เสนอซื้อ ${formatPrice(room.listing.price)}", fontWeight = FontWeight.SemiBold) }
                    }

                    // ช่องพิมพ์
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ui.input, onValueChange = viewModel::onInput,
                            modifier = Modifier.weight(1f), placeholder = { Text("พิมพ์ข้อความ") },
                            maxLines = 4, shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        )
                        IconButton(onClick = viewModel::send, enabled = ui.input.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "ส่ง", tint = if (ui.input.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    confirm?.let { (button, deal) ->
        AlertDialog(
            onDismissRequest = { confirmKey = null },
            title = { Text(confirmTitle(button)) },
            text = { Text(confirmText(button)) },
            confirmButton = { TextButton(onClick = { runDeal(viewModel, button, deal); confirmKey = null }) { Text("ยืนยัน") } },
            dismissButton = { TextButton(onClick = { confirmKey = null }) { Text("ยกเลิก") } },
        )
    }

    reviewFor?.let { deal ->
        ReviewDialog(busy = ui.busy, onSubmit = { r, c -> viewModel.review(deal.id, r, c); reviewId = null }, onDismiss = { reviewId = null })
    }

    reportMessageId?.let { id ->
        com.example.campusmarkettek.ui.report.ReportDialog(
            "MESSAGE", id.toString(), "ข้อความ",
            onDismiss = { reportMessageId = null },
            onSent = { reportMessageId = null; viewModel.notify("ส่งรายงานแล้ว ผู้ดูแลจะตรวจสอบ") },
        )
    }

    meetupFor?.let { deal ->
        var text by rememberSaveable(deal.id) { mutableStateOf(deal.meetup.orEmpty()) }
        AlertDialog(
            onDismissRequest = { meetupId = null },
            title = { Text("ที่และเวลานัดรับ") },
            text = { AppTextField(text, { text = it.take(200) }, "เช่น โรงอาหารกลาง ส. 12:30", singleLine = false, supportingText = "${text.length} / 200") },
            confirmButton = { TextButton(onClick = { viewModel.setMeetup(deal.id, text.trim()); meetupId = null }, enabled = text.isNotBlank()) { Text("บันทึก") } },
            dismissButton = { TextButton(onClick = { meetupId = null }) { Text("ยกเลิก") } },
        )
    }
}

// ทุกการกดที่เปลี่ยนกลับไม่ได้ต้องถามก่อน (ปฏิเสธ/ถอนข้อเสนอ ผู้ซื้อต้องเสนอใหม่เอง)
private fun needsConfirm(b: DealButton) = b in setOf(DealButton.Accept, DealButton.Reject, DealButton.Withdraw, DealButton.CancelDeal, DealButton.Receive, DealButton.Handover)

private fun runDeal(vm: ChatRoomViewModel, b: DealButton, d: DealEntity) = when (b) {
    DealButton.Accept -> vm.accept(d.id)
    DealButton.Reject -> vm.reject(d.id)
    DealButton.Withdraw, DealButton.CancelDeal -> vm.cancel(d.id)
    DealButton.Handover -> vm.handover(d.id)
    DealButton.Receive -> vm.receive(d.id)
    DealButton.Meetup, DealButton.Review -> Unit // เปิดกล่องของตัวเองในหน้าจอ
}

private fun confirmTitle(b: DealButton) = when (b) {
    DealButton.Accept -> "ตกลงขาย?"
    DealButton.Reject -> "ปฏิเสธข้อเสนอ?"
    DealButton.Withdraw -> "ถอนข้อเสนอ?"
    DealButton.CancelDeal -> "ยกเลิกดีล?"
    DealButton.Receive -> "ยืนยันว่าได้รับของแล้ว?"
    DealButton.Handover -> "ส่งมอบสินค้าแล้ว?"
    else -> "ยืนยัน?"
}

private fun confirmText(b: DealButton) = when (b) {
    DealButton.Accept -> "ประกาศนี้จะถูกจองให้ผู้ซื้อคนนี้ และข้อเสนอของคนอื่นจะถูกปฏิเสธอัตโนมัติ"
    DealButton.Reject -> "ผู้ซื้อจะได้รับแจ้ง และต้องเสนอซื้อใหม่ถ้ายังสนใจ"
    DealButton.Withdraw -> "ถอนแล้วต้องเสนอซื้อใหม่ถ้ายังสนใจ"
    DealButton.CancelDeal -> "ประกาศจะกลับมากำลังขาย และผู้ซื้อ/ผู้ขายคนอื่นเสนอได้อีกครั้ง"
    DealButton.Receive -> "หลังกดจะเปลี่ยนกลับไม่ได้ ดีลจะสำเร็จและประกาศเป็น \"ขายแล้ว\""
    DealButton.Handover -> "ผู้ซื้อจะได้รับแจ้งให้กดยืนยันเมื่อได้รับของ (ไม่กดภายใน 48 ชั่วโมง ระบบปิดดีลให้)"
    else -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    item: ChatItem, me: String, myRole: String, busy: Boolean,
    ratings: Map<String, Int>, reviewChecked: Set<String>, onReport: (Long) -> Unit,
    onDealButton: (DealButton, DealEntity) -> Unit, onRetry: (String) -> Unit,
) {
    val m = item.message
    when {
        // การ์ดดีล (ข้อความที่แนบดีล): แสดงสถานะล่าสุดของดีลพร้อมปุ่ม
        m.dealId != null && item.deal != null -> DealCard(
            item.deal, myRole, busy, onClick = { onDealButton(it, item.deal) },
            rating = ratings[item.deal.id],
            canReview = myRole == "BUYER" && item.deal.status == "COMPLETED" && item.deal.id in reviewChecked && item.deal.id !in ratings,
        )

        // ข้อความระบบ (เช่น "ผู้ขายตกลงขายแล้ว")
        m.senderId == null -> Text(
            m.body, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> {
            val mine = m.senderId == me
            var menuOpen by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                Box(
                    Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                        .let { if (mine && m.sendState == "FAILED") it.clickable { onRetry(m.localId) } else it }
                        .let { if (!mine && m.serverId != null) it.combinedClickable(onClick = {}, onLongClick = { menuOpen = true }) else it }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(m.body, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    // กดค้างที่ข้อความของอีกฝ่าย → รายงานข้อความ
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("รายงานข้อความ") }, onClick = { menuOpen = false; m.serverId?.let(onReport) })
                    }
                }
                Text(
                    when {
                        mine && m.sendState == "PENDING" -> "กำลังส่ง…"
                        mine && m.sendState == "FAILED" -> "ส่งไม่สำเร็จ · แตะเพื่อลองใหม่"
                        else -> timeFormat.format(Date(m.createdAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mine && m.sendState == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
    Spacer(Modifier.size(0.dp))
}
