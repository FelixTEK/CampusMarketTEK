package com.example.campusmarkettek.ui.auth

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.campusmarkettek.BuildConfig

/**
 * หน้า 13 คำชี้แจงก่อนไปหน้าเข้าสู่ระบบของสถาบัน
 * เปิดเบราว์เซอร์ไปที่ backend (/auth/kmitl/start) → สถาบัน → backend → deep link campusmarket://auth กลับมาแอป
 */
@Composable
fun SsoLinkScreen(onBack: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
                Text("เข้าสู่ระบบด้วยบัญชี KMITL", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ระบบเราทำอะไรกับข้อมูลของคุณ", style = MaterialTheme.typography.titleSmall)
                    Text("• คุณกรอกรหัสผ่านที่หน้าของสถาบันเท่านั้น ระบบเราไม่เห็นรหัสผ่าน")
                    Text("• เราเก็บรหัสอ้างอิงบัญชีสถาบัน ชื่อ-นามสกุล (ภาษาไทย) ใช้เป็นชื่อที่แสดง คณะ และอีเมลสถาบัน (ถ้ามี)")
                    Text("• เราไม่เก็บรหัสนักศึกษา เกรด และรหัสผ่าน (แต่อีเมลแบบ 67050514@kmitl.ac.th มีรหัสนักศึกษาอยู่ในตัว)")
                    Text("• ต้องมีสถานภาพ เรียน, เปลี่ยนหลักสูตร, รออนุมัติสำเร็จการศึกษา, โอนย้ายข้ามคณะ, ครบหลักสูตร (ค้างชำระ) หรือเรียนเกินหลักสูตร จึงจะเข้าใช้งานได้")
                }
            }

            if (s.error != null) {
                Text(s.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            if (s.ssoAvailable == false) {
                Text(
                    "ระบบนี้ยังไม่ได้เปิดใช้การเข้าสู่ระบบด้วยบัญชี สจล. (ยังไม่ได้ตั้งค่า SSO ฝั่ง server) บุคคลภายนอกใช้อีเมลแทนได้",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Button(
                onClick = {
                    val url = Uri.parse(BuildConfig.API_BASE_URL + "auth/kmitl/start")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: ActivityNotFoundException) {
                        // ไม่มีเบราว์เซอร์ในเครื่อง — ไม่ทำอะไร
                    }
                },
                enabled = s.ssoAvailable == true && !s.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 0.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("ไปหน้าเข้าสู่ระบบของสถาบัน") }
        }
    }
}
