package org.groktest.securemessenger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.crypto.E2ECrypto
import org.groktest.securemessenger.data.SecurePrefs
import org.groktest.securemessenger.data.SessionPrefs
import org.groktest.securemessenger.ui.components.AetherPrimaryButton
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherCircle
import org.groktest.securemessenger.ui.theme.aetherTextFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    savedSession: SessionPrefs.Session?,
    legacyLogin: SessionPrefs.LegacyLogin? = null,
    onLoginSuccess: (String, E2ECrypto.KeyPair, RelayApi, String, Boolean) -> Unit
) {
    var server by remember { mutableStateOf(legacyLogin?.server ?: ServerConfig.DEFAULT_BASE_URL) }
    var username by remember { mutableStateOf(legacyLogin?.username.orEmpty()) }
    var password by remember { mutableStateOf(legacyLogin?.password.orEmpty()) }
    var isRegister by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }
    var totpNeeded by remember { mutableStateOf(false) }
    var totpCode by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val crypto = remember { E2ECrypto() }
    val context = androidx.compose.ui.platform.LocalContext.current

    suspend fun submit(register: Boolean) {
        if (isSubmitting) return
        isSubmitting = true
        error = null
        try {
            val normalizedServer = ServerConfig.normalizeBaseUrl(server)
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

            server = normalizedServer
            username = normalizedUsername
            val api = RelayApi(normalizedServer)
            val keyPair = if (register) {
                val generated = crypto.generateKeyPair()
                val encryptedPrivateKey = crypto.encryptPrivateKey(generated.privateB64, password)
                withContext(Dispatchers.IO) {
                    api.register(normalizedUsername, generated.publicB64, encryptedPrivateKey, password)
                    SecurePrefs(context, normalizedUsername).saveKeys(generated)
                }
                generated
            } else {
                val result = withContext(Dispatchers.IO) {
                    api.login(normalizedUsername, password, totpCode.trim().takeIf { it.isNotBlank() })
                }
                val secure = SecurePrefs(context, normalizedUsername)
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
                "$normalizedServer|$normalizedUsername",
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
            error = e.message ?: "Ошибка сети"
        } finally {
            isSubmitting = false
        }
    }

    // Token first; the old password record is consumed only after a successful migration.
    LaunchedEffect(savedSession, legacyLogin) {
        if (savedSession != null) {
            val sessionServer = ServerConfig.normalizeBaseUrl(savedSession.server)
            val sessionUsername = savedSession.username.trim().lowercase()
            server = sessionServer
            username = sessionUsername
            try {
                val api = RelayApi(sessionServer)
                api.token = savedSession.token
                val tokenValid = withContext(Dispatchers.IO) { api.heartbeat() }
                val kp = withContext(Dispatchers.IO) {
                    SecurePrefs(context, savedSession.username).loadKeys()
                }
                if (tokenValid && kp != null) {
                    onLoginSuccess("$sessionServer|$sessionUsername", kp, api, sessionUsername, true)
                    return@LaunchedEffect
                }
                error = if (!tokenValid) "Сессия истекла — выполняю восстановление"
                else "Локальные ключи не найдены — выполняю восстановление"
            } catch (e: Exception) {
                error = e.message ?: "Не удалось восстановить сессию"
            }
        }
        if (legacyLogin != null) {
            server = legacyLogin.server
            username = legacyLogin.username
            password = legacyLogin.password
            rememberMe = true
            submit(register = false)
        }
    }

    GlassBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = { showSettings = !showSettings },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(AetherStyle.SmallControlSize)
                    .aetherCircle()
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

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

                Spacer(modifier = Modifier.height(48.dp))

                AnimatedVisibility(visible = showSettings) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("Сервер") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = aetherTextFieldColors(),
                        shape = RoundedCornerShape(AetherStyle.FieldRadius)
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = { Text("Имя пользователя") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = aetherTextFieldColors(),
                    shape = RoundedCornerShape(AetherStyle.FieldRadius)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = aetherTextFieldColors(),
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
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = aetherTextFieldColors(),
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
            }
        }
    }
}
