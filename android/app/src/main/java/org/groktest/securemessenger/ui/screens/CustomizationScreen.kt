package org.groktest.securemessenger.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.glass.supportsRealGlass
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.AppPalette
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import org.groktest.securemessenger.ui.theme.MAX_GLASS_BLUR_RADIUS
import org.groktest.securemessenger.ui.theme.MAX_SURFACE_TRANSPARENCY
import org.groktest.securemessenger.ui.theme.ThemePalettes
import org.groktest.securemessenger.ui.theme.ThemeSettings
import org.groktest.securemessenger.ui.theme.aetherBubbleShape
import org.groktest.securemessenger.ui.theme.aetherBubbleVisual
import org.groktest.securemessenger.ui.theme.aetherControl
import org.groktest.securemessenger.ui.theme.aetherControlContent
import org.groktest.securemessenger.ui.theme.aetherField
import org.groktest.securemessenger.ui.theme.aetherIsland
import org.groktest.securemessenger.ui.theme.aetherLargeShape
import org.groktest.securemessenger.ui.theme.aetherStroke
import org.groktest.securemessenger.ui.theme.aetherSurface

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CustomizationScreen(onBack: () -> Unit) {
    val themeSettings = LocalThemeSettings.current
    val context = LocalContext.current
    val fonts = listOf(
        "Default" to "Системный",
        "Serif" to "С засечками",
        "SansSerif" to "Гладкий",
        "Monospace" to "Моно",
        "Cursive" to "Рукописный"
    )
    val reactions = listOf("❤️", "👍", "🔥", "😂", "😮", "😢")
    var selectedArea by remember { mutableStateOf(VisualArea.Bubbles) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            themeSettings.setBackgroundImageUri(uri.toString())
        }
    }

    GlassBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = AetherStyle.EdgeBarHeight),
                contentPadding = PaddingValues(
                    start = AetherStyle.ScreenHorizontal,
                    top = AetherStyle.ScreenVertical,
                    end = AetherStyle.ScreenHorizontal,
                    bottom = 28.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
                    SectionTitle(
                        "Редактор элементов",
                        "Выбери часть интерфейса и меняй её по живому образцу"
                    )
                }

                stickyHeader {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(vertical = 8.dp)
                    ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(VisualArea.entries, key = { it.name }) { area ->
                            VisualAreaCard(
                                area = area,
                                selected = selectedArea == area,
                                onClick = { selectedArea = area }
                            )
                        }
                    }
                    }
                }

                item {
                    VisualEditor(area = selectedArea)
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
                    Spacer(Modifier.height(10.dp))
                    SettingSlider(
                        title = "Размер текста в сообщениях",
                        value = themeSettings.messageTextSize.value,
                        valueLabel = textSizeLabel(themeSettings.messageTextSize.value),
                        range = 14f..20f,
                        onChange = themeSettings::setMessageTextSize
                    )
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
                        onClick = { launcher.launch(arrayOf("image/*")) }
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

private enum class VisualArea(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    General("Общее", "Весь стиль", Icons.Default.Tune),
    Shapes("Формы", "Эффекты", Icons.Default.BlurCircular),
    Bubbles("Пузыри", "Сообщения", Icons.Default.ChatBubble),
    Panels("Панели", "Карточки", Icons.Default.ViewAgenda),
    Buttons("Кнопки", "Иконки", Icons.Default.TouchApp),
    Fields("Поля", "Ввод", Icons.Default.TextFields),
    Navigation("Навигация", "Нижний бар", Icons.Default.ViewCarousel),
    Accent("Цвета", "Оттенки", Icons.Default.Palette),
}

@Composable
private fun VisualAreaCard(
    area: VisualArea,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .width(108.dp)
            .height(112.dp)
            .aetherIsland(
                shape = CircleShape,
                fillAlpha = if (selected) AetherStyle.IslandFillAlpha else AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = if (selected) AetherStyle.SelectedStrokeAlpha else AetherStyle.SoftStrokeAlpha,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(area.icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
        Column {
            Text(area.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = content)
            Text(area.subtitle, fontSize = 11.sp, color = content.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun VisualEditor(area: VisualArea) {
    val settings = LocalThemeSettings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aetherIsland(
                shape = RoundedCornerShape(32.dp),
                fillAlpha = AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = AetherStyle.SoftStrokeAlpha,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VisualPreview(area)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = AetherStyle.DividerAlpha))
        when (area) {
            VisualArea.General -> GeneralControls(settings)
            VisualArea.Shapes -> ShapeControls(settings)
            VisualArea.Bubbles -> BubbleControls(settings)
            VisualArea.Panels -> PanelControls(settings)
            VisualArea.Buttons -> ButtonControls(settings)
            VisualArea.Fields -> FieldControls(settings)
            VisualArea.Navigation -> NavigationControls(settings)
            VisualArea.Accent -> AccentControls(settings)
        }
        TextButton(
            onClick = {
                when (area) {
                    VisualArea.General -> settings.resetGeneralStyle()
                    VisualArea.Shapes -> {
                        settings.setStrictRoundShapes(false)
                        settings.setLiquidGlassEnabled(false)
                    }
                    VisualArea.Bubbles -> settings.resetBubbleStyle()
                    VisualArea.Panels -> settings.resetPanelStyle()
                    VisualArea.Buttons -> settings.resetControlStyle()
                    VisualArea.Fields -> settings.resetFieldStyle()
                    VisualArea.Navigation -> settings.setDockIndicatorEdgeToEdge(false)
                    VisualArea.Accent -> settings.setCustomAccentEnabled(false)
                }
            },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Как в теме")
        }
    }
}

@Composable
private fun VisualPreview(area: VisualArea) {
    val previewShape = RoundedCornerShape(32.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(154.dp)
            .clip(previewShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                    )
                )
            )
            .border(AetherStyle.Stroke, aetherStroke(AetherStyle.SoftStrokeAlpha), previewShape)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (area) {
            VisualArea.General -> GeneralPreview()
            VisualArea.Shapes -> ShapePreview()
            VisualArea.Bubbles -> BubblePreview()
            VisualArea.Panels -> PanelPreview()
            VisualArea.Buttons -> ButtonPreview()
            VisualArea.Fields -> FieldPreview()
            VisualArea.Navigation -> NavigationPreview()
            VisualArea.Accent -> AccentPreview()
        }
    }
}

@Composable
private fun GeneralPreview() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelPreview()
        Row(
            modifier = Modifier.align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(3) {
                Box(modifier = Modifier.size(28.dp).aetherControl())
            }
        }
    }
}

