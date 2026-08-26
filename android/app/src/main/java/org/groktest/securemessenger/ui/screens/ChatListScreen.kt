package org.groktest.securemessenger.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.groktest.securemessenger.data.ChatListEntry
import org.groktest.securemessenger.data.SessionPrefs
import org.groktest.securemessenger.data.messagePreview
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.theme.AetherEdge
import org.groktest.securemessenger.ui.theme.AetherEdgeDim
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import org.groktest.securemessenger.ui.theme.aetherControl
import org.groktest.securemessenger.ui.theme.aetherControlContent
import org.groktest.securemessenger.ui.theme.aetherIsland
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class PendingChatToggle(
    val peerId: String,
    val action: String,
    val expected: Boolean,
    val message: String,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    chats: List<ChatListEntry>,
    activeSpace: SessionPrefs.Session?,
    spaces: List<SessionPrefs.Session>,
    onChatSelected: (String) -> Unit,
    onNewChat: () -> Unit,
    onCreateGroup: () -> Unit,
    onCreateChannel: () -> Unit,
    onAction: (String, String) -> Unit = { _, _ -> },
    onSwitchSpace: (SessionPrefs.Session) -> Unit,
    onAddServer: () -> Unit,
) {
    var createMenuOpen by remember { mutableStateOf(false) }
    var showSpaces by remember { mutableStateOf(false) }
    val appearance = LocalThemeSettings.current
    val activeListState = rememberLazyListState()
    val archiveListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val latestChats by rememberUpdatedState(chats)
    var pendingToggle by remember { mutableStateOf<PendingChatToggle?>(null) }
    // Подтверждение удаления чата: без диалога промах по свайп-кнопке стирал переписку
    var confirmDelete by remember { mutableStateOf<ChatListEntry?>(null) }
    // Просмотр архива: false — обычный список, true — архивные чаты
    var showArchive by remember { mutableStateOf(false) }

    val archivedChats = chats.filter { it.chat.isArchived && it.chat.type != 3 }
    val activeChats = chats.filter { !it.chat.isArchived || it.chat.type == 3 }

    BackHandler(enabled = showArchive) { showArchive = false }

    LaunchedEffect(pendingToggle) {
        val pending = pendingToggle ?: return@LaunchedEffect
        snapshotFlow {
            latestChats.firstOrNull { it.chat.peerId == pending.peerId }?.chat?.let { chat ->
                when (pending.action) {
                    "archive" -> chat.isArchived
                    "mute" -> chat.isMuted
                    "pin" -> chat.isPinned
                    else -> null
                }
            }
        }.filter { it == pending.expected }.first()

        val result = snackbarHostState.showSnackbar(
            message = pending.message,
            actionLabel = "Отменить",
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
        if (pendingToggle == pending) {
            if (result == SnackbarResult.ActionPerformed) onAction(pending.peerId, pending.action)
            pendingToggle = null
        }
    }

    fun runChatAction(entry: ChatListEntry, action: String) {
        val chat = entry.chat
        val feedback = when (action) {
            "archive" -> PendingChatToggle(
                chat.peerId,
                action,
                !chat.isArchived,
                if (chat.isArchived) "Чат возвращён из архива" else "Чат перемещён в архив"
            )
            "mute" -> PendingChatToggle(
                chat.peerId,
                action,
                !chat.isMuted,
                if (chat.isMuted) "Уведомления включены" else "Уведомления выключены"
            )
            "pin" -> PendingChatToggle(
                chat.peerId,
                action,
                !chat.isPinned,
                if (chat.isPinned) "Чат откреплён" else "Чат закреплён"
            )
            else -> null
        }
        if (feedback != null && pendingToggle?.peerId == chat.peerId && pendingToggle?.action == action) return
        snackbarHostState.currentSnackbarData?.dismiss()
        pendingToggle = feedback
        onAction(chat.peerId, action)
    }

    Box(modifier = Modifier.fillMaxSize()) {
            if (showArchive) {
                // Канонический топ-бар под-экрана вместо инлайн-дубля
                AetherSettingsTopBar("Архив", onBack = { showArchive = false })
            } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(appearance.edgeDimLength.value.dp)
                    .zIndex(20f)
            ) {
                AetherEdgeDim(AetherEdge.Top, Modifier.matchParentSize())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showSpaces = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            activeSpace?.serverName ?: "Aether",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "  ▾",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(AetherStyle.SmallControlSize)
                            .aetherControl(shape = CircleShape)
                            .clickable { createMenuOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Создать",
                            tint = aetherControlContent(),
                            modifier = Modifier.size(24.dp)
                        )
                        MaterialTheme(
                            colorScheme = MaterialTheme.colorScheme.copy(surfaceTint = MaterialTheme.colorScheme.surface),
                            shapes = MaterialTheme.shapes.copy(extraSmall = CircleShape)
                        ) {
                            DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Создать чат") },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                    onClick = { createMenuOpen = false; onNewChat() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Создать группу") },
                                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                                    onClick = { createMenuOpen = false; onCreateGroup() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Создать канал") },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = { createMenuOpen = false; onCreateChannel() }
                                )
                            }
                        }
                    }
                }
            }
        }
        val filteredChats = if (showArchive) archivedChats else activeChats
        val hasListContent = filteredChats.isNotEmpty() || (!showArchive && archivedChats.isNotEmpty())

        if (!hasListContent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .aetherControl(fillAlpha = 0.5f, strokeAlpha = 0.24f, shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (showArchive) Icons.Filled.Archive else Icons.Default.Email,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (showArchive) "В архиве пока пусто" else "Здесь пока пусто",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (showArchive) "Архивированные чаты появятся здесь" else "Нажмите +, чтобы начать переписку",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                LazyColumn(
                    state = if (showArchive) archiveListState else activeListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical + 8.dp,
                        bottom = appearance.edgeDimLength.value.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
                ) {
                    if (!showArchive && archivedChats.isNotEmpty()) {
                        item(key = "__archive_row__") {
                            ArchiveRow(
                                count = archivedChats.size,
                                names = archivedChats.joinToString(", ") { it.chat.name },
                                onClick = { showArchive = true }
                            )
                        }
                    }
                    items(filteredChats, key = { it.chat.peerId }) { chatWithMsg ->
                        ChatListItem(
                            chatWithMsg = chatWithMsg,
                            onClick = { onChatSelected(chatWithMsg.chat.peerId) },
                            onAction = { action ->
                                if (action == "delete") confirmDelete = chatWithMsg
                                else runChatAction(chatWithMsg, action)
                            }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = AetherStyle.DockHeight + AetherStyle.DockBottom + 8.dp)
        )

        confirmDelete?.let { entry ->
            val isSavedChat = entry.chat.type == 3
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                shape = RoundedCornerShape(AetherStyle.IslandRadius),
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(if (isSavedChat) "Очистить «Избранное»?" else "Удалить чат?") },
                text = {
                    Text(
                        if (isSavedChat)
                            "Все сохранённые сообщения будут удалены на этом устройстве."
                        else
                            "Переписка с «${entry.chat.name}» будет удалена только на этом устройстве."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onAction(entry.chat.peerId, "delete")
                        confirmDelete = null
                    }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("Отмена") }
                }
            )
        }
    }

    if (showSpaces) {
        ModalBottomSheet(onDismissRequest = { showSpaces = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text("Пространства", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                spaces.forEach { space ->
                    val selected = activeSpace?.serverId == space.serverId &&
                        activeSpace.username.equals(space.username, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                showSpaces = false
                                if (!selected) onSwitchSpace(space)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(space.serverName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${space.server.removePrefix("https://").removePrefix("http://")} · @${space.username}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(
                    onClick = {
                        showSpaces = false
                        onAddServer()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("+  Добавить сервер")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Свёрнутая строка «Архив» над списком — как в Telegram. */
@Composable
private fun ArchiveRow(count: Int, names: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(64.dp)
            .aetherIsland(fillAlpha = 0.42f, strokeAlpha = 0.24f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(AetherStyle.SmallControlSize)
                .aetherControl(fillAlpha = 0.5f, strokeAlpha = 0.24f, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Archive, contentDescription = null, tint = aetherControlContent(), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Архив", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(names, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(count.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

/** Время в списке чатов как в Telegram: сегодня — HH:mm, неделя — день недели, дальше — дата. */
private fun formatChatListTime(ts: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val locale = java.util.Locale.getDefault()
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> java.text.SimpleDateFormat("HH:mm", locale).format(java.util.Date(ts))
        now.timeInMillis - ts < 7L * 24 * 3600 * 1000 -> java.text.SimpleDateFormat("EE", locale).format(java.util.Date(ts))
        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> java.text.SimpleDateFormat("d MMM", locale).format(java.util.Date(ts))
        else -> java.text.SimpleDateFormat("dd.MM.yy", locale).format(java.util.Date(ts))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListItem(
    chatWithMsg: ChatListEntry,
    onClick: () -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isSaved = chatWithMsg.chat.type == 3
    val maxSwipePx = with(density) { (if (isSaved) 0 else -80).dp.toPx() }
    val actionWidthPx = with(density) { 80.dp.toPx() }
    var offsetX by remember { mutableStateOf(0f) } // px, синхронно за пальцем
    val swipeActive by remember { derivedStateOf { offsetX < -1f } }
    var menuOpen by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val appearance = LocalThemeSettings.current

    fun animateTo(target: Float, onFinished: (() -> Unit)? = null) {
        settleJob?.cancel()
        settleJob = coroutineScope.launch {
            if (appearance.animationsEnabled()) {
                animate(
                    offsetX,
                    target,
                    animationSpec = tween(
                        durationMillis = appearance.motionDuration(90).coerceAtMost(140),
                        easing = LinearOutSlowInEasing
                    )
                ) { v, _ -> offsetX = v }
            }
            offsetX = target
            onFinished?.invoke()
        }
    }

    DisposableEffect(Unit) {
        onDispose { settleJob?.cancel() }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .clipToBounds()
    ) {
        // Один быстрый жест вместо четырёх тесных кнопок. Остальные действия доступны
        // по долгому нажатию, а архив можно отменить через Snackbar.
        if (swipeActive) {
            val actionShape = CircleShape
            val actionColor = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(64.dp)
                    .width(80.dp)
                    .offset { IntOffset((actionWidthPx + offsetX).roundToInt(), 0) }
                    .background(
                        Brush.horizontalGradient(
                            listOf(actionColor.copy(alpha = 0f), actionColor.copy(alpha = 0.16f))
                        ),
                        actionShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (chatWithMsg.chat.isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                    contentDescription = null,
                    tint = actionColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxSize()
                .combinedClickable(
                    onClick = {
                        if (swipeActive) animateTo(0f) else onClick()
                    },
                    onLongClick = {
                        if (swipeActive) animateTo(0f) { menuOpen = true }
                        else menuOpen = true
                    }
                )
                .pointerInput(isSaved) {
                    if (!isSaved) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Свайп вправо по закрытой строке не потребляем — жест уходит
                            // родительскому HorizontalPager (перелистывание вкладок)
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                if (over < 0f || offsetX < 0f) {
                                    change.consume()
                                    offsetX = (offsetX + over).coerceIn(maxSwipePx, 0f)
                                }
                            }
                            if (drag != null) {
                                settleJob?.cancel()
                                val completed = horizontalDrag(drag.id) { change ->
                                    offsetX = (offsetX + change.positionChange().x).coerceIn(maxSwipePx, 0f)
                                    change.consume()
                                }
                                val triggered = completed && offsetX <= maxSwipePx * 0.55f
                                if (triggered) onAction("archive")
                                animateTo(0f)
                            }
                        }
                    }
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val chat = chatWithMsg.chat
            val lastText = chatWithMsg.lastText
            val lastTimestamp = chatWithMsg.lastTimestamp

            val displayName = if (isSaved) "Избранное" else chat.name

            org.groktest.securemessenger.ui.components.Avatar(
                name = chat.name,
                avatarFileId = chat.avatarFileId,
                size = 52.dp,
                type = chat.type
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName,
                            modifier = Modifier.weight(1f, fill = false),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (chat.type == 0 && !chat.statusEmoji.isNullOrBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Text(chat.statusEmoji.orEmpty(), fontSize = 15.sp, maxLines = 1)
                        }
                        if (chat.isMuted && !isSaved) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "Без звука",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (lastTimestamp != null) {
                        Spacer(Modifier.width(8.dp))
                        val time = remember(lastTimestamp) { formatChatListTime(lastTimestamp) }
                        Text(time, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val snippet = messagePreview(lastText)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val showYouPrefix = chatWithMsg.lastIsOut == true && !isSaved && lastText != null
                    Text(
                        text = if (showYouPrefix) "Вы: $snippet" else snippet,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // (#A4) Бейдж непрочитанных — как в Telegram (у замьюченных — серый)
                    if (chat.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (chat.isMuted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                                fontSize = 12.sp,
                                color = if (chat.isMuted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (chat.isPinned && !isSaved) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "Закреплён",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        if (menuOpen) Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    surface = MaterialTheme.colorScheme.surfaceVariant,
                    surfaceTint = MaterialTheme.colorScheme.surfaceVariant
                ),
                shapes = MaterialTheme.shapes.copy(extraSmall = CircleShape)
            ) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                if (!isSaved) {
                    DropdownMenuItem(
                        text = { Text(if (chatWithMsg.chat.isMuted) "Включить звук" else "Выключить звук") },
                        leadingIcon = {
                            Icon(
                                if (chatWithMsg.chat.isMuted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { menuOpen = false; onAction("mute") }
                    )
                    DropdownMenuItem(
                        text = { Text(if (chatWithMsg.chat.isArchived) "Вернуть из архива" else "Переместить в архив") },
                        leadingIcon = {
                            Icon(
                                if (chatWithMsg.chat.isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = { menuOpen = false; onAction("archive") }
                    )
                    DropdownMenuItem(
                        text = { Text(if (chatWithMsg.chat.isPinned) "Открепить" else "Закрепить") },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { menuOpen = false; onAction("pin") }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (isSaved) "Очистить сообщения" else "Удалить чат", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onAction("delete") }
                )
            }
            }
        }
    }
}
