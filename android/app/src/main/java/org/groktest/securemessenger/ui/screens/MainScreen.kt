package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.data.ChatListEntry
import org.groktest.securemessenger.ui.components.GlassBackground

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
    onNavigateToSecret: () -> Unit = {}
) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val screenWidth = configuration.screenWidthDp.dp
                val tabWidth = screenWidth / 3
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    // Доводка до ближайшей вкладки — не оставляем пейджер «между фазами»
                                    coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
                                },
                                onDragCancel = {
                                    coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    coroutineScope.launch {
                                        pagerState.scroll {
                                            scrollBy(dragAmount * 3f)
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    // Lava lamp viscous indicator
                    val rawPosition = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 2f)
                    val page = rawPosition.toInt().coerceAtMost(1)
                    val fraction = rawPosition - page
                    
                    val easeIn = fraction * fraction
                    val easeOut = 2f * fraction - fraction * fraction
                    
                    val indicatorLeft = (page + easeIn) * tabWidth.value
                    val indicatorRight = (page + easeOut + 1f) * tabWidth.value
                    val indicatorWidth = indicatorRight - indicatorLeft
                    
                    Box(
                        modifier = Modifier
                            .offset(x = indicatorLeft.dp)
                            .width(indicatorWidth.dp)
                            .fillMaxHeight()
                            .padding(8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val tabs = listOf(
                            Pair("Профиль", Icons.Default.Person),
                            Pair("Чаты", Icons.Default.Email),
                            Pair("Настройки", Icons.Default.Settings)
                        )
                        
                        tabs.forEachIndexed { index, tab ->
                            val selected = pagerState.currentPage == index
                            val color by animateColorAsState(
                                targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                animationSpec = tween(500)
                            )
                            
                            val iconSize by androidx.compose.animation.core.animateDpAsState(
                                targetValue = if (selected) 28.dp else 24.dp,
                                animationSpec = tween(200)
                            )
                            
                            // Вкладка «Настройки» (index 2): тап — переход; зажать 10с —
                            // тайные/экспериментальные настройки.
                            val tabGesture = if (index == 2) {
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(2, animationSpec = tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
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
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(
                                            page = index,
                                            animationSpec = tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                        )
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .then(tabGesture),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = tab.second,
                                    contentDescription = tab.first,
                                    tint = color,
                                    modifier = Modifier.size(iconSize)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(tab.first, fontSize = 12.sp, color = color, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) { page ->
                when (page) {
                    0 -> {
                        // Profile Screen
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            // Реальные данные профиля (имя, @username, фото)
                            val myProfile by produceState<RelayApi.UserProfile?>(initialValue = null, myId) {
                                value = try {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.getUserProfile(myId) }
                                } catch (e: Exception) { null }
                            }
                            val dispName = myProfile?.displayName?.takeIf { it.isNotBlank() } ?: myId
                            val uname = myProfile?.username?.takeIf { it.isNotBlank() }
                            val avatarId = myProfile?.avatarFileId

                            // Тап по шапке → редактор профиля (имя, @username, фото, описание)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onNavigateToProfileSettings() }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!avatarId.isNullOrBlank()) {
                                        coil.compose.AsyncImage(
                                            model = org.groktest.securemessenger.api.ServerConfig.avatarUrl(avatarId),
                                            contentDescription = "Фото",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    } else {
                                        Text(dispName.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(dispName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                                Text(uname?.let { "@$it" } ?: "@$myId", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            ListItem(
                                headlineContent = { Text("Изменить профиль") },
                                supportingContent = { Text("Имя, @username, фото, описание") },
                                leadingContent = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { onNavigateToProfileSettings() },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            
                            ListItem(
                                headlineContent = { Text("Создать группу") },
                                leadingContent = { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { onCreateGroupClick(false) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            ListItem(
                                headlineContent = { Text("Создать канал") },
                                leadingContent = { Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { onCreateGroupClick(true) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            ListItem(
                                headlineContent = { Text("Избранное") },
                                leadingContent = { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { onChatSelected(myId) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            
                            Spacer(modifier = Modifier.weight(1f))
                            // (#A6) Кнопка «Добавить аккаунт» убрана: мультиаккаунт ещё не
                            // реализован, неработающий контрол путал. Вернуть при поддержке.
                        }
                    }
                    1 -> {
                        // Chats Screen
                        ChatListScreen(
                            myId = myId,
                            chatListFlow = chatListFlow,
                            onChatSelected = onChatSelected,
                            onLogout = onLogout,
                            onNewChat = onNewChat,
                            onProfileClick = {}, 
                            onSettingsClick = {},
                            onSearchClick = onNavigateToSearch,
                            onAction = onAction
                        )
                    }
                    2 -> {
                        // Settings Screen
                        SettingsScreen(
                            onBack = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                            onNavigateToProfile = onNavigateToProfileSettings,
                            onNavigateToNotifications = onNavigateToNotificationsSettings,
                            onNavigateToPrivacy = onNavigateToPrivacySettings,
                            onNavigateToAbout = onNavigateToAboutApp,
                            onNavigateToCustomization = onNavigateToCustomization
                        )
                    }
                }
            }
        }
    }
}
