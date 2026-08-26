package org.groktest.securemessenger.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.data.ChatListEntry
import org.groktest.securemessenger.data.SessionPrefs
import org.groktest.securemessenger.data.messagePreview
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherEdge
import org.groktest.securemessenger.ui.theme.AetherEdgeDim
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import org.groktest.securemessenger.ui.theme.aetherCircle
import org.groktest.securemessenger.ui.theme.aetherControl
import org.groktest.securemessenger.ui.theme.aetherControlContent
import org.groktest.securemessenger.ui.theme.aetherField
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
    activeSpace: SessionPrefs.Session?,
    spaces: List<SessionPrefs.Session>,
    chatListFlow: kotlinx.coroutines.flow.StateFlow<List<ChatListEntry>>,
    onChatSelected: (String) -> Unit,
    onLogout: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToCustomization: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onCreateGroupClick: (Boolean) -> Unit,
    onAction: (String, String) -> Unit,
    onSwitchSpace: (SessionPrefs.Session) -> Unit,
    onAddServer: () -> Unit,
    onNavigateToProfileSettings: () -> Unit,
    onNavigateToNotificationsSettings: () -> Unit,
    onNavigateToPrivacySettings: () -> Unit,
    onNavigateToSecuritySettings: () -> Unit = {},
    onNavigateToAboutApp: () -> Unit,
    onNavigateToExperiments: () -> Unit = {},
    onStartAudioCall: (String) -> Unit = {},
    onStartVideoCall: (String) -> Unit = {}
) {
    val tabs = remember {
        listOf(
            MainTab("Контакты", Icons.Default.Person),
            MainTab("Звонки", Icons.Default.Phone),
            MainTab("Чаты", Icons.AutoMirrored.Filled.Chat),
            MainTab("Настройки", Icons.Default.Settings)
        )
    }
    val pagerState = rememberPagerState(initialPage = 2, pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    val chats by chatListFlow.collectAsState()
    val appearance = LocalThemeSettings.current
    val totalUnread = remember(chats) {
        chats.filter { !it.chat.isMuted && !it.chat.isArchived }.sumOf { it.chat.unreadCount }
    }
    var dockDragPosition by remember { mutableFloatStateOf(Float.NaN) }
    val dockIndicatorPosition = remember { Animatable(2f) }
    var dockTapAnimating by remember { mutableStateOf(false) }
    var dockScrollJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && !dockTapAnimating && dockDragPosition.isNaN()) {
            dockIndicatorPosition.snapTo(pagerState.currentPage.toFloat())
        }
    }

    fun moveToPage(index: Int, holdIndicator: Boolean = false) {
        dockScrollJob?.cancel()
        dockScrollJob = coroutineScope.launch {
            try {
                if (holdIndicator) {
                    dockIndicatorPosition.snapTo(index.toFloat())
                    pagerState.scrollToPage(index)
                } else if (appearance.animationsEnabled()) {
                    dockDragPosition = Float.NaN
                    dockTapAnimating = true
                    pagerState.scrollToPage(index)
                    dockIndicatorPosition.animateTo(
                        index.toFloat(),
                        animationSpec = tween(
                            durationMillis = appearance.motionDuration(160).coerceAtMost(180),
                            easing = FastOutSlowInEasing
                        )
                    )
                } else {
                    dockIndicatorPosition.snapTo(index.toFloat())
                    pagerState.scrollToPage(index)
                }
            } finally {
                dockTapAnimating = false
                dockDragPosition = Float.NaN
            }
        }
    }

    GlassBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            val dock: @Composable BoxScope.() -> Unit = {
                val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val dockAreaHeight = maxOf(
                    appearance.edgeDimLength.value.dp,
                    AetherStyle.DockHeight + AetherStyle.DockBottom + navigationBottom
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .zIndex(10f)
                        .height(dockAreaHeight)
                ) {
                    AetherEdgeDim(
                        edge = AetherEdge.Bottom,
                        modifier = Modifier.matchParentSize()
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = AetherStyle.DockHorizontal, end = AetherStyle.DockHorizontal, bottom = AetherStyle.DockBottom)
                            .fillMaxWidth()
                            .height(AetherStyle.DockHeight),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .aetherIsland(
                                shape = RoundedCornerShape(AetherStyle.DockRadius),
                                fillAlpha = AetherStyle.DockFillAlpha,
                                strokeAlpha = AetherStyle.DockStrokeAlpha
                            )
                    ) {
                        val tabWidth = maxWidth / tabs.size
                        val density = LocalDensity.current
                        val tabWidthPx = with(density) { tabWidth.toPx() }
                        val dockWidthPx = with(density) { maxWidth.toPx() }
                        val selectedContentColor = aetherControlContent()
                        val selectedVisualPage = pagerState.currentPage
                        val edgeToEdge = appearance.dockIndicatorEdgeToEdge.value
                        val indicatorInset = if (edgeToEdge) 0.dp else AetherStyle.DockIndicatorInset
                        val indicatorWidth = tabWidth - indicatorInset * 2
                        val indicatorHeight = if (edgeToEdge) {
                            AetherStyle.DockHeight
                        } else {
                            minOf(AetherStyle.DockIndicatorHeight, maxOf(42.dp, indicatorWidth / 1.25f))
                        }
                        val indicatorShape = RoundedCornerShape(
                            if (edgeToEdge) AetherStyle.DockRadius else AetherStyle.DockIndicatorRadius
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(indicatorWidth)
                                .height(indicatorHeight)
                                .graphicsLayer {
                                    val position = if (dockDragPosition.isNaN()) {
                                        if (dockTapAnimating) {
                                            dockIndicatorPosition.value
                                        } else {
                                            pagerState.currentPage + pagerState.currentPageOffsetFraction
                                        }
                                    } else {
                                        dockDragPosition
                                    }.coerceIn(0f, tabs.lastIndex.toFloat())
                                    translationX = tabWidth.toPx() * position + indicatorInset.toPx()
                                }
                                .aetherIsland(
                                    shape = indicatorShape,
                                    fillAlpha = 0.38f,
                                    strokeAlpha = 0f
                                )
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .selectableGroup()
                                .pointerInput(tabWidthPx, dockWidthPx) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            dockScrollJob?.cancel()
                                            dockDragPosition = (offset.x / tabWidthPx - 0.5f)
                                                .coerceIn(0f, tabs.lastIndex.toFloat())
                                        },
                                        onDragCancel = { dockDragPosition = Float.NaN },
                                        onDragEnd = {
                                            val target = dockDragPosition.roundToInt()
                                                .coerceIn(0, tabs.lastIndex)
                                            dockDragPosition = target.toFloat()
                                            moveToPage(target, holdIndicator = true)
                                        },
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            dockDragPosition = (dockDragPosition + dragAmount / tabWidthPx)
                                                .coerceIn(0f, tabs.lastIndex.toFloat())
                                        }
                                    )
                                },
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tabs.forEachIndexed { index, tab ->
                                val selected = selectedVisualPage == index
                                val color = if (selected) {
                                    selectedContentColor
                                } else {
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f)
                                }
                                val tabInteraction = remember { MutableInteractionSource() }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .semantics(mergeDescendants = true) {}
                                        .selectable(
                                            selected = selected,
                                            interactionSource = tabInteraction,
                                            indication = null,
                                            role = Role.Tab,
                                            onClick = { moveToPage(index) }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Box(
                                            modifier = Modifier.size(29.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = null,
                                                tint = color,
                                                modifier = Modifier.size(25.dp)
                                            )
                                            if (index == 2 && totalUnread > 0) {
                                                Badge(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .offset(x = 5.dp)
                                                        .clearAndSetSemantics {
                                                            contentDescription = "Непрочитано: $totalUnread"
                                                        },
                                                    containerColor = MaterialTheme.colorScheme.error,
                                                    contentColor = MaterialTheme.colorScheme.onError
                                                ) {
                                                    Text(
                                                        if (totalUnread > 999) "999+" else totalUnread.toString(),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = tab.title,
                                            color = color,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp),
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(AetherStyle.DockHeight)
                            .aetherIsland(
                                shape = CircleShape,
                                fillAlpha = AetherStyle.DockFillAlpha,
                                strokeAlpha = AetherStyle.DockStrokeAlpha
                            )
                            .clickable(
                                role = Role.Button,
                                onClick = onNavigateToSearch
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Поиск",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    }
                }
            }
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = true,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                when (page) {
                    0 -> ContactsTab(
                        api = api,
                        chats = chats,
                        onUserSelected = onChatSelected,
                        onNewContact = onNewChat
                    )
                    1 -> CallsTab(
                        chats = chats,
                        onOpenChat = onChatSelected,
                        onAudioCall = onStartAudioCall,
                        onVideoCall = onStartVideoCall,
                        onFindPeople = { moveToPage(0) }
                    )
                    2 -> ChatListScreen(
                        chats = chats,
                        activeSpace = activeSpace,
                        spaces = spaces,
                        onChatSelected = onChatSelected,
                        onNewChat = onNewChat,
                        onCreateGroup = { onCreateGroupClick(false) },
                        onCreateChannel = { onCreateGroupClick(true) },
                        onAction = onAction,
                        onSwitchSpace = onSwitchSpace,
                        onAddServer = onAddServer,
                    )
                    3 -> SettingsScreen(
                        api = api,
                        myId = myId,
                        onBack = { moveToPage(2) },
                        onNavigateToProfile = onNavigateToProfileSettings,
                        onNavigateToNotifications = onNavigateToNotificationsSettings,
                        onNavigateToPrivacy = onNavigateToPrivacySettings,
                        onNavigateToSecurity = onNavigateToSecuritySettings,
                        onNavigateToAbout = onNavigateToAboutApp,
                        onNavigateToCustomization = onNavigateToCustomization,
                        onNavigateToExperiments = onNavigateToExperiments,
                        onSavedMessages = { onChatSelected(myId) },
                        onLogout = onLogout,
                        showBack = false
                    )
                }
                }
            }
            dock()
        }
    }
}

