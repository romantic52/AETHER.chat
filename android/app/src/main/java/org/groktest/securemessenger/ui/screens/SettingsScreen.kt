package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.ui.components.AetherSectionTitle
import org.groktest.securemessenger.ui.components.AetherSettingsRow
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
    onNavigateToSecurity: () -> Unit = {},
    onNavigateToServers: () -> Unit = {},
    onNavigateToNearby: () -> Unit = {},
    onNavigateToAbout: () -> Unit,
    onNavigateToCustomization: () -> Unit,
    onNavigateToExperiments: () -> Unit,
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
                        .aetherCircle(fillAlpha = AetherStyle.SoftIslandFillAlpha, strokeAlpha = AetherStyle.ControlStrokeAlpha),
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

            AetherSectionTitle("Аккаунт")
            AetherSettingsRow(
                title = "Настройки профиля",
                subtitle = "Имя, юзернейм, аватар, био",
                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                onClick = onNavigateToProfile
            )
            AetherSettingsRow(
                title = "Избранное",
                subtitle = "Личные сохраненные сообщения",
                icon = { Icon(Icons.Default.Star, contentDescription = null) },
                onClick = onSavedMessages
            )

            Spacer(Modifier.height(12.dp))
            AetherSectionTitle("Приложение")
            AetherSettingsRow(
                title = "Оформление",
                subtitle = "Темы, прозрачность, пузыри и шрифты",
                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                onClick = onNavigateToCustomization
            )
            AetherSettingsRow(
                title = "Уведомления",
                subtitle = "Звуки, вибрация, исключения",
                icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                onClick = onNavigateToNotifications
            )
            AetherSettingsRow(
                title = "Конфиденциальность",
                subtitle = "Блокировка, скрытие данных",
                icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                onClick = onNavigateToPrivacy
            )
            AetherSettingsRow(
                title = "Сессии и безопасность",
                subtitle = "Устройства, 2FA, удаление данных",
                icon = { Icon(Icons.Default.Shield, contentDescription = null) },
                onClick = onNavigateToSecurity
            )
            AetherSettingsRow(
                title = "Серверы",
                subtitle = "Свои серверы, отпечатки, аккаунты",
                icon = { Icon(Icons.Default.Dns, contentDescription = null) },
                onClick = onNavigateToServers
            )
            AetherSettingsRow(
                title = "Рядом",
                subtitle = "Поиск людей поблизости по Bluetooth",
                icon = { Icon(Icons.Default.BluetoothSearching, contentDescription = null) },
                onClick = onNavigateToNearby
            )
            AetherSettingsRow(
                title = "Данные и память",
                subtitle = "Кеш медиа · ${MediaCache.formatSize(cacheBytes)}",
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { confirmClearCache = true }
            )

            Spacer(Modifier.height(12.dp))
            AetherSectionTitle("Aether")
            AetherSettingsRow(
                title = "О приложении",
                subtitle = "Версия, лицензии",
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = onNavigateToAbout
            )
            AetherSettingsRow(
                title = "Выйти из аккаунта",
                subtitle = myId,
                icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                destructive = true,
                onClick = { confirmLogout = true }
            )
            Spacer(Modifier.height(12.dp))
            AetherSectionTitle("Разработка")
            AetherSettingsRow(
                title = "Эксперименты",
                subtitle = "Анимации и новые функции",
                icon = { Icon(Icons.Default.Science, contentDescription = null) },
                onClick = onNavigateToExperiments
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
            shape = RoundedCornerShape(AetherStyle.IslandRadius),
            containerColor = MaterialTheme.colorScheme.surface,
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
            shape = RoundedCornerShape(AetherStyle.IslandRadius),
            containerColor = MaterialTheme.colorScheme.surface,
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = AetherStyle.IslandRadius, topEnd = AetherStyle.IslandRadius),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Эмодзи-статус", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(44.dp),
            modifier = Modifier.fillMaxWidth().height(192.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            userScrollEnabled = true
        ) {
            items(profileStatuses, key = { it }) { emoji ->
                TextButton(
                    onClick = { onSelect(emoji) },
                    enabled = !saving,
                    modifier = Modifier.size(44.dp).semantics { selected = emoji == current },
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
