package aether.desktop

import aether.desktop.auth.ActiveAccount
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
import kotlinx.coroutines.launch

fun main() {
    Natives.init()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Æther",
            state = rememberWindowState(width = 1100.dp, height = 720.dp),
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
    var account by remember { mutableStateOf<ActiveAccount?>(null) }
    var restoring by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        account = runCatching { auth.restore() }.getOrNull()
        restoring = false
    }

    when {
        restoring -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        account == null -> LoginScreen(auth = auth, onSuccess = { account = it })
        else -> HomeScreen(
            account = account!!,
            onLogout = {
                val current = account ?: return@HomeScreen
                scope.launch {
                    auth.logout(current)
                    account = null
                }
            },
        )
    }
}
