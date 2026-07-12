package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ExitToApp
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import org.groktest.securemessenger.ui.theme.aetherCircle
import org.groktest.securemessenger.ui.theme.aetherIsland
import org.groktest.securemessenger.data.MediaCache

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
    onSavedMessages: () -> Unit = {},
    onLogout: () -> Unit = {},
    showBack: Boolean = true
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember(myId) { mutableStateOf<RelayApi.UserProfile?>(null) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var showStatusPicker by remember { mutableStateOf(false) }
    var statusSaving by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    val appearance = LocalThemeSettings.current

    LaunchedEffect(myId) {
        profile = withContext(Dispatchers.IO) {
            runCatching { api.getUserProfile(myId) }.getOrNull()
        }
        cacheBytes = MediaCache.cacheSize(context)
    }

    val displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: myId
    val handle = profile?.username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Профиль и аккаунт"
    val avatarFileId = profile?.avatarFileId
    var avatarFailed by remember(avatarFileId) { mutableStateOf(false) }
    val remoteAvatarFileId = avatarFileId?.takeIf { it.isNotBlank() && !avatarFailed }

    fun updateStatus(emoji: String) {
        if (statusSaving) return
        scope.launch {
            statusSaving = true
            val error = withContext(Dispatchers.IO) {
                runCatching { api.setStatusEmoji(emoji) }.exceptionOrNull()
            }
            statusSaving = false
            if (error == null) {
                profile = profile?.copy(statusEmoji = emoji.takeIf { it.isNotBlank() })
                showStatusPicker = false
            } else {
                snackbarHostState.showSnackbar(error.message ?: "Не удалось изменить статус")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = AetherStyle.ScreenHorizontal,
                    end = AetherStyle.ScreenHorizontal,
                    bottom = AetherStyle.ScreenVertical
                )
        ) {
            Spacer(Modifier.height(AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical))
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displayName,
                            modifier = Modifier.weight(1f),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        IconButton(
                            onClick = { showStatusPicker = true },
                            enabled = !statusSaving,
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (profile?.statusEmoji.isNullOrBlank()) {
                                Icon(
                                    Icons.Default.AddReaction,
                                    contentDescription = "Выбрать эмодзи-статус",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                Text(profile?.statusEmoji.orEmpty(), fontSize = 21.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(handle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsSectionTitle("Аккаунт")
            SettingsItem(
                title = "Настройки профиля",
                subtitle = "Имя, юзернейм, аватар, био",
                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                onClick = onNavigateToProfile
            )
            SettingsItem(
                title = "Избранное",
                subtitle = "Личные сохраненные сообщения",
                icon = { Icon(Icons.Default.Star, contentDescription = null) },
                onClick = onSavedMessages
            )

            Spacer(Modifier.height(12.dp))
            SettingsSectionTitle("Приложение")
            SettingsItem(
                title = "Оформление",
                subtitle = "Темы, прозрачность, пузыри и шрифты",
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
                title = "Данные и память",
                subtitle = "Кеш медиа · ${MediaCache.formatSize(cacheBytes)}",
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { confirmClearCache = true }
            )

            Spacer(Modifier.height(12.dp))
            SettingsSectionTitle("Aether")
            SettingsItem(
                title = "О приложении",
                subtitle = "Версия, лицензии",
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = onNavigateToAbout
            )
            SettingsItem(
                title = "Выйти из аккаунта",
                subtitle = myId,
                icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                destructive = true,
                onClick = { confirmLogout = true }
            )
            Spacer(Modifier.height(if (showBack) 24.dp else appearance.edgeDimLength.value.dp))
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (showBack) 12.dp else appearance.edgeDimLength.value.dp)
        )
        AetherSettingsTopBar(
            "Настройки",
            if (showBack) onBack else null,
            Modifier.align(Alignment.TopCenter)
        )
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("Очистить кеш?") },
            text = { Text("Сообщения останутся. Фото, видео и файлы загрузятся заново при открытии.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearCache = false
                    scope.launch {
                        MediaCache.clearAll(context)
                        cacheBytes = MediaCache.cacheSize(context)
                    }
                }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("Отмена") } }
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) {
                    Text("Выйти", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } }
        )
    }

    if (showStatusPicker) {
        StatusPickerSheet(
            current = profile?.statusEmoji,
            saving = statusSaving,
            onDismiss = { if (!statusSaving) showStatusPicker = false },
            onSelect = ::updateStatus
        )
    }
}

private val profileStatuses = listOf(
    "😀", "😎", "🥳", "😴", "🤒", "🏝", "💻", "📵",
    "🎮", "🎧", "📚", "🏋️", "☕️", "🍕", "❤️", "🔥",
    "⭐️", "🌙", "⚡️", "🎯", "🚀", "🧘", "🐱", "🐶",
    "🌈", "🍀", "🎵", "💤", "🤫", "👑", "🫡", "🥷"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusPickerSheet(
    current: String?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Эмодзи-статус", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.fillMaxWidth().height(192.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            userScrollEnabled = false
        ) {
            items(profileStatuses, key = { it }) { emoji ->
                TextButton(
                    onClick = { onSelect(emoji) },
                    enabled = !saving,
                    modifier = Modifier.size(44.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(emoji, fontSize = if (emoji == current) 28.sp else 25.sp)
                }
            }
        }
        if (!current.isNullOrBlank()) {
            TextButton(
                onClick = { onSelect("") },
                enabled = !saving,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Убрать статус")
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
            CompositionLocalProvider(LocalContentColor provides accent) {
                icon()
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
