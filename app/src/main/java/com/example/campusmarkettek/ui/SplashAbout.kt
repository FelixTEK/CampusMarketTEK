package com.example.campusmarkettek.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.campusmarkettek.ui.theme.Green
import com.example.campusmarkettek.ui.theme.GreenTint
import kotlinx.coroutines.delay

private const val SPLASH_MS = 3000L

/** หน้าเปิดแอป (About ผู้จัดทำ) แสดง 3 วินาทีตอนเปิดแอปครั้งแรก แตะเพื่อข้ามได้ */
@Composable
fun SplashAbout(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(SPLASH_MS)
        onDone()
    }
    val soft = Color.White.copy(alpha = 0.8f)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Green)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone)
            .systemBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("67050514 วริษฐ์ อินทรีย์วงศ์", color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        Text("67051106 โรมัน บีเชดี้", color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Text("Presents", color = GreenTint, fontSize = 15.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text("\"CampusMarket\"", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        Text(
            "A project work of Android Mobile Application Programming, CS KMITL.",
            color = soft, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Assoc.Prof.Dr. Akan Narabin (ผศ.ดร.อัคคัญญ์ นรบิน)",
            color = soft, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
    }
}
