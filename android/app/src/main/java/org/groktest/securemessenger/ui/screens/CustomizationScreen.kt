package org.groktest.securemessenger.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.AppPalette
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import org.groktest.securemessenger.ui.theme.ThemePalettes
import org.groktest.securemessenger.ui.theme.aetherCircle
import org.groktest.securemessenger.ui.theme.aetherIsland

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationScreen(onBack: () -> Unit) {
    val themeSettings = LocalThemeSettings.current
    val fonts = listOf(
        "Default" to "Системный",
        "Serif" to "С засечками",
        "SansSerif" to "Гладкий",
        "Monospace" to "Моно",
        "Cursive" to "Рукописный"
    )
    val reactions = listOf("❤️", "👍", "🔥", "😂", "😮", "😢")

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) themeSettings.setBackgroundImageUri(uri.toString())
    }

    GlassBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Кастомизация", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = AetherStyle.ScreenHorizontal,
                    end = AetherStyle.ScreenHorizontal,
                    top = 8.dp,
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SectionTitle("Тема", "Цвета сразу меняют весь интерфейс")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ThemePalettes.all, key = { it.key }) { palette ->
                            ThemePreviewCard(
                                palette = palette,
                                selected = themeSettings.themeKey.value == palette.key,
                                onClick = { themeSettings.setThemeKey(palette.key) }
                            )
                        }
                    }
                }

                item { SoftDivider() }

                item {
                    SectionTitle("Шрифт", "Выбери характер текста без смены раскладки")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(fonts, key = { it.first }) { (fontKey, fontName) ->
                            ChoicePill(
                                label = fontName,
                                selected = themeSettings.fontFamilyStyle.value == fontKey,
                                onClick = { themeSettings.setFontFamilyStyle(fontKey) }
                            )
                        }
                    }
                }

                item { SoftDivider() }

                item {
                    SectionTitle("Быстрая реакция", "Ставится двойным тапом по сообщению")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(reactions, key = { it }) { emoji ->
                            ReactionButton(
                                emoji = emoji,
                                selected = themeSettings.quickReaction.value == emoji,
                                onClick = { themeSettings.setQuickReaction(emoji) }
                            )
                        }
                    }
                }

                item { SoftDivider() }

                item {
                    SectionTitle("Обои чата", "Фон для переписок и стеклянных панелей")
                    Spacer(Modifier.height(10.dp))
                    VisualActionRow(
                        icon = Icons.Default.Image,
                        title = "Выбрать из галереи",
                        subtitle = if (themeSettings.backgroundImageUri.value == null) "Сейчас используется фон темы" else "Свои обои включены",
                        onClick = { launcher.launch("image/*") }
                    )
                    if (themeSettings.backgroundImageUri.value != null) {
                        Spacer(Modifier.height(8.dp))
                        VisualActionRow(
                            icon = Icons.Default.Delete,
                            title = "Сбросить обои",
                            subtitle = "Вернуть чистый фон выбранной темы",
                            destructive = true,
                            onClick = { themeSettings.setBackgroundImageUri(null) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThemePreviewCard(
    palette: AppPalette,
    selected: Boolean,
    onClick: () -> Unit
) {
    val outerShape = RoundedCornerShape(AetherStyle.IslandRadius)
    val previewShape = RoundedCornerShape(AetherStyle.MediaRadius)
    Column(
        modifier = Modifier
            .width(138.dp)
            .clip(outerShape)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (selected) 0.68f else 0.36f),
                outerShape
            )
            .border(
                AetherStyle.Stroke,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.76f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                outerShape
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(previewShape)
                .background(palette.scheme.background)
                .border(AetherStyle.Stroke, palette.scheme.outline.copy(alpha = 0.72f), previewShape)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.scheme.surfaceVariant)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .width(68.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.scheme.tertiary)
                )
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MiniDot(palette.scheme.primary)
                    MiniDot(palette.scheme.secondary)
                    MiniDot(palette.scheme.tertiary)
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(palette.scheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = palette.scheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = palette.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (palette.isDark) "Тёмная" else "Светлая",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MiniDot(color: Color) {
    Box(
        modifier = Modifier
            .size(11.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun ChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(AetherStyle.PillRadius)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
                shape
            )
            .border(
                AetherStyle.Stroke,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.74f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
    }
}

@Composable
private fun ReactionButton(
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .aetherCircle(
                fillAlpha = if (selected) 0.28f else AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = if (selected) 0.76f else AetherStyle.SoftStrokeAlpha
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 24.sp)
    }
}

@Composable
private fun VisualActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .aetherIsland(
                shape = RoundedCornerShape(AetherStyle.RowRadius),
                fillAlpha = AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = if (destructive) 0.34f else AetherStyle.SoftStrokeAlpha
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(AetherStyle.SmallControlSize)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
                .border(AetherStyle.Stroke, accent.copy(alpha = 0.48f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides accent) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SoftDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = AetherStyle.DividerAlpha))
}
