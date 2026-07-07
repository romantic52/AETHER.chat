package org.groktest.securemessenger.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.ui.components.GlassBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    api: RelayApi,
    myId: String,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onSettings: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var successMsg by remember { mutableStateOf<String?>(null) }
    
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatarFileId by remember { mutableStateOf<String?>(null) }
    
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    // Server base for image URL
    val serverBase = api.javaClass.getDeclaredField("base").apply { isAccessible = true }.get(api) as String
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isSaving = true
                error = null
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes != null) {
                        val newFileId = withContext(Dispatchers.IO) {
                            api.uploadAvatar(bytes)
                        }
                        avatarFileId = newFileId
                    }
                } catch (e: Exception) {
                    error = "Failed to upload image: ${e.message}"
                } finally {
                    isSaving = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val profile = withContext(Dispatchers.IO) { api.getUserProfile(myId) }
            username = profile.username
            displayName = profile.displayName
            bio = profile.bio ?: ""
            avatarFileId = profile.avatarFileId
        } catch (e: Exception) {
            error = "Failed to load profile: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
                TopAppBar(
                    title = { Text("Мой профиль", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            coroutineScope.launch {
                                isSaving = true
                                error = null
                                successMsg = null
                                try {
                                    withContext(Dispatchers.IO) {
                                        api.updateProfile(username, displayName, avatarFileId, bio)
                                    }
                                    successMsg = "Сохранено"
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    isSaving = false
                                }
                            }
                        }) {
                            Text("Сохранить", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { if (!isSaving) launcher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarFileId != null) {
                            AsyncImage(
                                model = "$serverBase/avatars/$avatarFileId",
                                contentDescription = "Avatar",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = myId.take(1).uppercase(),
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = (-4).dp, y = (-4).dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Change Avatar", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    if (error != null) {
                        Text(text = error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 16.dp))
                    }
                    if (successMsg != null) {
                        Text(text = successMsg!!, color = Color(0xFF10B981), modifier = Modifier.padding(bottom = 16.dp))
                    }
                    
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Имя") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Имя пользователя (юзернейм)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = bio,
                        onValueChange = { bio = it },
                        label = { Text("О себе") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 3,
                        maxLines = 5
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    OutlinedButton(
                        onClick = onSettings,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Настройки оформления", fontWeight = FontWeight.SemiBold)
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Выйти из аккаунта", fontWeight = FontWeight.SemiBold)
                    }
                }
        }
    }
}
