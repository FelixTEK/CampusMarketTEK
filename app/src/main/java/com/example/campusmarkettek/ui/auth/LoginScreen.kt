package com.example.campusmarkettek.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.campusmarkettek.ui.components.AppTextField

/** หน้า 01 เข้าสู่ระบบ: คน สจล. ผ่าน SSO · คนนอกใช้อีเมล + รหัสผ่าน · ไม่มีโหมดผู้เยี่ยมชม */
@Composable
fun LoginScreen(
    onSso: () -> Unit,
    onRegister: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("CM", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                }
                Column {
                    Text("Campus Market", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "ตลาดมือสองของชาว สจล.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "ต้องเข้าสู่ระบบก่อนใช้งาน เพื่อให้ทุกคนรู้ว่ากำลังคุยกับคนจริง",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 22.dp, bottom = 16.dp),
            )

            // 1) คน สจล.
            Button(
                onClick = onSso,
                enabled = !s.loading,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Outlined.School, contentDescription = null, modifier = Modifier.size(26.dp))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("เข้าสู่ระบบด้วยบัญชี KMITL", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("นักศึกษาและบุคลากร สจล.", fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HorizontalDivider(Modifier.weight(1f))
                Text("บุคคลภายนอก", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.weight(1f))
            }

            // 2) คนนอก: อีเมล + รหัสผ่าน
            AppTextField(value = s.email, onValueChange = viewModel::onEmail, label = "อีเมล", keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(10.dp))
            AppTextField(value = s.password, onValueChange = viewModel::onPassword, label = "รหัสผ่าน", password = true)

            if (s.error != null) {
                Text(
                    s.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = viewModel::login,
                enabled = !s.loading,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (s.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("เข้าสู่ระบบ", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // 3) สมัครคนนอก + 4) ลืมรหัสผ่าน
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ยังไม่มีบัญชี?", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRegister) { Text("สมัครด้วยบัตรประชาชน หรือ ThaID", fontWeight = FontWeight.SemiBold) }
            }
            Text(
                "ลืมรหัสผ่าน? ติดต่อผู้ดูแลระบบ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))
            var showPrivacy by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            Text(
                "เมื่อดำเนินการต่อ ถือว่าคุณได้อ่านประกาศความเป็นส่วนตัวแล้ว",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            )
            androidx.compose.material3.TextButton(onClick = { showPrivacy = true }, modifier = Modifier.fillMaxWidth()) { Text("อ่านประกาศความเป็นส่วนตัว") }
            if (showPrivacy) com.example.campusmarkettek.ui.privacy.PrivacyNoticeDialog(onDismiss = { showPrivacy = false })
        }
    }
}
