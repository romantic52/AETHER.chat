package org.groktest.securemessenger.ui.screens

import androidx.biometric.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.groktest.securemessenger.data.LockPrefs
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { LockPrefs(context) }
    var lockEnabled by remember { mutableStateOf(prefs.enabled) }
    var biometricOn by remember { mutableStateOf(prefs.biometricEnabled) }
    var showSetPin by remember { mutableStateOf(false) }

    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                Spacer(Modifier.height(AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical))
                ListItem(
                    headlineContent = { Text("Код-пароль") },
                    supportingContent = { Text(if (lockEnabled) "Включён · запрашивается при входе" else "Запрашивать пароль при входе") },
                    leadingContent = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(
                            checked = lockEnabled,
                            onCheckedChange = { on ->
                                if (on) {
                                    showSetPin = true
                                } else {
                                    prefs.disable()
                                    lockEnabled = false
                                    biometricOn = false
                                }
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                if (lockEnabled) {
                    ListItem(
                        headlineContent = { Text("Сменить код-пароль") },
                        leadingContent = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { showSetPin = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text("Разблокировка по отпечатку/лицу") },
                        supportingContent = { Text(if (biometricAvailable) "Биометрия вместо ввода PIN" else "Биометрия недоступна на устройстве") },
                        leadingContent = { Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(
                                checked = biometricOn,
                                enabled = biometricAvailable,
                                onCheckedChange = {
                                    biometricOn = it
                                    prefs.biometricEnabled = it
                                }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))

                val blocked = org.groktest.securemessenger.data.BlockStore.blocked
                var showBlocked by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("Черный список") },
                    supportingContent = { Text(if (blocked.isEmpty()) "Список пуст" else "Заблокировано: ${blocked.size}") },
                    leadingContent = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.clickable { showBlocked = !showBlocked },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                if (showBlocked) {
                    if (blocked.isEmpty()) {
                        Text(
                            "Здесь будут пользователи, которых вы заблокировали (в профиле собеседника).",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    } else {
                        blocked.toList().forEach { id ->
                            ListItem(
                                headlineContent = { Text(id) },
                                trailingContent = {
                                    TextButton(onClick = { org.groktest.securemessenger.data.BlockStore.unblock(context, id) }) {
                                        Text("Разблокировать")
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            }
            AetherSettingsTopBar(
                "Конфиденциальность",
                onBack,
                Modifier.align(Alignment.TopCenter)
            )
        }
    }

    if (showSetPin) {
        SetPinDialog(
            onConfirm = { pin ->
                prefs.setPin(pin)
                lockEnabled = true
                showSetPin = false
            },
            onDismiss = {
                showSetPin = false
                // Если включали впервые и отменили — тумблер остаётся выключенным
                lockEnabled = prefs.enabled
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetPinDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val match = pin.length == 4 && pin == confirm
    val mismatch = confirm.length == 4 && pin != confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Код-пароль (4 цифры)") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) confirm = it },
                    label = { Text("Повторите PIN") },
                    singleLine = true,
                    isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (mismatch) Text("PIN не совпадает", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = match, onClick = { onConfirm(pin) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
