package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.crypto.KeyTrustStore
import org.groktest.securemessenger.data.PinnedKeyEntity
import org.groktest.securemessenger.ui.components.AetherPrimaryButton
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherIsland

/**
 * P6: Экран «Цифры безопасности» — сверка ключей с собеседником (как в Signal).
 *
 * Показывает 60 цифр, одинаковых у обеих сторон при отсутствии MitM.
 * Здесь же: отметка «сверено» и принятие нового ключа после его смены.
 */
@Composable
fun SafetyNumberScreen(
    myId: String,
    myPublicKeyB64: String,
    peerId: String,
    trustStore: KeyTrustStore,
    onBack: () -> Unit
) {
    var pin by remember { mutableStateOf<PinnedKeyEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun reload() {
        coroutineScope.launch {
            pin = withContext(Dispatchers.IO) { trustStore.pinFor(peerId) }
            loading = false
        }
    }
    LaunchedEffect(peerId) { reload() }

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = AetherStyle.ScreenHorizontal)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical))
                when {
                    loading -> CircularProgressIndicator()

                    pin == null -> Text(
                        "Ключ собеседника ещё не сохранён. Он будет запомнен автоматически " +
                        "при первом сообщении (отправленном или полученном).",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> {
                        val p = pin!!

                        Icon(
                            if (p.verified) Icons.Default.Verified else Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = if (p.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (p.verified) "Сверено вручную" else "Не сверено",
                            color = if (p.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Предупреждение о смене ключа
                        if (p.previousKeyB64 != null) {
                            Spacer(Modifier.height(16.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = AetherStyle.IslandFillAlpha)
                                ),
                                shape = RoundedCornerShape(AetherStyle.IslandRadius)
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        "⚠️ Ключ этого собеседника менялся",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Это бывает при смене устройства или переустановке приложения, " +
                                        "но может означать и атаку. Сверьте цифры по другому каналу связи " +
                                        "(звонок, личная встреча), прежде чем принимать новый ключ.",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) { trustStore.acceptServerKey(peerId) }
                                                    status = "Новый ключ принят. Сверьте цифры заново."
                                                    reload()
                                                } catch (e: Exception) {
                                                    status = "Ошибка: ${e.message}"
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) { Text("Принять новый ключ с сервера") }
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // Сами цифры: 12 групп по 5, моноширинно, в 4 строки
                        val number = remember(p.publicKeyB64, myPublicKeyB64) {
                            KeyTrustStore.safetyNumber(myId, myPublicKeyB64, peerId, p.publicKeyB64)
                        }
                        Column(
                            Modifier
                                .aetherIsland()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            number.split(" ").chunked(3).forEach { row ->
                                Text(
                                    row.joinToString("  "),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Если у вас и у $peerId эти цифры совпадают — переписку никто не " +
                            "перехватывает. Сверяйте по голосу или при встрече, не через этот чат.",
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(24.dp))
                        AetherPrimaryButton(
                            text = if (p.verified) "Снять отметку «сверено»" else "Отметить как сверенные",
                            onClick = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) { trustStore.setVerified(peerId, !p.verified) }
                                    reload()
                                }
                            }
                        )
                    }
                }

                status?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                }
            }
            AetherSettingsTopBar("Цифры безопасности", onBack, Modifier.align(Alignment.TopCenter))
        }
    }
}
