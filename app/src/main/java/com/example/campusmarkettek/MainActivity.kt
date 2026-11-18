package com.example.campusmarkettek

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.campusmarkettek.data.auth.AuthEvents
import com.example.campusmarkettek.data.auth.NavRequests
import com.example.campusmarkettek.work.EXTRA_ROUTE
import com.example.campusmarkettek.data.auth.SsoResult
import com.example.campusmarkettek.ui.AppRoot
import com.example.campusmarkettek.ui.theme.CampusMarketTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authEvents: AuthEvents
    @Inject lateinit var navRequests: NavRequests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            CampusMarketTheme {
                AppRoot()
            }
        }
    }

    // launchMode=singleTop: deep link ตอนแอปเปิดอยู่จะเข้ามาที่นี่
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /** campusmarket://auth?code=… (สำเร็จ) หรือ ?error=… (ไม่ผ่าน) ที่ backend ส่งกลับหลัง SSO */
    private fun handleDeepLink(intent: Intent?) {
        // แตะแจ้งเตือนของระบบ → ไปหน้าที่เกี่ยวข้อง (ใช้แล้วลบ extra กันพาไปซ้ำตอนหมุนจอ)
        intent?.getStringExtra(EXTRA_ROUTE)?.let { navRequests.post(it); intent.removeExtra(EXTRA_ROUTE) }
        val uri = intent?.data ?: return
        if (uri.scheme == "campusmarket" && uri.host == "listing") {
            // campusmarket://listing/<id> → หน้าประกาศ (ตรวจรูปแบบ id ก่อน กันลิงก์แปลกเข้า navigate)
            uri.pathSegments.firstOrNull()?.takeIf { Regex("[0-9a-fA-F-]{36}").matches(it) }?.let { navRequests.post("listing/$it") }
            setIntent(Intent())
            return
        }
        if (uri.scheme != "campusmarket" || uri.host != "auth") return
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        when {
            code != null -> authEvents.post(SsoResult.Code(code))
            error != null -> authEvents.post(SsoResult.Failed(error))
        }
        setIntent(Intent()) // ใช้แล้วทิ้ง กันประมวลผลซ้ำตอนหมุนจอ
    }
}
