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
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    onForwardRequest: (List<MessageEntity>) -> Unit,
    onOpenViewer: (List<MessageEntity>, Int) -> Unit,
) {
    // Поток и позиция скролла привязаны к peerId: без key() при смене чата на
    // мгновение показывалась лента предыдущего и переносился скролл.
    val messages by remember(peerId) { session.store.getMessagesForPeer(peerId) }.collectAsState()
    var chat by remember(peerId) { mutableStateOf<ChatEntity?>(null) }
    var presence by remember(peerId) { mutableStateOf("") }
    var canPost by remember(peerId) { mutableStateOf(true) }
    // Текст поля живёт в черновиках: переход в другой чат и перезапуск не должны
    // стирать недописанное.
    var input by remember(peerId) { mutableStateOf(aether.desktop.data.Drafts.get(peerId)) }
    LaunchedEffect(peerId, input) {
        delay(400)
        aether.desktop.data.Drafts.set(peerId, input)
    }
    var replyTo by remember(peerId) { mutableStateOf<MessageEntity?>(null) }
    var editing by remember(peerId) { mutableStateOf<MessageEntity?>(null) }
    val scope = rememberCoroutineScope()
    val listState = remember(peerId) { LazyListState() }
    // Курсор сразу в поле ввода при открытии чата — как в Telegram, где можно
    // начать печатать не целясь мышью.
    val composerFocus = remember { FocusRequester() }
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
    LaunchedEffect(peerId) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { composerFocus.requestFocus() }
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank()) return
        val currentEditing = editing
        val currentReply = replyTo
        input = ""
        aether.desktop.data.Drafts.clear(peerId)
        replyTo = null
        editing = null
        scope.launch {
            if (currentEditing != null) {
                session.repository.editMessage(peerId, currentEditing.msgId, text)
            } else {
                aether.desktop.media.UiSounds.playSent()
                session.repository.enqueueText(
                    peerId,
                    text,
                    currentReply?.msgId,
                    currentReply?.let { aether.desktop.data.messagePreview(it.text, "") .take(120) },
                )
            }
        }
    }

    // Что показать в предпросмотре вложений: файлы и режим по умолчанию.
    var pendingAttach by remember(peerId) { mutableStateOf<Pair<List<File>, Boolean>?>(null) }

    /** Общий путь отправки вложений: пикер, перетаскивание, вставка из буфера. */
    fun sendWithCaption(files: List<File>, caption: String, asFile: Boolean) {
        val real = files.filter { it.isFile && it.length() > 0L }
        if (real.isEmpty()) return
        // Шифрование и заливка файла — не на UI-потоке, иначе окно замирает
        // на всё время отправки.
        scope.launch(Dispatchers.IO) {
            val error = if (asFile) session.repository.sendFiles(peerId, real, caption.ifBlank { null })
            else session.repository.sendMedia(peerId, real, caption.ifBlank { null })
            if (error == null) session.store.preloadMessages(peerId)
        }
    }

    fun pickAndSend(asFile: Boolean) {
        val dialog = FileDialog(null as java.awt.Frame?, "Выбор файла", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        val chosen = dialog.files?.toList().orEmpty().filter { it.isFile && it.length() > 0L }
        if (chosen.isNotEmpty()) pendingAttach = chosen to asFile
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
                pendingAttach = files to files.none { isImageFile(it) }
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
            pendingAttach = listOf(target) to false
            return true
        }
        return false
    }

    // Запись голосовых: один диктофон на панель, состояние переживает смену чата
    // только если запись не идёт — иначе микрофон остался бы открытым.
    val recorder = remember { aether.desktop.media.VoiceRecorder() }
    val recording by recorder.state.collectAsState()
    var micDenied by remember { mutableStateOf(false) }
    var emojiOpen by remember(peerId) { mutableStateOf(false) }

    fun stopAndSendVoice() {
        val captured = recorder.stop() ?: return
        scope.launch(Dispatchers.IO) {
            val result = aether.desktop.media.VoiceRecorder.encode(captured)
            session.repository.sendVoice(peerId, result.file, result.durationMs, result.waveform)
            result.file.delete()
            session.store.preloadMessages(peerId)
        }
    }

    DisposableEffect(peerId) {
        onDispose { recorder.cancel() }
    }

    // Поиск по открытому чату (Ctrl+F): история peer'а целиком в памяти,
    // поэтому ищем на месте, без запросов к серверу.
    var searchOpen by remember(peerId) { mutableStateOf(false) }
    var searchQuery by remember(peerId) { mutableStateOf("") }
    var hitIndex by remember(peerId) { mutableStateOf(0) }
    val hits = remember(messages, searchQuery) {
        val q = searchQuery.trim()
        if (q.length < 2) emptyList()
        else messages.mapIndexedNotNull { index, message ->
            index.takeIf { messageSearchText(message).contains(q, ignoreCase = true) }
        }
    }
    val currentHit = hits.getOrNull(hitIndex.coerceIn(0, (hits.size - 1).coerceAtLeast(0)))
    LaunchedEffect(currentHit) {
        currentHit?.let { listState.animateScrollToItem(it) }
    }
    LaunchedEffect(searchQuery) { hitIndex = 0 }

    // Разделитель непрочитанных: счётчик снимается один раз при открытии чата,
    // до того как отправка квитанции его обнулит.
    val unreadAnchor = remember(peerId) { mutableStateOf<String?>(null) }
    LaunchedEffect(peerId, messages.size) {
        if (unreadAnchor.value == null) {
            val count = session.store.cachedChat(peerId)?.unreadCount ?: 0
            if (count in 1..messages.size) {
                unreadAnchor.value = messages.getOrNull(messages.size - count)
                    ?.takeIf { !it.isOut }?.msgId
            }
        }
    }

    // Выделение нескольких сообщений: включается из контекстного меню.
    val selectedIds = remember(peerId) { androidx.compose.runtime.mutableStateListOf<String>() }
    val selectionMode = selectedIds.isNotEmpty()
    fun toggleSelect(message: MessageEntity) {
        if (!selectedIds.remove(message.msgId)) selectedIds.add(message.msgId)
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
        onForward = { onForwardRequest(listOf(it)) },
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
        // Шапка чата (в режиме выделения её заменяет панель действий)
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                onCancel = { selectedIds.clear() },
                onCopy = {
                    val text = messages.filter { it.msgId in selectedIds }
                        .joinToString("\n") { aether.desktop.data.messagePreview(it.text, "") }
                    val selection = java.awt.datatransfer.StringSelection(text)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                    selectedIds.clear()
                },
                onForward = {
                    onForwardRequest(messages.filter { it.msgId in selectedIds })
                    selectedIds.clear()
                },
                onDelete = {
                    val chosen = messages.filter { it.msgId in selectedIds }
                    selectedIds.clear()
                    scope.launch { chosen.forEach { session.repository.deleteForMe(it.msgId) } }
                },
                onDeleteForAll = {
                    val chosen = messages.filter { it.msgId in selectedIds && it.isOut }
                    selectedIds.clear()
                    scope.launch {
                        chosen.forEach { session.repository.deleteForEveryone(peerId, it.msgId) }
                    }
                },
                canDeleteForAll = messages.any { it.msgId in selectedIds && it.isOut },
            )
        } else
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
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }) {
                    Icon(Icons.Filled.Search, contentDescription = "Поиск по чату")
                }
            }
        }

        if (searchOpen) {
            ChatSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                hitCount = hits.size,
                hitIndex = hitIndex,
                onPrev = { if (hits.isNotEmpty()) hitIndex = (hitIndex - 1 + hits.size) % hits.size },
                onNext = { if (hits.isNotEmpty()) hitIndex = (hitIndex + 1) % hits.size },
                onClose = { searchOpen = false; searchQuery = "" },
            )
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
                                // Перетаскивание тоже проходит через предпросмотр:
                                // случайный сброс мимо не должен уходить собеседнику.
                                // Картинок нет — значит это документы.
                                pendingAttach = files to files.none { isImageFile(it) }
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
                if (message.msgId == unreadAnchor.value) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                            Text(
                                "Непрочитанные сообщения",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
                MessageBubble(
                    session = session,
                    message = message,
                    isGroupChat = (chat?.type ?: 0) in 1..2,
                    actions = actions,
                    highlighted = index == currentHit,
                    selectionMode = selectionMode,
                    selected = message.msgId in selectedIds,
                    onToggleSelect = { toggleSelect(message) },
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
                if (recording.recording) {
                    VoiceRecordingBar(
                        millis = recording.millis,
                        level = recording.level,
                        waveform = recording.waveform,
                        onCancel = { recorder.cancel() },
                        onSend = ::stopAndSendVoice,
                    )
                } else {
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
                    Box {
                        IconButton(onClick = { emojiOpen = !emojiOpen }) {
                            Icon(Icons.Filled.EmojiEmotions, contentDescription = "Эмодзи")
                        }
                        if (emojiOpen) {
                            EmojiPicker(
                                onPick = { emoji -> input += emoji },
                                onDismiss = { emojiOpen = false },
                            )
                        }
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
                            .focusRequester(composerFocus)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    // Enter отправляет, Shift+Enter — перенос строки.
                                    event.key == Key.Enter && !event.isShiftPressed -> { send(); true }
                                    // Ctrl+V: картинка или файлы из буфера уходят вложением.
                                    event.key == Key.V && event.isCtrlPressed -> pasteFromClipboard()
                                    // Ctrl+F открывает поиск по чату.
                                    event.key == Key.F && event.isCtrlPressed -> { searchOpen = true; true }
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
                    // Пустое поле — микрофон, как в Telegram; есть текст — самолётик.
                    if (input.isBlank()) {
                        IconButton(onClick = { micDenied = !recorder.start() }) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Записать голосовое",
                                tint = if (micDenied) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        IconButton(onClick = ::send) {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = "Отправить",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
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

    pendingAttach?.let { (files, asFile) ->
        AttachDialog(
            files = files,
            asFileInitial = asFile,
            onDismiss = { pendingAttach = null },
            onSend = { chosen, caption, sendAsFile ->
                pendingAttach = null
                sendWithCaption(chosen, caption, sendAsFile)
            },
        )
    }
}

/** Панель массовых действий над выделенными сообщениями. */
@Composable
private fun SelectionBar(
    count: Int,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onDeleteForAll: () -> Unit,
    canDeleteForAll: Boolean,
) {
    var confirmAll by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 1.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Отменить выделение")
            }
            Text(
                "Выбрано: $count",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать")
            }
            IconButton(onClick = onForward) {
                Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = "Переслать")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить у меня")
            }
            if (canDeleteForAll) {
                IconButton(onClick = { confirmAll = true }) {
                    Icon(
                        Icons.Filled.DeleteForever,
                        contentDescription = "Удалить у всех",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (confirmAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Удалить у всех?") },
            text = { Text("Сообщения исчезнут и у собеседника. Отменить будет нельзя.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmAll = false; onDeleteForAll() }) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmAll = false }) { Text("Отмена") }
            },
        )
    }
}

/** Строка поиска по открытому чату: счётчик совпадений и переход по ним. */
@Composable
private fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    hitCount: Int,
    hitIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Поиск по чату") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onClose(); true }
                        Key.Enter -> { onNext(); true }
                        else -> false
                    }
                },
            )
            Text(
                if (query.trim().length < 2) "" else if (hitCount == 0) "нет совпадений"
                else "${hitIndex + 1} из $hitCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            IconButton(onClick = onPrev, enabled = hitCount > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Предыдущее")
            }
            IconButton(onClick = onNext, enabled = hitCount > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Следующее")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть поиск")
            }
        }
    }
}

