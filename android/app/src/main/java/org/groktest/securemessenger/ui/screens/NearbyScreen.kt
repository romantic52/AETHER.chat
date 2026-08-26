package org.groktest.securemessenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
fun NearbyScreen(
    onBack: () -> Unit,
    knownKeys: suspend () -> Map<String, String> = { emptyMap() },
    shareKey: suspend (String) -> Int = { 0 },
    setDirectTransport: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val privacy = remember { NearbyPrivacy.get(context) }
    val service = remember { NearbyDiscoveryService.get(context) }

    val peers by service.peers.collectAsState()
    val advertising by service.advertising.collectAsState()
    val scanning by service.scanning.collectAsState()

    var enabled by remember { mutableStateOf(privacy.enabled) }
    var audience by remember { mutableStateOf(privacy.audience) }
    var stranger by remember { mutableStateOf(privacy.strangerVisibility) }
    var canMessage by remember { mutableStateOf(privacy.strangersCanMessage) }
    var granted by remember { mutableStateOf(service.hasPermissions()) }
    var keys by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { keys = knownKeys() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        if (granted && enabled) service.start(keys)
    }

    DisposableEffect(enabled, granted, keys) {
        if (enabled && granted) {
            service.start(keys)
            // Приём напрямую поднимаем только вместе с обнаружением: без
            // спроса держать открытый GATT-сервер нельзя.
            setDirectTransport(true)
        } else {
            service.stop()
            setDirectTransport(false)
        }
        onDispose {
            service.stop()
            setDirectTransport(false)
        }
    }

    GlassBackground {
      Box(Modifier.fillMaxSize()) {
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
                AetherSectionTitle("Кто сможет узнать именно вас")
                AetherSettingsRow(
                    title = if (sharing) "Раздаём…" else "Дать контактам ключ обнаружения",
                    subtitle = if (keys.isEmpty()) {
                        "Пока никто из контактов вас не опознает"
                    } else {
                        "Вы узнаёте рядом: ${keys.size}"
                    },
                    icon = { Icon(Icons.Filled.Key, contentDescription = null) },
                    onClick = {
                        if (sharing) return@AetherSettingsRow
                        sharing = true
                        scope.launch {
                            val sent = runCatching { shareKey(privacy.discoveryKey()) }.getOrDefault(0)
                            sharing = false
                            snackbar.showSnackbar(
                                if (sent > 0) "Ключ отправлен контактам: $sent"
                                else "Не удалось отправить — нет сети или контактов"
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ключ уходит внутри зашифрованной переписки, сервер его не видит. " +
                        "Получивший сможет замечать вас рядом — отозвать это можно только " +
                        "сменой ключа, и тогда вас перестанут узнавать все сразу.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
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
                        "Текст такому собеседнику уходит напрямую, минуя сервер — это " +
                        "запасной путь там, где нет интернета. Голосовые и картинки " +
                        "по Bluetooth пока не идут: слишком медленно.\n\n" +
                        "Всё это живёт, пока приложение открыто: фоновое радио система " +
                        "глушит ради батареи.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
      }
    }
}
