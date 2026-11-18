package com.example.campusmarkettek.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.campusmarkettek.BuildConfig

/** การ์ดแสดงสถานะการเชื่อมต่อ backend + ฐานข้อมูล (เรียก GET /health) */
@Composable
fun ConnectionStatusCard(
    modifier: Modifier = Modifier,
    viewModel: HealthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("สถานะระบบ", style = MaterialTheme.typography.titleSmall)
            Text(
                BuildConfig.API_BASE_URL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val s = state) {
                HealthUiState.Loading -> CircularProgressIndicator()
                is HealthUiState.Connected -> Text(
                    "เชื่อมต่อ backend สำเร็จ · ฐานข้อมูล: ${s.database}",
                    color = MaterialTheme.colorScheme.primary,
                )
                is HealthUiState.Failed -> {
                    Text(
                        "เชื่อมต่อ backend ไม่ได้\n${s.reason}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = viewModel::check) { Text("ลองใหม่") }
                }
            }
        }
    }
}
