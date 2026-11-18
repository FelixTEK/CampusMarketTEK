package com.example.campusmarkettek.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.campusmarkettek.ui.common.statusLabel
import com.example.campusmarkettek.ui.theme.Ok
import com.example.campusmarkettek.ui.theme.OkTint
import com.example.campusmarkettek.ui.theme.WarnInk
import com.example.campusmarkettek.ui.theme.WarnTint

/** ป้ายสถานะของประกาศ — สีตามความหมาย: เขียว=ขายอยู่ เหลือง=ถูกจอง แดง=มีปัญหา เทา=จบแล้ว */
@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        "AVAILABLE" -> OkTint to Ok
        "RESERVED" -> WarnTint to WarnInk
        "RETURNED", "TAKEN_DOWN" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        statusLabel(status),
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun DraftBadge(label: String, isProblem: Boolean = false, modifier: Modifier = Modifier) {
    val bg: Color = if (isProblem) MaterialTheme.colorScheme.errorContainer else WarnTint
    val fg: Color = if (isProblem) MaterialTheme.colorScheme.onErrorContainer else WarnInk
    Text(
        label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
