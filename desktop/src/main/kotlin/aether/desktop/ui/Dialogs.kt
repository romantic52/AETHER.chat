package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.api.RelayApi
import aether.desktop.crypto.KeyTrustStore
import aether.desktop.data.MessageEntity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Вторичная строка в списках чатов: тип по ChatEntity.type (0/1/2/3). */
private fun chatTypeLabel(type: Int): String = when (type) {
    1 -> "Группа"
    2 -> "Канал"
    3 -> "Избранное"
    else -> "Личный чат"
}

/** Пересылка: выбор чата-получателя. */
@Composable
fun ForwardDialog(
    session: AppSession,
    messages: List<MessageEntity>,
    onDismiss: () -> Unit,
    onForwarded: (String) -> Unit,
) {
    val chats by session.store.getChatList().collectAsState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(chats, query) {
        val q = query.trim()
        if (q.isEmpty()) chats else chats.filter { it.chat.name.contains(q, ignoreCase = true) }
    }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (messages.size > 1) "Переслать (${messages.size})" else "Переслать",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Фиксированный диапазон высоты: при фильтрации диалог не прыгает.
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 400.dp)) {
                    items(filtered, key = { it.chat.peerId }) { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        // Порядок сохраняем: пересылка пачкой должна
                                        // лечь в чат в том же виде, что и в исходном.
                                        messages.forEach { session.repository.enqueueForward(entry.chat.peerId, it) }
                                        onForwarded(entry.chat.peerId)
                                    }
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            PeerAvatar(session, entry.chat.peerId, entry.chat.name, entry.chat.avatarFileId, size = 40.dp)
                            Column {
                                Text(entry.chat.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    chatTypeLabel(entry.chat.type),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "Ничего не найдено",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

// Сервер длину названия не проверяет, поэтому предел держим на клиенте:
// длиннее 64 символов всё равно не помещается в списке чатов и шапке.
private const val GROUP_NAME_LIMIT = 64

/** Создание группы/канала с E2E-ключом (порт CreateGroupScreen). */
@Composable
fun CreateGroupDialog(
    session: AppSession,
    isChannelInitial: Boolean,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isChannel by remember { mutableStateOf(isChannelInitial) }
    var withDiscussion by remember { mutableStateOf(true) }
    var memberQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<RelayApi.UserSearchResult>>(emptyList()) }
    val selectedMembers = remember { mutableStateMapOf<String, String>() }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(memberQuery) {
        val q = memberQuery.trim().removePrefix("@")
        if (q.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searchResults = runCatching {
            withContext(Dispatchers.IO) {
                session.api.searchUsers(q)
                    .filter { !it.isGroup && !it.userId.equals(session.myId, ignoreCase = true) }
            }
        }.getOrDefault(emptyList())
    }

    fun doCreate() {
        if (name.isBlank() || isLoading) return
        isLoading = true
        errorText = null
        scope.launch(Dispatchers.IO) {
            try {
                val repo = session.repository
                val api = session.api
                val failed = mutableListOf<String>()

                suspend fun createOne(prefix: String, gName: String, asChannel: Boolean, linkedId: String?): String {
                    val gid = prefix + java.util.UUID.randomUUID().toString().take(8)
                    val keyB64 = repo.newGroupKeyB64()
                    api.createGroup(
                        groupId = gid,
                        name = gName,
                        description = description,
                        isChannel = asChannel,
                        encryptedKeyB64 = repo.wrapGroupKeyFor(repo.myId, keyB64),
                        linkedGroupId = linkedId,
                    )
                    for ((memberId, label) in selectedMembers) {
                        try {
                            api.addGroupMember(gid, memberId, repo.wrapGroupKeyFor(memberId, keyB64))
                        } catch (e: Exception) {
                            failed.add(label)
                        }
                    }
                    repo.registerCreatedGroup(gid, gName, keyB64, asChannel)
                    repo.cacheGroupKey(gid, keyB64, linkedId)
                    return gid
                }

                val discussionId = if (isChannel && withDiscussion) {
                    createOne("group_", "$name · обсуждение", asChannel = false, linkedId = null)
                } else null

                val mainId = createOne(
                    if (isChannel) "channel_" else "group_",
                    name,
                    asChannel = isChannel,
                    linkedId = discussionId,
                )

                withContext(Dispatchers.Main) {
                    if (failed.isNotEmpty()) {
                        errorText = "Не добавлены: ${failed.distinct().joinToString(", ")}"
                        isLoading = false
                    } else {
                        onCreated(mainId)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorText = e.message ?: "Ошибка создания"
                    isLoading = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isChannel) "Новый канал" else "Новая группа",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isChannel,
                        onClick = { isChannel = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Группа") }
                    SegmentedButton(
                        selected = isChannel,
                        onClick = { isChannel = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Канал") }
                }
                Text(
                    if (isChannel) {
                        "Писать в канал могут только администраторы. Публичное @имя назначается " +
                            "позже в настройках: по нему канал находят в поиске и подписываются сами."
                    } else {
                        "Писать могут все участники. Публичное @имя назначается позже в настройках: " +
                            "по нему группу находят в поиске и вступают без приглашения."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(GROUP_NAME_LIMIT) },
                    label = { Text("Название") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            "${name.length} / $GROUP_NAME_LIMIT",
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isChannel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Группа обсуждений", modifier = Modifier.weight(1f))
                        Switch(checked = withDiscussion, onCheckedChange = { withDiscussion = it })
                    }
                }
                OutlinedTextField(
                    value = memberQuery,
                    onValueChange = { memberQuery = it },
                    label = { Text("Добавить участников (поиск)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (selectedMembers.isNotEmpty()) {
                    Text(
                        "Выбраны: " + selectedMembers.values.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(searchResults, key = { it.userId }) { result ->
                        val label = result.displayName.ifBlank { result.username.ifBlank { result.userId } }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedMembers.containsKey(result.userId)) {
                                        selectedMembers.remove(result.userId)
                                    } else {
                                        selectedMembers[result.userId] = label
                                    }
                                }
                                .padding(vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = selectedMembers.containsKey(result.userId),
                                onCheckedChange = null,
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::doCreate, enabled = name.isNotBlank() && !isLoading) {
                Text(if (isLoading) "Создание…" else "Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/** Цифры безопасности (как в Signal): 60 цифр по Olm-identity или box-ключу. */
@Composable
fun SafetyNumbersDialog(
    session: AppSession,
    peerId: String,
    onDismiss: () -> Unit,
) {
    var digits by remember { mutableStateOf("…") }
    var changed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(peerId) {
        val pin = session.trustStore.pinFor(peerId)
        changed = pin?.changedAt != null && pin.previousKeyB64 != null
        val peerKey = pin?.publicKeyB64 ?: runCatching {
            withContext(Dispatchers.IO) { session.api.getPublicKey(peerId) }
        }.getOrDefault("")
        digits = if (peerKey.isBlank()) "ключ собеседника недоступен" else KeyTrustStore.safetyNumber(
            session.myId,
            session.account.keys.publicB64,
            peerId,
            peerKey,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Цифры безопасности", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Сравните цифры с собеседником по другому каналу. Совпадают — шифрование не подменено.",
                    style = MaterialTheme.typography.bodySmall,
                )
                // safetyNumber отдаёт группы по 5 через пробел, а в состоянии может
                // лежать «…» или текст ошибки — поэтому сначала оставляем одни цифры
                // и перегруппировываем только настоящее 60-значное число.
                val rawDigits = digits.filter(Char::isDigit)
                val formatted = if (rawDigits.length == 60) {
                    rawDigits.chunked(4).chunked(5).joinToString("\n") { it.joinToString("  ") }
                } else {
                    digits
                }
                Text(
                    formatted,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                if (changed) {
                    Text(
                        "⚠ Ключ собеседника менялся. Если вы не ожидали смены устройства — сверьте цифры.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { session.trustStore.acceptServerKey(peerId) }
                                onDismiss()
                            }
                        }) {
                            Text("Принять новый ключ")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { session.trustStore.setVerified(peerId, true) }
                onDismiss()
            }) { Text("Цифры совпали") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

/** Мой профиль: имя, био, эмодзи-статус. */
@Composable
fun ProfileDialog(
    session: AppSession,
    onDismiss: () -> Unit,
) {
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching {
            val p = withContext(Dispatchers.IO) { session.api.getUserProfile(session.myId) }
            displayName = p.displayName
            bio = p.bio.orEmpty()
        }
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Профиль @${session.myId}", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Отображаемое имя") },
                    singleLine = true,
                    enabled = loaded,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("О себе") },
                    enabled = loaded,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                session.api.updateProfile(null, displayName.trim(), null, bio.trim())
                            }
                            onDismiss()
                        } catch (e: Exception) {
                            errorText = e.message
                        }
                    }
                },
                enabled = loaded,
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
