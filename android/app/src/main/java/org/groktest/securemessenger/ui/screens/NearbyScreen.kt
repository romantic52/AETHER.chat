package org.groktest.securemessenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.groktest.securemessenger.nearby.NearbyAudience
import org.groktest.securemessenger.nearby.NearbyDiscoveryService
import org.groktest.securemessenger.nearby.NearbyPrivacy
import org.groktest.securemessenger.nearby.StrangerVisibility
import org.groktest.securemessenger.ui.components.AetherSectionTitle
import org.groktest.securemessenger.ui.components.AetherSettingsRow
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.AetherSwitchRow
import org.groktest.securemessenger.ui.components.GlassBackground

/**
 * «Рядом»: кто из пользователей Aether находится поблизости.
 *
 * Экран честен насчёт границ. Обнаружение работает, пока приложение открыто:
 * Android глушит фоновое радио через Doze и оптимизацию батареи, и обещать
 * «всегда на связи» нельзя. Отправка напрямую пока не реализована — здесь
 * только обнаружение.
 */
@Composable
fun NearbyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val privacy = remember { NearbyPrivacy.get(context) }
    val service = remember { NearbyDiscoveryService(context) }

    val peers by service.peers.collectAsState()
    val advertising by service.advertising.collectAsState()
    val scanning by service.scanning.collectAsState()

    var enabled by remember { mutableStateOf(privacy.enabled) }
    var audience by remember { mutableStateOf(privacy.audience) }
    var stranger by remember { mutableStateOf(privacy.strangerVisibility) }
    var canMessage by remember { mutableStateOf(privacy.strangersCanMessage) }
    var granted by remember { mutableStateOf(service.hasPermissions()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        if (granted && enabled) service.start(emptyMap())
    }

    // Ключи знакомых пока не рассылаются, поэтому список пуст: всех видим
    // как незнакомцев. Обмен ключами обнаружения — отдельная задача.
    DisposableEffect(enabled, granted) {
        if (enabled && granted) service.start(emptyMap()) else service.stop()
        onDispose { service.stop() }
    }

    GlassBackground {
        Column(Modifier.fillMaxSize()) {
            AetherSettingsTopBar(title = "Рядом", onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .navigationBarsPadding(),
            ) {
                AetherSwitchRow(
                    title = "Искать людей рядом",
                    subtitle = "Bluetooth, пока приложение открыто",
                    checked = enabled,
                    onCheckedChange = { value ->
                        enabled = value
                        privacy.enabled = value
                        if (value && !granted) {
                            permissionLauncher.launch(service.requiredPermissions())
                        }
                    },
                )

                if (enabled && !service.bluetoothReady()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Bluetooth выключен — включите его в системных настройках.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (enabled && !granted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Без разрешения на Bluetooth обнаружение не работает.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(20.dp))
                AetherSectionTitle("Кто может меня найти")
                NearbyAudience.entries.forEach { option ->
                    AetherSettingsRow(
                        title = option.title,
                        trailing = {
                            RadioButton(
                                selected = audience == option,
                                onClick = { audience = option; privacy.audience = option },
                            )
                        },
                        onClick = { audience = option; privacy.audience = option },
                    )
                }

                Spacer(Modifier.height(20.dp))
                AetherSectionTitle("Что увидит незнакомец")
                StrangerVisibility.entries.forEach { option ->
                    AetherSettingsRow(
                        title = option.title,
                        trailing = {
                            RadioButton(
                                selected = stranger == option,
                                onClick = {
                                    stranger = option
                                    privacy.strangerVisibility = option
                                },
                            )
                        },
                        onClick = { stranger = option; privacy.strangerVisibility = option },
                    )
                }

                Spacer(Modifier.height(12.dp))
                AetherSwitchRow(
                    title = "Незнакомцы могут писать",
                    subtitle = "Видимость и разрешение — разные вещи",
                    checked = canMessage,
                    onCheckedChange = {
                        canMessage = it
                        privacy.strangersCanMessage = it
                    },
                )

                Spacer(Modifier.height(20.dp))
                AetherSectionTitle(
                    if (peers.isEmpty()) "Пока никого не видно" else "Найдены поблизости"
                )
                peers.sortedByDescending { it.rssi }.forEach { peer ->
                    AetherSettingsRow(
                        title = if (peer.known) {
                            peer.identityId ?: "Знакомый"
                        } else {
                            "Пользователь Aether"
                        },
                        subtitle = "${peer.proximity.title} · ${peer.proximity.hint} (приблизительно)",
                        icon = {
                            Icon(
                                if (peer.known) Icons.Filled.Person else Icons.Filled.PersonOutline,
                                contentDescription = null,
                            )
                        },
                    )
                }

                if (enabled) {
                    Spacer(Modifier.height(12.dp))
                    AetherSettingsRow(
                        title = when {
                            advertising && scanning -> "Объявляем о себе и слушаем"
                            scanning -> "Слушаем, но о себе не объявляем"
                            advertising -> "Объявляем о себе"
                            else -> "Радио молчит"
                        },
                        icon = { Icon(Icons.Filled.BluetoothSearching, contentDescription = null) },
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "Наружу уходит только меняющийся код, а не ваше имя: тот, кому вы " +
                        "не давали ключ обнаружения, не свяжет две встречи в одно устройство.\n\n" +
                        "Обнаружение работает, пока приложение открыто — фоновое радио " +
                        "система глушит ради батареи. Отправка напрямую, без сервера, " +
                        "пока не сделана: это следующий этап.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
