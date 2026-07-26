package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.data.ChatEntity
import aether.desktop.data.MessageEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.key.isCtrlPressed
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

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
fun ChatPane(
    session: AppSession,
    peerId: String,
    typingUntil: Long,
    onShowInfo: () -> Unit,
    onForwardRequest: (MessageEntity) -> Unit,
    onOpenViewer: (List<MessageEntity>, Int) -> Unit,
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

    // Автоскролл только если пользователь уже внизу: иначе чтение истории
    // дёргало бы ленту при каждом входящем (в Telegram так же).
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && atBottom) {
            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
        }
        if (atBottom) session.repository.sendReadReceipt(peerId)
    }
    // При открытии чата всегда показываем конец ленты.
    LaunchedEffect(peerId, messages.isNotEmpty()) {
        if (messages.isNotEmpty()) listState.scrollToItem((messages.size - 1).coerceAtLeast(0))
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

    /** Общий путь отправки файлов: пикер, перетаскивание, вставка из буфера. */
    fun sendFiles(files: List<File>, asFile: Boolean) {
        val real = files.filter { it.isFile && it.length() > 0L }
        if (real.isEmpty()) return
        // Шифрование и заливка файла — не на UI-потоке, иначе окно замирает
        // на всё время отправки.
        scope.launch(Dispatchers.IO) {
            val error = if (asFile) session.repository.sendFiles(peerId, real, null)
            else session.repository.sendMedia(peerId, real, null)
            if (error == null) session.store.preloadMessages(peerId)
        }
    }

    fun pickAndSend(asFile: Boolean) {
        val dialog = FileDialog(null as java.awt.Frame?, "Выбор файла", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        sendFiles(dialog.files?.toList().orEmpty(), asFile)
    }

    /** Ctrl+V: скриншот или файлы из буфера уходят как вложение (как в Telegram). */
    fun pasteFromClipboard(): Boolean {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        val contents = runCatching { clipboard.getContents(null) }.getOrNull() ?: return false
        if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = runCatching {
                contents.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<File>
            }.getOrNull().orEmpty()
            if (files.isNotEmpty()) {
                sendFiles(files, asFile = false)
                return true
            }
        }
        if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
            val image = runCatching {
                contents.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.Image
            }.getOrNull() ?: return false
            val buffered = java.awt.image.BufferedImage(
                image.getWidth(null).coerceAtLeast(1),
                image.getHeight(null).coerceAtLeast(1),
                java.awt.image.BufferedImage.TYPE_INT_RGB,
            )
            buffered.createGraphics().apply {
                drawImage(image, 0, 0, null)
                dispose()
            }
            val target = File.createTempFile("aether_paste", ".png")
            javax.imageio.ImageIO.write(buffered, "png", target)
            sendFiles(listOf(target), asFile = false)
            return true
        }
        return false
    }

    val viewable = remember(messages) { viewableMedia(messages) }
    var dragOver by remember(peerId) { mutableStateOf(false) }

    val actions = MessageActions(
        onOpenMedia = { message ->
            val position = viewable.indexOfFirst { it.msgId == message.msgId }
            onOpenViewer(viewable, position.coerceAtLeast(0))
        },
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

        // Лента сообщений + перетаскивание файлов в чат
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { true },
                    target = remember(peerId) {
                        object : DragAndDropTarget {
                            override fun onEntered(event: DragAndDropEvent) { dragOver = true }
                            override fun onExited(event: DragAndDropEvent) { dragOver = false }
                            override fun onEnded(event: DragAndDropEvent) { dragOver = false }
                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                dragOver = false
                                val data = event.awtTransferable
                                if (!data.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                                    return false
                                }
                                @Suppress("UNCHECKED_CAST")
                                val files = runCatching {
                                    data.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<File>
                                }.getOrNull().orEmpty()
                                if (files.isEmpty()) return false
                                // Картинки уходят как фото, остальное — документами.
                                val images = files.filter { isImageFile(it) }
                                val documents = files - images.toSet()
                                if (images.isNotEmpty()) sendFiles(images, asFile = false)
                                if (documents.isNotEmpty()) sendFiles(documents, asFile = true)
                                return true
                            }
                        }
                    },
                ),
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

            // Кнопка «вниз» появляется, когда лента прокручена вверх.
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom && messages.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(44.dp).clickable {
                        scope.launch { listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0)) }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Вниз",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Подсветка зоны перетаскивания
            if (dragOver) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Text(
                            "Отпустите, чтобы отправить",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
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
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    // Enter отправляет, Shift+Enter — перенос строки.
                                    event.key == Key.Enter && !event.isShiftPressed -> { send(); true }
                                    // Ctrl+V: картинка или файлы из буфера уходят вложением.
                                    event.key == Key.V && event.isCtrlPressed -> pasteFromClipboard()
                                    // Esc снимает ответ/редактирование.
                                    event.key == Key.Escape && (replyTo != null || editing != null) -> {
                                        if (editing != null) input = ""
                                        editing = null
                                        replyTo = null
                                        true
                                    }
                                    // Стрелка вверх в пустом поле — правка последнего своего.
                                    event.key == Key.DirectionUp && input.isEmpty() -> {
                                        messages.lastOrNull { it.isOut && !it.text.startsWith("{\"type\"") }
                                            ?.let { last ->
                                                editing = last
                                                replyTo = null
                                                input = last.text
                                            }
                                        true
                                    }
                                    else -> false
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

/** Картинка ли это по расширению — для раскладки перетащенных файлов. */
internal fun isImageFile(file: File): Boolean =
    file.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
