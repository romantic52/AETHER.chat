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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var group by remember(peerId) { mutableStateOf<RelayApi.GroupInfo?>(null) }
    var members by remember(peerId) { mutableStateOf<List<RelayApi.GroupMember>>(emptyList()) }
    var membersLoading by remember(peerId) { mutableStateOf(false) }
    var error by remember(peerId) { mutableStateOf<String?>(null) }
    var refreshKey by remember(peerId) { mutableStateOf(0) }
    var showAddMember by remember(peerId) { mutableStateOf(false) }
    var confirmRemove by remember(peerId) { mutableStateOf<RelayApi.GroupMember?>(null) }
    var confirmLeave by remember(peerId) { mutableStateOf(false) }
    var confirmDelete by remember(peerId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(peerId, refreshKey) {
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
            membersLoading = true
            try {
                val (loadedGroup, loadedMembers) = withContext(Dispatchers.IO) {
                    // Роль и владелец приходят только в /groups/my —
                    // список участников про текущего пользователя не знает.
                    val g = session.api.getMyGroups()
                        .firstOrNull { it.id.equals(peerId, ignoreCase = true) }
                    g to session.api.getGroupMembers(peerId)
                }
                group = loadedGroup
                members = loadedMembers
                error = null
            } catch (e: Exception) {
                error = e.message
            } finally {
                membersLoading = false
            }
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
                1 -> if (members.isEmpty() && membersLoading) "группа"
                else pluralRu(members.size, "участник", "участника", "участников")
                2 -> if (members.isEmpty() && membersLoading) "канал"
                else "канал · " + pluralRu(members.size, "подписчик", "подписчика", "подписчиков")
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
            group?.description?.takeIf(String::isNotBlank)?.let {
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
            val isChannel = current?.type == 2
            val ownerId = group?.ownerId.orEmpty()
            val isAdmin = group?.role == "admin"
            val isOwner = ownerId.isNotBlank() && ownerId.equals(session.myId, ignoreCase = true)

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isChannel) "Подписчики" else "Участники",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (members.isNotEmpty()) {
                    Text(
                        "${members.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isAdmin) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAddMember = true }
                        .padding(vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "Добавить участника",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (membersLoading && members.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .size(22.dp)
                        .align(Alignment.CenterHorizontally),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(members, key = { it.userId }) { member ->
                    val memberIsOwner = ownerId.isNotBlank() &&
                        member.userId.equals(ownerId, ignoreCase = true)
                    val memberIsSelf = member.userId.equals(session.myId, ignoreCase = true)
                    MemberRow(
                        session = session,
                        member = member,
                        ownerBadge = memberIsOwner,
                        canToggleAdmin = isOwner && !memberIsOwner,
                        canRemove = isAdmin && !memberIsSelf && !memberIsOwner,
                        onToggleAdmin = {
                            scope.launch {
                                try {
                                    val newRole = if (member.role == "admin") "member" else "admin"
                                    withContext(Dispatchers.IO) {
                                        session.api.setMemberRole(peerId, member.userId, newRole)
                                    }
                                    refreshKey++
                                } catch (e: Exception) {
                                    error = e.message
                                }
                            }
                        },
                        onRemove = { confirmRemove = member },
                    )
                }
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            TextButton(onClick = { if (isOwner) confirmDelete = true else confirmLeave = true }) {
                Text(
                    when {
                        isOwner && isChannel -> "Удалить канал"
                        isOwner -> "Удалить группу"
                        isChannel -> "Покинуть канал"
                        else -> "Покинуть группу"
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showAddMember) {
        AddMemberDialog(
            session = session,
            groupId = peerId,
            existing = members.map { it.userId.lowercase() }.toSet(),
            onDone = {
                showAddMember = false
                refreshKey++
            },
            onDismiss = { showAddMember = false },
            onError = {
                error = it
                showAddMember = false
            },
        )
    }

    confirmRemove?.let { member ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Удалить участника?") },
            text = { Text(member.displayName.ifBlank { member.userId }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                session.api.removeGroupMember(peerId, member.userId)
                            }
                            refreshKey++
                        } catch (e: Exception) {
                            error = e.message
                        }
                        confirmRemove = null
                    }
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Отмена") } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (current?.type == 2) "Покинуть канал?" else "Покинуть группу?") },
            text = { Text("Чат пропадёт из списка, история на этом устройстве будет удалена.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        // Сервер может уже не считать нас участником,
                        // поэтому локально чат чистим в любом случае.
                        runCatching { withContext(Dispatchers.IO) { session.api.leaveGroup(peerId) } }
                        session.store.deleteChatAndMessages(peerId)
                        onChatDeleted()
                    }
                }) { Text("Покинуть", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Отмена") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (current?.type == 2) "Удалить канал?" else "Удалить группу?") },
            text = { Text("Безвозвратно и для всех участников.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { session.api.deleteGroup(peerId) }
                            confirmDelete = false
                            session.store.deleteChatAndMessages(peerId)
                            onChatDeleted()
                        } catch (e: Exception) {
                            error = e.message
                            confirmDelete = false
                        }
                    }
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

/** Строка участника: аватар, имя с бейджем роли, @username; справа — управление для админа. */
@Composable
private fun MemberRow(
    session: AppSession,
    member: RelayApi.GroupMember,
    ownerBadge: Boolean,
    canToggleAdmin: Boolean,
    canRemove: Boolean,
    onToggleAdmin: () -> Unit,
    onRemove: () -> Unit,
) {
    val title = member.displayName.ifBlank { member.userId }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PeerAvatar(session, member.userId, title, member.avatarFileId, size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val badge = when {
                    ownerBadge -> "владелец"
                    member.role == "admin" -> "админ"
                    else -> null
                }
                badge?.let {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            if (member.username.isNotBlank()) {
                Text(
                    "@${member.username}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canToggleAdmin) {
            val adminNow = member.role == "admin"
            IconButton(onClick = onToggleAdmin, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (adminNow) Icons.Filled.Shield else Icons.Outlined.Shield,
                    contentDescription = if (adminNow) "Снять админа" else "Сделать админом",
                    tint = if (adminNow) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (canRemove) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Удалить из группы",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Поиск по каталогу и добавление участника. Для E2E-группы общий ключ
 * заворачивается на публичный ключ новичка (wrapGroupKeyFor), сервер видит
 * только шифртекст.
 */
@Composable
private fun AddMemberDialog(
    session: AppSession,
    groupId: String,
    existing: Set<String>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RelayApi.UserSearchResult>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        val clean = query.trim().removePrefix("@")
        if (clean.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        // Дебаунс, чтобы не дёргать каталог на каждый введённый символ.
        delay(300)
        results = runCatching {
            withContext(Dispatchers.IO) {
                session.api.searchUsers(clean).filter { it.userId.lowercase() !in existing }
            }
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить участника") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Имя или @username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (adding) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(results, key = { it.userId }) { user ->
                            val title = user.displayName
                                .ifBlank { user.username.ifBlank { user.userId } }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        adding = true
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    val keyB64 = session.repository.groupKeyB64For(groupId)
                                                        ?: throw IllegalStateException("Нет ключа группы")
                                                    session.api.addGroupMember(
                                                        groupId,
                                                        user.userId,
                                                        session.repository.wrapGroupKeyFor(user.userId, keyB64),
                                                    )
                                                }
                                                onDone()
                                            } catch (e: Exception) {
                                                onError(e.message ?: "Не удалось добавить")
                                            }
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                PeerAvatar(session, user.userId, title, user.avatarFileId, size = 32.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.bodyMedium)
                                    if (user.username.isNotBlank()) {
                                        Text(
                                            "@${user.username}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Filled.PersonAdd,
                                    contentDescription = "Добавить",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        if (results.isEmpty() && query.trim().removePrefix("@").length >= 2) {
                            item {
                                Text(
                                    "Никого не найдено",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** Русские формы числительных: 1 участник, 2 участника, 5 участников. */
private fun pluralRu(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val form = when {
        mod100 in 11..14 -> many
        mod100 % 10 == 1 -> one
        mod100 % 10 in 2..4 -> few
        else -> many
    }
    return "$count $form"
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
