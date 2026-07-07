package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Archive
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
import org.groktest.securemessenger.data.ChatListEntry
import org.groktest.securemessenger.ui.components.GlassBackground
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
    chatListFlow: kotlinx.coroutines.flow.Flow<List<ChatListEntry>>,
    onChatSelected: (String) -> Unit,
    onLogout: () -> Unit,
    onNewChat: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onAction: (String, String) -> Unit = { _, _ -> } // peerId, action ("mute", "archive", "pin", "delete")
) {
    val chats by chatListFlow.collectAsState(initial = emptyList())
    var currentTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier.background(Color.Transparent)
                ) {
                    TopAppBar(
                        title = { Text("Aether", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 12.dp)
                            .height(50.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f), CircleShape)
                            .clickable(onClick = onSearchClick),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Поиск...", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChat,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Новый чат")
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        val filteredChats = chats

        if (filteredChats.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
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
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    items(filteredChats, key = { it.chat.peerId }) { chatWithMsg ->
                        ChatListItem(
                            modifier = Modifier.animateItemPlacement(tween(260)),
                            chatWithMsg = chatWithMsg,
                            onClick = { onChatSelected(chatWithMsg.chat.peerId) },
                            onAction = { action -> onAction(chatWithMsg.chat.peerId, action) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatListItem(chatWithMsg: ChatListEntry, onClick: () -> Unit, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxSwipePx = with(density) { (-240).dp.toPx() }
    var offsetX by remember { mutableStateOf(0f) } // px, синхронно за пальцем

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(Color.Transparent)
    ) {
        // Background Actions (Mute, Archive, Pin, Delete) — рисуем только при свайпе,
        // чтобы прозрачные строки Liquid Glass их не просвечивали
        if (offsetX < -1f) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val buttons = listOf(
                Pair("mute", Color.Gray),
                Pair("archive", Color.Blue),
                Pair("pin", Color(0xFF10B981)),
                Pair("delete", Color.Red)
            )
            buttons.forEach { (action, color) ->
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp)
                        .background(color)
                        .clickable { 
                            onAction(action)
                            coroutineScope.launch { animate(offsetX, 0f, animationSpec = tween(150)) { v, _ -> offsetX = v } }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when(action) {
                        "mute" -> Icons.Filled.VolumeOff
                        "archive" -> Icons.Filled.Archive
                        "pin" -> Icons.Filled.PushPin
                        "delete" -> Icons.Filled.Delete
                        else -> Icons.Filled.Delete
                    }
                    Icon(icon, contentDescription = action, tint = Color.White)
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
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onClick)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = if (offsetX < maxSwipePx / 2) maxSwipePx else 0f
                            coroutineScope.launch {
                                animate(offsetX, target, animationSpec = tween(180, easing = FastOutSlowInEasing)) { v, _ -> offsetX = v }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount).coerceIn(maxSwipePx, 0f)
                        }
                    )
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val chat = chatWithMsg.chat
            val lastText = chatWithMsg.lastText
            val lastTimestamp = chatWithMsg.lastTimestamp

            val displayName = chat.name

            org.groktest.securemessenger.ui.components.Avatar(
                name = chat.name,
                avatarFileId = chat.avatarFileId,
                size = 52.dp,
                type = chat.type
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (lastTimestamp != null) {
                        val time = remember(lastTimestamp) {
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastTimestamp))
                        }
                        Text(time, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val snippet = if (lastText == null) "Нет сообщений" else if (lastText.startsWith("{") && lastText.contains("file_id")) "📎 Медиа" else lastText
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = snippet,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // (#A4) Бейдж непрочитанных — как в Telegram
                    if (chat.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            if (chat.isPinned) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}
