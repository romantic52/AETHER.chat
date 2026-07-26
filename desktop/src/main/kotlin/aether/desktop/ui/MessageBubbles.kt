package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.data.MessageEntity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class MessageActions(
    val onReply: (MessageEntity) -> Unit,
    val onEdit: (MessageEntity) -> Unit,
    val onForward: (MessageEntity) -> Unit,
    val onDeleteForMe: (MessageEntity) -> Unit,
    val onDeleteForAll: (MessageEntity) -> Unit,
    val onReact: (MessageEntity, String) -> Unit,
    val onRetry: (MessageEntity) -> Unit,
    val onCopy: (MessageEntity) -> Unit,
    /** Открыть фото во встроенном просмотрщике (а не системным приложением). */
    val onOpenMedia: (MessageEntity) -> Unit,
)

private val reactionChoices = listOf("👍", "❤️", "🔥", "😂", "😮", "😢")

@Composable
fun MessageBubble(
    session: AppSession,
    message: MessageEntity,
    isGroupChat: Boolean,
    actions: MessageActions,
) {
    val isMedia = message.text.trimStart().startsWith("{") &&
        runCatching { JSONObject(message.text).optString("type") == "media" }.getOrDefault(false)
    val isMine = message.isOut

    val menuItems = buildList {
        add(ContextMenuItem("Ответить") { actions.onReply(message) })
        if (!isMedia) add(ContextMenuItem("Копировать") { actions.onCopy(message) })
        if (isMine && !isMedia) add(ContextMenuItem("Редактировать") { actions.onEdit(message) })
        add(ContextMenuItem("Переслать") { actions.onForward(message) })
        if (message.status == -1) add(ContextMenuItem("Повторить отправку") { actions.onRetry(message) })
        add(ContextMenuItem("Удалить у меня") { actions.onDeleteForMe(message) })
        if (isMine) add(ContextMenuItem("Удалить у всех") { actions.onDeleteForAll(message) })
        for (emoji in reactionChoices) {
            add(ContextMenuItem("Реакция $emoji") { actions.onReact(message, emoji) })
        }
        add(ContextMenuItem("Убрать реакцию") { actions.onReact(message, "") })
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        ContextMenuArea(items = { menuItems }) {
            Surface(
                color = if (isMine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(
                    topStart = 14.dp,
                    topEnd = 14.dp,
                    bottomStart = if (isMine) 14.dp else 4.dp,
                    bottomEnd = if (isMine) 4.dp else 14.dp,
                ),
                modifier = Modifier.widthIn(max = 440.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    message.forwardedFrom?.let {
                        Text(
                            "Переслано от @$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    message.replyToText?.let { quote ->
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            Text(
                                quote,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    if (isMedia) {
                        MediaContent(session, message, actions)
                    } else {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(message.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    ReactionsRow(message.reactions)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        if (message.isEdited) {
                            Text(
                                "изм.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            formatTime(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isMine) {
                            Text(
                                when (message.status) {
                                    -1 -> "⚠"
                                    0 -> "🕓"
                                    1 -> "✓"
                                    2 -> "✓✓"
                                    else -> "✓✓"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.status == 3) MaterialTheme.colorScheme.primary
                                else if (message.status == -1) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionsRow(reactionsJson: String) {
    if (reactionsJson.isBlank()) return
    val counts = remember(reactionsJson) {
        runCatching {
            val obj = JSONObject(reactionsJson)
            obj.keys().asSequence()
                .mapNotNull { user -> obj.optString(user).takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
        }.getOrDefault(emptyMap())
    }
    if (counts.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        counts.forEach { (emoji, count) ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Text(
                    if (count > 1) "$emoji $count" else emoji,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MediaContent(session: AppSession, message: MessageEntity, actions: MessageActions) {
    val payload = remember(message.text) { runCatching { JSONObject(message.text) }.getOrNull() }
    val kind = payload?.optString("kind").orEmpty()
    val caption = payload?.optString("caption").orEmpty()

    Column {
        when {
            kind == "image" -> InlineImage(session, message, onOpen = { actions.onOpenMedia(message) })
            kind == "voice" -> VoiceRow(session, message, payload)
            kind == "video_note" -> OpenExternallyRow(session, message, Icons.Filled.PlayCircle, "Видеосообщение")
            kind == "video" -> OpenExternallyRow(session, message, Icons.Filled.PlayCircle, "Видео")
            else -> FileRow(session, message, payload)
        }
        if (caption.isNotBlank()) {
            Text(caption, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun InlineImage(session: AppSession, message: MessageEntity, onOpen: () -> Unit) {
    var bitmap by remember(message.msgId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(message.msgId) { mutableStateOf(false) }

    LaunchedEffect(message.msgId, message.text) {
        bitmap = withContext(Dispatchers.IO) {
            val file = session.repository.downloadMedia(message.text)
            file?.let {
                runCatching {
                    org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()
                }.getOrNull()
            }
        }
        if (bitmap == null) failed = true
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpen),
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = 240.dp, height = 160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (failed) "Не удалось загрузить фото" else "Загрузка…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceRow(session: AppSession, message: MessageEntity, payload: JSONObject?) {
    val waveform = remember(message.text) {
        payload?.optJSONArray("waveform")?.let { arr ->
            (0 until arr.length()).map { arr.optInt(it).coerceIn(0, 31) }
        }.orEmpty()
    }
    val duration = payload?.optDouble("duration", 0.0) ?: 0.0
    val playback by aether.desktop.media.AudioPlayback.state.collectAsState()
    val isCurrent = playback.id == message.msgId
    val progress = if (isCurrent) playback.progress else 0f
    val scope = rememberCoroutineScope()
    var unsupported by remember(message.msgId) { mutableStateOf(false) }

    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)

    fun togglePlayback() {
        scope.launch(Dispatchers.IO) {
            val file = session.repository.downloadMedia(message.text) ?: return@launch
            val ok = aether.desktop.media.AudioPlayback.toggle(message.msgId, file)
            if (!ok) {
                unsupported = true
                openExternally(session, message)
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (isCurrent && playback.playing) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
            contentDescription = if (isCurrent && playback.playing) "Пауза" else "Воспроизвести",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp).clickable(onClick = ::togglePlayback),
        )
        if (waveform.isNotEmpty()) {
            // Клик по волне — перемотка, как в Telegram.
            Canvas(
                modifier = Modifier.width(160.dp).height(24.dp)
                    .pointerInput(message.msgId) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            if (isCurrent) {
                                aether.desktop.media.AudioPlayback.seek(message.msgId, fraction)
                            } else {
                                togglePlayback()
                            }
                        }
                    },
            ) {
                val barWidth = size.width / (waveform.size * 1.5f)
                val playedUntil = size.width * progress
                waveform.forEachIndexed { index, amp ->
                    val h = (amp / 31f).coerceAtLeast(0.12f) * size.height
                    val x = index * barWidth * 1.5f
                    drawRoundRect(
                        color = if (x <= playedUntil) playedColor else restColor,
                        topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2f),
                        size = androidx.compose.ui.geometry.Size(barWidth, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
                    )
                }
            }
        } else {
            Text(
                "Голосовое сообщение",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(onClick = ::togglePlayback),
            )
        }
        val shown = if (isCurrent && playback.durationMs > 0) {
            formatDuration((playback.durationMs - playback.positionMs) / 1000.0)
        } else if (duration > 0) {
            formatDuration(duration)
        } else {
            ""
        }
        if (shown.isNotBlank()) {
            Text(
                shown,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (unsupported) {
            Text(
                "формат не поддержан",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun OpenExternallyRow(
    session: AppSession,
    message: MessageEntity,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable { openExternally(session, message) },
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FileRow(session: AppSession, message: MessageEntity, payload: JSONObject?) {
    val name = payload?.optString("file_name")?.takeIf(String::isNotBlank) ?: "Файл"
    val size = payload?.optLong("file_size", 0L) ?: 0L
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable { openExternally(session, message) },
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (size > 0) {
                Text(
                    aether.desktop.data.MediaCache.formatSize(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Скачивает (если надо) и открывает расшифрованный файл системным приложением. */
private fun openExternally(session: AppSession, message: MessageEntity) {
    Thread {
        val file: File? = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            session.repository.downloadMedia(message.text)
        }
        if (file != null && Desktop.isDesktopSupported()) {
            runCatching {
                val named = withKnownExtension(file, message.text)
                Desktop.getDesktop().open(named)
            }
        }
    }.start()
}

/** Windows открывает по расширению: даём кешу говорящее имя рядом. */
private fun withKnownExtension(file: File, jsonText: String): File {
    val payload = runCatching { JSONObject(jsonText) }.getOrNull() ?: return file
    val explicit = payload.optString("file_name").takeIf { it.isNotBlank() && it.contains('.') }
    val mime = payload.optString("mime_type")
    val ext = explicit?.substringAfterLast('.') ?: when {
        mime.startsWith("image/") -> mime.substringAfter('/').replace("jpeg", "jpg")
        mime.startsWith("video/") -> mime.substringAfter('/')
        mime == "audio/ogg" || mime.contains("opus") -> "ogg"
        mime.startsWith("audio/mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
        mime.startsWith("audio/") -> mime.substringAfter('/')
        else -> "bin"
    }
    val named = File(file.parentFile, "${file.name}.$ext")
    if (!named.exists() || named.length() != file.length()) {
        runCatching { file.copyTo(named, overwrite = true) }
    }
    return if (named.exists()) named else file
}

fun formatTime(timestamp: Long): String {
    val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return format.format(java.util.Date(timestamp))
}

fun formatDayHeader(timestamp: Long): String {
    val format = java.text.SimpleDateFormat("d MMMM", java.util.Locale("ru"))
    return format.format(java.util.Date(timestamp))
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
