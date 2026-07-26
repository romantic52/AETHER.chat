package org.groktest.securemessenger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.groktest.securemessenger.data.LockPrefs
import org.groktest.securemessenger.ui.components.AetherSettingsRow
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.AetherSwitchRow
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.glass.glassSource
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import org.groktest.securemessenger.ui.theme.aetherTextFieldColors

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
    val appearance = LocalThemeSettings.current

    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .glassSource()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(
                        start = AetherStyle.ScreenHorizontal,
                        end = AetherStyle.ScreenHorizontal,
                        bottom = 24.dp
                    )
            ) {
                Spacer(Modifier.height(AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical))
                AetherSwitchRow(
                    title = "Код-пароль",
                    subtitle = if (lockEnabled) "Включён · запрашивается при входе" else "Запрашивать пароль при входе",
                    checked = lockEnabled,
                    onCheckedChange = { on ->
                        if (on) {
                            showSetPin = true
                        } else {
                            prefs.disable()
                            lockEnabled = false
                            biometricOn = false
                        }
                    },
                    icon = { Icon(Icons.Default.Lock, contentDescription = null) }
                )

                AnimatedVisibility(
                    visible = lockEnabled,
                    enter = fadeIn(tween(appearance.motionDuration(150))) + expandVertically(
                        animationSpec = tween(appearance.motionDuration(200), easing = FastOutSlowInEasing)
                    ),
                    exit = fadeOut(tween(appearance.motionDuration(110))) + shrinkVertically(
                        animationSpec = tween(appearance.motionDuration(170), easing = FastOutSlowInEasing)
                    )
                ) {
                    Column {
                        AetherSettingsRow(
                            title = "Сменить код-пароль",
                            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            onClick = { showSetPin = true }
                        )
                        AetherSwitchRow(
                            title = "Разблокировка по отпечатку/лицу",
                            subtitle = if (biometricAvailable) "Биометрия вместо ввода PIN" else "Биометрия недоступна на устройстве",
                            checked = biometricOn,
                            onCheckedChange = {
                                biometricOn = it
                                prefs.biometricEnabled = it
                            },
                            icon = { Icon(Icons.Default.Fingerprint, contentDescription = null) },
                            enabled = biometricAvailable
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                val blocked = org.groktest.securemessenger.data.BlockStore.blocked
                var showBlocked by remember { mutableStateOf(false) }
                AetherSettingsRow(
                    title = "Черный список",
                    subtitle = if (blocked.isEmpty()) "Список пуст" else "Заблокировано: ${blocked.size}",
                    icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                    onClick = { showBlocked = !showBlocked }
                )
                AnimatedVisibility(
                    visible = showBlocked,
                    enter = fadeIn(tween(appearance.motionDuration(140))) + expandVertically(tween(appearance.motionDuration(190))),
                    exit = fadeOut(tween(appearance.motionDuration(100))) + shrinkVertically(tween(appearance.motionDuration(160)))
                ) {
                    Column {
                        if (blocked.isEmpty()) {
                            Text(
                                "Здесь будут пользователи, которых вы заблокировали (в профиле собеседника).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        } else {
                            blocked.toList().forEach { id ->
                                AetherSettingsRow(
                                    title = id,
                                    trailing = {
                                        TextButton(onClick = { org.groktest.securemessenger.data.BlockStore.unblock(context, id) }) {
                                            Text("Разблокировать")
                                        }
                                    }
                                )
                            }
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
        shape = RoundedCornerShape(AetherStyle.IslandRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Код-пароль (4 цифры)") },
        text = {
            Column {
                TextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(AetherStyle.FieldRadius),
                    colors = aetherTextFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = confirm,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) confirm = it },
                    label = { Text("Повторите PIN") },
                    singleLine = true,
                    isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(AetherStyle.FieldRadius),
                    colors = aetherTextFieldColors()
                )
                if (mismatch) Text("PIN не совпадает", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = match, onClick = { onConfirm(pin) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
