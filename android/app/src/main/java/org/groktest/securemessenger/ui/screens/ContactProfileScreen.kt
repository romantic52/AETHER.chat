package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.ui.components.AetherActionCircle
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherIsland

/**
 * Профиль собеседника (как в Telegram): фото, имя, @username, описание,
 * статус, действия (сообщение/звонок/видео) и сверка E2E-ключей.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(
    api: RelayApi,
    peerId: String,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onOpenSafety: (() -> Unit)? = null
) {
    var isLoading by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatarFileId by remember { mutableStateOf<String?>(null) }
    var lastActive by remember { mutableStateOf<String?>(null) }
    var statusEmoji by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(peerId) {
        try {
            val p = withContext(Dispatchers.IO) { api.getUserProfile(peerId) }
            username = p.username
            displayName = p.displayName
            bio = p.bio ?: ""
            avatarFileId = p.avatarFileId
            lastActive = p.lastActive
            statusEmoji = p.statusEmoji
        } catch (e: Exception) {
            // профиль может быть недоступен — покажем минимум по peerId
        } finally {
            isLoading = false
        }
    }

    val title = displayName.ifBlank { peerId }

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- Аватар ---
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(peerColor(peerId)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!avatarFileId.isNullOrBlank()) {
                        AsyncImage(
                            model = ServerConfig.avatarUrl(avatarFileId!!),
                            contentDescription = "Фото",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            peerId.trim().firstOrNull()?.uppercase() ?: "?",
                            fontSize = 52.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f, fill = false),
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!statusEmoji.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(statusEmoji!!, fontSize = 21.sp, maxLines = 1)
                    }
                }
                if (username.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text("@$username", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    formatLastSeen(lastActive),
                    fontSize = 14.sp,
                    color = if (formatLastSeen(lastActive) == "в сети") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(22.dp))

                // --- Действия ---
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    AetherActionCircle(Icons.AutoMirrored.Filled.Chat, "Сообщение", onMessage)
                    AetherActionCircle(Icons.Filled.Phone, "Звонок", onAudioCall)
                    AetherActionCircle(Icons.Filled.Videocam, "Видео", onVideoCall)
                }

                Spacer(Modifier.height(26.dp))

                // --- Информация ---
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AetherStyle.ScreenHorizontal)) {
                    if (bio.isNotBlank()) {
                        InfoCard(label = "О себе", value = bio)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (username.isNotBlank()) {
                        InfoCard(label = "Имя пользователя", value = "@$username")
                        Spacer(Modifier.height(10.dp))
                    }
                    if (onOpenSafety != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aetherIsland(
                                    shape = RoundedCornerShape(AetherStyle.RowRadius),
                                    fillAlpha = AetherStyle.SoftIslandFillAlpha,
                                    strokeAlpha = AetherStyle.SoftStrokeAlpha
                                )
                                .clickable { onOpenSafety() }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Шифрование", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                                Text("Сверить цифры безопасности", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    // --- Блокировка (чёрный список) ---
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val blocked = org.groktest.securemessenger.data.BlockStore.blocked.contains(peerId.lowercase())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aetherIsland(
                                shape = RoundedCornerShape(AetherStyle.RowRadius),
                                fillAlpha = AetherStyle.SoftIslandFillAlpha,
                                strokeAlpha = AetherStyle.SoftStrokeAlpha
                            )
                            .clickable {
                                org.groktest.securemessenger.data.BlockStore.toggle(context, peerId)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (blocked) "Разблокировать" else "Заблокировать пользователя",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
            AetherSettingsTopBar(
                "Профиль",
                onBack,
                Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aetherIsland(
                shape = RoundedCornerShape(AetherStyle.RowRadius),
                fillAlpha = AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = AetherStyle.SoftStrokeAlpha
            )
            .padding(16.dp)
    ) {
        Text(value, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    }
}
