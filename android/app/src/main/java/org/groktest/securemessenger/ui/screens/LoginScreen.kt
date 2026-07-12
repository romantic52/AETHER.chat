package org.groktest.securemessenger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.crypto.E2ECrypto
import org.groktest.securemessenger.data.SecurePrefs
import org.groktest.securemessenger.data.SessionPrefs
import org.groktest.securemessenger.ui.components.GlassBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    savedSession: SessionPrefs.Session?,
    onLoginSuccess: (String, E2ECrypto.KeyPair, RelayApi, String, Boolean) -> Unit
) {
    var server by remember { mutableStateOf(ServerConfig.DEFAULT_BASE_URL) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()
    val crypto = remember { E2ECrypto() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Авто-вход по сохранённому токену сессии (пароль на устройстве не хранится).
    LaunchedEffect(savedSession) {
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
                    val prefs = "$sessionServer|$sessionUsername"
                    withContext(Dispatchers.Main) {
                        onLoginSuccess(prefs, kp, api, sessionUsername, true)
                    }
                } else {
                    error = if (!tokenValid) "Сессия истекла — войдите заново"
                            else "Локальные ключи не найдены — войдите заново"
                }
            } catch (e: Exception) {
                error = "Auto-login failed: ${e.message ?: "Network Error"}"
            }
        }
    }

    GlassBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = { showSettings = !showSettings },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Aether",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isRegister) "Создайте свой профиль в новой экосистеме" else "С возвращением в безопасное пространство",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(48.dp))

                AnimatedVisibility(visible = showSettings) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("Сервер", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha=0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha=0.5f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = { Text("Имя пользователя", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha=0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha=0.5f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("Пароль", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha=0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha=0.5f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(16.dp)
                )

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
                    Text("Запомнить меня", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            error = null
                            val normalizedServer = ServerConfig.normalizeBaseUrl(server)
                            val normalizedUsername = username.trim().lowercase()
                            if (!normalizedServer.startsWith("http://") &&
                                !normalizedServer.startsWith("https://")) {
                                error = "Адрес сервера должен начинаться с http:// или https://"
                                return@launch
                            }
                            if (normalizedUsername.length !in 2..64) {
                                error = "Имя пользователя должно содержать от 2 до 64 символов"
                                return@launch
                            }
                            if (isRegister && password.length < 8) {
                                error = "Пароль должен быть не короче 8 символов"
                                return@launch
                            }
                            if (!isRegister && password.isEmpty()) {
                                error = "Введите пароль"
                                return@launch
                            }
                            try {
                                server = normalizedServer
                                username = normalizedUsername
                                val api = RelayApi(normalizedServer)
                                if (isRegister) {
                                    val pubkey = crypto.generateKeyPair()
                                    val encPriv = crypto.encryptPrivateKey(pubkey.privateB64, password)

                                    withContext(Dispatchers.IO) {
                                        api.register(normalizedUsername, pubkey.publicB64, encPriv, password)
                                        SecurePrefs(context, normalizedUsername).saveKeys(pubkey)
                                    }

                                    val prefs = "$normalizedServer|$normalizedUsername"
                                    withContext(Dispatchers.Main) {
                                        onLoginSuccess(prefs, pubkey, api, normalizedUsername, rememberMe)
                                    }
                                } else {
                                    val result = withContext(Dispatchers.IO) {
                                        api.login(normalizedUsername, password)
                                    }

                                    val secure = SecurePrefs(context, normalizedUsername)
                                    val kp = if (!result.encryptedPrivateKeyB64.isNullOrEmpty() && result.encryptedPrivateKeyB64 != "null") {
                                        val priv = crypto.decryptPrivateKey(result.encryptedPrivateKeyB64, password)
                                        val pubKeyStr = withContext(Dispatchers.IO) { api.getPublicKey(normalizedUsername) }
                                        E2ECrypto.KeyPair(priv, pubKeyStr)
                                    } else {
                                        // P3.9: НЕ генерируем новую пару молча — она не совпадёт с
                                        // публичным ключом на сервере и переписка перестанет читаться.
                                        withContext(Dispatchers.IO) { secure.loadKeys() }
                                            ?: throw IllegalStateException(
                                                "На сервере нет резервной копии ключа, локальных ключей тоже нет. " +
                                                "Вход без ключа невозможен — восстановите ключ с другого устройства " +
                                                "или зарегистрируйте новый аккаунт."
                                            )
                                    }
                                    withContext(Dispatchers.IO) { secure.saveKeys(kp) }

                                    val prefs = "$normalizedServer|$normalizedUsername"
                                    withContext(Dispatchers.Main) {
                                        onLoginSuccess(prefs, kp, api, normalizedUsername, rememberMe)
                                    }
                                }
                            } catch (e: Exception) {
                                error = e.message ?: "Network Error / Timeout"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = if (isRegister) "Продолжить" else "Войти",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = error!!, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isRegister) "Уже есть аккаунт? Войти" else "Нет аккаунта? Создать",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp,
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
