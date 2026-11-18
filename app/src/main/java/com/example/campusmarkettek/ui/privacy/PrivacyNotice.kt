package com.example.campusmarkettek.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** ข้อมูลที่เก็บ · ใช้ทำอะไร · เก็บนานแค่ไหน — ตรงกับตารางข้อ 2.7 ของแผนงานและงานลบข้อมูลที่ backend ทำจริง (jobs/retention, purge-id-images, logger) */
private val ROWS = listOf(
    Triple("บัญชี", "ชื่อที่แสดง คณะ · คนนอกเก็บอีเมลเป็นชื่อล็อกอิน (รหัสผ่านเก็บเป็นแฮช) · คน สจล. เก็บรหัสอ้างอิงจาก SSO (ไม่เก็บรหัสผ่าน) ใช้ชื่อ-นามสกุลภาษาไทยจากสถาบันเป็นชื่อที่แสดง (เปลี่ยนเองได้) และอีเมลสถาบันถ้ามี", "จนกว่าคุณลบบัญชี"),
    Triple("ประกาศ ดีล รีวิว รายการโปรด", "เพื่อให้ซื้อขายกันได้", "จนกว่าคุณลบบัญชี"),
    Triple("ข้อความแชต", "คุยกันเรื่องสินค้า (ผู้ดูแลเห็นเฉพาะข้อความที่ถูกรายงาน)", "1 ปีหลังข้อความล่าสุดของห้อง"),
    Triple("รูปบัตร / เซลฟี", "ยืนยันตัวตนของคนนอก · เข้ารหัสและเก็บแยก", "ลบ 7 วันหลังผู้ดูแลตัดสิน"),
    Triple("ค่าแฮชของเลขบัตร", "กัน 1 คนมีหลายบัญชี (ไม่เก็บเลขจริง ย้อนกลับไม่ได้)", "จนกว่าคุณลบบัญชี"),
    Triple("ไฟล์ log การเข้าใช้", "ตามกฎหมายคอมพิวเตอร์ (เวลา ที่อยู่คำขอ บัญชี) — ไม่มีรหัสผ่านและเนื้อหา", "90 วัน"),
    Triple("รายงานและหลักฐาน", "ตรวจเรื่องที่ถูกรายงาน เก็บสำเนา ณ เวลานั้น", "จนปิดเรื่อง + 1 ปี"),
)

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

/** ประกาศความเป็นส่วนตัวฉบับภาษาง่าย (PDPA ม.23) — ใช้ทั้งหน้า 17 และกล่องจากหน้าล็อกอิน */
@Composable
fun PrivacyNoticeContent(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("แอปนี้เก็บอะไรเกี่ยวกับคุณ และเก็บนานแค่ไหน", style = MaterialTheme.typography.bodyMedium)
        ROWS.forEach { (what, why, keep) ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(what, fontWeight = FontWeight.SemiBold)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row { Text("เก็บ: $keep", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
        Heading("สิ่งที่ไม่เก็บ")
        Text("เบอร์โทร รหัสนักศึกษา รูปโปรไฟล์ ผลการเรียน (บัญชี สจล. เก็บชื่อ-นามสกุลไทยเป็นชื่อที่แสดง และถ้าอีเมลแบบ รหัสนักศึกษา@kmitl.ac.th ก็มีรหัสอยู่ในอีเมลนั้น) · ไม่ทำการจดจำใบหน้า (เซลฟี่ใช้ให้ผู้ดูแลเทียบกับบัตรด้วยตาเท่านั้น)")
        Heading("สิทธิของคุณ")
        Text("• ขอสำเนาข้อมูลทั้งหมดเป็นไฟล์ JSON — กดดาวน์โหลดได้ทันทีในหน้านี้\n• แก้ชื่อที่แสดงและคณะได้ที่แท็บ \"ฉัน\"\n• ลบบัญชี — ล้างข้อมูลระบุตัวตนทั้งหมด (ลบไม่ได้ถ้ายังมีดีลที่ไม่จบ)\n• คัดค้านหรือขอให้แก้ไขข้อมูลอื่น ๆ — แจ้งผู้ดูแลระบบ ตอบภายใน 30 วัน")
        Heading("หลังลบบัญชี")
        Text("ประกาศที่ยังไม่ขายถูกยกเลิกและลบรูป · ข้อความที่คุณเคยส่งยังอยู่ให้อีกฝ่ายเห็นตามระยะข้างบน แต่แสดงเป็น \"ผู้ใช้ที่ลบบัญชีแล้ว\" · สมัครใหม่ด้วยอีเมลหรือบัตรเดิมได้")
        Heading("ความปลอดภัย")
        Text("รหัสผ่านเก็บเป็นแฮช · รูปบัตรเข้ารหัส · ถ้าเกิดข้อมูลรั่วไหล เราจะแจ้งหน่วยงานที่เกี่ยวข้องภายใน 72 ชั่วโมงและแจ้งคุณถ้ามีความเสี่ยงสูง")
        Heading("อายุ")
        Text("คนนอกสถาบันต้องมีอายุ 20 ปีขึ้นไป (ผู้ดูแลตรวจจากบัตรตอนยืนยันตัวตน)")
    }
}

@Composable
fun PrivacyNoticeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ประกาศความเป็นส่วนตัว") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { PrivacyNoticeContent() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
    )
}