@Composable
private fun ShapePreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aetherIsland(shape = aetherLargeShape())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).aetherControl())
            Box(Modifier.weight(1f).height(42.dp).aetherField())
        }
        Box(Modifier.fillMaxWidth().height(42.dp).aetherIsland())
    }
}

@Composable
private fun BubblePreview() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewBubble(
            outgoing = false,
            text = "Привет! Как тебе новый вид?",
            time = "12:40",
            modifier = Modifier.align(Alignment.Start),
        )
        PreviewBubble(
            outgoing = true,
            text = "Так намного лучше",
            time = "12:41 ✓✓",
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun PreviewBubble(
    outgoing: Boolean,
    text: String,
    time: String,
    modifier: Modifier = Modifier,
) {
    val visual = aetherBubbleVisual(outgoing)
    val shape = aetherBubbleShape(outgoing, showTail = true)
    Box(
        modifier = modifier
            .widthIn(max = 270.dp)
            .clip(shape)
            .background(visual.fill, shape)
            .border(AetherStyle.Stroke, visual.stroke, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text + "\u00A0".repeat(if (outgoing) 13 else 9),
            color = visual.content,
            fontSize = 15.sp,
            lineHeight = 19.sp,
        )
        Text(
            time,
            modifier = Modifier.align(Alignment.BottomEnd),
            color = visual.metadata,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PanelPreview() {
    val shape = CircleShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .aetherIsland(
                shape = shape,
                fillAlpha = AetherStyle.IslandFillAlpha,
                strokeAlpha = AetherStyle.SelectedStrokeAlpha,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .aetherControl(shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Palette, contentDescription = null, tint = aetherControlContent())
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Оформление", fontWeight = FontWeight.SemiBold)
            Text("Панель меняется сразу", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ButtonPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Icons.Default.ChatBubble, Icons.Default.Palette, Icons.Default.TouchApp).forEachIndexed { index, icon ->
            Box(
                modifier = Modifier
                    .size(if (index == 1) 56.dp else 48.dp)
                    .aetherControl(
                        fillAlpha = if (index == 1) AetherStyle.IslandFillAlpha else AetherStyle.ControlFillAlpha,
                        strokeAlpha = if (index == 1) AetherStyle.SelectedStrokeAlpha else AetherStyle.ControlStrokeAlpha,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = aetherControlContent())
            }
        }
    }
}

@Composable
private fun FieldPreview() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .aetherField()
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ChatBubble, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text("Напиши сообщение…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavigationPreview() {
    val settings = LocalThemeSettings.current
    val edgeToEdge = settings.dockIndicatorEdgeToEdge.value
    val outerShape = CircleShape
    val indicatorShape = CircleShape
    val tabs = listOf(
        "Люди" to Icons.Default.Person,
        "Звонки" to Icons.Default.Phone,
        "Чаты" to Icons.Default.ChatBubble,
        "Ещё" to Icons.Default.Settings,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .aetherIsland(shape = outerShape),
    ) {
        tabs.forEachIndexed { index, (label, icon) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(if (index == 0 && !edgeToEdge) 2.dp else 0.dp)
                    .then(
                        if (index == 0) Modifier.background(
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
                            indicatorShape,
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(label, fontSize = 8.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AccentPreview() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.tertiary,
            ).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(AetherStyle.Stroke, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f), CircleShape)
                )
            }
        }
        Text("Основной цвет интерфейса", fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BubbleControls(settings: ThemeSettings) {
    SettingSwitch(
        title = "Свои оттенки",
        subtitle = "Разные цвета для входящих и исходящих",
        checked = settings.customBubbleColors.value,
        onCheckedChange = settings::setCustomBubbleColors,
    )
    if (settings.customBubbleColors.value) {
        HueSlider("Входящие", settings.incomingBubbleHue.value, settings::setIncomingBubbleHue)
        HueSlider("Исходящие", settings.outgoingBubbleHue.value, settings::setOutgoingBubbleHue)
        SettingSlider(
            title = "Цветность",
            subtitle = "Насколько заметен выбранный оттенок",
            value = settings.bubbleTintStrength.value,
            valueLabel = strengthLabel(settings.bubbleTintStrength.value),
            range = 0f..1f,
            onChange = settings::setBubbleTintStrength,
        )
    }
    SettingSlider(
        title = "Сила прозрачности",
        subtitle = "От лёгкой дымки к более прозрачному виду",
        value = settings.bubbleTransparency.value,
        valueLabel = percentLabel(settings.bubbleTransparency.value),
        range = 0f..0.6f,
        onChange = settings::setBubbleTransparency,
    )
    SettingSlider(
        title = "Контур",
        value = settings.bubbleStrokeStrength.value,
        valueLabel = strokeLabel(settings.bubbleStrokeStrength.value),
        range = 0f..1f,
        onChange = settings::setBubbleStrokeStrength,
    )
    SettingSwitch(
        title = "Хвостики",
        subtitle = "Показывать направление последнего сообщения",
        checked = settings.bubbleTails.value,
        onCheckedChange = settings::setBubbleTails,
    )
}

@Composable
private fun GeneralControls(settings: ThemeSettings) {
    SettingSlider(
        title = "Общая прозрачность",
        subtitle = "Панели, кнопки, поля и сообщения",
        value = settings.surfaceTransparency.value,
        valueLabel = percentLabel(settings.surfaceTransparency.value),
        range = 0f..MAX_SURFACE_TRANSPARENCY,
        onChange = settings::setSurfaceTransparency,
    )
    SettingSlider(
        title = "Общий контур",
        subtitle = "Базовая сила для всех элементов",
        value = settings.strokeStrength.value,
        valueLabel = strokeLabel(settings.strokeStrength.value),
        range = 0f..1f,
        onChange = settings::setStrokeStrength,
    )
    SettingSlider(
        title = "Толщина контура",
        value = settings.strokeWidth.value,
        valueLabel = thicknessLabel(settings.strokeWidth.value),
        range = 0.5f..2f,
        onChange = settings::setStrokeWidth,
    )
    SettingSwitch(
        title = "Мягкие края экрана",
        subtitle = "Затемнение под верхней и нижней панелью",
        checked = settings.edgeDimEnabled.value,
        onCheckedChange = settings::setEdgeDimEnabled,
    )
    if (settings.edgeDimEnabled.value) {
        SettingSlider(
            title = "Сила затемнения",
            value = settings.edgeDimStrength.value,
            valueLabel = strengthLabel(settings.edgeDimStrength.value),
            range = 0f..1f,
            onChange = settings::setEdgeDimStrength,
        )
        SettingSlider(
            title = "Глубина затемнения",
            value = settings.edgeDimLength.value,
            valueLabel = depthLabel(settings.edgeDimLength.value),
            range = 112f..240f,
            onChange = settings::setEdgeDimLength,
        )
    }
}

@Composable
private fun ShapeControls(settings: ThemeSettings) {
    SettingSwitch(
        title = "Максимальные скругления",
        subtitle = "Усилить радиус карточек и диалогов без превращения их в овалы",
        checked = settings.strictRoundShapes.value,
        onCheckedChange = settings::setStrictRoundShapes,
    )
    SettingSwitch(
        title = "Настоящее размытие",
        subtitle = if (supportsRealGlass) {
            "Backdrop-blur отключён по умолчанию, потому что нагружает слабые устройства"
        } else {
            "Доступно на Android 12L и новее"
        },
        checked = settings.liquidGlassEnabled.value,
        onCheckedChange = settings::setLiquidGlassEnabled,
    )
    if (settings.liquidGlassEnabled.value && supportsRealGlass) {
        SettingSlider(
            title = "Размытие",
            subtitle = "Плавающая шапка чата",
            value = settings.glassBlurRadius.value,
            valueLabel = blurLabel(settings.glassBlurRadius.value),
            range = 0f..MAX_GLASS_BLUR_RADIUS,
            onChange = settings::setGlassBlurRadius,
        )
        SettingSlider(
            title = "Чистота стекла",
            subtitle = "От матового к чистому",
            value = settings.glassClarity.value,
            valueLabel = clarityLabel(settings.glassClarity.value),
            range = 0f..1f,
            onChange = settings::setGlassClarity,
        )
    }
}

@Composable
private fun PanelControls(settings: ThemeSettings) {
    SettingSlider(
        title = "Оттенок панелей",
        value = settings.surfaceTintStrength.value,
        valueLabel = strengthLabel(settings.surfaceTintStrength.value / 0.35f),
        range = 0f..0.35f,
        onChange = settings::setSurfaceTintStrength,
    )
    val transparency = 1f - settings.panelOpacity.value
    SettingSlider(
        title = "Прозрачность панелей",
        value = transparency,
        valueLabel = percentLabel(transparency),
        range = 0f..0.4f,
        onChange = { settings.setPanelOpacity(1f - it) },
    )
    SettingSlider(
        title = "Контур панелей",
        value = settings.panelStrokeStrength.value,
        valueLabel = strokeLabel(settings.panelStrokeStrength.value),
        range = 0f..1f,
        onChange = settings::setPanelStrokeStrength,
    )
}

@Composable
private fun ButtonControls(settings: ThemeSettings) {
    SettingSlider(
        title = "Цветность кнопок",
        value = settings.controlTintStrength.value,
        valueLabel = strengthLabel(settings.controlTintStrength.value / 0.35f),
        range = 0f..0.35f,
        onChange = settings::setControlTintStrength,
    )
    val transparency = 1f - settings.controlOpacity.value
    SettingSlider(
        title = "Прозрачность кнопок",
        value = transparency,
        valueLabel = percentLabel(transparency),
        range = 0f..0.4f,
        onChange = { settings.setControlOpacity(1f - it) },
    )
    SettingSlider(
        title = "Контур кнопок",
        value = settings.controlStrokeStrength.value,
        valueLabel = strokeLabel(settings.controlStrokeStrength.value),
        range = 0f..1f,
        onChange = settings::setControlStrokeStrength,
    )
}

@Composable
private fun NavigationControls(settings: ThemeSettings) {
    SettingSwitch(
        title = "Линза до края",
        subtitle = "Активная вкладка полностью касается края нижнего бара",
        checked = settings.dockIndicatorEdgeToEdge.value,
        onCheckedChange = settings::setDockIndicatorEdgeToEdge,
    )
}

@Composable
private fun FieldControls(settings: ThemeSettings) {
    SettingSlider(
        title = "Цветность полей",
        value = settings.fieldTintStrength.value,
        valueLabel = strengthLabel(settings.fieldTintStrength.value / 0.35f),
        range = 0f..0.35f,
        onChange = settings::setFieldTintStrength,
    )
    val transparency = 1f - settings.fieldOpacity.value
    SettingSlider(
        title = "Прозрачность полей",
        value = transparency,
        valueLabel = percentLabel(transparency),
        range = 0f..0.4f,
        onChange = { settings.setFieldOpacity(1f - it) },
    )
    SettingSlider(
        title = "Контур полей",
        value = settings.fieldStrokeStrength.value,
        valueLabel = strokeLabel(settings.fieldStrokeStrength.value),
        range = 0f..1f,
        onChange = settings::setFieldStrokeStrength,
    )
}

@Composable
private fun AccentControls(settings: ThemeSettings) {
    val paletteAccent = MaterialTheme.colorScheme.primary
    SettingSwitch(
        title = "Свой основной цвет",
        subtitle = "Кнопки, иконки и исходящие сообщения",
        checked = settings.customAccentEnabled.value,
        onCheckedChange = { enabled ->
            if (enabled && settings.accentColorHex.value == "#CCFFFFFF") {
                settings.setAccentColor(colorHex(paletteAccent))
            }
            settings.setCustomAccentEnabled(enabled)
        },
    )
    if (settings.customAccentEnabled.value) {
        val hsv = accentHsv(settings)
        HueSlider(
            title = "Оттенок",
            value = hsv[0],
            onChange = { setAccentHsv(settings, 0, it) },
        )
        SettingSlider(
            title = "Насыщенность",
            value = hsv[1],
            valueLabel = strengthLabel(hsv[1]),
            range = 0f..1f,
            onChange = { setAccentHsv(settings, 1, it) },
        )
        SettingSlider(
            title = "Яркость",
            value = hsv[2],
            valueLabel = brightnessLabel(hsv[2]),
            range = 0.2f..1f,
            onChange = { setAccentHsv(settings, 2, it) },
        )
    }
}

@Composable
private fun HueSlider(
    title: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    val hue = value.coerceIn(0f, 360f)
    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.92f)))
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(current)
                    .border(AetherStyle.Stroke, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f), CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Red,
                                Color.Yellow,
                                Color.Green,
                                Color.Cyan,
                                Color.Blue,
                                Color.Magenta,
                                Color.Red,
                            )
                        )
                    )
            )
            Slider(
                value = hue,
                onValueChange = onChange,
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    thumbColor = current,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            )
        }
    }
}

