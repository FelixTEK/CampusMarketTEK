package com.example.campusmarkettek.ui.verification

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.campusmarkettek.data.remote.MeDto

/**
 * ล็อกอินแล้วแต่ยังไม่ผ่านการยืนยันตัวตน: เห็นได้เฉพาะหน้าเหล่านี้ (BR01)
 *   status → สถานะ (ยังไม่ส่ง / รอตรวจ / ถูกปฏิเสธ)   idcard → ถ่ายบัตร (หน้า 14)   thaid → ThaID จำลอง (หน้า 15)
 */
@Composable
fun VerificationNavHost(me: MeDto, onMeChanged: () -> Unit, onSignOut: () -> Unit) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "status") {
        composable("status") {
            VerificationStatusScreen(
                me = me,
                onIdCard = { nav.navigate("idcard") },
                onThaid = { nav.navigate("thaid") },
                onRefresh = onMeChanged,
                onSignOut = onSignOut,
            )
        }
        composable("idcard") {
            IdCardScreen(
                onBack = { nav.popBackStack() },
                onSubmitted = {
                    onMeChanged()
                    nav.popBackStack("status", inclusive = false)
                },
            )
        }
        composable("thaid") {
            ThaIdScreen(onBack = { nav.popBackStack() }, onVerified = onMeChanged)
        }
    }
}
