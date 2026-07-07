package org.groktest.securemessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.groktest.securemessenger.api.ServerConfig

/**
 * Аватарка: картинка с сервера, если есть avatarFileId, иначе инициалы.
 * type: 0 = личный, 1 = группа, 2 = канал, 3 = Избранное
 */
@Composable
fun Avatar(
    name: String,
    avatarFileId: String?,
    size: Dp,
    type: Int = 0,
    modifier: Modifier = Modifier
) {
    val fallbackColor = when (type) {
        3 -> Color(0xFFFFB300)
        2 -> Color(0xFF10B981)
        else -> MaterialTheme.colorScheme.primary
    }
    val initial = when {
        type == 3 -> "★"
        // Канал/группа/личный — первая буква имени; «C» больше не заглушка.
        name.isNotBlank() -> name.trim().take(1).uppercase()
        type == 2 -> "#"
        else -> "?"
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fallbackColor),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarFileId.isNullOrBlank() && ServerConfig.baseUrl.isNotBlank()) {
            AsyncImage(
                model = ServerConfig.avatarUrl(avatarFileId),
                contentDescription = "Аватар $name",
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                initial,
                // type 0 — фон = primary (в монохроме белый/чёрный): берём контрастный
                // onPrimary, иначе инициал «исчезал». Канал/Избранное цветные — белый ок.
                color = if (type == 0) MaterialTheme.colorScheme.onPrimary else Color.White,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
