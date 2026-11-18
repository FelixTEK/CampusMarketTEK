package com.example.campusmarkettek.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

data class Bar(val label: String, val value: Int)

private val THAI_MONTHS = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

/** "2026-11" → "พ.ย." */
fun monthLabel(yyyyMm: String): String {
    val month = yyyyMm.substringAfter('-', "").toIntOrNull() ?: return yyyyMm
    return THAI_MONTHS.getOrElse(month - 1) { yyyyMm }
}

/**
 * กราฟแท่งแนวตั้งแบบเรียบ ๆ (ไม่ใช้ไลบรารี): ความสูงแท่งเทียบกับค่าสูงสุดในกราฟ · เดือนที่เป็น 0 ยังขึ้นแท่งเตี้ยสีจาง
 * ตัวเลขอยู่เหนือแท่ง ชื่อเดือนอยู่ใต้แท่ง — อ่านค่าจริงได้ ไม่ต้องเดาจากสเกล
 */
@Composable
fun BarChart(bars: List<Bar>, modifier: Modifier = Modifier, format: (Int) -> String = { it.toString() }) {
    val max = (bars.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1)
    val plotHeight = 120.dp
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(plotHeight + 22.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { b ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(format(b.value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .fillMaxWidth(0.55f)
                            .height((plotHeight * b.value / max).coerceAtLeast(4.dp))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (b.value > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            bars.forEach { b ->
                Text(b.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** แถบสัดส่วนหนึ่งแถว (เช่น "ขายแล้ว 4") ยาวตามสัดส่วนของค่าสูงสุด */
@Composable
fun ShareBar(label: String, value: Int, max: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.weight(0.34f), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(0.5f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (value > 0) Box(Modifier.fillMaxWidth(value.toFloat() / max.coerceAtLeast(1)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
        }
        Text(value.toString(), Modifier.weight(0.16f), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
