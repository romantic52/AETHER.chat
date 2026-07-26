package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.groktest.securemessenger.data.ChatListEntry
import org.groktest.securemessenger.data.messagePreview
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.glass.glassSource
import org.groktest.securemessenger.ui.theme.AetherEdge
import org.groktest.securemessenger.ui.theme.AetherEdgeDim
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import org.groktest.securemessenger.ui.theme.aetherCircle
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    myId: String,
    chats: List<ChatListEntry>,
    onChatSelected: (String) -> Unit,
    onLogout: () -> Unit,
    onNewChat: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onCreateGroup: () -> Unit,
    onCreateChannel: () -> Unit,
    onCallsClick: () -> Unit = {},
    onAction: (String, String) -> Unit = { _, _ -> } // peerId, action ("mute", "archive", "pin", "delete")
) {
    var createMenuOpen by remember { mutableStateOf(false) }
    val appearance = LocalThemeSettings.current
    val listState = rememberLazyListState()
    // Подтверждение удаления чата: без диалога промах по свайп-кнопке стирал переписку
    var confirmDelete by remember { mutableStateOf<ChatListEntry?>(null) }
    // Только одна строка со свайп-кнопками может быть открыта (как в Telegram)
    var openSwipeKey by remember { mutableStateOf<String?>(null) }
    // Просмотр архива: false — обычный список, true — архивные чаты
    var showArchive by remember { mutableStateOf(false) }

    val archivedChats = chats.filter { it.chat.isArchived && it.chat.type != 3 }
    val activeChats = chats.filter { !it.chat.isArchived || it.chat.type == 3 }
    // Если из архива удалили/разархивировали последний чат — возвращаемся к списку
    LaunchedEffect(archivedChats.isEmpty()) {
        if (archivedChats.isEmpty()) showArchive = false
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
                    Text(
                        "Aether",
                        modifier = Modifier.weight(1f),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        modifier = Modifier.aetherIsland(
                            shape = RoundedCornerShape(AetherStyle.PillRadius),
                            fillAlpha = AetherStyle.SoftIslandFillAlpha,
                            strokeAlpha = AetherStyle.SearchStrokeAlpha
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onNewChat, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.Person, contentDescription = "Контакты", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onCallsClick, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.Phone, contentDescription = "Звонки", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onSearchClick, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.Search, contentDescription = "Поиск", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Box(contentAlignment = Alignment.TopEnd) {
                            IconButton(onClick = { createMenuOpen = true }, modifier = Modifier.size(42.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Создать", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            }
                            // В material3 1.2.0 у DropdownMenu нет shape/containerColor —
                            // задаём скругление и чистый surface через локальную тему
                            MaterialTheme(
                                colorScheme = MaterialTheme.colorScheme.copy(surfaceTint = MaterialTheme.colorScheme.surface),
                                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(20.dp))
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
            }
        val filteredChats = if (showArchive) archivedChats else activeChats

        if (filteredChats.isEmpty()) {
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
                            .aetherCircle(fillAlpha = 0.5f, strokeAlpha = 0.24f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Email,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Здесь пока пусто", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Нажмите +, чтобы начать переписку",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().glassSource(),
                    contentPadding = PaddingValues(
                        top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical + 8.dp,
                        bottom = appearance.edgeDimLength.value.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
                ) {
                    if (!showArchive && archivedChats.isNotEmpty()) {
                        item(key = "__archive_row__") {
                            ArchiveRow(
                                modifier = Modifier.animateItemPlacement(tween(appearance.motionDuration(260))),
                                count = archivedChats.size,
                                names = archivedChats.joinToString(", ") { it.chat.name },
                                onClick = { openSwipeKey = null; showArchive = true }
                            )
                        }
                    }
                    items(filteredChats, key = { it.chat.peerId }) { chatWithMsg ->
                        ChatListItem(
                            modifier = Modifier.animateItemPlacement(tween(appearance.motionDuration(260))),
                            chatWithMsg = chatWithMsg,
                            onClick = { onChatSelected(chatWithMsg.chat.peerId) },
                            onAction = { action ->
                                if (action == "delete") confirmDelete = chatWithMsg
                                else onAction(chatWithMsg.chat.peerId, action)
                            },
                            swipeOpen = openSwipeKey == chatWithMsg.chat.peerId,
                            onSwipeOpenChanged = { open ->
                                openSwipeKey = if (open) chatWithMsg.chat.peerId else openSwipeKey.takeIf { it != chatWithMsg.chat.peerId }
                            }
                        )
                    }
                }
            }
        }

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
}

/** Свёрнутая строка «Архив» над списком — как в Telegram. */
@Composable
private fun ArchiveRow(count: Int, names: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(AetherStyle.SmallControlSize)
                .aetherCircle(fillAlpha = 0.5f, strokeAlpha = 0.24f),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Архив", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(names, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(count.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
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
    modifier: Modifier = Modifier,
    swipeOpen: Boolean = false,
    onSwipeOpenChanged: (Boolean) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isSaved = chatWithMsg.chat.type == 3
    val maxSwipePx = with(density) { (if (isSaved) 0 else -240).dp.toPx() }
    var offsetX by remember { mutableStateOf(0f) } // px, синхронно за пальцем
    var menuOpen by remember { mutableStateOf(false) }
    val appearance = LocalThemeSettings.current

    fun animateTo(target: Float) {
        coroutineScope.launch {
            animate(offsetX, target, animationSpec = tween(appearance.motionDuration(180), easing = FastOutSlowInEasing)) { v, _ -> offsetX = v }
        }
        onSwipeOpenChanged(target != 0f)
    }

    // Другая строка открылась (или список сменился) — закрываем эту
    LaunchedEffect(swipeOpen) {
        if (!swipeOpen && offsetX != 0f) {
            animate(offsetX, 0f, animationSpec = tween(appearance.motionDuration(180), easing = FastOutSlowInEasing)) { v, _ -> offsetX = v }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
    ) {
        // Background Actions (Mute, Archive, Pin, Delete) — рисуем только при свайпе,
        // чтобы прозрачные строки Liquid Glass их не просвечивали
        if (offsetX < -1f) {
        val isArchivedNow = chatWithMsg.chat.isArchived
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val buttons = listOf(
                Pair("mute", MaterialTheme.colorScheme.onSurfaceVariant),
                Pair("archive", MaterialTheme.colorScheme.primary),
                Pair("pin", MaterialTheme.colorScheme.tertiary),
                Pair("delete", MaterialTheme.colorScheme.error)
            )
            buttons.forEach { (action, color) ->
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when(action) {
                        "mute" -> Icons.Filled.VolumeOff
                        "archive" -> if (isArchivedNow) Icons.Filled.Unarchive else Icons.Filled.Archive
                        "pin" -> Icons.Filled.PushPin
                        "delete" -> Icons.Filled.Delete
                        else -> Icons.Filled.Delete
                    }
                    Box(
                        modifier = Modifier
                            .size(AetherStyle.SmallControlSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f))
                            .border(AetherStyle.Stroke, color.copy(alpha = 0.85f), CircleShape)
                            .clickable {
                                onAction(action)
                                animateTo(0f)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = action, tint = color)
                    }
                }
            }
        }
        }

        // Foreground Chat Row — ВСЕГДА непрозрачный фон, чтобы кнопки под ним были
        // спрятаны и открывались только когда строка уезжает (а не просвечивали сразу).
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxSize()
                .background(if (offsetX < -1f) MaterialTheme.colorScheme.background else Color.Transparent)
                .combinedClickable(
                    onClick = {
                        // Тап по свайпнутой строке закрывает кнопки, а не открывает чат
                        if (offsetX < -1f) animateTo(0f) else onClick()
                    },
                    onLongClick = {
                        if (offsetX < -1f) animateTo(0f)
                        menuOpen = true
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
                                // Начали тянуть эту строку — остальные открытые закрываются
                                onSwipeOpenChanged(true)
                                horizontalDrag(drag.id) { change ->
                                    offsetX = (offsetX + change.positionChange().x).coerceIn(maxSwipePx, 0f)
                                    change.consume()
                                }
                                val target = if (offsetX < maxSwipePx / 2) maxSwipePx else 0f
                                animateTo(target)
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
                            Text(chat.statusEmoji!!, fontSize = 15.sp, maxLines = 1)
                        }
                        if (chat.isMuted && !isSaved) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.VolumeOff,
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
    }
}
