package org.groktest.securemessenger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.crypto.E2ECrypto
import org.groktest.securemessenger.data.LockPrefs
import org.groktest.securemessenger.pairing.PairingLink
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.AetherPrimaryButton
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.json.JSONObject

/**
 * «Подключить устройство»: сканируем QR с десктопа, показываем явный экран
 * подтверждения (платформа + время, PIN/биометрия при включённом AppLock) и
 * отправляем аккаунтный приватник, завёрнутый crypto_box на эфемерный ключ
 * нового устройства. Пароль пользователя не участвует.
 */
@Composable
fun PairDeviceScreen(
    api: RelayApi,
    myId: String,
    keys: E2ECrypto.KeyPair,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val crypto = remember { E2ECrypto() }

    var link by remember { mutableStateOf<PairingLink?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var doneDeviceId by remember { mutableStateOf<String?>(null) }
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    fun approve(target: PairingLink) {
        if (submitting) return
        submitting = true
        scope.launch {
            try {
                val deviceId = withContext(Dispatchers.IO) {
                    // Bundle: аккаунтный X25519 (нужен и для групп), завёрнутый
                    // crypto_box на эфемерный паблик из QR. Сервер видит шифртекст.
                    val payload = JSONObject()
                        .put("v", 1)
                        .put("user_id", myId)
                        .put("public_b64", keys.publicB64)
                        .put("private_b64", keys.privateB64)
                        .toString()
                    val envelope = crypto.encrypt(payload, keys, target.ephPubB64)
                    val bundleB64 = android.util.Base64.encodeToString(
                        envelope.toJson().toByteArray(Charsets.UTF_8),
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or
                            android.util.Base64.NO_WRAP,
                    )
                    api.approvePairing(target.pairingId, target.secret, bundleB64, "desktop")
                }
                doneDeviceId = deviceId
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Не удалось подтвердить вход")
                link = null
            } finally {
                submitting = false
            }
        }
    }

    /** Подтверждение с доверенного устройства: при включённом AppLock — PIN/биометрия. */
    fun confirmAndApprove(target: PairingLink) {
        val prefs = LockPrefs(context)
        val activity = context as? androidx.fragment.app.FragmentActivity
        val canBiometric = prefs.enabled && activity != null &&
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (!canBiometric) {
            approve(target)
            return
        }
        val prompt = BiometricPrompt(
            activity!!,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    approve(target)
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Подтвердите вход")
                .setSubtitle("Новое устройство получит доступ к вашей переписке")
                .setNegativeButtonText("Отмена")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
        )
    }

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = AetherStyle.ScreenHorizontal),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical))

                val finished = doneDeviceId
                when {
                    finished != null -> {
                        Spacer(Modifier.height(48.dp))
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Устройство подключено", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            finished,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "История переписки не переносится: на новом устройстве появятся " +
                                "сообщения, отправленные с этого момента.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        AetherPrimaryButton(text = "Готово", onClick = onBack, fillWidth = false)
                    }
                    !hasCamera -> {
                        Spacer(Modifier.height(48.dp))
                        Text(
                            "Нужен доступ к камере, чтобы отсканировать код.",
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        AetherPrimaryButton(
                            text = "Разрешить",
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            fillWidth = false,
                        )
                    }
                    else -> {
                        Text(
                            "Наведите камеру на QR-код на экране компьютера",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        QrScannerView(
                            // После успешной привязки «догоняющий» кадр не должен
                            // снова открывать диалог поверх экрана «Подключено».
                            enabled = link == null && !submitting && doneDeviceId == null,
                            onScanned = { raw ->
                                if (link == null && doneDeviceId == null) {
                                    PairingLink.parse(raw)?.let { link = it }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.75f)
                                .clip(RoundedCornerShape(AetherStyle.IslandRadius)),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Подтверждайте вход, только если код показывает ваш компьютер.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            AetherSettingsTopBar("Подключить устройство", onBack, Modifier.align(Alignment.TopCenter))
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            )
        }
    }

    link?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!submitting) link = null },
            shape = RoundedCornerShape(AetherStyle.IslandRadius),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Разрешить вход?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Компьютер (Windows) получит доступ к вашему аккаунту @$myId.")
                    Text(
                        "Сервер: ${target.host}\nВремя: ${
                            java.text.SimpleDateFormat("HH:mm, d MMMM", java.util.Locale("ru"))
                                .format(java.util.Date())
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Если вы не запускали вход на компьютере — отклоните.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmAndApprove(target) }, enabled = !submitting) {
                    Text(if (submitting) "Подтверждаем…" else "Разрешить", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { link = null }, enabled = !submitting) { Text("Отклонить") }
            },
        )
    }
}

/** Превью камеры с ML Kit-анализом кадров: первый распознанный QR отдаётся наверх. */
@Composable
@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
private fun QrScannerView(
    enabled: Boolean,
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val enabledState = rememberUpdatedState(enabled)
    val callback = rememberUpdatedState(onScanned)

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // Плотный QR (ссылка привязки — версия ~10): на 640x480
                    // модули сливаются, распознавание не срабатывает.
                    .setTargetResolution(android.util.Size(1280, 720))
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || !enabledState.value) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull { it.valueType == Barcode.TYPE_TEXT || it.rawValue != null }
                                ?.rawValue
                                ?.let { value ->
                                    if (enabledState.value) callback.value(value)
                                }
                        }
                        .addOnFailureListener { error ->
                            android.util.Log.w("PairScanner", "barcode process failed", error)
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}
