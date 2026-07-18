package org.groktest.securemessenger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.glass.supportsRealGlass
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretSettingsScreen(onBack: () -> Unit) {
    val themeSettings = LocalThemeSettings.current
    val animationsOn = themeSettings.animationsEnabled()

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AetherStyle.ScreenHorizontal,
                    top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical,
                    end = AetherStyle.ScreenHorizontal,
                    bottom = 28.dp
                )
            ) {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Новые функции можно отключить отдельно.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Text("Движение", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    ListItem(
                        headlineContent = { Text("Плавный интерфейс") },
                        supportingContent = { Text("Переходы, меню, сообщения и реакции") },
                        trailingContent = {
                            Switch(
                                checked = animationsOn,
                                onCheckedChange = { themeSettings.setExperimentalAnimations(it) }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    AnimatedVisibility(
                        visible = animationsOn,
                        enter = fadeIn(tween(themeSettings.motionDuration(180))) + expandVertically(tween(themeSettings.motionDuration(220))),
                        exit = fadeOut(tween(themeSettings.motionDuration(120))) + shrinkVertically(tween(themeSettings.motionDuration(180)))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text("Скорость · ${(themeSettings.animationSpeed.value * 100).toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = themeSettings.animationSpeed.value.coerceIn(0.5f, 2f),
                                onValueChange = themeSettings::setAnimationSpeed,
                                valueRange = 0.5f..2f
                            )
                            Text("Выразительность · ${(themeSettings.motionIntensity.value * 100).toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = themeSettings.motionIntensity.value,
                                onValueChange = themeSettings::setMotionIntensity,
                                valueRange = 0f..1.5f
                            )
                            ListItem(
                                headlineContent = { Text("Эффекты реакций") },
                                supportingContent = { Text("Мягкая вспышка при выборе эмодзи") },
                                trailingContent = {
                                    Switch(
                                        checked = themeSettings.reactionEffects.value,
                                        onCheckedChange = themeSettings::setReactionEffects
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }

                item {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                    Text("Отрисовка", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    ListItem(
                        headlineContent = { Text("Жидкое стекло") },
                        supportingContent = {
                            Text(if (supportsRealGlass) "Размытие содержимого под островками" else "Имитация без аппаратного размытия")
                        },
                        trailingContent = {
                            Switch(
                                checked = themeSettings.liquidGlassEnabled.value,
                                onCheckedChange = themeSettings::setLiquidGlassEnabled
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    AnimatedVisibility(
                        visible = themeSettings.liquidGlassEnabled.value,
                        enter = fadeIn(tween(themeSettings.motionDuration(160))) + expandVertically(
                            tween(themeSettings.motionDuration(200))
                        ),
                        exit = fadeOut(tween(themeSettings.motionDuration(120))) + shrinkVertically(
                            tween(themeSettings.motionDuration(160))
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text("Прозрачность", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = themeSettings.glassTransparency.value,
                                onValueChange = themeSettings::setGlassTransparency,
                                valueRange = 0f..1f
                            )
                        }
                    }
                }
            }
            AetherSettingsTopBar(
                "Эксперименты",
                onBack,
                Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
