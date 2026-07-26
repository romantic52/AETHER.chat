package aether.desktop.ui

import aether.desktop.auth.ActiveAccount
import aether.desktop.auth.AuthRepository
import aether.desktop.data.DesktopPrefs
import aether.desktop.pairing.PairingClient
import aether.desktop.pairing.QrRenderer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * «Вход по QR» — дефолтная вкладка: телефон сканирует код и подтверждает вход.
 * Пароль здесь не нужен и никуда не передаётся.
 */
@Composable
fun QrLoginPane(
    auth: AuthRepository,
    server: String,
    onSuccess: (ActiveAccount) -> Unit,
) {
    var qr by remember(server) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(server) { mutableStateOf<String?>(null) }
    var attempt by remember(server) { mutableStateOf(0) }
    var status by remember(server) { mutableStateOf<String?>(null) }

    LaunchedEffect(server, attempt) {
        error = null
        qr = null
        val client = PairingClient(server)
        try {
            val started = withContext(Dispatchers.IO) { client.start() }
            // Рендерим с запасом по пикселям (плашка всегда белая, поэтому
            // тёмный вариант не нужен) — при уменьшении до 220dp код остаётся резким.
            qr = withContext(Dispatchers.Default) { QrRenderer.render(started.qrPayload, 440, dark = false) }
            val deadline = System.currentTimeMillis() + started.expiresIn * 1000L
            while (System.currentTimeMillis() < deadline) {
                val approved = withContext(Dispatchers.IO) { client.poll(started) }
                if (approved != null) {
                    status = "Подтверждено, входим…"
                    val bundle = withContext(Dispatchers.Default) { client.openBundle(approved) }
                    client.forget()
                    val account = auth.adoptPairedSession(
                        server = server,
                        userId = approved.userId,
                        token = approved.sessionToken,
                        deviceId = approved.deviceId,
                        keys = DesktopPrefs.AccountKeys(bundle.publicB64, bundle.privateB64),
                    )
                    onSuccess(account)
                    return@LaunchedEffect
                }
                delay(500)
            }
            // Истёк — обновляем код сами, как в Telegram.
            attempt += 1
        } catch (e: PairingClient.PairingExpired) {
            attempt += 1
        } catch (e: Exception) {
            error = e.message ?: "Не удалось получить код"
        } finally {
            client.forget()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Белая плашка в обеих темах: тёмный QR на ней читается камерой лучше всего.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
        ) {
            Box(
                modifier = Modifier.size(244.dp).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val current = qr
                if (current != null) {
                    Image(
                        bitmap = current,
                        contentDescription = "QR для входа",
                        modifier = Modifier.size(220.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            QrStep(1, "Откройте Æther на телефоне")
            QrStep(2, "Перейдите в Настройки → Устройства → Подключить устройство")
            QrStep(3, "Наведите камеру на этот QR-код")
        }
        Text(
            "Никому не показывайте этот код: тот, кто его отсканирует, получит доступ к вашей переписке.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { attempt += 1 }) { Text("Обновить код") }
        }
    }
}

/** Пронумерованный шаг инструкции: кружок с цифрой + текст, как в Telegram. */
@Composable
private fun QrStep(number: Int, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