private fun accentHsv(settings: ThemeSettings): FloatArray =
    FloatArray(3).also { android.graphics.Color.colorToHSV(settings.getAccentColor().toArgb(), it) }

private fun setAccentHsv(settings: ThemeSettings, index: Int, value: Float) {
    val hsv = accentHsv(settings)
    hsv[index] = value
    settings.setAccentColor(colorHex(Color(android.graphics.Color.HSVToColor(hsv))))
}

private fun colorHex(color: Color): String = String.format("#%08X", color.toArgb())

private fun percentLabel(value: Float): String = "${(value * 100f).toInt()}%"
private fun strengthLabel(value: Float): String = when {
    value < 0.18f -> "Спокойный"
    value < 0.55f -> "Мягкий"
    value < 0.82f -> "Цветной"
    else -> "Яркий"
}
private fun strokeLabel(value: Float): String = when {
    value < 0.12f -> "Без контура"
    value < 0.45f -> "Тихий"
    value < 0.78f -> "Мягкий"
    else -> "Яркий"
}
private fun thicknessLabel(value: Float): String = when {
    value < 0.2f -> "Нет"
    value < 0.85f -> "Тонкий"
    value < 1.45f -> "Средний"
    else -> "Выраженный"
}
private fun blurLabel(value: Float): String = when {
    value < 0.5f -> "Нет"
    value < 9f -> "Мягкое"
    value < 17f -> "Среднее"
    else -> "Сильное"
}
private fun clarityLabel(value: Float): String = when {
    value < 0.3f -> "Матовое"
    value < 0.7f -> "Мягкое"
    else -> "Чистое"
}
private fun depthLabel(value: Float): String = when {
    value < 145f -> "Короткая"
    value < 195f -> "Средняя"
    else -> "Глубокая"
}
private fun brightnessLabel(value: Float): String = when {
    value < 0.38f -> "Тёмный"
    value < 0.72f -> "Мягкий"
    else -> "Светлый"
}
private fun textSizeLabel(value: Float): String = when {
    value < 15.2f -> "Компактный"
    value < 17.5f -> "Обычный"
    else -> "Крупный"
}

