package com.example.campusmarkettek.ui.verification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.MeDto
import com.example.campusmarkettek.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VerificationStatusViewModel @Inject constructor(private val accounts: AccountRepository) : ViewModel() {
    private val _thaidAvailable = MutableStateFlow(false)
    val thaidAvailable: StateFlow<Boolean> = _thaidAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _thaidAvailable.value = accounts.config().thaidMock
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ไม่รู้ = ไม่แสดงตัวเลือกจำลอง
            }
        }
    }
}

/**
 * หน้าสถานะการยืนยันตัวตน — คนที่ยังไม่ผ่านเห็นได้แค่หน้านี้
 *   ยังไม่ส่ง   → เลือกวิธียืนยัน (บัตรประชาชน / ThaID จำลอง)
 *   PENDING    → รอผู้ดูแลตรวจ 1–2 วันทำการ
 *   REJECTED   → เห็นเหตุผล และส่งใหม่ได้
 */
@Composable
fun VerificationStatusScreen(
    me: MeDto,
    onIdCard: () -> Unit,
    onThaid: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: VerificationStatusViewModel = hiltViewModel(),
) {
    val thaid by viewModel.thaidAvailable.collectAsStateWithLifecycle()
    val request = me.verification
    val pending = request?.status == "PENDING"

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("ยืนยันตัวตนก่อนใช้งาน", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "สวัสดี ${me.displayName} บัญชีของคุณยังใช้งานตลาดไม่ได้จนกว่าจะยืนยันตัวตน เพื่อให้ทุกคนมั่นใจว่ากำลังคุยกับคนจริง",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (pending) {
                StatusCard(
                    title = "รอผู้ดูแลตรวจ",
                    body = "เราได้รับรูปบัตรของคุณแล้ว ผู้ดูแลจะตรวจภายใน 1–2 วันทำการ แล้วคุณจะเข้าใช้งานได้ทันทีเมื่อผ่าน",
                )
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("ตรวจสอบสถานะอีกครั้ง") }
            } else {
                if (request?.status == "REJECTED") {
                    StatusCard(
                        title = "ครั้งที่แล้วไม่ผ่าน",
                        body = "เหตุผล: ${request.rejectReason ?: "-"}\nส่งใหม่ได้ทันที",
                        isProblem = true,
                    )
                }
                Button(
                    onClick = onIdCard,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("ถ่ายรูปบัตรประชาชน", fontWeight = FontWeight.SemiBold)
                        Text("ผู้ดูแลตรวจภายใน 1–2 วันทำการ", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (thaid) {
                    OutlinedButton(
                        onClick = onThaid,
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("ยืนยันด้วยแอป ThaID (จำลอง)", fontWeight = FontWeight.SemiBold)
                            Text("ยืนยันทันที ใช้ตอนพัฒนาเท่านั้น", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("ออกจากระบบ") }
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String, isProblem: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isProblem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (isProblem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body)
        }
    }
}
