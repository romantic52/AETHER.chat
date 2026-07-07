package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import org.groktest.securemessenger.ui.components.GlassBackground

/**
 * P6: Экран «Цифры безопасности» — сверка ключей с собеседником (как в Signal).
 *
 * Показывает 60 цифр, одинаковых у обеих сторон при отсутствии MitM.
 * Здесь же: отметка «сверено» и принятие нового ключа после его смены.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Цифры безопасности", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                            tint = if (p.verified) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (p.verified) "Сверено вручную" else "Не сверено",
                            color = if (p.verified) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Предупреждение о смене ключа
                        if (p.previousKeyB64 != null) {
                            Spacer(Modifier.height(16.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                shape = RoundedCornerShape(16.dp)
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
                        Card(shape = RoundedCornerShape(16.dp)) {
                            Column(
                                Modifier.padding(20.dp),
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
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) { trustStore.setVerified(peerId, !p.verified) }
                                    reload()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(if (p.verified) "Снять отметку «сверено»" else "Отметить как сверенные")
                        }
                    }
                }

                status?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
