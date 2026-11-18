package com.example.campusmarkettek.ui.admin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.campusmarkettek.ui.common.UiState
import com.example.campusmarkettek.ui.components.AppTextField
import com.example.campusmarkettek.ui.components.EmptyView
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** คิวคำขอยืนยันตัวตนของคนนอก — เฉพาะผู้ดูแล */
@Composable
fun AdminQueueScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: AdminQueueViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // กลับมาจากหน้าตรวจ (อนุมัติ/ปฏิเสธแล้ว) → โหลดคิวใหม่ รายการที่ตัดสินแล้วจะหายไป
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("ตรวจยืนยันตัวตน", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        when (val s = state) {
            UiState.Loading -> LoadingView()
            UiState.Empty -> EmptyView(Icons.Outlined.VerifiedUser, "ไม่มีคำขอที่รอตรวจ", "คำขอใหม่จะแสดงที่นี่ (เก่าสุดก่อน)")
            is UiState.Error -> ErrorView(s.message, onRetry = viewModel::load)
            is UiState.Success -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(s.data, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(item.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "ส่งเมื่อ ${item.createdAt.take(16).replace('T', ' ')} (UTC)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * หน้า 16 ตรวจคำขอ — ห้ามบันทึกหน้าจอ (FLAG_SECURE) ตลอดเวลาที่เห็นรูปบัตร
 * ผู้ดูแลอ่านเลขบัตรจากรูปแล้วกรอก ระบบเก็บเฉพาะค่าแฮช
 */
@Composable
fun AdminReviewScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: AdminReviewViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var rejecting by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            Text("ตรวจคำขอ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.loading) {
                LoadingView(Modifier.height(300.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("หน้าบัตร" to s.card, "เซลฟีคู่บัตร" to s.selfie).forEach { (label, bitmap) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = label,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
                                )
                            } else {
                                Text("ไม่มีรูป", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Text(
                    "ตรวจ: เซลฟีตรงกับรูปบนบัตร · อายุ 20 ปีขึ้นไป · ตัวอักษรบนบัตรอ่านได้ชัด\nแล้วกรอกเลขบัตร 13 หลักที่อ่านได้ (ระบบแฮชทันที ไม่เก็บเลขจริง ใช้กันสมัครซ้ำ)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppTextField(
                    value = s.nationalId,
                    onValueChange = viewModel::onNationalId,
                    label = "เลขบัตรประชาชน 13 หลัก",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )
                if (s.error != null) Text(s.error!!, color = MaterialTheme.colorScheme.error)

                Button(
                    onClick = { viewModel.approve(onDone) },
                    enabled = !s.busy && s.card != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("อนุมัติ", fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    onClick = { rejecting = true },
                    enabled = !s.busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("ปฏิเสธ") }
            }
        }
    }

    if (rejecting) {
        AlertDialog(
            onDismissRequest = { rejecting = false },
            title = { Text("ปฏิเสธคำขอ") },
            text = {
                AppTextField(
                    value = reason,
                    onValueChange = { reason = it.take(300) },
                    label = "เหตุผล (ผู้ใช้จะเห็น)",
                    singleLine = false,
                    supportingText = "เช่น รูปไม่ชัด / เซลฟีไม่ตรงกับบัตร",
                )
            },
            confirmButton = {
                TextButton(onClick = { rejecting = false; viewModel.reject(reason, onDone) }, enabled = reason.isNotBlank()) {
                    Text("ปฏิเสธ")
                }
            },
            dismissButton = { TextButton(onClick = { rejecting = false }) { Text("ยกเลิก") } },
        )
    }
}
