package aether.desktop.ui

import aether.desktop.auth.ActiveAccount
import aether.desktop.auth.AuthRepository
import aether.desktop.auth.TotpRequired
import aether.desktop.data.ServerConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    auth: AuthRepository,
    onSuccess: (ActiveAccount) -> Unit,
) {
    var server by remember { mutableStateOf(ServerConfig.DEFAULT_BASE_URL) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }
    var totpNeeded by remember { mutableStateOf(false) }
    var isRegister by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // QR — дефолт (как в Telegram); пароль остаётся как fallback.
    var qrMode by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val user = username.trim().lowercase()
                if (user.length !in 2..64) {
                    throw IllegalArgumentException("Имя пользователя: от 2 до 64 символов")
                }
                if (isRegister && password.length < 8) {
                    throw IllegalArgumentException("Пароль должен быть не короче 8 символов")
                }
                if (password.isEmpty()) throw IllegalArgumentException("Введите пароль")
                val account = if (isRegister) {
                    auth.register(server, user, password)
                } else {
                    auth.login(server, user, password, totpCode.takeIf { totpNeeded })
                }
                onSuccess(account)
            } catch (e: TotpRequired) {
                totpNeeded = true
                totpCode = ""
                error = if (e.invalid) "Неверный код 2FA — попробуйте ещё раз"
                else "На аккаунте включена 2FA: введите код из аутентификатора"
            } catch (e: Exception) {
                error = e.message ?: "Ошибка сети"
            } finally {
                busy = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(if (qrMode) 420.dp else 360.dp).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Æther", style = MaterialTheme.typography.headlineLarge)
            Text(
                when {
                    qrMode -> "Вход по QR-коду"
                    isRegister -> "Создание аккаунта"
                    else -> "Вход"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Сервер") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (qrMode) {
                QrLoginPane(auth = auth, server = server, onSuccess = onSuccess)
                TextButton(onClick = { qrMode = false; error = null }) {
                    Text("Войти по паролю")
                }
                return@Column
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Имя пользователя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (totpNeeded && !isRegister) {
                OutlinedTextField(
                    value = totpCode,
                    onValueChange = { totpCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("Код 2FA") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = ::submit,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (isRegister) "Создать аккаунт" else "Войти")
                }
            }
            TextButton(onClick = {
                isRegister = !isRegister
                totpNeeded = false
                error = null
            }) {
                Text(if (isRegister) "У меня уже есть аккаунт" else "Создать новый аккаунт")
            }
            TextButton(onClick = { qrMode = true; error = null }) {
                Text("Войти по QR-коду")
            }
        }
    }
}
