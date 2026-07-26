package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.ui.components.AetherSectionTitle
import org.groktest.securemessenger.ui.components.AetherSettingsRow
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.glass.glassSource
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherTextFieldColors

/**
 * «Сессии и безопасность»: устройства аккаунта с адресным выходом,
 * 2FA (TOTP) и «удалить всё». Серверная часть общая с web/iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    api: RelayApi,
    myId: String,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var info by remember { mutableStateOf<RelayApi.SessionsInfo?>(null) }
    var totpOn by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableStateOf(0) }

    var kickTarget by remember { mutableStateOf<RelayApi.DeviceSession?>(null) }
    var totpSetup by remember { mutableStateOf<RelayApi.TotpSetup?>(null) }
    var showTotpDisable by remember { mutableStateOf(false) }
    var showWipe by remember { mutableStateOf(false) }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    LaunchedEffect(reloadKey) {
        loading = true
        withContext(Dispatchers.IO) {
            runCatching { api.listSessions(myId) }
                .onSuccess { info = it }
                .onFailure { toast(it.message ?: "Не удалось загрузить сессии") }
            runCatching { api.totpStatus(myId) }.onSuccess { totpOn = it }
        }
        loading = false
    }

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .glassSource()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = AetherStyle.ScreenHorizontal)
                    .padding(bottom = 24.dp)
            ) {
                Spacer(Modifier.height(AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical))

                AetherSectionTitle("Устройства и сессии")
                val current = info
                if (loading && current == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (current != null) {
                    current.devices.forEach { dev ->
                        val allowed = dev.current || current.canKick
                        val since = formatDate(dev.createdAt)
                        AetherSettingsRow(
                            title = deviceTitle(dev.deviceId) + if (dev.current) " · это устройство" else "",
                            subtitle = "${dev.deviceId} · сессий: ${dev.sessions}" +
                                if (since.isNotEmpty()) " · с $since" else "",
                            icon = {
                                Icon(
                                    deviceIcon(dev.deviceId),
                                    contentDescription = null,
                                    tint = if (dev.current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailing = {
                                TextButton(onClick = { kickTarget = dev }, enabled = allowed) {
                                    Text(
                                        if (dev.current) "Выйти" else "Выкинуть",
                                        color = if (allowed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        )
                    }
                    if (!current.canKick) {
                        Hint("С нового устройства выкидывать другие можно через ${current.kickMinHours} ч.")
                    }
                    if (current.unboundSessions > 0) {
                        Hint("+ ${current.unboundSessions} сессий со старых версий приложений.")
                    }
                }

                Spacer(Modifier.height(12.dp))
                AetherSectionTitle("Двухфакторная аутентификация")
                AetherSettingsRow(
                    title = "Двухфакторная (TOTP)",
                    subtitle = if (totpOn) "Включена · вход требует код из аутентификатора"
                    else "Одноразовые коды из приложения",
                    icon = {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (totpOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailing = {
                        TextButton(onClick = {
                            if (totpOn) {
                                showTotpDisable = true
                            } else {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { api.totpSetup(myId) }
                                            .onSuccess { totpSetup = it }
                                            .onFailure { toast(it.message ?: "Не удалось настроить 2FA") }
                                    }
                                }
                            }
                        }) {
                            Text(if (totpOn) "Выключить" else "Включить", fontWeight = FontWeight.SemiBold)
                        }
                    }
                )

                Spacer(Modifier.height(12.dp))
                AetherSectionTitle("Данные")
                AetherSettingsRow(
                    title = "Удалить всё: чаты, группы, каналы",
                    subtitle = "Аккаунт и ключи останутся, данные на сервере будут стёрты",
                    icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                    destructive = true,
                    onClick = { showWipe = true }
                )
            }
            AetherSettingsTopBar("Безопасность", onBack, Modifier.align(Alignment.TopCenter))
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)
            )
        }
    }

    kickTarget?.let { dev ->
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            shape = RoundedCornerShape(AetherStyle.IslandRadius),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (dev.current) "Выйти на этом устройстве?" else "Выкинуть устройство?") },
            text = {
                Text(
                    if (dev.current) "Сессии этого устройства будут отозваны, его ключи удалены с сервера."
                    else "Сессии «${dev.deviceId}» будут отозваны, его ключи удалены. Устройство перестанет получать сообщения."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    kickTarget = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { api.kickDevice(myId, dev.deviceId) }
                                .onFailure { toast(it.message ?: "Не удалось выкинуть устройство") }
                                .isSuccess
                        }
                        if (ok) {
                            if (dev.current) onLoggedOut() else reloadKey++
                        }
                    }
                }) {
                    Text(if (dev.current) "Выйти" else "Выкинуть", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { kickTarget = null }) { Text("Отмена") } }
        )
    }

    totpSetup?.let { setup ->
        TotpCodeDialog(
            title = "Включить 2FA",
            confirmLabel = "Включить",
            destructive = false,
            secret = setup.secret,
            onConfirm = { code ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { api.totpEnable(myId, code) }
                            .onFailure { toast("Неверный код — 2FA не включена") }
                            .isSuccess
                    }
                    if (ok) {
                        totpSetup = null
                        toast("2FA включена")
                        reloadKey++
                    }
                }
            },
            onDismiss = { totpSetup = null }
        )
    }

    if (showTotpDisable) {
        TotpCodeDialog(
            title = "Выключить 2FA",
            confirmLabel = "Выключить",
            destructive = true,
            secret = null,
            onConfirm = { code ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { api.totpDisable(myId, code) }
                            .onFailure { toast("Неверный код") }
                            .isSuccess
                    }
                    if (ok) {
                        showTotpDisable = false
                        toast("2FA выключена")
                        reloadKey++
                    }
                }
            },
            onDismiss = { showTotpDisable = false }
        )
    }

    if (showWipe) {
        WipeDialog(
            onConfirm = { password ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { api.wipeAccount(myId, password) }
                            .onFailure { toast("Не удалось: проверьте пароль") }
                            .isSuccess
                    }
                    if (ok) {
                        showWipe = false
                        toast("Готово: сервер очищен, остальные сессии отозваны")
                        reloadKey++
                    }
                }
            },
            onDismiss = { showWipe = false }
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/** Диалог с вводом одноразового кода; при включении дополнительно показывает секрет. */
@Composable
private fun TotpCodeDialog(
    title: String,
    confirmLabel: String,
    destructive: Boolean,
    secret: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AetherStyle.IslandRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            Column {
                if (secret != null) {
                    Text("Добавьте секрет в аутентификатор (Google Authenticator, 1Password), затем введите код для подтверждения:")
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            secret,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(secret))
                    }) { Text("Скопировать секрет") }
                    Spacer(Modifier.height(4.dp))
                } else {
                    Text("Введите текущий код из аутентификатора.")
                    Spacer(Modifier.height(8.dp))
                }
                TextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(10) },
                    label = { Text("Код") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(AetherStyle.FieldRadius),
                    colors = aetherTextFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.length >= 6,
                onClick = { onConfirm(code.trim()) }
            ) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun WipeDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AetherStyle.IslandRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Удалить всё") },
        text = {
            Column {
                Text(
                    "Переписки на сервере, выход из всех групп и каналов, отзыв остальных сессий. " +
                        "Введите пароль аккаунта для подтверждения."
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(AetherStyle.FieldRadius),
                    colors = aetherTextFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { onConfirm(password) }) {
                Text("Удалить всё", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun deviceTitle(id: String): String = when {
    id == "primary" -> "Основное"
    id.startsWith("web-") -> "Веб"
    id.startsWith("ios-") -> "iOS"
    id.startsWith("android-") -> "Android"
    else -> "Устройство"
}

private fun deviceIcon(id: String): ImageVector = when {
    id == "primary" -> Icons.Default.Smartphone
    id.startsWith("web-") -> Icons.Default.Language
    id.startsWith("ios-") -> Icons.Default.PhoneIphone
    id.startsWith("android-") -> Icons.Default.Android
    else -> Icons.Default.Smartphone
}

/** "2026-07-21T09:15:00" -> "21.07.2026"; пустое/кривое — "". */
private fun formatDate(iso: String): String {
    val date = iso.take(10).split("-")
    return if (date.size == 3) "${date[2]}.${date[1]}.${date[0]}" else ""
}
