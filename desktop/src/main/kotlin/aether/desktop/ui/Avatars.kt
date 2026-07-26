package aether.desktop.ui

import aether.desktop.AppSession
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Кэш декодированных аватарок (file_id -> bitmap) на время сессии. */
private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

private val avatarPalette = listOf(
    Color(0xFFE17076), Color(0xFF7BC862), Color(0xFF65AADD),
    Color(0xFFA695E7), Color(0xFFEE7AAE), Color(0xFF6EC9CB), Color(0xFFFAA774),
)

@Composable
fun PeerAvatar(
    session: AppSession?,
    peerId: String,
    name: String,
    avatarFileId: String?,
    size: Dp = 48.dp,
) {
    var bitmap by remember(avatarFileId) { mutableStateOf(avatarFileId?.let(avatarCache::get)) }

    LaunchedEffect(avatarFileId) {
        val fileId = avatarFileId ?: return@LaunchedEffect
        if (bitmap != null || session == null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            avatarCache[fileId] ?: runCatching {
                val bytes = session.api.downloadFile(fileId)
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()?.also { avatarCache[fileId] = it }
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        val color = avatarPalette[(peerId.lowercase().hashCode().let { if (it < 0) -it else it }) % avatarPalette.size]
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), color))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initialsOf(name.ifBlank { peerId }),
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = (size.value * 0.38f).sp,
            )
        }
    }
}

private fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

fun chatDisplayName(name: String, statusEmoji: String?): String =
    if (statusEmoji.isNullOrBlank()) name else "$name $statusEmoji"