@Composable
private fun ContactsTab(
    api: RelayApi,
    chats: List<ChatListEntry>,
    onUserSelected: (String) -> Unit,
    onNewContact: () -> Unit
) {
    val appearance = LocalThemeSettings.current
    val edgeDimHeight = appearance.edgeDimLength.value.dp
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

    Box(modifier = Modifier.fillMaxSize()) {
        AetherEdgeDim(AetherEdge.Top, Modifier.fillMaxWidth().height(edgeDimHeight))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = AetherStyle.ScreenHorizontal, vertical = AetherStyle.ScreenVertical)
        ) {
        Text("Контакты", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        AetherSearchField(
            value = query,
            onValueChange = {
                query = it
                performSearch(it)
            },
            placeholder = "Поиск..."
        )
        Box(Modifier.fillMaxWidth().height(12.dp)) {
            Crossfade(
                targetState = isLoading,
                animationSpec = tween(appearance.motionDuration(120)),
                label = "contactSearchProgress"
            ) { loading ->
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        val recentContacts = remember(chats) { chats.filter { it.chat.type == 0 } }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = edgeDimHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            )
        ) {
            if (query.length >= 2) {
                if (results.isEmpty() && !isLoading) {
                    item {
                        EmptyTabState(
                            title = "Ничего не найдено",
                            action = "Новый поиск",
                            onAction = onNewContact
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
                            subtitle = messagePreview(entry.lastText, "Открыть чат"),
                            avatarFileId = entry.chat.avatarFileId,
                            onClick = { onUserSelected(entry.chat.peerId) }
                        )
                    }
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
    val edgeDimHeight = LocalThemeSettings.current.edgeDimLength.value.dp
    val people = remember(chats) { chats.filter { it.chat.type == 0 } }
    Box(modifier = Modifier.fillMaxSize()) {
        AetherEdgeDim(AetherEdge.Top, Modifier.fillMaxWidth().height(edgeDimHeight))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = AetherStyle.ScreenHorizontal, vertical = AetherStyle.ScreenVertical)
        ) {
        Text("Звонки", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = edgeDimHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            )
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
            .aetherField(
                shape = RoundedCornerShape(AetherStyle.FieldRadius),
                fillAlpha = AetherStyle.SearchFillAlpha,
                strokeAlpha = AetherStyle.SearchStrokeAlpha
            ),
        shape = RoundedCornerShape(AetherStyle.FieldRadius),
        colors = aetherTextFieldColors(containerAlpha = 0f)
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
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AetherAvatar(name = name, avatarFileId = avatarFileId, size = AetherStyle.AvatarMedium)
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
            .clickable { onOpenChat() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AetherAvatar(name = entry.chat.name, avatarFileId = entry.chat.avatarFileId, size = AetherStyle.AvatarMedium)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.chat.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Выберите тип звонка", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            .aetherControl(fillAlpha = AetherStyle.ControlFillAlpha, strokeAlpha = AetherStyle.ControlStrokeAlpha)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = aetherControlContent(), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AetherAvatar(name: String, avatarFileId: String?, size: androidx.compose.ui.unit.Dp) {
    var avatarFailed by remember(avatarFileId) { mutableStateOf(false) }
    val remoteAvatarFileId = avatarFileId?.takeIf { it.isNotBlank() && !avatarFailed }

    Box(
        modifier = Modifier
            .size(size)
            .aetherCircle(fillAlpha = AetherStyle.AvatarFillAlpha, strokeAlpha = AetherStyle.AvatarStrokeAlpha),
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
                Box(
                    modifier = Modifier
                        .aetherIsland(
                            shape = RoundedCornerShape(AetherStyle.PillRadius),
                            fillAlpha = AetherStyle.SoftIslandFillAlpha
                        )
                        .clickable { onAction() }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(action, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
