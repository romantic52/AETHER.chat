package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.AetherSwitchRow
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.glass.glassSource
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
                    .glassSource()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AetherStyle.ScreenHorizontal)
                    .padding(top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical, bottom = 24.dp)
            ) {
                AetherSwitchRow(
                    title = "Звук уведомлений",
                    subtitle = "Звук при новых сообщениях",
                    checked = themeSettings.notifSound.value,
                    onCheckedChange = { themeSettings.setNotifSound(it) }
                )

                AetherSwitchRow(
                    title = "Вибрация",
                    subtitle = "Вибрировать при новых сообщениях",
                    checked = themeSettings.notifVibration.value,
                    onCheckedChange = { themeSettings.setNotifVibration(it) }
                )

                AetherSwitchRow(
                    title = "Уведомления в фоне",
                    subtitle = "Показывать пуш о новых сообщениях",
                    checked = themeSettings.notifPreviews.value,
                    onCheckedChange = { themeSettings.setNotifPreviews(it) }
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
