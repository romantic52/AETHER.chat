package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.data.MessageEntity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Медиа чата, пригодные для просмотра во встроенном вьюере. */
fun viewableMedia(messages: List<MessageEntity>): List<MessageEntity> =
    messages.filter { message ->
        val payload = runCatching { JSONObject(message.text) }.getOrNull() ?: return@filter false
        if (payload.optString("type") != "media") return@filter false
        payload.optString("kind") == "image" || payload.optString("mime_type").startsWith("image/")
    }

/** Декодированные миниатюры (msgId -> bitmap) на время сессии: без них лента внизу дёргается. */
private val thumbnailCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

/** Наибольшая сторона миниатюры в пикселях. */
private const val THUMBNAIL_SIDE = 160

/**
 * Полноэкранный просмотрщик фото поверх окна: зум колесом, панорамирование
 * перетаскиванием, стрелки между фото чата, лента миниатюр внизу, Esc —
 * закрыть, сохранение и копирование в буфер.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MediaViewer(
    session: AppSession,
    items: List<MessageEntity>,
    startIndex: Int,
    onClose: () -> Unit,
) {
    if (items.isEmpty()) return
    // Текущий снимок держим по msgId, а не по позиции: лента чата пополняется
    // на ходу, и позиционный индекс тогда указывает уже на другое фото — вместе
    // с ним слетал бы и зум.
    val initialId = items[startIndex.coerceIn(0, items.lastIndex)].msgId
    var currentId by remember(initialId) { mutableStateOf(initialId) }
    val index = items.indexOfFirst { it.msgId == currentId }
        .takeIf { it >= 0 } ?: startIndex.coerceIn(0, items.lastIndex)
    val current = items[index]

    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    // Режим показа и зум живут ровно столько, сколько открыт один и тот же
    // снимок: обновление списка сообщений их не трогает.
    var fitToWindow by remember(current.msgId) { mutableStateOf(true) }
    var scale by remember(current.msgId) { mutableStateOf(1f) }
    var offsetX by remember(current.msgId) { mutableStateOf(0f) }
    var offsetY by remember(current.msgId) { mutableStateOf(0f) }
    val focusRequester = remember { FocusRequester() }
    val animatedScale by animateFloatAsState(scale)

    fun resetView() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    fun move(delta: Int) {
        if (items.size <= 1) return
        currentId = items[(index + delta + items.size) % items.size].msgId
    }

    LaunchedEffect(current.msgId) {
        bitmap = null
        failed = false
        file = null
        val loaded = withContext(Dispatchers.IO) {
            val downloaded = session.repository.downloadMedia(current.text)
            downloaded to downloaded?.let {
                runCatching { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
                    .getOrNull()
            }
        }
        file = loaded.first
        bitmap = loaded.second
        failed = loaded.second == null
    }

    // Фокус запрашиваем ПОСЛЕ первого кадра: до этого модификатор ещё не
    // привязан к узлу и requestFocus падает («FocusRequester is not initialized»).
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    val chatName = remember(current.peerId) {
        session.store.cachedChat(current.peerId)?.name?.takeIf(String::isNotBlank) ?: current.peerId
    }
    // Отправителя в сообщении нет — ядро хранит его только как peerId, поэтому
    // для чужого снимка показываем имя чата.
    val senderName = if (current.isOut) "Вы" else chatName

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onClose(); true }
                    Key.DirectionLeft -> { move(-1); true }
                    Key.DirectionRight, Key.Spacebar -> { move(1); true }
                    Key.Equals, Key.Plus -> { scale = (scale * 1.25f).coerceAtMost(8f); true }
                    Key.Minus -> { scale = (scale / 1.25f).coerceAtLeast(1f); true }
                    Key.F -> { fitToWindow = !fitToWindow; resetView(); true }
                    Key.Zero -> { fitToWindow = true; resetView(); true }
                    else -> false
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        senderName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildList {
                            add(formatMediaDate(current.timestamp))
                            if (items.size > 1) add("${index + 1} из ${items.size}")
                        }.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { fitToWindow = !fitToWindow; resetView() }) {
                    Icon(
                        if (fitToWindow) Icons.Filled.ZoomIn else Icons.Filled.ZoomOut,
                        contentDescription = if (fitToWindow) "Натуральный размер" else "Вписать в окно",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { file?.let(::copyToClipboard) }, enabled = file != null) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать", tint = Color.White)
                }
                IconButton(onClick = { saveAs(current, file) }, enabled = file != null) {
                    Icon(Icons.Filled.Download, contentDescription = "Сохранить как", tint = Color.White)
                }
                IconButton(onClick = { file?.let(::openInSystemViewer) }, enabled = file != null) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = "Открыть во внешнем", tint = Color.White)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        if (bitmap == null) return@onPointerEvent
                        val delta = event.changes.first().scrollDelta.y
                        scale = (scale * if (delta < 0) 1.15f else 1 / 1.15f).coerceIn(1f, 8f)
                        if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                    }
                    // Клик мимо снимка закрывает; по самому снимку, по шапке и
                    // по ленте миниатюр — нет.
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
                contentAlignment = Alignment.Center,
            ) {
                val image = bitmap
                when {
                    image != null -> {
                        val density = LocalDensity.current
                        // «Вписать в окно» никогда не растягивает снимок сверх
                        // натурального размера: растянутая мелкая картинка мылится.
                        val fitScale = minOf(
                            with(density) { maxWidth.toPx() } / image.width,
                            with(density) { maxHeight.toPx() } / image.height,
                            1f,
                        )
                        val base = if (fitToWindow) fitScale else 1f
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(
                                    with(density) { (image.width * base).toDp() },
                                    with(density) { (image.height * base).toDp() },
                                )
                                .graphicsLayer(
                                    scaleX = animatedScale,
                                    scaleY = animatedScale,
                                    translationX = offsetX,
                                    translationY = offsetY,
                                )
                                .pointerInput(current.msgId) {
                                    detectDragGestures { _, dragAmount ->
                                        if (scale > 1f || !fitToWindow) {
                                            offsetX += dragAmount.x
                                            offsetY += dragAmount.y
                                        }
                                    }
                                }
                                .pointerInput(current.msgId) {
                                    detectTapGestures(
                                        onDoubleTap = { fitToWindow = !fitToWindow; resetView() },
                                        onTap = { /* клик по фото не закрывает */ },
                                    )
                                },
                        )
                    }
                    failed -> Text("Не удалось загрузить изображение", color = Color.White)
                    else -> CircularProgressIndicator(color = Color.White)
                }
            }

            val caption = remember(current.msgId) {
                runCatching { JSONObject(current.text).optString("caption") }.getOrNull().orEmpty()
            }
            if (caption.isNotBlank()) {
                Text(
                    caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            if (items.size > 1) {
                MediaFilmstrip(
                    session = session,
                    items = items,
                    currentIndex = index,
                    onPick = { currentId = items[it].msgId },
                )
            }
        }

        if (items.size > 1) {
            IconButton(
                onClick = { move(-1) },
                modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущее", tint = Color.White)
            }
            IconButton(
                onClick = { move(1) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp).size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующее", tint = Color.White)
            }
        }
    }
}

