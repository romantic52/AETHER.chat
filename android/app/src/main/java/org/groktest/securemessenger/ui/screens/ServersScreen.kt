package org.groktest.securemessenger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.ServerDirectory
import org.groktest.securemessenger.data.ServerInspection
import org.groktest.securemessenger.data.ServerKind
import org.groktest.securemessenger.data.ServerRecord
import org.groktest.securemessenger.data.ServerRegistrationMode
import org.groktest.securemessenger.data.ServerRegistry
import org.groktest.securemessenger.ui.components.AetherPrimaryButton
import org.groktest.securemessenger.ui.components.AetherSectionTitle
import org.groktest.securemessenger.ui.components.AetherSettingsRow
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.aetherField
import org.groktest.securemessenger.ui.theme.aetherTextFieldColors

private fun ServerRegistrationMode.label(): String = when (this) {
    ServerRegistrationMode.OPEN -> "Открытая регистрация"
    ServerRegistrationMode.APPROVAL -> "Вход по одобрению владельца"
    ServerRegistrationMode.INVITE_ONLY -> "Только по приглашению"
    ServerRegistrationMode.CLOSED -> "Регистрация закрыта"
}

/**
 * Управление серверами: список, добавление по адресу, отпечаток для сверки
 * и удаление своих серверов. Официальный сервер удалить нельзя — иначе
 * после выхода из своего сервера некуда возвращаться.
 */
@Composable
fun ServersScreen(
    registry: ServerRegistry,
    activeServerId: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val directory = remember(registry) { ServerDirectory(registry) }

    var servers by remember { mutableStateOf(registry.servers()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<ServerRecord?>(null) }
    var identityChange by remember { mutableStateOf<ServerInspection.IdentityChanged?>(null) }

    fun refresh() { servers = registry.servers() }

    fun add() {
        val target = input.trim()
        if (target.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                when (val inspection = withContext(Dispatchers.IO) { directory.inspect(target) }) {
                    is ServerInspection.Fresh -> {
                        registry.trust(inspection.info, ServerKind.CUSTOM)
                        input = ""
                        refresh()
                        snackbar.showSnackbar("Сервер добавлен")
                    }
                    is ServerInspection.Known -> {
                        registry.trust(inspection.info, inspection.record.kind)
                        input = ""
                        refresh()
                        snackbar.showSnackbar("Сервер уже был в списке — данные обновлены")
                    }
                    // Отпечаток разошёлся с запомненным: молча доверять нельзя.
                    is ServerInspection.IdentityChanged -> identityChange = inspection
                }
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Не удалось связаться с сервером")
            } finally {
                busy = false
            }
        }
    }

    GlassBackground {
      Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AetherSettingsTopBar(title = "Серверы", onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .navigationBarsPadding(),
            ) {
                AetherSectionTitle("Ваши серверы")
                servers.forEach { record ->
                    val active = record.id == activeServerId ||
                        (activeServerId == null && record.isOfficial)
                    AetherSettingsRow(
                        title = record.displayName.ifBlank { record.declaredName },
                        subtitle = buildString {
                            append(record.hostLabel)
                            if (record.accounts.isNotEmpty()) {
                                append(" · аккаунтов: ${record.accounts.size}")
                            }
                            if (active) append(" · сейчас здесь")
                        },
                        icon = {
                            Icon(
                                if (record.isOfficial) Icons.Filled.Cloud else Icons.Filled.Dns,
                                contentDescription = null,
                            )
                        },
                        onClick = { expandedId = if (expandedId == record.id) null else record.id },
                    )
                    AnimatedVisibility(visible = expandedId == record.id) {
                        ServerDetails(
                            record = record,
                            canRemove = !record.isOfficial && !active,
                            onRemove = { pendingRemoval = record },
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                AetherSectionTitle("Добавить сервер")
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().aetherField(),
                    singleLine = true,
                    enabled = !busy,
                    placeholder = { Text("адрес сервера") },
                    colors = aetherTextFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Мы запросим у сервера его удостоверение, проверим подпись и запомним " +
                        "отпечаток. Если позже отпечаток изменится — предупредим.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                AetherPrimaryButton(
                    text = if (busy) "Проверяем…" else "Добавить",
                    onClick = ::add,
                    enabled = !busy && input.isNotBlank(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
      }
    }

    pendingRemoval?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Удалить сервер?") },
            text = {
                Text(
                    "«${record.displayName}» исчезнет из списка вместе с запомненным " +
                        "отпечатком. Сообщения и аккаунты на самом сервере останутся — " +
                        "удаляется только запись на этом устройстве."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    registry.remove(record.id)
                    if (expandedId == record.id) expandedId = null
                    pendingRemoval = null
                    refresh()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Отмена") }
            },
        )
    }

    identityChange?.let { change ->
        AlertDialog(
            onDismissRequest = { identityChange = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Сервер сменил удостоверение") },
            text = {
                Column {
                    Text(
                        "У «${change.record.displayName}» изменился отпечаток. Так бывает при " +
                            "переустановке сервера — но так же выглядит и подмена.\n\n" +
                            "Сверьте новый отпечаток с владельцем сервера по другому каналу."
                    )
                    Spacer(Modifier.height(12.dp))
                    FingerprintBlock("Было", change.oldPin.fingerprintB64)
                    Spacer(Modifier.height(8.dp))
                    FingerprintBlock("Стало", change.info.fingerprintB64)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Новая личность — новое пространство: старые аккаунты не переносятся.
                    registry.acceptChangedIdentity(change.info, change.record)
                    identityChange = null
                    input = ""
                    refresh()
                }) { Text("Принять новый", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { identityChange = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun ServerDetails(
    record: ServerRecord,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
        DetailLine("Адрес", record.origin)
        DetailLine("Протокол", "версия ${record.protocolVersion}")
        DetailLine("Допуск", record.registrationMode.label())
        if (record.capabilities.isNotEmpty()) {
            DetailLine("Умеет", record.capabilities.joinToString(", "))
        }
        if (record.cleartext) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Соединение без шифрования канала. Содержимое сообщений всё равно " +
                    "зашифровано из конца в конец, но адреса и время видны сети.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        record.pin?.let { pin ->
            Spacer(Modifier.height(8.dp))
            FingerprintBlock("Отпечаток", pin.fingerprintB64)
        }
        record.accounts.forEach { account ->
            DetailLine("Аккаунт", account.displayName.ifBlank { account.userId })
        }
        if (canRemove) {
            Spacer(Modifier.height(8.dp))
            AetherSettingsRow(
                title = "Удалить сервер",
                destructive = true,
                icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Отпечаток даётся моноширинным и выделяемым — его сверяют глазами и пересылают. */
@Composable
private fun FingerprintBlock(label: String, fingerprintB64: String) {
    val pretty = remember(fingerprintB64) {
        runCatching { uniffi.sm_core.formatFingerprint(fingerprintB64) }
            .getOrDefault(fingerprintB64)
    }
    Column {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                pretty,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
