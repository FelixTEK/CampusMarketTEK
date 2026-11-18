package com.example.campusmarkettek.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.example.campusmarkettek.ui.components.AppTextField

/** แถวดาว: แสดงอย่างเดียว (onPick = null) หรือแตะเลือกได้ */
@Composable
fun Stars(rating: Int, onPick: ((Int) -> Unit)? = null) {
    Row {
        (1..5).forEach { i ->
            val icon = if (i <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder
            val tint = if (i <= rating) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
            if (onPick != null) {
                IconButton(onClick = { onPick(i) }) { Icon(icon, contentDescription = "$i ดาว", tint = tint) }
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = androidx.compose.ui.Modifier.padding(end = 2.dp))
            }
        }
    }
}

/** ฟอร์มรีวิวผู้ขาย (BR12): ดาว 1–5 บังคับ · ความเห็นไม่เกิน 300 ตัว · รีวิวได้ครั้งเดียวต่อดีล */
@Composable
fun ReviewDialog(busy: Boolean, onSubmit: (rating: Int, comment: String) -> Unit, onDismiss: () -> Unit) {
    var rating by rememberSaveable { mutableIntStateOf(0) }
    var comment by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("ให้คะแนนผู้ขาย") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stars(rating, onPick = { rating = it })
                AppTextField(comment, { comment = it.take(300) }, "ความเห็น (ไม่บังคับ)", singleLine = false, supportingText = "${comment.length} / 300")
                Text("รีวิวได้ครั้งเดียวต่อดีล และแก้ไขไม่ได้", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(rating, comment) }, enabled = rating in 1..5 && !busy) { Text("ส่งรีวิว") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("ยกเลิก") } },
    )
}
