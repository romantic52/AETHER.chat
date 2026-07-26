package aether.desktop.ui

import aether.desktop.AppSession
import aether.desktop.api.RelayApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «Устройства и сессии»: список устройств, kick, 2FA, «удалить всё». */
@Composable
fun SessionsDialog(
    session: AppSession,
    onDismiss: () -> Unit,
    onAccountWiped: () -> Unit,
) {
    var info by remember { mutableStateOf<RelayApi.SessionsInfo?>(null) }
    var totpEnabled by remember { mutableStateOf<Boolean?>(null) }
    var totpSetup by remember { mutableStateOf<RelayApi.TotpSetup?>(null) }
    var totpCode by remember { mutableStateOf("") }
    var wipePassword by remember { mutableStateOf("") }
    var showWipe by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var myDevice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        info = runCatching {
            withContext(Dispatchers.IO) { session.api.listSessions(session.myId) }
        }.getOrNull()
        totpEnabled = runCatching {
            withContext(Dispatchers.IO) { session.api.totpStatus(session.myId) }
        }.getOrNull()
        myDevice = runCatching { session.repository.myDeviceId() }.getOrDefault("")
    }

    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Устройства и сессии") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = info
                if (current == null) {
                    Text("Загрузка…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(current.devices, key = { it.deviceId }) { device ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        device.deviceId + if (device.deviceId == myDevice) " (это устройство)" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (device.current) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    Text(
                                        "сессий: ${device.sessions} · c ${device.createdAt.take(10)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (device.deviceId != myDevice) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        session.api.kickDevice(session.myId, device.deviceId)
                                                    }
                                                    reload()
                                                } catch (e: Exception) {
                                                    errorText = e.message
                                                }
                                            }
                                        },
                                        enabled = current.canKick,
                                    ) {
                                        Text("Выкинуть", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    if (!current.canKick) {
                        Text(
                            "Кик доступен устройствам старше ${current.kickMinHours} ч (анти-вор).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                when (totpEnabled) {
                    null -> {}
                    true -> {
                        Text("2FA включена", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = totpCode,
                            onValueChange = { totpCode = it.filter(Char::isDigit).take(6) },
                            label = { Text("Код для отключения") },
                            singleLine = true,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { session.api.totpDisable(session.myId, totpCode) }
                                    totpCode = ""
                                    reload()
                                } catch (e: Exception) {
                                    errorText = e.message
                                }
                            }
                        }) { Text("Выключить 2FA") }
                    }
                    false -> {
                        val setup = totpSetup
                        if (setup == null) {
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        totpSetup = withContext(Dispatchers.IO) { session.api.totpSetup(session.myId) }
                                    } catch (e: Exception) {
                                        errorText = e.message
                                    }
                                }
                            }) { Text("Включить 2FA") }
                        } else {
                            Text(
                                "Добавьте секрет в аутентификатор и введите код:",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(setup.secret, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = totpCode,
                                onValueChange = { totpCode = it.filter(Char::isDigit).take(6) },
                                label = { Text("Код из приложения") },
                                singleLine = true,
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { session.api.totpEnable(session.myId, totpCode) }
                                        totpSetup = null
                                        totpCode = ""
                                        reload()
                                    } catch (e: Exception) {
                                        errorText = e.message
                                    }
                                }
                            }) { Text("Подтвердить") }
                        }
                    }
                }

                HorizontalDivider()

                if (!showWipe) {
                    TextButton(onClick = { showWipe = true }) {
                        Text("Удалить все данные…", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text(
                        "Сервер удалит переписки, выведет из групп и отзовёт другие сессии. Необратимо.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(
                        value = wipePassword,
                        onValueChange = { wipePassword = it },
                        label = { Text("Пароль для подтверждения") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { session.api.wipeAccount(session.myId, wipePassword) }
                                    onAccountWiped()
                                } catch (e: Exception) {
                                    errorText = e.message
                                }
                            }
                        },
                        enabled = wipePassword.isNotBlank(),
                    ) { Text("Удалить всё", color = MaterialTheme.colorScheme.error) }
                }

                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}
