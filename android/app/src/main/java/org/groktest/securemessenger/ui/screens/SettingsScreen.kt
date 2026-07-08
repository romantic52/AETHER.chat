package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherCircle
import org.groktest.securemessenger.ui.theme.aetherIsland

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: RelayApi,
    myId: String,
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToCustomization: () -> Unit,
    onCreateGroup: () -> Unit = {},
    onCreateChannel: () -> Unit = {},
    onSavedMessages: () -> Unit = {}
) {
    var profile by remember(myId) { mutableStateOf<RelayApi.UserProfile?>(null) }

    LaunchedEffect(myId) {
        profile = withContext(Dispatchers.IO) {
            runCatching { api.getUserProfile(myId) }.getOrNull()
        }
    }

    val displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: myId
    val handle = profile?.username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Профиль и аккаунт"
    val avatarFileId = profile?.avatarFileId
    var avatarFailed by remember(avatarFileId) { mutableStateOf(false) }
    val remoteAvatarFileId = avatarFileId?.takeIf { it.isNotBlank() && !avatarFailed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AetherStyle.ScreenHorizontal, vertical = AetherStyle.ScreenVertical)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .aetherIsland(
                        shape = RoundedCornerShape(AetherStyle.IslandRadius),
                        fillAlpha = AetherStyle.IslandFillAlpha,
                        strokeAlpha = AetherStyle.SelectedStrokeAlpha
                    )
                    .clickable { onNavigateToProfile() }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(AetherStyle.AvatarLarge)
                        .aetherCircle(fillAlpha = 0.24f, strokeAlpha = 0.58f),
                    contentAlignment = Alignment.Center
                ) {
                    if (remoteAvatarFileId != null) {
                        AsyncImage(
                            model = ServerConfig.avatarUrl(remoteAvatarFileId),
                            contentDescription = "Аватар",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            onError = { avatarFailed = true },
                            onSuccess = { avatarFailed = false }
                        )
                    } else {
                        Text(
                            text = displayName.take(1).uppercase(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(displayName, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(handle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsItem(
                title = "Настройки профиля",
                subtitle = "Имя, юзернейм, аватар, био",
                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                onClick = onNavigateToProfile
            )
            SettingsItem(
                title = "Создать группу",
                subtitle = "Новый общий чат",
                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                onClick = onCreateGroup
            )
            SettingsItem(
                title = "Создать канал",
                subtitle = "Публикации для подписчиков",
                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                onClick = onCreateChannel
            )
            SettingsItem(
                title = "Избранное",
                subtitle = "Личные сохраненные сообщения",
                icon = { Icon(Icons.Default.Star, contentDescription = null) },
                onClick = onSavedMessages
            )
            SettingsItem(
                title = "Кастомизация",
                subtitle = "Шрифт и обои чата",
                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                onClick = onNavigateToCustomization
            )
            SettingsItem(
                title = "Уведомления",
                subtitle = "Звуки, вибрация, исключения",
                icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                onClick = onNavigateToNotifications
            )
            SettingsItem(
                title = "Конфиденциальность",
                subtitle = "Блокировка, скрытие данных",
                icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                onClick = onNavigateToPrivacy
            )
            SettingsItem(
                title = "О приложении",
                subtitle = "Версия, лицензии",
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = onNavigateToAbout
            )
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .aetherIsland(
                shape = RoundedCornerShape(AetherStyle.RowRadius),
                fillAlpha = AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = AetherStyle.SoftStrokeAlpha
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(AetherStyle.SmallControlSize)
                .aetherCircle(fillAlpha = 0.7f, strokeAlpha = 0.45f),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                icon()
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
