package com.example.campusmarkettek.ui.verification

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.toUserMessage
import com.example.campusmarkettek.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** ขั้นตอนถ่ายบัตร: 1 หน้าบัตร → 2 เซลฟีถือบัตร → 3 ตรวจทานแล้วส่ง */
data class IdCardUiState(
    val card: ByteArray? = null,
    val selfie: ByteArray? = null,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val step: Int get() = when {
        card == null -> 1
        selfie == null -> 2
        else -> 3
    }
}

@HiltViewModel
class IdCardViewModel @Inject constructor(private val accounts: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(IdCardUiState())
    val state: StateFlow<IdCardUiState> = _state.asStateFlow()

    // รูปอยู่ในหน่วยความจำเท่านั้น ไม่เขียนลงดิสก์ และถูกล้างหลังส่งสำเร็จ
    fun onPhoto(bytes: ByteArray) = _state.update {
        if (it.card == null) it.copy(card = bytes, error = null) else it.copy(selfie = bytes, error = null)
    }

    fun onError(message: String) = _state.update { it.copy(error = message) }

    fun retake() = _state.update { IdCardUiState() }

    fun send(onDone: () -> Unit) {
        val s = _state.value
        val card = s.card ?: return
        val selfie = s.selfie ?: return
        if (s.sending) return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                accounts.submitIdCard(card, selfie)
                _state.value = IdCardUiState()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, error = e.toUserMessage()) }
            }
        }
    }
}

/** หน้า 14 ถ่ายรูปบัตรประชาชน (หน้าบัตร + เซลฟีถือบัตร) */
@Composable
fun IdCardScreen(onBack: () -> Unit, onSubmitted: () -> Unit, viewModel: IdCardViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
                Text("ยืนยันตัวตนด้วยบัตรประชาชน", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1 หน้าบัตร", "2 เซลฟีคู่บัตร", "3 ส่งตรวจ").forEachIndexed { i, label ->
                    AssistChip(onClick = {}, label = { Text(label) }, enabled = s.step == i + 1)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    "ใช้เพื่อยืนยันตัวตนเท่านั้น ผู้ดูแลเห็นเฉพาะตอนตรวจ และรูปถูกลบภายใน 7 วันหลังตัดสิน ถ่ายเฉพาะหน้าบัตร (ไม่ต้องถ่ายด้านหลัง)",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            when (s.step) {
                1 -> {
                    Text("ถ่ายหน้าบัตรประชาชน ให้เห็นตัวอักษรชัด ไม่เบลอ ไม่สะท้อนแสง")
                    CameraCapture(useFrontCamera = false, onCaptured = viewModel::onPhoto, onFailure = viewModel::onError)
                }
                2 -> {
                    Text("ถ่ายเซลฟีโดยถือบัตรไว้ข้างใบหน้า (กันการนำบัตรของคนอื่นมาใช้)")
                    CameraCapture(useFrontCamera = true, onCaptured = viewModel::onPhoto, onFailure = viewModel::onError)
                }
                else -> {
                    Text("ตรวจทานรูปก่อนส่ง")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Thumb(s.card!!, Modifier.weight(1f))
                        Thumb(s.selfie!!, Modifier.weight(1f))
                    }
                    Button(
                        onClick = { viewModel.send(onSubmitted) },
                        enabled = !s.sending,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text(if (s.sending) "กำลังส่ง..." else "ส่งตรวจ", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(onClick = viewModel::retake, enabled = !s.sending, modifier = Modifier.fillMaxWidth()) {
                        Text("ถ่ายใหม่ทั้งหมด")
                    }
                }
            }

            if (s.error != null) Text(s.error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Thumb(bytes: ByteArray, modifier: Modifier) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.height(150.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
}
