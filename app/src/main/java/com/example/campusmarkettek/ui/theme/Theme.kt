package com.example.campusmarkettek.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenTint,
    onPrimaryContainer = GreenDark,
    secondary = InkSoft,
    onSecondary = Color.White,
    secondaryContainer = GreenTint,          // ชิปที่เลือก, ตัวไฮไลต์แท็บล่าง
    onSecondaryContainer = GreenDark,
    tertiary = Rust,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkSoft,
    surfaceContainer = Color.White,
    surfaceContainerLow = Paper,
    surfaceContainerHigh = Color.White,      // กล่องโต้ตอบ/เมนู
    surfaceContainerHighest = PaperDeep,
    outline = Line,
    outlineVariant = LineSoft,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FD3BC),
    onPrimary = Color(0xFF00382D),
    primaryContainer = GreenDark,
    onPrimaryContainer = GreenTint,
    tertiary = Color(0xFFFFB68F),
    secondaryContainer = GreenDark,
    onSecondaryContainer = GreenTint,
    background = Color(0xFF121614),
    onBackground = Color(0xFFE6E9E7),
    surface = Color(0xFF121614),
    onSurface = Color(0xFFE6E9E7),
    surfaceVariant = Color(0xFF2A302D),
    onSurfaceVariant = Color(0xFFB7BEBB),
    surfaceContainer = Color(0xFF1B201E),
    surfaceContainerHigh = Color(0xFF232826),
    surfaceContainerHighest = Color(0xFF2A302D),
    outline = Color(0xFF4A524F),
    outlineVariant = Color(0xFF353B38),
)

// มุมโค้งตาม mockup: ปุ่ม/ช่องกรอก 12, การ์ด 16
private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

/**
 * ธีมของทั้งแอป — กำหนดสี/รูปทรงครั้งเดียวที่นี่ (เทียบได้กับตัวแปร CSS ที่ :root)
 * ฟอนต์ใช้ฟอนต์ระบบ ซึ่งบน Android คือ Noto Sans Thai อยู่แล้ว จึงไม่ต้องฝังไฟล์ฟอนต์
 * ปิด Dynamic Color ไว้เพื่อให้สีแบรนด์ตรงกับ mockup เสมอ
 */
@Composable
fun CampusMarketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
