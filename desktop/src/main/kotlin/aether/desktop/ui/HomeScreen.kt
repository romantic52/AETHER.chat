package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.data.MessageEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Трёхколонка: список чатов | лента | инфо-панель. */
@Composable
fun HomeScreen(
    session: AppSession,
    typingUntil: SnapshotStateMap<String, Long>,
    settings: aether.desktop.data.UiSettings,
    theme: aether.desktop.data.UiSettings.ThemeMode,
    onThemeChange: (aether.desktop.data.UiSettings.ThemeMode) -> Unit,
    onLogout: () -> Unit,
) {
    var selectedPeer by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var forwardMessages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var showCreateGroup by remember { mutableStateOf<Boolean?>(null) } // null=скрыт, false=группа, true=канал
    var showSessions by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showSafetyFor by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // Просмотрщик живёт на уровне окна: он должен перекрывать и список, и панель информации.
    var viewerItems by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.width(320.dp).fillMaxHeight()) {
            ChatListPane(
                session = session,
                selectedPeer = selectedPeer,
                onSelectPeer = { selectedPeer = it },
                onMenuClick = { menuOpen = true },
                menuContent = {
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Профиль") },
                            onClick = { menuOpen = false; showProfile = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Избранное") },
                            onClick = { menuOpen = false; selectedPeer = session.myId },
                        )
                        DropdownMenuItem(
                            text = { Text("Новая группа") },
                            onClick = { menuOpen = false; showCreateGroup = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Новый канал") },
                            onClick = { menuOpen = false; showCreateGroup = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Устройства и сессии") },
                            onClick = { menuOpen = false; showSessions = true },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Настройки") },
                            onClick = { menuOpen = false; showSettings = true },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Выйти из аккаунта") },
                            onClick = { menuOpen = false; onLogout() },
                        )
                    }
                },
            )
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        val peer = selectedPeer
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (peer == null) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Выберите чат",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ChatPane(
                    session = session,
                    peerId = peer,
                    typingUntil = typingUntil[peer.lowercase()] ?: 0L,
                    onShowInfo = { showInfo = !showInfo },
                    onForwardRequest = { forwardMessages = it },
                    onOpenViewer = { items, index ->
                        viewerItems = items
                        viewerIndex = index
                    },
                )
            }
        }

        if (showInfo && selectedPeer != null) {
            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Box(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                InfoPane(
                    session = session,
                    peerId = selectedPeer!!,
                    onClose = { showInfo = false },
                    onShowSafetyNumbers = { showSafetyFor = selectedPeer },
                    onChatDeleted = {
                        showInfo = false
                        selectedPeer = null
                    },
                )
            }
        }
    }

        viewerIndex?.let { start ->
            MediaViewer(
                session = session,
                items = viewerItems,
                startIndex = start,
                onClose = { viewerIndex = null },
            )
        }
    }

    if (forwardMessages.isNotEmpty()) {
        ForwardDialog(
            session = session,
            messages = forwardMessages,
            onDismiss = { forwardMessages = emptyList() },
            onForwarded = { target ->
                forwardMessages = emptyList()
                selectedPeer = target
            },
        )
    }

    showCreateGroup?.let { asChannel ->
        CreateGroupDialog(
            session = session,
            isChannelInitial = asChannel,
            onDismiss = { showCreateGroup = null },
            onCreated = { gid ->
                showCreateGroup = null
                selectedPeer = gid
            },
        )
    }

    if (showSessions) {
        SessionsDialog(
            session = session,
            onDismiss = { showSessions = false },
            onAccountWiped = {
                showSessions = false
                onLogout()
            },
        )
    }

    if (showProfile) {
        ProfileDialog(session = session, onDismiss = { showProfile = false })
    }

    if (showSettings) {
        SettingsDialog(
            session = session,
            settings = settings,
            theme = theme,
            onThemeChange = onThemeChange,
            onDismiss = { showSettings = false },
        )
    }

    showSafetyFor?.let { peerId ->
        SafetyNumbersDialog(
            session = session,
            peerId = peerId,
            onDismiss = { showSafetyFor = null },
        )
    }
}
