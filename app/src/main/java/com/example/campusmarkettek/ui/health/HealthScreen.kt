package com.example.campusmarkettek.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.campusmarkettek.BuildConfig

@Composable
fun HealthScreen(viewModel: HealthViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Campus Market", style = MaterialTheme.typography.headlineMedium)
            Text(
                BuildConfig.API_BASE_URL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                HealthUiState.Loading -> CircularProgressIndicator()

                is HealthUiState.Connected -> Text(
                    "เชื่อมต่อ backend สำเร็จ\nฐานข้อมูล: ${s.database}",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                )

                is HealthUiState.Failed -> {
                    Text(
                        "เชื่อมต่อ backend ไม่ได้\n${s.reason}",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = viewModel::check) { Text("ลองใหม่") }
                }
            }
        }
    }
}
