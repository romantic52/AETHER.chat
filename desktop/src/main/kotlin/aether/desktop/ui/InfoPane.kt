package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.api.RelayApi
import aether.desktop.data.BlockStore
import aether.desktop.data.ChatEntity
import aether.desktop.data.MessageEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InfoPane(
    session: AppSession,
    peerId: String,
    onClose: () -> Unit,
    onShowSafetyNumbers: () -> Unit,
    onChatDeleted: () -> Unit,
    /** Открыть просмотрщик на выбранном снимке; без него сетка «Медиа» просто некликабельна. */
    onOpenMedia: ((List<MessageEntity>, Int) -> Unit)? = null,
) {
    var chat by remember(peerId) { mutableStateOf<ChatEntity?>(null) }
    var profile by remember(peerId) { mutableStateOf<RelayApi.UserProfile?>(null) }
    var members by remember(peerId) { mutableStateOf<List<RelayApi.GroupMember>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(peerId) {
        // Разделу «Медиа» нужна история чата: панель могут открыть раньше, чем
        // лента успеет её подтянуть.
        session.store.preloadMessages(peerId)
        chat = session.store.getChat(peerId)
        val type = chat?.type ?: 0
        if (type == 0 && !peerId.equals(session.myId, ignoreCase = true)) {
            profile = runCatching {
                withContext(Dispatchers.IO) { session.api.getUserProfile(peerId) }
            }.getOrNull()
        }
        if (type in 1..2) {
            members = runCatching {
                withContext(Dispatchers.IO) { session.api.getGroupMembers(peerId) }
            }.getOrDefault(emptyList())
        }
    }

    val current = chat
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Информация",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PeerAvatar(session, peerId, current?.name ?: peerId, current?.avatarFileId, size = 84.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                chatDisplayName(current?.name ?: peerId, current?.statusEmoji),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = when (current?.type) {
                1 -> "${members.size} участников"
                2 -> "канал · ${members.size} подписчиков"
                3 -> "ваши заметки"
                else -> "@" + (profile?.username?.ifBlank { peerId } ?: peerId)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profile?.bio?.takeIf(String::isNotBlank)?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text("Уведомления", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = !(current?.isMuted ?: false),
                onCheckedChange = { enabled ->
                    scope.launch {
                        session.store.setMuted(peerId, !enabled)
                        chat = session.store.getChat(peerId)
                    }
                },
            )
        }

        if ((current?.type ?: 0) == 0 && !peerId.equals(session.myId, ignoreCase = true)) {
            TextButton(onClick = onShowSafetyNumbers) { Text("Цифры безопасности") }
            TextButton(onClick = { BlockStore.toggle(peerId) }) {
                Text(
                    if (BlockStore.isBlocked(peerId)) "Разблокировать" else "Заблокировать",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        MediaSection(session, peerId, onOpenMedia)

        if ((current?.type ?: 0) in 1..2) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Участники",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(members, key = { it.userId }) { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PeerAvatar(session, member.userId, member.displayName, member.avatarFileId, size = 32.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(member.displayName, style = MaterialTheme.typography.bodyMedium)
                            if (member.role != "member") {
                                Text(
                                    member.role,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { session.api.leaveGroup(peerId) } }
                    session.store.deleteChatAndMessages(peerId)
                    onChatDeleted()
                }
            }) {
                Text(
                    if (current?.type == 2) "Покинуть канал" else "Покинуть группу",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Количество снимков в разделе «Медиа» и ширина его сетки в ячейках. */
private const val MEDIA_PREVIEW_COUNT = 12
private const val MEDIA_COLUMNS = 4

/** Раздел «Медиа»: последние снимки чата сеткой, клик открывает просмотрщик. */
@Composable
private fun MediaSection(
    session: AppSession,
    peerId: String,
    onOpenMedia: ((List<MessageEntity>, Int) -> Unit)?,
) {
    val messages by remember(peerId) { session.store.getMessagesForPeer(peerId) }.collectAsState()
    val media = remember(messages) { viewableMedia(messages) }
    if (media.isEmpty()) return
    // В сетке — свежие сверху, а в просмотрщик отдаём всю подборку целиком,
    // чтобы стрелки листали не только показанные здесь снимки.
    val recent = remember(media) { media.takeLast(MEDIA_PREVIEW_COUNT).reversed() }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Медиа · ${media.size}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        recent.chunked(MEDIA_COLUMNS).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { message ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = onOpenMedia != null) {
                                val position = media.indexOfFirst { it.msgId == message.msgId }
                                onOpenMedia?.invoke(media, position.coerceAtLeast(0))
                            },
                    ) {
                        MediaThumbnail(session, message, modifier = Modifier.fillMaxSize())
                    }
                }
                // Неполный последний ряд добираем пустотой, иначе его снимки
                // растянулись бы на всю ширину панели.
                repeat(MEDIA_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}
