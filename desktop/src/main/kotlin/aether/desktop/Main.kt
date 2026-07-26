package aether.desktop

import aether.desktop.auth.ActiveAccount
import aether.desktop.auth.AuthRepository
import aether.desktop.data.ChatListEntry
import aether.desktop.data.DesktopPrefs
import aether.desktop.data.Natives
import aether.desktop.data.UiSettings
import aether.desktop.data.messagePreview
import aether.desktop.ui.AetherTheme
import aether.desktop.ui.AppIcon
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
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() {
    Natives.init()
    application {
        val prefs = remember { DesktopPrefs() }
        val auth = remember { AuthRepository(prefs) }
        var session by remember { mutableStateOf<AppSession?>(null) }
        var restoring by remember { mutableStateOf(true) }
        val typingUntil = remember { mutableStateMapOf<String, Long>() }
        val scope = rememberCoroutineScope()

        val settings = remember { UiSettings() }
        var theme by remember { mutableStateOf(settings.theme) }
        LaunchedEffect(Unit) { aether.desktop.media.UiSounds.enabled = settings.soundsEnabled }

        // Окно живёт в трее: закрытие прячет его, как в Telegram Desktop.
        var windowVisible by remember { mutableStateOf(true) }
        var windowFocused by remember { mutableStateOf(true) }
        // Геометрия окна восстанавливается между запусками.
        val saved = remember { settings.windowBounds() }
        val windowState = rememberWindowState(
            width = saved?.width?.dp ?: 1200.dp,
            height = saved?.height?.dp ?: 760.dp,
            position = if (saved != null && saved.x != Int.MIN_VALUE) {
                WindowPosition(saved.x.dp, saved.y.dp)
            } else {
                WindowPosition.PlatformDefault
            },
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        val trayState = rememberTrayState()

        var chats by remember { mutableStateOf<List<ChatListEntry>>(emptyList()) }
        val unreadTotal = chats.sumOf { it.chat.unreadCount }

        fun openSession(account: ActiveAccount) {
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

        // Список чатов на уровне приложения: из него берутся счётчик в трее и
        // уведомления о новых сообщениях, когда окно свёрнуто или не в фокусе.
        val current = session
        LaunchedEffect(current) {
            if (current == null) {
                chats = emptyList()
                return@LaunchedEffect
            }
            val seenUnread = HashMap<String, Int>()
            var primed = false
            current.store.getChatList().collect { entries ->
                chats = entries
                for (entry in entries) {
                    val peer = entry.chat.peerId.lowercase()
                    val previous = seenUnread.put(peer, entry.chat.unreadCount) ?: 0
                    val grew = entry.chat.unreadCount > previous
                    if (primed && grew && !entry.chat.isMuted) {
                        // Звук — и когда окно на виду: в Telegram так же.
                        aether.desktop.media.UiSounds.playReceived()
                        if (!windowFocused && settings.notificationsEnabled) {
                            trayState.sendNotification(
                                Notification(
                                    title = entry.chat.name,
                                    message = messagePreview(entry.lastText, "Новое сообщение"),
                                    type = Notification.Type.Info,
                                )
                            )
                        }
                    }
                }
                primed = true
            }
        }

        AppTray(
            trayState = trayState,
            unread = unreadTotal,
            onOpen = { windowVisible = true },
            onExit = {
                session?.close()
                exitApplication()
            },
        )

        Window(
            onCloseRequest = {
                if (settings.closeToTray) {
                    windowVisible = false
                } else {
                    session?.close()
                    exitApplication()
                }
            },
            visible = windowVisible,
            title = if (unreadTotal > 0) "Æther ($unreadTotal)" else "Æther",
            icon = AppIcon.window(),
            state = windowState,
            // Оконные сокращения Telegram Desktop: Ctrl+W прячет окно в трей,
            // Ctrl+Q завершает приложение целиком.
            onKeyEvent = { event ->
                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    false
                } else when {
                    event.isCtrlPressed && event.key == androidx.compose.ui.input.key.Key.W -> {
                        windowVisible = false
                        true
                    }
                    event.isCtrlPressed && event.key == androidx.compose.ui.input.key.Key.Q -> {
                        session?.close()
                        exitApplication()
                        true
                    }
                    else -> false
                }
            },
        ) {
            LaunchedEffect(window) {
                window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { windowFocused = true }
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) { windowFocused = false }
                })
            }
            // Геометрию сохраняем при изменении, а не только на выходе:
            // закрытие в трей и убийство процесса не должны её терять.
            LaunchedEffect(
                windowState.size,
                windowState.position,
                windowState.placement,
            ) {
                delay(400)
                val position = windowState.position
                settings.saveWindowBounds(
                    UiSettings.WindowBounds(
                        x = if (position.isSpecified) position.x.value.toInt() else Int.MIN_VALUE,
                        y = if (position.isSpecified) position.y.value.toInt() else Int.MIN_VALUE,
                        width = windowState.size.width.value.toInt(),
                        height = windowState.size.height.value.toInt(),
                        maximized = windowState.placement == WindowPlacement.Maximized,
                    )
                )
            }
            AetherTheme(mode = theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppContent(
                        auth = auth,
                        session = session,
                        restoring = restoring,
                        typingUntil = typingUntil,
                        settings = settings,
                        theme = theme,
                        onThemeChange = {
                            theme = it
                            settings.theme = it
                        },
                        onLoggedIn = { account -> scope.launch(Dispatchers.IO) { openSession(account) } },
                        onLogout = {
                            val active = session ?: return@AppContent
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    auth.logout(active.account)
                                    active.close()
                                }
                                session = null
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplicationScope.AppTray(
    trayState: androidx.compose.ui.window.TrayState,
    unread: Int,
    onOpen: () -> Unit,
    onExit: () -> Unit,
) {
    Tray(
        state = trayState,
        icon = AppIcon.tray(unread),
        tooltip = if (unread > 0) "Æther — непрочитанных: $unread" else "Æther",
        onAction = onOpen,
        menu = {
            Item("Открыть Æther", onClick = onOpen)
            Separator()
            Item("Выход", onClick = onExit)
        },
    )
}

@Composable
private fun AppContent(
    auth: AuthRepository,
    session: AppSession?,
    restoring: Boolean,
    typingUntil: SnapshotStateMap<String, Long>,
    settings: UiSettings,
    theme: UiSettings.ThemeMode,
    onThemeChange: (UiSettings.ThemeMode) -> Unit,
    onLoggedIn: (ActiveAccount) -> Unit,
    onLogout: () -> Unit,
) {
    when {
        restoring -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        session == null -> LoginScreen(auth = auth, onSuccess = onLoggedIn)
        else -> HomeScreen(
            session = session,
            typingUntil = typingUntil,
            settings = settings,
            theme = theme,
            onThemeChange = onThemeChange,
            onLogout = onLogout,
        )
    }
}
