package aether.desktop.ui

import aether.desktop.auth.ActiveAccount
import aether.desktop.auth.AuthRepository
import aether.desktop.data.DesktopPrefs
import aether.desktop.pairing.PairingClient
import aether.desktop.pairing.QrRenderer
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.ImageBitmap
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
    var status by remember(server) { mutableStateOf("Откройте Æther на телефоне → Настройки → Устройства → Подключить устройство") }
    val dark = isSystemInDarkTheme()

    LaunchedEffect(server, attempt) {
        error = null
        qr = null
        val client = PairingClient(server)
        try {
            val started = withContext(Dispatchers.IO) { client.start() }
            qr = withContext(Dispatchers.Default) { QrRenderer.render(started.qrPayload, 320, dark) }
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Box(
                modifier = Modifier.size(320.dp).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val current = qr
                if (current != null) {
                    Image(
                        bitmap = current,
                        contentDescription = "QR для входа",
                        modifier = Modifier.size(300.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "Никому не показывайте этот код: тот, кто его отсканирует, получит доступ к вашей переписке.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { attempt += 1 }) { Text("Обновить код") }
        }
    }
}
