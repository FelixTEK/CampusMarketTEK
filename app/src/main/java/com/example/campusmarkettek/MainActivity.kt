package com.example.campusmarkettek

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.campusmarkettek.ui.health.HealthScreen
import com.example.campusmarkettek.ui.theme.CampusMarketTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CampusMarketTheme {
                // Phase 0: หน้าตรวจการเชื่อมต่อ backend — Phase 2 จะแทนด้วย NavGraph
                HealthScreen()
            }
        }
    }
}