/** Текст сообщения для поиска: у медиа ищем по подписи и имени файла. */
private fun messageSearchText(message: MessageEntity): String {
    val raw = message.text
    if (!raw.trimStart().startsWith("{")) return raw
    val json = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return raw
    if (json.optString("type") != "media") return raw
    return listOf(json.optString("caption"), json.optString("file_name")).joinToString(" ")
}

/** Композер во время записи: таймер, живая волна, отмена и отправка. */
@Composable
private fun VoiceRecordingBar(
    millis: Long,
    level: Float,
    waveform: List<Int>,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val blink by androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
        .animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(700),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "recAlpha",
        )
    val waveColor = MaterialTheme.colorScheme.primary
    // Панель забирает фокус на себя: иначе Esc некому поймать — поле ввода на
    // время записи из композера убрано.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onCancel(); true }
                    Key.Enter -> { onSend(); true }
                    else -> false
                }
            },
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Delete, contentDescription = "Отменить", tint = MaterialTheme.colorScheme.error)
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.error.copy(alpha = blink), androidx.compose.foundation.shape.CircleShape),
        )
        Text(
            formatRecordTime(millis),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 10.dp, end = 12.dp),
        )
        // Волна ползёт справа налево: видно последние ~5 секунд.
        androidx.compose.foundation.Canvas(modifier = Modifier.weight(1f).height(28.dp)) {
            val tail = waveform.takeLast(50)
            if (tail.isEmpty()) return@Canvas
            val barWidth = size.width / (tail.size * 1.6f)
            tail.forEachIndexed { index, amp ->
                val h = ((amp / 31f).coerceAtLeast(0.08f)) * size.height
                drawRoundRect(
                    color = waveColor,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        size.width - (tail.size - index) * barWidth * 1.6f,
                        (size.height - h) / 2f,
                    ),
                    size = androidx.compose.ui.geometry.Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
                )
            }
        }
        Text(
            "Esc — отменить",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        IconButton(onClick = onSend) {
            Icon(Icons.Filled.Send, contentDescription = "Отправить", tint = MaterialTheme.colorScheme.primary)
        }
    }
    // level используется для мгновенной обратной связи, если волна ещё пуста.
    if (waveform.isEmpty() && level > 0f) Unit
}

