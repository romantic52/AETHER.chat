package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit
) {
    val themeSettings = LocalThemeSettings.current

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical)
            ) {
                ListItem(
                    headlineContent = { Text("Звук уведомлений") },
                    supportingContent = { Text("Звук при новых сообщениях") },
                    trailingContent = {
                        Switch(
                            checked = themeSettings.notifSound.value,
                            onCheckedChange = { themeSettings.setNotifSound(it) }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                ListItem(
                    headlineContent = { Text("Вибрация") },
                    supportingContent = { Text("Вибрировать при новых сообщениях") },
                    trailingContent = {
                        Switch(
                            checked = themeSettings.notifVibration.value,
                            onCheckedChange = { themeSettings.setNotifVibration(it) }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                ListItem(
                    headlineContent = { Text("Уведомления в фоне") },
                    supportingContent = { Text("Показывать пуш о новых сообщениях") },
                    trailingContent = {
                        Switch(
                            checked = themeSettings.notifPreviews.value,
                            onCheckedChange = { themeSettings.setNotifPreviews(it) }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            AetherSettingsTopBar(
                "Уведомления",
                onBack,
                Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
