package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.data.ChatEntity
import aether.desktop.data.MessageEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatPane(
    session: AppSession,
    peerId: String,
    typingUntil: Long,
    onShowInfo: () -> Unit,
    onForwardRequest: (MessageEntity) -> Unit,
) {
    // Поток и позиция скролла привязаны к peerId: без key() при смене чата на
    // мгновение показывалась лента предыдущего и переносился скролл.
    val messages by remember(peerId) { session.store.getMessagesForPeer(peerId) }.collectAsState()
    var chat by remember(peerId) { mutableStateOf<ChatEntity?>(null) }
    var presence by remember(peerId) { mutableStateOf("") }
    var canPost by remember(peerId) { mutableStateOf(true) }
    var input by remember(peerId) { mutableStateOf("") }
    var replyTo by remember(peerId) { mutableStateOf<MessageEntity?>(null) }
    var editing by remember(peerId) { mutableStateOf<MessageEntity?>(null) }
    val scope = rememberCoroutineScope()
    val listState = remember(peerId) { LazyListState() }
    var lastTypingSentAt by remember(peerId) { mutableStateOf(0L) }
    // «печатает…» гаснет сам: без тика состояние висело до следующей перекомпозиции.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(typingUntil) {
        while (System.currentTimeMillis() < typingUntil) {
            delay(500)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }

    // Всё, что ходит в сеть или в ядро, — на IO: иначе открытие чата
    // подвешивает окно на время запросов.
    LaunchedEffect(peerId) {
        withContext(Dispatchers.IO) {
            session.store.preloadMessages(peerId)
            val loaded = session.store.getChat(peerId)
            val allowed = runCatching { session.repository.canPostTo(peerId) }.getOrDefault(true)
            withContext(Dispatchers.Main) {
                chat = loaded
                canPost = allowed
            }
            session.repository.sendReadReceipt(peerId)
            if (loaded?.type == 0 && !peerId.equals(session.myId, ignoreCase = true)) {
                val status = runCatching {
                    formatPresence(session.api.getUserProfile(peerId).lastActive)
                }.getOrDefault("")
                withContext(Dispatchers.Main) { presence = status }
            }
        }
    }

    // Новые входящие в открытом чате: квитанция «прочитано» и сброс непрочитанного.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem((messages.size - 1).coerceAtLeast(0))
        }
        session.repository.sendReadReceipt(peerId)
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank()) return
        val currentEditing = editing
        val currentReply = replyTo
        input = ""
        replyTo = null
        editing = null
        scope.launch {
            if (currentEditing != null) {
                session.repository.editMessage(peerId, currentEditing.msgId, text)
            } else {
                session.repository.enqueueText(
                    peerId,
                    text,
                    currentReply?.msgId,
                    currentReply?.let { aether.desktop.data.messagePreview(it.text, "") .take(120) },
                )
            }
        }
    }

    fun pickAndSend(asFile: Boolean) {
        val dialog = FileDialog(null as java.awt.Frame?, "Выбор файла", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        val files = dialog.files?.toList().orEmpty().filter(File::isFile)
        if (files.isEmpty()) return
        // Шифрование и заливка файла — не на UI-потоке, иначе окно замирает
        // на всё время отправки.
        scope.launch(Dispatchers.IO) {
            val error = if (asFile) session.repository.sendFiles(peerId, files, null)
            else session.repository.sendMedia(peerId, files, null)
            if (error == null) session.store.preloadMessages(peerId)
        }
    }

    val actions = MessageActions(
        onReply = { replyTo = it; editing = null },
        onEdit = { editing = it; replyTo = null; input = it.text },
        onForward = onForwardRequest,
        onDeleteForMe = { msg -> scope.launch { session.repository.deleteForMe(msg.msgId) } },
        onDeleteForAll = { msg -> scope.launch { session.repository.deleteForEveryone(peerId, msg.msgId) } },
        onReact = { msg, emoji -> scope.launch { session.repository.react(peerId, msg.msgId, emoji) } },
        onRetry = { msg -> session.repository.retryMessage(msg.msgId) },
        onCopy = { msg ->
            val selection = java.awt.datatransfer.StringSelection(msg.text)
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        },
    )

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Шапка чата
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShowInfo)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                PeerAvatar(session, peerId, chat?.name ?: peerId, chat?.avatarFileId, size = 40.dp)
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        chatDisplayName(chat?.name ?: peerId, chat?.statusEmoji),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = when {
                        now < typingUntil -> "печатает…"
                        chat?.type == 1 -> "группа"
                        chat?.type == 2 -> "канал"
                        chat?.type == 3 -> "ваши заметки"
                        presence.isNotBlank() -> presence
                        else -> ""
                    }
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (subtitle == "печатает…") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Лента сообщений
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.msgId }) { index, message ->
                val day = formatDayHeader(message.timestamp)
                val prevDay = if (index > 0) formatDayHeader(messages[index - 1].timestamp) else ""
                if (day != prevDay) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        ) {
                            Text(
                                day,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                MessageBubble(
                    session = session,
                    message = message,
                    isGroupChat = (chat?.type ?: 0) in 1..2,
                    actions = actions,
                )
            }
        }

        // Плашка ответа/редактирования
        val bar = editing ?: replyTo
        if (bar != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (editing != null) "Редактирование" else "Ответ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            aether.desktop.data.messagePreview(bar.text, ""),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        if (editing != null) input = ""
                        editing = null
                        replyTo = null
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Отмена", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Композер
        if (canPost) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    IconButton(onClick = { pickAndSend(asFile = false) }) {
                        Icon(Icons.Filled.Image, contentDescription = "Фото/видео")
                    }
                    IconButton(onClick = { pickAndSend(asFile = true) }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Файл")
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            val now = System.currentTimeMillis()
                            if (it.isNotBlank() && now - lastTypingSentAt > 3_000) {
                                lastTypingSentAt = now
                                session.realtime.sendTyping(peerId)
                            }
                        },
                        placeholder = { Text("Сообщение") },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 140.dp)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter && !event.isShiftPressed
                                ) {
                                    send()
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                    IconButton(onClick = ::send, enabled = input.isNotBlank()) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "Отправить",
                            tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Публиковать в канале могут только администраторы",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatPresence(lastActive: String?): String {
    val ts = aether.desktop.api.RelayApi.parseUtcIso(lastActive)
    if (ts <= 0L) return ""
    val ago = System.currentTimeMillis() - ts
    return when {
        ago < 70_000L -> "в сети"
        ago < 3_600_000L -> "был(а) ${ago / 60_000} мин назад"
        ago < 86_400_000L -> "был(а) ${ago / 3_600_000} ч назад"
        else -> "был(а) " + java.text.SimpleDateFormat("d MMMM", java.util.Locale("ru"))
            .format(java.util.Date(ts))
    }
}
