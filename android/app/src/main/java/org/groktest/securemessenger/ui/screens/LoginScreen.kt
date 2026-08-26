package org.groktest.securemessenger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.api.ServerDirectory
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.crypto.E2ECrypto
import org.groktest.securemessenger.data.SecurePrefs
import org.groktest.securemessenger.data.ServerInspection
import org.groktest.securemessenger.data.ServerRecord
import org.groktest.securemessenger.data.ServerRegistrationMode
import org.groktest.securemessenger.data.ServerRegistry
import org.groktest.securemessenger.data.SessionPrefs
import org.groktest.securemessenger.ui.components.AetherPrimaryButton
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherField
import org.groktest.securemessenger.ui.theme.aetherTextFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    registry: ServerRegistry,
    savedSession: SessionPrefs.Session?,
    legacyLogin: SessionPrefs.LegacyLogin? = null,
    forceCustomServer: Boolean = false,
    onLoginSuccess: (ServerRecord, E2ECrypto.KeyPair, RelayApi, String, Boolean) -> Unit
) {
    val official = remember(registry) { registry.official() }
    var selectedServer by remember {
        mutableStateOf(savedSession?.let { registry.server(it.serverId) } ?: official)
    }
    var customMode by remember { mutableStateOf(forceCustomServer || selectedServer.kind.name == "CUSTOM") }
    var serverInput by remember { mutableStateOf(legacyLogin?.server.orEmpty()) }
    var knownServers by remember { mutableStateOf(registry.servers().filterNot { it.isOfficial }) }
    var username by remember { mutableStateOf(legacyLogin?.username.orEmpty()) }
    var password by remember { mutableStateOf(legacyLogin?.password.orEmpty()) }
    var isRegister by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rememberMe by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var identityChange by remember { mutableStateOf<ServerInspection.IdentityChanged?>(null) }
    var identityConfirmStep by remember { mutableStateOf(false) }
    var totpNeeded by remember { mutableStateOf(false) }
    var totpCode by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val crypto = remember { E2ECrypto() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val directory = remember(registry) { ServerDirectory(registry) }

    suspend fun discoverServer() {
        if (isDiscovering) return
        isDiscovering = true
        error = null
        try {
            val inspection = withContext(Dispatchers.IO) { directory.inspect(serverInput) }
            selectedServer = when (inspection) {
                is ServerInspection.Fresh -> registry.trust(inspection.info)
                is ServerInspection.Known -> registry.trust(inspection.info, inspection.record.kind)
                is ServerInspection.IdentityChanged -> {
                    identityChange = inspection
                    identityConfirmStep = false
                    throw IllegalStateException(
                        "Идентификатор сервера изменился. Подключение заблокировано."
                    )
                }
            }
            serverInput = selectedServer.origin
            knownServers = registry.servers().filterNot { it.isOfficial }
        } catch (e: Exception) {
            error = when (e) {
                is uniffi.sm_core.CoreException.Crypto -> "Не удалось подтвердить подпись сервера"
                is uniffi.sm_core.CoreException.Network -> "Нет соединения с сервером"
                is uniffi.sm_core.CoreException.BadInput -> e.msg
                else -> e.message ?: "По этому адресу нет сервера Aether"
            }
        } finally {
            isDiscovering = false
        }
    }

    suspend fun submit(register: Boolean) {
        if (isSubmitting) return
        isSubmitting = true
        error = null
        try {
            val target = selectedServer
            if (customMode && target.isOfficial) {
                throw IllegalArgumentException("Сначала найдите и выберите пользовательский сервер")
            }
            if (register && target.registrationMode != ServerRegistrationMode.OPEN) {
                throw IllegalArgumentException(
                    when (target.registrationMode) {
                        ServerRegistrationMode.APPROVAL -> "На этом сервере регистрация только по одобрению администратора"
                        ServerRegistrationMode.INVITE_ONLY -> "Для регистрации на этом сервере нужен код приглашения"
                        ServerRegistrationMode.CLOSED -> "Регистрация на этом сервере отключена"
                        ServerRegistrationMode.OPEN -> ""
                    }
                )
            }
            val normalizedServer = ServerConfig.normalizeBaseUrl(target.apiUrl)
            val normalizedUsername = username.trim().lowercase()
            if (!normalizedServer.startsWith("http://") &&
                !normalizedServer.startsWith("https://")
            ) throw IllegalArgumentException("Адрес сервера должен начинаться с http:// или https://")
            if (normalizedUsername.length !in 2..64) {
                throw IllegalArgumentException("Имя пользователя должно содержать от 2 до 64 символов")
            }
            if (register && password.length < 8) {
                throw IllegalArgumentException("Пароль должен быть не короче 8 символов")
            }
            if (!register && password.isEmpty()) throw IllegalArgumentException("Введите пароль")

            username = normalizedUsername
            val api = RelayApi(normalizedServer)
            val keyPair = if (register) {
                val generated = crypto.generateKeyPair()
                val encryptedPrivateKey = crypto.encryptPrivateKey(generated.privateB64, password)
                withContext(Dispatchers.IO) {
                    api.register(normalizedUsername, generated.publicB64, encryptedPrivateKey, password)
                    SecurePrefs(context, target.storageServerId, normalizedUsername).saveKeys(generated)
                }
                generated
            } else {
                val result = withContext(Dispatchers.IO) {
                    api.login(normalizedUsername, password, totpCode.trim().takeIf { it.isNotBlank() })
                }
                val secure = SecurePrefs(context, target.storageServerId, normalizedUsername)
                val restored = if (result.encryptedPrivateKeyB64.isNotEmpty() &&
                    result.encryptedPrivateKeyB64 != "null"
                ) {
                    val privateKey = crypto.decryptPrivateKey(result.encryptedPrivateKeyB64, password)
                    val publicKey = withContext(Dispatchers.IO) { api.getPublicKey(normalizedUsername) }
                    E2ECrypto.KeyPair(privateKey, publicKey)
                } else {
                    withContext(Dispatchers.IO) { secure.loadKeys() }
                        ?: throw IllegalStateException(
                            "На сервере нет резервной копии ключа, локальные ключи тоже не найдены. " +
                                "Восстановите ключ с прежнего устройства."
                        )
                }
                withContext(Dispatchers.IO) { secure.saveKeys(restored) }
                restored
            }
            onLoginSuccess(
                target,
                keyPair,
                api,
                normalizedUsername,
                rememberMe,
            )
        } catch (e: RelayApi.TotpRequired) {
            totpNeeded = true
            totpCode = ""
            error = if (e.invalid) "Неверный код 2FA — попробуйте ещё раз"
            else "На аккаунте включена 2FA: введите код из аутентификатора"
        } catch (e: Exception) {
            error = if (selectedServer.isOfficial) {
                e.message ?: "Ошибка сети"
            } else {
                when (e) {
                    is IllegalArgumentException -> e.message
                    else -> if (register) "Сервер не принял регистрацию" else "Не удалось войти на сервер"
                } ?: "Ошибка запроса"
            }
        } finally {
            isSubmitting = false
        }
    }

    // Token first; the old password record is consumed only after a successful migration.
    LaunchedEffect(savedSession, legacyLogin) {
        if (!forceCustomServer && savedSession != null) {
            val sessionServer = ServerConfig.normalizeBaseUrl(savedSession.server)
            val sessionUsername = savedSession.username.trim().lowercase()
            var target = registry.server(savedSession.serverId) ?: official
            selectedServer = target
            customMode = !target.isOfficial
            serverInput = sessionServer
            username = sessionUsername
            try {
                if (!target.isOfficial) {
                    target = when (val inspection = withContext(Dispatchers.IO) {
                        directory.inspect(target.origin)
                    }) {
                        is ServerInspection.Fresh -> throw IllegalStateException("Сервер потерян из локального реестра")
                        is ServerInspection.Known -> registry.trust(inspection.info, target.kind)
                        is ServerInspection.IdentityChanged -> {
                            identityChange = inspection
                            identityConfirmStep = false
                            throw IllegalStateException(
                                "Идентификатор сервера изменился. Автоматический вход заблокирован."
                            )
                        }
                    }
                    selectedServer = target
                }
                val api = RelayApi(sessionServer)
                api.token = savedSession.token
                val tokenValid = withContext(Dispatchers.IO) { api.heartbeat() }
                val kp = withContext(Dispatchers.IO) {
                    SecurePrefs(context, target.storageServerId, savedSession.username).loadKeys()
                }
                if (tokenValid && kp != null) {
                    onLoginSuccess(target, kp, api, sessionUsername, true)
                    return@LaunchedEffect
                }
                val nextStep = if (legacyLogin != null) "выполняю восстановление" else "войдите снова"
                error = if (!tokenValid) "Сессия истекла — $nextStep"
                else "Локальные ключи не найдены — $nextStep"
            } catch (e: Exception) {
                error = e.message ?: "Не удалось восстановить сессию"
            }
        }
        if (!forceCustomServer && legacyLogin != null) {
            selectedServer = official
            serverInput = legacyLogin.server
            username = legacyLogin.username
            password = legacyLogin.password
            rememberMe = true
            submit(register = false)
        }
    }

    GlassBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = AetherStyle.ScreenHorizontal)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Aether",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isRegister) "Создайте свой профиль в новой экосистеме" else "С возвращением в безопасное пространство",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !customMode,
                        onClick = {
                            customMode = false
                            selectedServer = official
                            error = null
                        },
                        label = { Text("Наши серверы") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = customMode,
                        onClick = {
                            customMode = true
                            selectedServer = knownServers.firstOrNull() ?: official
                            error = null
                        },
                        label = { Text("Пользовательские") },
                        modifier = Modifier.weight(1f),
                    )
                }

                AnimatedVisibility(visible = customMode) {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = serverInput,
                            onValueChange = { serverInput = it },
                            label = { Text("Адрес сервера") },
                            supportingText = { Text("Домен, IP или ссылка aether://") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aetherField(shape = RoundedCornerShape(AetherStyle.FieldRadius)),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = aetherTextFieldColors(containerAlpha = 0f),
                            shape = RoundedCornerShape(AetherStyle.FieldRadius),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { coroutineScope.launch { discoverServer() } },
                            enabled = serverInput.isNotBlank() && !isDiscovering,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (isDiscovering) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Найти сервер")
                        }
                        if (!selectedServer.isOfficial) {
                            Spacer(Modifier.height(12.dp))
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(selectedServer.displayName, fontWeight = FontWeight.Bold)
                                    Text(selectedServer.hostLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    selectedServer.pin?.let { pin ->
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Отпечаток: ${pin.fingerprintB64.chunked(4).take(4).joinToString(" ")}…",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                        knownServers.filter { it.id != selectedServer.id }.forEach { known ->
                            TextButton(
                                onClick = {
                                    selectedServer = known
                                    serverInput = known.origin
                                    error = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("${known.displayName} · ${known.hostLabel}")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = { Text("Имя пользователя") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aetherField(shape = RoundedCornerShape(AetherStyle.FieldRadius)),
                    singleLine = true,
                    colors = aetherTextFieldColors(containerAlpha = 0f),
                    shape = RoundedCornerShape(AetherStyle.FieldRadius)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aetherField(shape = RoundedCornerShape(AetherStyle.FieldRadius)),
                    singleLine = true,
                    colors = aetherTextFieldColors(containerAlpha = 0f),
                    shape = RoundedCornerShape(AetherStyle.FieldRadius)
                )

                AnimatedVisibility(visible = totpNeeded && !isRegister) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = totpCode,
                            onValueChange = { totpCode = it.filter(Char::isDigit).take(10) },
                            placeholder = { Text("Код 2FA") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aetherField(shape = RoundedCornerShape(AetherStyle.FieldRadius)),
                            singleLine = true,
                            colors = aetherTextFieldColors(containerAlpha = 0f),
                            shape = RoundedCornerShape(AetherStyle.FieldRadius)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rememberMe,
                        onCheckedChange = { rememberMe = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                    )
                    Text("Запомнить меня", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(32.dp))

                AetherPrimaryButton(
                    text = if (isRegister) "Продолжить" else "Войти",
                    onClick = {
                        coroutineScope.launch { submit(isRegister) }
                    },
                    loading = isSubmitting
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (!customMode || selectedServer.registrationMode == ServerRegistrationMode.OPEN) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isRegister) "Уже есть аккаунт? Войти" else "Нет аккаунта? Создать",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { isRegister = !isRegister; error = null }
                                .padding(8.dp)
                        )
                    }
                } else if (!selectedServer.isOfficial) {
                    Text(
                        when (selectedServer.registrationMode) {
                            ServerRegistrationMode.APPROVAL -> "Регистрация требует подтверждения администратора"
                            ServerRegistrationMode.INVITE_ONLY -> "Регистрация доступна только по приглашению"
                            ServerRegistrationMode.CLOSED -> "Регистрация на этом сервере отключена"
                            ServerRegistrationMode.OPEN -> ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    identityChange?.let { change ->
        AlertDialog(
            onDismissRequest = {
                identityChange = null
                identityConfirmStep = false
            },
            title = { Text("Идентификатор сервера изменился") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (identityConfirmStep) {
                            "Подтвердите ещё раз. Новый ключ может принадлежать другому владельцу."
                        } else {
                            "Это может означать переустановку сервера или попытку подмены. Сверьте новый отпечаток с владельцем другим способом."
                        }
                    )
                    Text(
                        "Было: ${change.oldPin.fingerprintB64.chunked(4).take(4).joinToString(" ")}…\n" +
                            "Стало: ${change.info.fingerprintB64.chunked(4).take(4).joinToString(" ")}…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!identityConfirmStep) {
                        identityConfirmStep = true
                    } else {
                        selectedServer = registry.acceptChangedIdentity(change.info, change.record)
                        serverInput = selectedServer.origin
                        knownServers = registry.servers().filterNot { it.isOfficial }
                        identityChange = null
                        identityConfirmStep = false
                        error = null
                    }
                }) {
                    Text(if (identityConfirmStep) "Доверять новому серверу" else "Я сверил отпечаток")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    identityChange = null
                    identityConfirmStep = false
                }) { Text("Отмена") }
            },
        )
    }
}