@Composable
private fun SettingSlider(
    title: String,
    subtitle: String? = null,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    enabled: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.5f)
            )
            Text(
                valueLabel,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.5f),
                fontSize = 13.sp
            )
        }
        if (subtitle != null) {
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.65f),
                fontSize = 13.sp
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
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
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics { }
        )
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThemePreviewCard(
    palette: AppPalette,
    selected: Boolean,
    onClick: () -> Unit
) {
    val outerShape = RoundedCornerShape(32.dp)
    val previewShape = RoundedCornerShape(AetherStyle.MediaRadius)
    val fill = if (selected) aetherSurface(AetherStyle.IslandFillAlpha)
        else aetherSurface(AetherStyle.SoftIslandFillAlpha)
    val stroke = if (selected) aetherStroke(AetherStyle.SelectedStrokeAlpha)
        else MaterialTheme.colorScheme.outline.copy(alpha = AetherStyle.SoftStrokeAlpha)
    Column(
        modifier = Modifier
            .width(138.dp)
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
    val fill = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = AetherStyle.SelectedFillAlpha)
        else aetherSurface(AetherStyle.SoftIslandFillAlpha)
    val stroke = MaterialTheme.colorScheme.primary.copy(
        alpha = if (selected) AetherStyle.SelectedStrokeAlpha else AetherStyle.SoftStrokeAlpha
    )
    val content = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
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
    val strokeAlpha = if (selected) AetherStyle.SelectedStrokeAlpha else AetherStyle.SoftStrokeAlpha
    Box(
        modifier = Modifier
            .size(52.dp)
            .aetherControl(
                fillAlpha = AetherStyle.SoftIslandFillAlpha,
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
