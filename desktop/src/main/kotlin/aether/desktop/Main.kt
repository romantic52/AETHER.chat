package aether.desktop

import aether.desktop.auth.AuthRepository
import aether.desktop.data.DesktopPrefs
import aether.desktop.data.Natives
import aether.desktop.ui.AetherTheme
import aether.desktop.ui.HomeScreen
import aether.desktop.ui.LoginScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() {
    Natives.init()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Æther",
            state = rememberWindowState(width = 1200.dp, height = 760.dp),
        ) {
            AetherTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App() {
    val prefs = remember { DesktopPrefs() }
    val auth = remember { AuthRepository(prefs) }
    var session by remember { mutableStateOf<AppSession?>(null) }
    var restoring by remember { mutableStateOf(true) }
    val typingUntil = remember { mutableStateMapOf<String, Long>() }
    val scope = rememberCoroutineScope()

    fun openSession(account: aether.desktop.auth.ActiveAccount) {
        session = AppSession.create(
            account = account,
            prefs = prefs,
            onTyping = { peer ->
                if (peer.isNotBlank()) {
                    typingUntil[peer.lowercase()] = System.currentTimeMillis() + 5_000
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        val restored = runCatching { auth.restore() }.getOrNull()
        if (restored != null) {
            withContext(Dispatchers.IO) { openSession(restored) }
        }
        restoring = false
    }

    when {
        restoring -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        session == null -> LoginScreen(
            auth = auth,
            onSuccess = { account ->
                scope.launch(Dispatchers.IO) { openSession(account) }
            },
        )
        else -> HomeScreen(
            session = session!!,
            typingUntil = typingUntil,
            onLogout = {
                val current = session ?: return@HomeScreen
                scope.launch {
                    withContext(Dispatchers.IO) {
                        auth.logout(current.account)
                        current.close()
                    }
                    session = null
                }
            },
        )
    }
}
