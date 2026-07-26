package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.api.RelayApi
import aether.desktop.data.BlockStore
import aether.desktop.data.ChatListEntry
import aether.desktop.data.messagePreview
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatListPane(
    session: AppSession,
    selectedPeer: String?,
    onSelectPeer: (String) -> Unit,
    onMenuClick: () -> Unit,
    menuContent: @Composable () -> Unit,
) {
    val chats by session.store.getChatList().collectAsState()
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<RelayApi.UserSearchResult>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        val q = query.trim().removePrefix("@")
        if (q.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searchResults = runCatching {
            withContext(Dispatchers.IO) { session.api.searchDirectory(q) }
        }.getOrDefault(emptyList())
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Box {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Filled.Menu, contentDescription = "Меню")
                }
                menuContent()
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            )
        }

        if (query.trim().length >= 2) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(searchResults, key = { "s:" + it.userId }) { result ->
                    SearchResultRow(session, result) {
                        query = ""
                        scope.launch {
                            if (result.isGroup && result.publicJoin) {
                                runCatching {
                                    withContext(Dispatchers.IO) { session.api.joinGroup(result.userId) }
                                }
                            }
                            session.repository.ensureChatExists(result.userId, forceGroup = result.isGroup)
                            onSelectPeer(result.userId)
                        }
                    }
                }
                if (searchResults.isEmpty()) {
                    item {
                        Text(
                            "Никого не нашлось",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        } else {
            val sorted = remember(chats) {
                chats.filterNot { it.chat.isArchived }
                    .sortedWith(
                        compareByDescending<ChatListEntry> { it.chat.isPinned }
                            .thenByDescending { it.lastTimestamp ?: 0L }
                    )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sorted, key = { it.chat.peerId }) { entry ->
                    ChatRow(
                        session = session,
                        entry = entry,
                        selected = entry.chat.peerId.equals(selectedPeer, ignoreCase = true),
                        onClick = { onSelectPeer(entry.chat.peerId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    session: AppSession,
    result: RelayApi.UserSearchResult,
    onClick: () -> Unit,
) {
    val title = result.displayName.ifBlank { result.username.ifBlank { result.userId } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        PeerAvatar(session, result.userId, title, result.avatarFileId, size = 44.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    result.isChannel -> "канал"
                    result.isGroup -> "группа"
                    else -> "@${result.username.ifBlank { result.userId }}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatRow(
    session: AppSession,
    entry: ChatListEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val chat = entry.chat
    val scope = rememberCoroutineScope()
    val menuItems = buildList {
        add(
            ContextMenuItem(if (chat.isPinned) "Открепить" else "Закрепить") {
                scope.launch { session.store.setPinned(chat.peerId, !chat.isPinned) }
            }
        )
        add(
            ContextMenuItem(if (chat.isMuted) "Включить уведомления" else "Отключить уведомления") {
                scope.launch { session.store.setMuted(chat.peerId, !chat.isMuted) }
            }
        )
        if (chat.type == 0) {
            add(
                ContextMenuItem(if (BlockStore.isBlocked(chat.peerId)) "Разблокировать" else "Заблокировать") {
                    BlockStore.toggle(chat.peerId)
                }
            )
        }
        add(
            ContextMenuItem("Удалить чат") {
                scope.launch { session.store.deleteChatAndMessages(chat.peerId) }
            }
        )
    }

    ContextMenuArea(items = { menuItems }) {
        Surface(
            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surface,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                PeerAvatar(session, chat.peerId, chat.name, chat.avatarFileId, size = 48.dp)
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            chatDisplayName(chat.name, chat.statusEmoji),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (chat.isMuted) {
                            Icon(
                                Icons.Filled.VolumeOff,
                                contentDescription = "Без звука",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp).padding(start = 2.dp),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        entry.lastTimestamp?.let {
                            Text(
                                formatTime(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            messagePreview(entry.lastText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (chat.unreadCount > 0) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    chat.unreadCount.coerceAtMost(99).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
