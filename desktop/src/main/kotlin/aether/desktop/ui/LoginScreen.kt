package aether.desktop.ui

import aether.desktop.auth.ActiveAccount
import aether.desktop.auth.AuthRepository
import aether.desktop.auth.TotpRequired
import aether.desktop.data.ServerConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.padding(vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.width(360.dp).padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Æ",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Æther",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Мессенджер со сквозным шифрованием",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                TabRow(
                    selectedTabIndex = if (qrMode) 0 else 1,
                    containerColor = Color.Transparent,
                ) {
                    Tab(
                        selected = qrMode,
                        onClick = { qrMode = true; error = null },
                        text = { Text("QR-код") },
                    )
                    Tab(
                        selected = !qrMode,
                        onClick = { qrMode = false; error = null },
                        text = { Text("Пароль") },
                    )
                }
                Spacer(Modifier.height(20.dp))
                if (qrMode) {
                    QrLoginPane(auth = auth, server = server, onSuccess = onSuccess)
                    Spacer(Modifier.height(16.dp))
                    ServerField(server) { server = it }
                } else {
                    PasswordPane(
                        server = server,
                        onServerChange = { server = it },
                        username = username,
                        onUsernameChange = { username = it },
                        password = password,
                        onPasswordChange = { password = it },
                        totpCode = totpCode,
                        onTotpChange = { totpCode = it.filter(Char::isDigit).take(6) },
                        totpNeeded = totpNeeded && !isRegister,
                        isRegister = isRegister,
                        error = error,
                        busy = busy,
                        onSubmit = ::submit,
                        onToggleRegister = {
                            isRegister = !isRegister
                            totpNeeded = false
                            error = null
                        },
                    )
                }
            }
        }
    }
}

/** Форма запасного входа по паролю: поля + кнопка на всю ширину карточки. */
@Composable
private fun PasswordPane(
    server: String,
    onServerChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    totpCode: String,
    onTotpChange: (String) -> Unit,
    totpNeeded: Boolean,
    isRegister: Boolean,
    error: String?,
    busy: Boolean,
    onSubmit: () -> Unit,
    onToggleRegister: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ServerField(server, onServerChange)
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Имя пользователя") },
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Пароль") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (totpNeeded) {
            OutlinedTextField(
                value = totpCode,
                onValueChange = onTotpChange,
                label = { Text("Код 2FA") },
                leadingIcon = { Icon(Icons.Filled.Pin, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Кнопка не гасится на время запроса: submit() сам глотает повторные
        // клики, а контейнер остаётся синим — иначе спиннер onPrimary
        // неразличим на сером disabled-фоне.
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(if (isRegister) "Создать аккаунт" else "Войти")
            }
        }
        TextButton(onClick = onToggleRegister) {
            Text(if (isRegister) "У меня уже есть аккаунт" else "Создать новый аккаунт")
        }
    }
}

@Composable
private fun ServerField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Сервер") },
        leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
