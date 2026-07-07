package org.groktest.securemessenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.ui.components.GlassBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    myId: String,
    api: RelayApi,
    onBack: () -> Unit
) {
    var username by remember { mutableStateOf(myId) }
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatarFileId by remember { mutableStateOf<String?>(null) }
    var isUploadingAvatar by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Загрузка текущего профиля
    LaunchedEffect(myId) {
        try {
            val p = withContext(Dispatchers.IO) { api.getUserProfile(myId) }
            if (p.username.isNotBlank()) username = p.username
            if (p.displayName.isNotBlank()) displayName = p.displayName
            bio = p.bio ?: ""
            avatarFileId = p.avatarFileId
        } catch (e: Exception) {}
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isUploadingAvatar = true
            coroutineScope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        // Сжимаем до 512px JPEG
                        val input = context.contentResolver.openInputStream(uri)
                        val bmp = android.graphics.BitmapFactory.decodeStream(input)
                        input?.close()
                        val maxDim = 512
                        val scale = minOf(
                            maxDim.toFloat() / bmp.width,
                            maxDim.toFloat() / bmp.height,
                            1f
                        )
                        val scaled = android.graphics.Bitmap.createScaledBitmap(
                            bmp,
                            (bmp.width * scale).toInt().coerceAtLeast(1),
                            (bmp.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                        val out = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                        out.toByteArray()
                    }
                    val fileId = withContext(Dispatchers.IO) { api.uploadAvatar(bytes) }
                    withContext(Dispatchers.IO) { api.updateProfile(null, null, fileId, null) }
                    avatarFileId = fileId
                    snackbarHostState.showSnackbar("Фото профиля обновлено")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Ошибка загрузки фото: ${e.message}")
                } finally {
                    isUploadingAvatar = false
                }
            }
        }
    }

    GlassBackground {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("Настройки профиля", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Аватар
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = !isUploadingAvatar) {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isUploadingAvatar) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                    } else if (!avatarFileId.isNullOrBlank() && ServerConfig.baseUrl.isNotBlank()) {
                        coil.compose.AsyncImage(
                            model = ServerConfig.avatarUrl(avatarFileId!!),
                            contentDescription = "Аватар",
                            modifier = Modifier.size(100.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = myId.take(1).uppercase(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    enabled = !isUploadingAvatar,
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                ) {
                    Text("Выбрать фото профиля")
                }

                Spacer(modifier = Modifier.height(24.dp))

                TextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Имя") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Юзернейм (@)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("О себе") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                    )
                )

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    enabled = !isSaving,
                    onClick = {
                        isSaving = true
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    api.updateProfile(
                                        username.trim().removePrefix("@").ifBlank { null },
                                        displayName.trim().ifBlank { null },
                                        null,
                                        bio.trim()
                                    )
                                }
                                snackbarHostState.showSnackbar("Изменения сохранены")
                                onBack()
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Ошибка: ${e.message}")
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Text("Сохранить", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
