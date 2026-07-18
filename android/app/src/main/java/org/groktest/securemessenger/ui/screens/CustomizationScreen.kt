package org.groktest.securemessenger.ui.screens

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
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
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AetherStyle.ScreenHorizontal,
                    top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical,
                    end = AetherStyle.ScreenHorizontal,
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
                    SectionTitle("Интерфейс", "Прозрачность применяется ко всем панелям и пузырям")
                    Spacer(Modifier.height(12.dp))
                    SettingSlider(
                        title = "Прозрачность",
                        value = themeSettings.surfaceTransparency.value,
                        valueLabel = "${(themeSettings.surfaceTransparency.value * 100).toInt()}%",
                        range = 0f..1f,
                        onChange = themeSettings::setSurfaceTransparency
                    )
                    SettingSwitch(
                        title = "Затемнение краёв",
                        subtitle = "Мягкий градиент вместо сплошных верхних и нижних панелей",
                        checked = themeSettings.edgeDimEnabled.value,
                        onCheckedChange = themeSettings::setEdgeDimEnabled
                    )
                    SettingSlider(
                        title = "Сила затемнения",
                        value = themeSettings.edgeDimStrength.value,
                        valueLabel = if (themeSettings.edgeDimStrength.value <= 0.01f) "Выкл." else "${(themeSettings.edgeDimStrength.value * 100).toInt()}%",
                        range = 0f..1f,
                        onChange = themeSettings::setEdgeDimStrength
                    )
                    SettingSlider(
                        title = "Длина затемнения",
                        value = themeSettings.edgeDimLength.value,
                        valueLabel = "${themeSettings.edgeDimLength.value.toInt()} dp",
                        range = 112f..240f,
                        onChange = themeSettings::setEdgeDimLength
                    )
                }

                item { SoftDivider() }

                item {
                    SectionTitle("Сообщения", "Размер текста и форма пузырей")
                    Spacer(Modifier.height(12.dp))
                    SettingSlider(
                        title = "Размер текста",
                        value = themeSettings.messageTextSize.value,
                        valueLabel = "${themeSettings.messageTextSize.value.toInt()}",
                        range = 14f..20f,
                        onChange = themeSettings::setMessageTextSize
                    )
                    SettingSlider(
                        title = "Скругление",
                        value = themeSettings.bubbleRadius.value,
                        valueLabel = "${themeSettings.bubbleRadius.value.toInt()}",
                        range = 8f..30f,
                        onChange = themeSettings::setBubbleRadius
                    )
                    SettingSwitch(
                        title = "Хвостики пузырей",
                        subtitle = "Показывать у последнего сообщения в группе",
                        checked = themeSettings.bubbleTails.value,
                        onCheckedChange = themeSettings::setBubbleTails
                    )
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
            AetherSettingsTopBar(
                "Оформление",
                onBack,
                Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(valueLabel, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    val appearance = LocalThemeSettings.current
    val outerShape = RoundedCornerShape(AetherStyle.IslandRadius)
    val previewShape = RoundedCornerShape(AetherStyle.MediaRadius)
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        tween(appearance.motionDuration(180)),
        label = "themeFill"
    )
    val stroke by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.76f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
        tween(appearance.motionDuration(180)),
        label = "themeStroke"
    )
    val scale by animateFloatAsState(
        if (selected) 1f else 0.985f,
        if (appearance.animationsEnabled()) spring(dampingRatio = 0.82f, stiffness = 520f) else snap(),
        label = "themeScale"
    )
    val checkScale by animateFloatAsState(
        if (selected) 1f else 0f,
        tween(appearance.motionDuration(160)),
        label = "themeCheck"
    )
    Column(
        modifier = Modifier
            .width(138.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(outerShape)
            .background(fill, outerShape)
            .border(AetherStyle.Stroke, stroke, outerShape)
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .graphicsLayer {
                        alpha = checkScale
                        scaleX = checkScale
                        scaleY = checkScale
                    }
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
    val appearance = LocalThemeSettings.current
    val shape = RoundedCornerShape(AetherStyle.PillRadius)
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
        tween(appearance.motionDuration(160)),
        label = "pillFill"
    )
    val stroke by animateColorAsState(
        MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.74f else 0.22f),
        tween(appearance.motionDuration(160)),
        label = "pillStroke"
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        tween(appearance.motionDuration(160)),
        label = "pillContent"
    )
    Box(
        modifier = Modifier
            .clip(shape)
            .background(fill, shape)
            .border(AetherStyle.Stroke, stroke, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = content,
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
    val appearance = LocalThemeSettings.current
    val fillAlpha by animateFloatAsState(
        if (selected) 0.28f else AetherStyle.SoftIslandFillAlpha,
        tween(appearance.motionDuration(160)),
        label = "reactionFill"
    )
    val strokeAlpha by animateFloatAsState(
        if (selected) 0.76f else AetherStyle.SoftStrokeAlpha,
        tween(appearance.motionDuration(160)),
        label = "reactionStroke"
    )
    val scale by animateFloatAsState(
        if (selected) 1.1f else 1f,
        if (appearance.animationsEnabled()) spring(dampingRatio = 0.62f, stiffness = 620f) else snap(),
        label = "reactionScale"
    )
    Box(
        modifier = Modifier
            .size(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .aetherCircle(
                fillAlpha = fillAlpha,
                strokeAlpha = strokeAlpha
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
