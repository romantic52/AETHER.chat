package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.layout.*
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

/**
 * Скрытые / экспериментальные настройки. Открываются долгим зажатием (10 сек)
 * вкладки «Настройки» в нижней навигации. Сюда складываем сырые/опасные опции.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretSettingsScreen(onBack: () -> Unit) {
    val themeSettings = LocalThemeSettings.current

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Сырые функции. Могут работать нестабильно.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ListItem(
                    headlineContent = { Text("Жидкое стекло") },
                    supportingContent = {
                        Text(if (supportsRealGlass) "Бары размывают то, что прокручивается под ними" else "Имитация (нужен Android 12+ для размытия)")
                    },
                    trailingContent = {
                        Switch(
                            checked = themeSettings.liquidGlassEnabled.value,
                            onCheckedChange = { themeSettings.setLiquidGlassEnabled(it) }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                if (themeSettings.liquidGlassEnabled.value) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("Прозрачность", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = themeSettings.glassTransparency.value,
                            onValueChange = { themeSettings.setGlassTransparency(it) },
                            valueRange = 0f..1f
                        )
                    }
                }
            }
            AetherSettingsTopBar(
                "Экспериментальное",
                onBack,
                Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
