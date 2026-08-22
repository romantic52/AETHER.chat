package org.groktest.securemessenger.ui.screens

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.groktest.securemessenger.data.LockPrefs
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherControl
import org.groktest.securemessenger.ui.theme.aetherControlContent

private const val PIN_LEN = 4

// Размер клавиш/ячеек PIN-клавиатуры: крупный таргет осознанно больше ControlSize.
private val KeypadSize = 72.dp

/**
 * Экран блокировки приложения: ввод PIN (4 цифры) + биометрия (если включена).
 * Показывается поверх всего, пока [onUnlocked] не вызовется.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { LockPrefs(context) }
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun tryBiometric() {
        val activity = context as? androidx.fragment.app.FragmentActivity ?: return
        if (!prefs.biometricEnabled) return
        if (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            != BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Разблокировка")
            .setSubtitle("Подтвердите, что это вы")
            .setNegativeButtonText("Ввести PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) { tryBiometric() }

    fun onDigit(d: String) {
        if (entered.length >= PIN_LEN) return
        entered += d
        if (entered.length == PIN_LEN) {
            if (prefs.verify(entered)) {
                onUnlocked()
            } else {
                wrong = true
                entered = ""
            }
        } else {
            wrong = false
        }
    }

    // Непрозрачный фон темы + обои рисует сам GlassBackground — приватность сохраняется.
    GlassBackground {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    if (wrong) "Неверный код, попробуйте ещё" else "Введите код-пароль",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(24.dp))

                // Точки-индикаторы
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    repeat(PIN_LEN) { i ->
                        val filled = i < entered.length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))

                // Клавиатура
                val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { d -> KeypadButton(d) { onDigit(d) } }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Биометрия (если включена) или пусто
                    Box(modifier = Modifier.size(KeypadSize), contentAlignment = Alignment.Center) {
                        if (prefs.biometricEnabled) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = "Отпечаток",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp).clickable { tryBiometric() }
                            )
                        }
                    }
                    KeypadButton("0") { onDigit("0") }
                    Box(modifier = Modifier.size(KeypadSize), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Стереть",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp).clickable { if (entered.isNotEmpty()) entered = entered.dropLast(1) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(KeypadSize)
            .aetherControl(strokeAlpha = AetherStyle.SoftStrokeAlpha, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium, color = aetherControlContent())
    }
}
