package aether.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Предпросмотр вложений перед отправкой, как в Telegram: видно, что именно
 * уходит, можно снять лишнее, добавить подпись и выбрать — сжатым фото или
 * файлом без потерь. Раньше выбранное улетало сразу из системного диалога.
 */
@Composable
fun AttachDialog(
    files: List<File>,
    asFileInitial: Boolean,
    onDismiss: () -> Unit,
    onSend: (files: List<File>, caption: String, asFile: Boolean) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    var asFile by remember { mutableStateOf(asFileInitial) }
    val kept = remember { androidx.compose.runtime.mutableStateListOf(*files.toTypedArray()) }
    val hasImages = remember(kept.toList()) { kept.any(::isImageFile) }

    if (kept.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    fun send() = onSend(kept.toList(), caption.trim(), asFile)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kept.size > 1) "Отправить ${kept.size} файла(ов)" else "Отправить файл") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(kept.toList(), key = { it.absolutePath }) { file ->
                        AttachThumb(file) { kept.remove(file) }
                    }
                }
                if (hasImages) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !asFile, onClick = { asFile = false })
                        Text("Фото", modifier = Modifier.clickable { asFile = false })
                        RadioButton(
                            selected = asFile,
                            onClick = { asFile = true },
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        Text("Файлом", modifier = Modifier.clickable { asFile = true })
                    }
                }
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = { Text("Подпись") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            // Ctrl+Enter отправляет: обычный Enter должен оставаться
                            // переносом строки, подписи бывают многострочными.
                            if (event.key == Key.Enter && event.isCtrlPressed) {
                                send()
                                true
                            } else {
                                false
                            }
                        },
                )
            }
        },
        confirmButton = { TextButton(onClick = ::send) { Text("Отправить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun AttachThumb(file: File, onRemove: () -> Unit) {
    var bitmap by remember(file.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.absolutePath) {
        if (!isImageFile(file)) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
            }.getOrNull()
        }
    }

    Box(modifier = Modifier.size(96.dp)) {
        val preview = bitmap
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Column(
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
                Text(
                    file.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Filled.Close,
            contentDescription = "Убрать",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onRemove),
        )
    }
}
