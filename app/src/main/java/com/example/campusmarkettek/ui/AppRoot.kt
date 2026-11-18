package com.example.campusmarkettek.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.campusmarkettek.ui.auth.AuthNavHost
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.navigation.MainShell
import com.example.campusmarkettek.ui.session.MeState
import com.example.campusmarkettek.ui.session.SessionViewModel
import com.example.campusmarkettek.ui.verification.VerificationNavHost

/**
 * ราก UI: ต้องล็อกอินและผ่านการยืนยันตัวตนก่อนถึงเห็นแอป (BR01)
 *   ยังไม่ล็อกอิน (หรือ 401)  → หน้าเข้าสู่ระบบ / สมัคร
 *   ล็อกอินแล้วแต่ยังไม่ยืนยัน → หน้าสถานะและส่งบัตร (ทำอย่างอื่นไม่ได้)
 *   ยืนยันแล้ว                → แถบนำทาง 5 แท็บ
 */
@Composable
fun AppRoot(viewModel: SessionViewModel = hiltViewModel()) {
    val loggedIn by viewModel.session.isLoggedIn.collectAsStateWithLifecycle()
    val meState by viewModel.me.collectAsStateWithLifecycle()

    if (!loggedIn) {
        AuthNavHost()
        return
    }

    when (val s = meState) {
        MeState.Loading -> LoadingView()

        is MeState.Failed -> Column {
            ErrorView(message = s.message, onRetry = viewModel::refreshMe, modifier = Modifier.weight(1f))
            TextButton(
                onClick = viewModel::signOut,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
            ) { Text("ออกจากระบบ") }
        }

        MeState.Banned -> BannedScreen(onSignOut = viewModel::signOut)

        is MeState.Ready ->
            if (s.me.isVerified) {
                MainShell(me = s.me, onMeChanged = viewModel::refreshMe, onSignOut = viewModel::signOut)
            } else {
                VerificationNavHost(me = s.me, onMeChanged = viewModel::refreshMe, onSignOut = viewModel::signOut)
            }
    }
}

@Composable
private fun BannedScreen(onSignOut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("บัญชีของคุณถูกระงับ", style = MaterialTheme.typography.titleLarge)
        Text(
            com.example.campusmarkettek.ui.APPEAL_HELP,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onSignOut) { Text("ออกจากระบบ") }
    }
}