/** Лента миниатюр всех фото чата: текущее обведено рамкой, клик переключает на него. */
@Composable
private fun MediaFilmstrip(
    session: AppSession,
    items: List<MessageEntity>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    // При переключении стрелками лента должна догонять выбранный кадр, иначе
    // рамка уезжает за край.
    LaunchedEffect(currentIndex) {
        runCatching { state.animateScrollToItem(currentIndex.coerceAtLeast(0)) }
    }
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(items, key = { _, message -> message.msgId }) { position, message ->
            val selected = position == currentIndex
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.20f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { onPick(position) },
            ) {
                MediaThumbnail(session, message, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Квадратная миниатюра медиа-сообщения. Скачивается и декодируется один раз на
 * сессию, поэтому годится и для ленты просмотрщика, и для сетки в панели
 * информации.
 */
@Composable
internal fun MediaThumbnail(
    session: AppSession,
    message: MessageEntity,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(message.msgId) { mutableStateOf(thumbnailCache[message.msgId]) }

    LaunchedEffect(message.msgId) {
        if (bitmap != null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            thumbnailCache[message.msgId] ?: session.repository.downloadMedia(message.text)
                ?.let(::decodeThumbnail)
                ?.also { thumbnailCache[message.msgId] = it }
        }
    }

    val current = bitmap
    Box(
        modifier = modifier.background(Color.Gray.copy(alpha = 0.30f)),
        contentAlignment = Alignment.Center,
    ) {
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Уменьшенная копия снимка: полноразмерные bitmap'ы всех фото чата съели бы
 * сотни мегабайт и заметно тормозили бы прокрутку ленты миниатюр.
 */
private fun decodeThumbnail(file: File): ImageBitmap? {
    val source = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull()
        // ImageIO не знает часть форматов (webp) — там выручает декодер Skia.
        ?: return runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
        }.getOrNull()
    val longest = maxOf(source.width, source.height).coerceAtLeast(1)
    if (longest <= THUMBNAIL_SIDE) return runCatching { source.toComposeImageBitmap() }.getOrNull()
    val ratio = THUMBNAIL_SIDE.toFloat() / longest
    val width = (source.width * ratio).toInt().coerceAtLeast(1)
    val height = (source.height * ratio).toInt().coerceAtLeast(1)
    val scaled = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    scaled.createGraphics().apply {
        setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        drawImage(source, 0, 0, width, height, null)
        dispose()
    }
    return runCatching { scaled.toComposeImageBitmap() }.getOrNull()
}

/** Дата снимка в шапке: «сегодня в 14:35», «12 марта 2025 в 09:04». */
private fun formatMediaDate(timestamp: Long): String {
    val locale = java.util.Locale("ru")
    val date = java.util.Date(timestamp)
    val time = java.text.SimpleDateFormat("HH:mm", locale).format(date)
    val shot = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = java.util.Calendar.getInstance()
    val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }

    fun sameDay(left: java.util.Calendar, right: java.util.Calendar): Boolean =
        left.get(java.util.Calendar.YEAR) == right.get(java.util.Calendar.YEAR) &&
            left.get(java.util.Calendar.DAY_OF_YEAR) == right.get(java.util.Calendar.DAY_OF_YEAR)

    return when {
        sameDay(shot, today) -> "сегодня в $time"
        sameDay(shot, yesterday) -> "вчера в $time"
        shot.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) ->
            java.text.SimpleDateFormat("d MMMM", locale).format(date) + " в $time"
        else -> java.text.SimpleDateFormat("d MMMM yyyy", locale).format(date) + " в $time"
    }
}

private fun copyToClipboard(file: File) {
    runCatching {
        val image = javax.imageio.ImageIO.read(file) ?: return
        val transferable = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) =
                flavor == java.awt.datatransfer.DataFlavor.imageFlavor
            override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = image
        }
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }
}

private fun saveAs(message: MessageEntity, cached: File?) {
    val source = cached ?: return
    val payload = runCatching { JSONObject(message.text) }.getOrNull()
    val suggested = payload?.optString("file_name")?.takeIf { it.isNotBlank() && it.contains('.') }
        ?: ("photo_" + message.msgId.take(8) + "." + extensionOf(payload?.optString("mime_type").orEmpty()))
    val dialog = FileDialog(null as Frame?, "Сохранить как", FileDialog.SAVE).apply {
        file = suggested
        isVisible = true
    }
    val dir = dialog.directory ?: return
    val name = dialog.file ?: return
    runCatching { source.copyTo(File(dir, name), overwrite = true) }
}

private fun openInSystemViewer(file: File) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(file)
    }
}

private fun extensionOf(mime: String): String = when {
    mime.contains("png") -> "png"
    mime.contains("webp") -> "webp"
    mime.contains("gif") -> "gif"
    else -> "jpg"
}
