package com.example.campusmarkettek.ui.auth

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/** หน้าก่อนล็อกอิน: เข้าสู่ระบบ (หน้า 01) → SSO (หน้า 13) หรือ สมัครคนนอก */
@Composable
fun AuthNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "login") {
        composable("login") {
            LoginScreen(onSso = { nav.navigate("sso") }, onRegister = { nav.navigate("register") })
        }
        composable("sso") { SsoLinkScreen(onBack = { nav.popBackStack() }) }
        composable("register") { RegisterScreen(onBack = { nav.popBackStack() }) }
    }
}
