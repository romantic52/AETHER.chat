package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.data.MessageEntity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
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

/**
 * Полноэкранный просмотрщик фото поверх окна: зум колесом, панорамирование
 * перетаскиванием, стрелки между фото чата, Esc — закрыть, сохранение и
 * копирование в буфер. Раньше фото открывалось системным приложением.
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
    var index by remember(items, startIndex) { mutableStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var scale by remember(index) { mutableStateOf(1f) }
    var offsetX by remember(index) { mutableStateOf(0f) }
    var offsetY by remember(index) { mutableStateOf(0f) }
    var file by remember { mutableStateOf<File?>(null) }
    val focusRequester = remember { FocusRequester() }
    val current = items.getOrNull(index)
    val animatedScale by animateFloatAsState(scale)

    fun move(delta: Int) {
        if (items.size <= 1) return
        index = (index + delta + items.size) % items.size
    }

    LaunchedEffect(current?.msgId) {
        bitmap = null
        failed = false
        file = null
        val message = current ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            val downloaded = session.repository.downloadMedia(message.text)
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
                        Key.Zero -> { scale = 1f; offsetX = 0f; offsetY = 0f; true }
                        else -> false
                    }
                }
                // Клик по фону закрывает; по самому фото — нет.
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(56.dp)
                        .graphicsLayer(
                            scaleX = animatedScale,
                            scaleY = animatedScale,
                            translationX = offsetX,
                            translationY = offsetY,
                        )
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val delta = event.changes.first().scrollDelta.y
                            scale = (scale * if (delta < 0) 1.15f else 1 / 1.15f).coerceIn(1f, 8f)
                            if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                        }
                        .pointerInput(index) {
                            detectDragGestures { _, dragAmount ->
                                if (scale > 1f) {
                                    offsetX += dragAmount.x
                                    offsetY += dragAmount.y
                                }
                            }
                        }
                        .pointerInput(index) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else scale = 2.5f
                                },
                                onTap = { /* клик по фото не закрывает */ },
                            )
                        },
                )
                failed -> Text("Не удалось загрузить изображение", color = Color.White)
                else -> CircularProgressIndicator(color = Color.White)
            }

            // Верхняя панель: счётчик и действия
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (items.size > 1) "${index + 1} из ${items.size}" else "",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                IconButton(onClick = { file?.let(::copyToClipboard) }, enabled = file != null) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать", tint = Color.White)
                }
                IconButton(onClick = { current?.let { saveAs(session, it, file) } }, enabled = file != null) {
                    Icon(Icons.Filled.Download, contentDescription = "Сохранить как", tint = Color.White)
                }
                IconButton(onClick = { file?.let(::openInSystemViewer) }, enabled = file != null) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = "Открыть во внешнем", tint = Color.White)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
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

            val caption = remember(current?.msgId) {
                runCatching { JSONObject(current?.text.orEmpty()).optString("caption") }.getOrNull().orEmpty()
            }
            if (caption.isNotBlank()) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(caption, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
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

private fun saveAs(session: AppSession, message: MessageEntity, cached: File?) {
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
