package com.example.campusmarkettek.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.campusmarkettek.data.local.entity.DealEntity
import com.example.campusmarkettek.ui.components.DraftBadge

fun dealStatusLabel(status: String) = when (status) {
    "PENDING" -> "รอผู้ขายตอบ"
    "ACCEPTED" -> "ตกลงขายแล้ว"
    "HANDED_OVER" -> "ส่งมอบแล้ว"
    "COMPLETED" -> "ดีลสำเร็จ"
    "REJECTED" -> "ถูกปฏิเสธ"
    "CANCELLED" -> "ยกเลิกแล้ว"
    else -> status
}

/** การกดบนการ์ดดีล — หน้าห้องแชตเป็นผู้ถามยืนยันและเรียก server */
enum class DealButton { Accept, Reject, Withdraw, CancelDeal, Handover, Receive, Meetup, Review }

/**
 * การ์ดดีลในห้องแชต (ปิดดีลด้วยการกดการ์ด: ผู้ขาย 1 ครั้ง ผู้ซื้อ 1 ครั้ง)
 *   PENDING     ผู้ขาย: ตกลงขาย / ปฏิเสธ · ผู้ซื้อ: ถอนข้อเสนอ
 *   ACCEPTED    ทั้งคู่: ระบุนัดรับ / ยกเลิกดีล · ผู้ขาย: ส่งมอบแล้ว
 *   HANDED_OVER ผู้ซื้อ: ได้รับแล้ว (ไม่กดภายใน 48 ชม. ระบบปิดให้)
 */
@Composable
fun DealCard(
    deal: DealEntity, myRole: String, busy: Boolean, onClick: (DealButton) -> Unit, modifier: Modifier = Modifier,
    /** ดาวที่ผู้ซื้อให้ดีลนี้ (null = ยังไม่รีวิว หรือยังโหลดไม่เสร็จ) */
    rating: Int? = null,
    /** ผู้ซื้อของดีลที่สำเร็จ และเช็กแล้วว่ายังไม่เคยรีวิว */
    canReview: Boolean = false,
) {
    val seller = myRole == "SELLER"
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ข้อเสนอซื้อ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                DraftBadge(
                    dealStatusLabel(deal.status),
                    isProblem = deal.status == "REJECTED" || deal.status == "CANCELLED",
                )
            }
            if (!deal.meetup.isNullOrBlank()) {
                Text("นัดรับ: ${deal.meetup}", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            @Composable fun primary(text: String, b: DealButton) =
                Button(onClick = { onClick(b) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(text) }

            @Composable fun secondary(text: String, b: DealButton) =
                OutlinedButton(onClick = { onClick(b) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(text) }

            when (deal.status) {
                "PENDING" ->
                    if (seller) {
                        primary("ตกลงขาย", DealButton.Accept)
                        secondary("ปฏิเสธ", DealButton.Reject)
                    } else {
                        Text("รอผู้ขายตอบ", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        secondary("ถอนข้อเสนอ", DealButton.Withdraw)
                    }
                "ACCEPTED" -> {
                    if (seller) primary("ส่งมอบแล้ว", DealButton.Handover)
                    secondary(if (deal.meetup.isNullOrBlank()) "ระบุที่และเวลานัดรับ" else "แก้ไขนัดรับ", DealButton.Meetup)
                    secondary("ยกเลิกดีล", DealButton.CancelDeal)
                }
                "COMPLETED" ->
                    if (rating != null) {
                        Text(if (seller) "ผู้ซื้อให้คะแนน" else "คุณให้คะแนนผู้ขาย", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Stars(rating)
                    } else if (canReview) primary("ให้คะแนนผู้ขาย", DealButton.Review)
                "HANDED_OVER" ->
                    if (seller) Text("รอผู้ซื้อกดยืนยันว่าได้รับของ (ไม่กดภายใน 48 ชั่วโมง ระบบปิดดีลให้)", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    else primary("ได้รับแล้ว", DealButton.Receive)
            }
        }
    }
}
