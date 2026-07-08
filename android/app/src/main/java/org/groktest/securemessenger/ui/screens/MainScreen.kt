package org.groktest.securemessenger.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.data.ChatListEntry
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherCircle
import org.groktest.securemessenger.ui.theme.aetherIsland
import org.groktest.securemessenger.ui.theme.aetherTextFieldColors
import kotlin.math.roundToInt

private data class MainTab(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    api: RelayApi,
    myId: String,
    chatListFlow: kotlinx.coroutines.flow.Flow<List<ChatListEntry>>,
    onChatSelected: (String) -> Unit,
    onLogout: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToCustomization: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onCreateGroupClick: (Boolean) -> Unit,
    onAction: (String, String) -> Unit,
    onNavigateToProfileSettings: () -> Unit,
    onNavigateToNotificationsSettings: () -> Unit,
    onNavigateToPrivacySettings: () -> Unit,
    onNavigateToAboutApp: () -> Unit,
    onNavigateToSecret: () -> Unit = {},
    onStartAudioCall: (String) -> Unit = {},
    onStartVideoCall: (String) -> Unit = {}
) {
    val tabs = remember {
        listOf(
            MainTab("Контакты", Icons.Default.Person),
            MainTab("Звонки", Icons.Default.Phone),
            MainTab("Чаты", Icons.Default.Email),
            MainTab("Настройки", Icons.Default.Settings)
        )
    }
    val pagerState = rememberPagerState(initialPage = 2, pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    val chats by chatListFlow.collectAsState(initial = emptyList())
    var dockDragCenterPx by remember { mutableFloatStateOf(Float.NaN) }
    var dockSettlingPosition by remember { mutableFloatStateOf(Float.NaN) }
    var dockScrollJob by remember { mutableStateOf<Job?>(null) }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = AetherStyle.DockHorizontal, end = AetherStyle.DockHorizontal, bottom = AetherStyle.DockBottom)
                        .height(AetherStyle.DockHeight)
                        .aetherIsland(
                            shape = RoundedCornerShape(AetherStyle.DockRadius),
                            fillAlpha = AetherStyle.DockFillAlpha,
                            strokeAlpha = AetherStyle.DockStrokeAlpha
                        )
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        val tabWidth = maxWidth / tabs.size
                        val density = LocalDensity.current
                        val tabWidthPx = with(density) { tabWidth.toPx() }
                        val dockWidthPx = with(density) { maxWidth.toPx() }
                        val isDockDragging = !dockDragCenterPx.isNaN()
                        val isDockSettling = !dockSettlingPosition.isNaN()
                        val pagerPosition = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                            .coerceIn(0f, tabs.lastIndex.toFloat())
                        val dragPosition = if (isDockDragging) {
                            ((dockDragCenterPx / tabWidthPx) - 0.5f).coerceIn(0f, tabs.lastIndex.toFloat())
                        } else {
                            null
                        }
                        val indicatorPosition by animateFloatAsState(
                            targetValue = dragPosition
                                ?: dockSettlingPosition.takeIf { isDockSettling }
                                ?: pagerPosition,
                            animationSpec = tween(
                                durationMillis = when {
                                    isDockDragging -> 0
                                    isDockSettling -> 280
                                    else -> 90
                                },
                                easing = FastOutSlowInEasing
                            ),
                            label = "dockIndicatorPosition"
                        )
                        val selectedVisualPage = indicatorPosition.roundToInt().coerceIn(0, tabs.lastIndex)
                        val indicatorInset = AetherStyle.DockIndicatorInset
                        val indicatorWidth = tabWidth - indicatorInset * 2
                        val indicatorShape = RoundedCornerShape(AetherStyle.DockIndicatorRadius)
                        val indicatorX = tabWidth * indicatorPosition + indicatorInset

                        Box(
                            modifier = Modifier
                                .offset(x = indicatorX)
                                .align(Alignment.CenterStart)
                                .width(indicatorWidth)
                                .height(AetherStyle.DockIndicatorHeight)
                                .aetherIsland(
                                    shape = indicatorShape,
                                    fillAlpha = AetherStyle.SelectedFillAlpha,
                                    strokeAlpha = AetherStyle.SelectedStrokeAlpha
                                )
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(tabWidthPx, dockWidthPx) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            dockScrollJob?.cancel()
                                            dockSettlingPosition = Float.NaN
                                            dockDragCenterPx = offset.x.coerceIn(0f, dockWidthPx)
                                        },
                                        onDragCancel = {
                                            dockDragCenterPx = Float.NaN
                                            dockSettlingPosition = Float.NaN
                                        },
                                        onDragEnd = {
                                            val target = ((if (dockDragCenterPx.isNaN()) 0f else dockDragCenterPx) / tabWidthPx)
                                                .toInt()
                                                .coerceIn(0, tabs.lastIndex)
                                            dockDragCenterPx = Float.NaN
                                            dockSettlingPosition = target.toFloat()
                                            dockScrollJob?.cancel()
                                            dockScrollJob = coroutineScope.launch {
                                                pagerState.animateScrollToPage(
                                                    target,
                                                    animationSpec = tween(320, easing = FastOutSlowInEasing)
                                                )
                                                dockSettlingPosition = Float.NaN
                                            }
                                        },
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            val current = if (dockDragCenterPx.isNaN()) change.position.x else dockDragCenterPx
                                            dockDragCenterPx = (current + dragAmount).coerceIn(0f, dockWidthPx)
                                        }
                                    )
                                },
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tabs.forEachIndexed { index, tab ->
                                val selected = selectedVisualPage == index
                                val color by animateColorAsState(
                                    targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    animationSpec = tween(240),
                                    label = "mainTabColor"
                                )
                                val scale by animateFloatAsState(
                                    targetValue = if (selected) 1.08f else 1f,
                                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                                    label = "mainTabScale"
                                )
                                val tabGesture = if (index == 3) {
                                    Modifier.pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = {
                                                dockDragCenterPx = Float.NaN
                                                dockSettlingPosition = index.toFloat()
                                                dockScrollJob?.cancel()
                                                dockScrollJob = coroutineScope.launch {
                                                    pagerState.animateScrollToPage(index, animationSpec = tween(320, easing = FastOutSlowInEasing))
                                                    dockSettlingPosition = Float.NaN
                                                }
                                            },
                                            onPress = {
                                                val released = withTimeoutOrNull(10_000L) { tryAwaitRelease() }
                                                if (released == null) onNavigateToSecret()
                                            }
                                        )
                                    }
                                } else {
                                    Modifier.clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        dockDragCenterPx = Float.NaN
                                        dockSettlingPosition = index.toFloat()
                                        dockScrollJob?.cancel()
                                        dockScrollJob = coroutineScope.launch {
                                            pagerState.animateScrollToPage(index, animationSpec = tween(320, easing = FastOutSlowInEasing))
                                            dockSettlingPosition = Float.NaN
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .then(tabGesture),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.title,
                                        tint = color,
                                        modifier = Modifier
                                            .size(26.dp)
                                            .graphicsLayer { scaleX = scale; scaleY = scale }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { _ ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> ContactsTab(
                        api = api,
                        chats = chats,
                        onUserSelected = onChatSelected,
                        onNewContact = onNewChat,
                        onCreateGroup = { onCreateGroupClick(false) },
                        onCreateChannel = { onCreateGroupClick(true) }
                    )
                    1 -> CallsTab(
                        chats = chats,
                        onOpenChat = onChatSelected,
                        onAudioCall = onStartAudioCall,
                        onVideoCall = onStartVideoCall,
                        onFindPeople = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(0, animationSpec = tween(260, easing = FastOutSlowInEasing))
                            }
                        }
                    )
                    2 -> ChatListScreen(
                        myId = myId,
                        chats = chats,
                        onChatSelected = onChatSelected,
                        onLogout = onLogout,
                        onNewChat = onNewChat,
                        onProfileClick = {},
                        onSettingsClick = {},
                        onSearchClick = onNavigateToSearch,
                        onCallsClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1, animationSpec = tween(280, easing = FastOutSlowInEasing))
                            }
                        },
                        onAction = onAction
                    )
                    3 -> SettingsScreen(
                        api = api,
                        myId = myId,
                        onBack = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(2, animationSpec = tween(280, easing = FastOutSlowInEasing))
                            }
                        },
                        onNavigateToProfile = onNavigateToProfileSettings,
                        onNavigateToNotifications = onNavigateToNotificationsSettings,
                        onNavigateToPrivacy = onNavigateToPrivacySettings,
                        onNavigateToAbout = onNavigateToAboutApp,
                        onNavigateToCustomization = onNavigateToCustomization,
                        onCreateGroup = { onCreateGroupClick(false) },
                        onCreateChannel = { onCreateGroupClick(true) },
                        onSavedMessages = { onChatSelected(myId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactsTab(
    api: RelayApi,
    chats: List<ChatListEntry>,
    onUserSelected: (String) -> Unit,
    onNewContact: () -> Unit,
    onCreateGroup: () -> Unit,
    onCreateChannel: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RelayApi.UserSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun performSearch(q: String) {
        searchJob?.cancel()
        if (q.length < 2) {
            results = emptyList()
            isLoading = false
            return
        }
        searchJob = coroutineScope.launch {
            delay(260)
            isLoading = true
            results = withContext(Dispatchers.IO) {
                runCatching { api.searchUsers(q) }.getOrDefault(emptyList())
            }
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = AetherStyle.ScreenHorizontal, vertical = AetherStyle.ScreenVertical)
    ) {
        Text("Контакты", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DockActionPill(
                title = "Найти",
                icon = Icons.Default.Search,
                modifier = Modifier.weight(1f),
                onClick = onNewContact
            )
            DockActionPill(
                title = "Группа",
                icon = Icons.Default.Person,
                modifier = Modifier.weight(1f),
                onClick = onCreateGroup
            )
            DockActionPill(
                title = "Канал",
                icon = Icons.Default.Email,
                modifier = Modifier.weight(1f),
                onClick = { onCreateChannel("") }
            )
        }
        Spacer(Modifier.height(12.dp))
        AetherSearchField(
            value = query,
            onValueChange = {
                query = it
                performSearch(it)
            },
            placeholder = "Поиск..."
        )
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }

        val recentContacts = remember(chats) { chats.filter { it.chat.type == 0 } }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (query.length >= 2) {
                if (results.isEmpty() && !isLoading) {
                    item {
                        EmptyTabState(
                            title = "Ничего не найдено",
                            action = "Создать канал",
                            onAction = { onCreateChannel(query) }
                        )
                    }
                } else {
                    items(results, key = { it.userId }) { user ->
                        ContactResultRow(user = user, onClick = { onUserSelected(user.userId) })
                    }
                }
            } else {
                if (recentContacts.isEmpty()) {
                    item {
                        EmptyTabState(
                            title = "Добавь контакт через поиск",
                            action = "Найти людей",
                            onAction = onNewContact
                        )
                    }
                } else {
                    items(recentContacts, key = { it.chat.peerId }) { entry ->
                        ChatPersonRow(
                            name = entry.chat.name,
                            subtitle = entry.lastText ?: "Открыть чат",
                            avatarFileId = entry.chat.avatarFileId,
                            onClick = { onUserSelected(entry.chat.peerId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallsTab(
    chats: List<ChatListEntry>,
    onOpenChat: (String) -> Unit,
    onAudioCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    onFindPeople: () -> Unit
) {
    val people = remember(chats) { chats.filter { it.chat.type == 0 } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = AetherStyle.ScreenHorizontal, vertical = AetherStyle.ScreenVertical)
    ) {
        Text("Звонки", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DockActionPill(
                title = "Контакты",
                icon = Icons.Default.Person,
                modifier = Modifier.weight(1f),
                onClick = onFindPeople
            )
            DockActionPill(
                title = "Аудио",
                icon = Icons.Default.Phone,
                modifier = Modifier.weight(1f),
                onClick = { people.firstOrNull()?.let { onAudioCall(it.chat.peerId) } ?: onFindPeople() }
            )
            DockActionPill(
                title = "Видео",
                icon = Icons.Default.Videocam,
                modifier = Modifier.weight(1f),
                onClick = { people.firstOrNull()?.let { onVideoCall(it.chat.peerId) } ?: onFindPeople() }
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (people.isEmpty()) {
                item {
                    EmptyTabState(
                        title = "Выбери контакт для звонка",
                        action = "Открыть контакты",
                        onAction = onFindPeople
                    )
                }
            } else {
                items(people, key = { it.chat.peerId }) { entry ->
                    CallRow(
                        entry = entry,
                        onOpenChat = { onOpenChat(entry.chat.peerId) },
                        onAudioCall = { onAudioCall(entry.chat.peerId) },
                        onVideoCall = { onVideoCall(entry.chat.peerId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DockActionPill(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .aetherIsland(
                shape = RoundedCornerShape(AetherStyle.PillRadius),
                fillAlpha = AetherStyle.IslandFillAlpha,
                strokeAlpha = 0.48f
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AetherSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .aetherIsland(
                shape = RoundedCornerShape(AetherStyle.FieldRadius),
                fillAlpha = AetherStyle.ControlFillAlpha,
                strokeAlpha = 0.42f
            ),
        shape = RoundedCornerShape(AetherStyle.FieldRadius),
        colors = aetherTextFieldColors()
    )
}

@Composable
private fun ContactResultRow(user: RelayApi.UserSearchResult, onClick: () -> Unit) {
    ChatPersonRow(
        name = user.displayName.takeIf { it.isNotBlank() } ?: user.username.takeIf { it.isNotBlank() } ?: user.userId,
        subtitle = if (user.isGroup) "Группа / канал" else user.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Открыть чат",
        avatarFileId = user.avatarFileId,
        onClick = onClick
    )
}

@Composable
private fun ChatPersonRow(
    name: String,
    subtitle: String,
    avatarFileId: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .aetherIsland(
                shape = RoundedCornerShape(AetherStyle.RowRadius),
                fillAlpha = AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = AetherStyle.SoftStrokeAlpha
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AetherAvatar(name = name, avatarFileId = avatarFileId, size = 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CallRow(
    entry: ChatListEntry,
    onOpenChat: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .aetherIsland(
                shape = RoundedCornerShape(AetherStyle.IslandRadius),
                fillAlpha = AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = AetherStyle.SoftStrokeAlpha
            )
            .clickable { onOpenChat() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AetherAvatar(name = entry.chat.name, avatarFileId = entry.chat.avatarFileId, size = 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.chat.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Быстрый звонок", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RoundActionButton(icon = Icons.Default.Phone, contentDescription = "Аудиозвонок", onClick = onAudioCall)
        Spacer(Modifier.width(8.dp))
        RoundActionButton(icon = Icons.Default.Videocam, contentDescription = "Видеозвонок", onClick = onVideoCall)
    }
}

@Composable
private fun RoundActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(AetherStyle.SmallControlSize)
            .aetherCircle(fillAlpha = AetherStyle.DockFillAlpha, strokeAlpha = 0.58f)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AetherAvatar(name: String, avatarFileId: String?, size: androidx.compose.ui.unit.Dp) {
    var avatarFailed by remember(avatarFileId) { mutableStateOf(false) }
    val remoteAvatarFileId = avatarFileId?.takeIf { it.isNotBlank() && !avatarFailed }

    Box(
        modifier = Modifier
            .size(size)
            .aetherCircle(fillAlpha = 0.84f, strokeAlpha = 0.46f),
        contentAlignment = Alignment.Center
    ) {
        if (remoteAvatarFileId != null) {
            AsyncImage(
                model = ServerConfig.avatarUrl(remoteAvatarFileId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { avatarFailed = true },
                onSuccess = { avatarFailed = false }
            )
        } else {
            Text(
                text = name.take(1).uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EmptyTabState(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            if (action != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAction) {
                    Text(action)
                }
            }
        }
    }
}