private fun formatRecordTime(millis: Long): String {
    val total = millis / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** Панель эмодзи над композером: вкладки категорий и сетка символов. */
@Composable
private fun EmojiPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var category by remember { mutableStateOf(0) }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.BottomStart,
        // Панель раскрывается вверх и упирается нижним краем в кнопку.
        offset = androidx.compose.ui.unit.IntOffset(0, -44),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier.size(width = 320.dp, height = 260.dp),
        ) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                    EMOJI_CATEGORIES.forEachIndexed { index, group ->
                        Text(
                            group.first,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { category = index }
                                .background(
                                    if (index == category) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(vertical = 6.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                ) {
                    items(EMOJI_CATEGORIES[category].second) { emoji ->
                        Text(
                            emoji,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .clickable { onPick(emoji) }
                                .padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

private val EMOJI_CATEGORIES: List<Pair<String, List<String>>> = listOf(
    "😀" to (
        "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤗 🤭 🤔 " +
            "🤨 😐 😑 😶 😏 😒 🙄 😬 😮 😯 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤧 😷 🤒 🤕 🤑 😎 🤓 🧐 " +
            "😕 😟 🙁 😮‍💨 😯 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 😤 😡 😠 🤬 💀 👻 🤡"
        ).split(" ")
    ,
    "👍" to (
        "👍 👎 👌 🤌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 🙏 ✍️ 💪 🦾 👏 🙌 👐 🤲 " +
            "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 🔥 ⭐ 🌟 ✨ ⚡ 💥 💫 💯 ✅ ❌"
        ).split(" ")
    ,
    "🐶" to (
        "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦉 🦇 🐺 🐗 🐴 " +
            "🦄 🐝 🐛 🦋 🐌 🐞 🐢 🐍 🐙 🦑 🦐 🦀 🐬 🐳 🐟 🌷 🌸 🌹 🌻 🌼 🌱 🌲 🌳 🌴 🍀 🍁 🍂"
        ).split(" ")
    ,
    "🍕" to (
        "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🍆 🥕 🌽 🌶️ 🥒 🥬 🥦 🧄 🧅 🥔 " +
            "🍞 🥐 🥖 🧀 🥚 🍳 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🌯 🥙 🍜 🍝 🍣 🍤 🍦 🍰 🎂 🍫 🍬 ☕ 🍺 🍷"
        ).split(" ")
    ,
    "⚽" to (
        "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥅 🏒 🏑 🏏 ⛳ 🏹 🎣 🥊 🥋 🎽 ⛸️ 🎿 🛷 🏂 🏋️ 🤸 ⛹️ 🚴 " +
            "🎮 🎲 🎯 🎳 🎪 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 ✈️ 🚗 🚕 🚙 🚌 🚑 🚒 🚀 🛸 ⛵"
        ).split(" ")
    ,
    "💡" to (
        "💡 🔦 🕯️ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 💿 📷 📸 📹 🎥 📺 📻 ⏰ ⌚ 📅 📆 📌 📍 📎 🔗 📏 📐 ✂️ " +
            "🔒 🔓 🔑 🔨 🪓 ⚙️ 🔧 🧲 💣 🧨 🛡️ 🚪 🛏️ 🚿 🧴 🧻 🧹 🎁 🎈 🎉 🎊 🏆 🥇 🥈 🥉 📚 ✏️"
        ).split(" ")
    ,
)

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
