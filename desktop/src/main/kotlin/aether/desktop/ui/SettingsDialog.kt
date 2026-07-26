package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.data.UiSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Держим в одном значении с packageVersion в desktop/build.gradle.kts.
private const val APP_VERSION = "1.0.0"

/** Настройки: оформление, уведомления, поведение и сведения о программе. */
@Composable
fun SettingsDialog(
    session: AppSession,
    settings: UiSettings,
    theme: UiSettings.ThemeMode,
    onThemeChange: (UiSettings.ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var uiScale by remember { mutableStateOf(settings.uiScale) }
    var notificationsOn by remember { mutableStateOf(settings.notificationsEnabled) }
    var previewOn by remember { mutableStateOf(settings.notificationPreview) }
    var soundsOn by remember { mutableStateOf(settings.soundsEnabled) }
    var closeToTray by remember { mutableStateOf(settings.closeToTray) }
    var ctrlEnter by remember { mutableStateOf(settings.sendOnCtrlEnter) }
    var deviceId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        deviceId = runCatching { withContext(Dispatchers.IO) { session.repository.myDeviceId() } }
            .getOrDefault("")
    }

    // Сохранение пишет файл, поэтому уходит с UI-потока: переключатель встаёт
    // в интерфейсе сразу, диск догоняет следом.
    fun save(write: () -> Unit) {
        scope.launch(Dispatchers.IO) { write() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SettingsSection("Оформление") {
                    SettingsRadioRow("Как в системе", theme == UiSettings.ThemeMode.SYSTEM) {
                        onThemeChange(UiSettings.ThemeMode.SYSTEM)
                    }
                    SettingsRadioRow("Светлая", theme == UiSettings.ThemeMode.LIGHT) {
                        onThemeChange(UiSettings.ThemeMode.LIGHT)
                    }
                    SettingsRadioRow("Тёмная", theme == UiSettings.ThemeMode.DARK) {
                        onThemeChange(UiSettings.ThemeMode.DARK)
                    }
                    Text(
                        "Масштаб интерфейса",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    UiSettings.UI_SCALES.forEach { scale ->
                        SettingsRadioRow(
                            title = "${(scale * 100).roundToInt()} %",
                            // Сравнение float'ов: значение приходит и из файла, и из этого же списка.
                            selected = abs(uiScale - scale) < 0.001f,
                        ) {
                            uiScale = scale
                            save { settings.uiScale = scale }
                        }
                    }
                    Text(
                        "Новый масштаб применяется после перезапуска.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsSection("Уведомления") {
                    SettingsSwitchRow("Показывать уведомления", notificationsOn) {
                        notificationsOn = it
                        save { settings.notificationsEnabled = it }
                    }
                    SettingsSwitchRow("Текст сообщения в уведомлении", previewOn, enabled = notificationsOn) {
                        previewOn = it
                        save { settings.notificationPreview = it }
                    }
                    SettingsSwitchRow("Звуки", soundsOn) {
                        soundsOn = it
                        aether.desktop.media.UiSounds.enabled = it
                        save { settings.soundsEnabled = it }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsSection("Поведение") {
                    SettingsSwitchRow("Сворачивать в трей при закрытии", closeToTray) {
                        closeToTray = it
                        save { settings.closeToTray = it }
                    }
                    Text(
                        "Отправка сообщения",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    SettingsRadioRow("По Enter (перенос строки — Shift+Enter)", !ctrlEnter) {
                        ctrlEnter = false
                        save { settings.sendOnCtrlEnter = false }
                    }
                    SettingsRadioRow("По Ctrl+Enter (Enter переносит строку)", ctrlEnter) {
                        ctrlEnter = true
                        save { settings.sendOnCtrlEnter = true }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsSection("О программе") {
                    Text("Æther", style = MaterialTheme.typography.titleMedium)
                    AboutRow("Версия", APP_VERSION)
                    AboutRow("Лицензия", "AGPL-3.0")
                    AboutRow("Аккаунт", "@${session.myId}")
                    AboutRow("Устройство", deviceId.ifBlank { "определяется…" })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

/** Заголовок раздела и его содержимое. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        // Клик обрабатывает вся строка, поэтому сам переключатель его не перехватывает.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsRadioRow(title: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}
