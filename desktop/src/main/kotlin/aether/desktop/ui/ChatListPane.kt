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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    var archiveOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val trimmedQuery = query.trim().removePrefix("@")

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

    val archived = remember(chats) { chats.filter { it.chat.isArchived }.sortedForChatList() }
    val inbox = remember(chats) { chats.filterNot { it.chat.isArchived }.sortedForChatList() }
    val archivedUnread = remember(archived) { archived.sumOf { it.chat.unreadCount } }

    // Разархивировали последний чат — папки больше нет, иначе список завис бы пустым.
    LaunchedEffect(archived.isEmpty()) { if (archived.isEmpty()) archiveOpen = false }

    val localMatches = remember(chats, trimmedQuery) {
        if (trimmedQuery.length < 2) {
            emptyList()
        } else {
            chats.filter {
                it.chat.name.contains(trimmedQuery, ignoreCase = true) ||
                    it.chat.peerId.contains(trimmedQuery, ignoreCase = true)
            }.sortedForChatList()
        }
    }
    val remoteMatches = remember(searchResults, localMatches) {
        // Уже открытый чат показан секцией выше — дубль из каталога только путает.
        searchResults.filterNot { found ->
            localMatches.any { it.chat.peerId.equals(found.userId, ignoreCase = true) }
        }
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

        // Обрыв WebSocket раньше выглядел как затишье в чатах: сообщения просто
        // переставали приходить, и понять причину было неоткуда.
        val online by session.realtime.connected.collectAsState()
        androidx.compose.animation.AnimatedVisibility(visible = !online) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "Соединение…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        if (trimmedQuery.length >= 2) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (localMatches.isNotEmpty()) {
                    item(key = "h:local") { SearchSectionHeader("Чаты") }
                    items(localMatches, key = { "c:" + it.chat.peerId }) { entry ->
                        ChatRow(
                            session = session,
                            entry = entry,
                            selected = entry.chat.peerId.equals(selectedPeer, ignoreCase = true),
                            onClick = {
                                query = ""
                                onSelectPeer(entry.chat.peerId)
                            },
                        )
                    }
                }
                if (remoteMatches.isNotEmpty()) {
                    item(key = "h:remote") { SearchSectionHeader("Глобальный поиск") }
                    items(remoteMatches, key = { "s:" + it.userId }) { result ->
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
                }
                if (localMatches.isEmpty() && remoteMatches.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Никого не нашлось",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        } else if (inbox.isEmpty() && archived.isEmpty()) {
            EmptyChatList(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (archiveOpen) {
                    item(key = "archive:back") { ArchiveHeaderRow { archiveOpen = false } }
                } else if (archived.isNotEmpty()) {
                    item(key = "archive:folder") {
                        ArchiveFolderRow(unread = archivedUnread) { archiveOpen = true }
                    }
                }
                items(if (archiveOpen) archived else inbox, key = { "c:" + it.chat.peerId }) { entry ->
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

/** Закреплённые сверху, остальные — по времени последнего сообщения. */
private fun List<ChatListEntry>.sortedForChatList(): List<ChatListEntry> = sortedWith(
    compareByDescending<ChatListEntry> { it.chat.isPinned }
        .thenByDescending { it.lastTimestamp ?: 0L }
)

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyChatList(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Text(
                "Здесь пока пусто",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Найдите собеседника через поиск сверху — по имени или @нику.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Строка-папка «Архив» в общем списке. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ArchiveFolderRow(unread: Int, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Surface(color = rowBackground(selected = false, hovered = hovered)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            ) {
                Icon(
                    Icons.Filled.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                "Архив",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            if (unread > 0) UnreadBadge(count = unread, muted = true)
        }
    }
}

/** Шапка режима архива с возвратом в общий список. */
@Composable
private fun ArchiveHeaderRow(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
        }
        Text(
            "Архив",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChatRow(
    session: AppSession,
    entry: ChatListEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val chat = entry.chat
    val scope = rememberCoroutineScope()
    var hovered by remember(chat.peerId) { mutableStateOf(false) }

    // lastIsOut ядро в списке чатов не заполняет, поэтому статус исходящего берём
    // из уже загруженного кеша сообщений — это лишь чтение in-memory StateFlow.
    // Без remember: статус меняется (отправлено -> доставлено -> прочитано) при том
    // же тексте и времени превью, и запомненное значение осталось бы устаревшим.
    // Совпадение времени отсекает случай, когда кеш отстал от превью чата.
    val outgoingStatus = session.store.cachedMessages(chat.peerId).lastOrNull()
        ?.takeIf { it.isOut && it.timestamp == entry.lastTimestamp }
        ?.status

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
        add(
            ContextMenuItem(if (chat.isArchived) "Из архива" else "В архив") {
                scope.launch { session.store.setArchived(chat.peerId, !chat.isArchived) }
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
        Surface(color = rowBackground(selected = selected, hovered = hovered)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false }
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
                        outgoingStatus?.let { status ->
                            Text(
                                when (status) {
                                    -1 -> "⚠"
                                    0 -> "🕓"
                                    1 -> "✓"
                                    else -> "✓✓"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (status) {
                                    3 -> MaterialTheme.colorScheme.primary
                                    -1 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        Text(
                            messagePreview(entry.lastText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        when {
                            chat.unreadCount > 0 -> UnreadBadge(chat.unreadCount, muted = chat.isMuted)
                            chat.isPinned -> Icon(
                                Icons.Filled.PushPin,
                                contentDescription = "Закреплён",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp).size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Счётчик непрочитанных: у чатов без звука — приглушённый, как в Telegram. */
@Composable
private fun UnreadBadge(count: Int, muted: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 6.dp)
            .defaultMinSize(minWidth = 20.dp)
            .height(20.dp)
            .clip(CircleShape)
            .background(
                if (muted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.primary
            )
            .padding(horizontal = 6.dp),
    ) {
        Text(
            count.coerceAtMost(99).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (muted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun rowBackground(selected: Boolean, hovered: Boolean): Color = when {
    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    else -> MaterialTheme.colorScheme.surface
}
