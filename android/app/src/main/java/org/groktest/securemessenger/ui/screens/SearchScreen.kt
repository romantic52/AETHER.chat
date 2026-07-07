package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.ui.components.GlassBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    api: RelayApi,
    onBack: () -> Unit,
    onUserSelected: (String) -> Unit,
    onCreateChannel: (String) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RelayApi.UserSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun performSearch(q: String) {
        if (q.length < 2) {
            results = emptyList()
            return
        }
        searchJob?.cancel()
        searchJob = coroutineScope.launch {
            delay(300) // debounce
            isLoading = true
            try {
                val users = withContext(Dispatchers.IO) { api.searchUsers(q) }
                results = users
            } catch (e: Exception) {
                // handle error
            } finally {
                isLoading = false
            }
        }
    }

    GlassBackground {
        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier.background(Color.Transparent)
                ) {
                    TopAppBar(
                        title = {
                            TextField(
                                value = query,
                                onValueChange = { 
                                    query = it
                                    performSearch(it)
                                },
                                placeholder = { Text("Поиск...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onBackground)
                }
                
                if (query.length >= 2 && results.isEmpty() && !isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { onCreateChannel(query) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Создать канал '$query'")
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(results) { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onUserSelected(user.userId) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                    val avatarUrl = if (user.avatarFileId != null && org.groktest.securemessenger.api.ServerConfig.baseUrl.isNotBlank())
                                        org.groktest.securemessenger.api.ServerConfig.avatarUrl(user.avatarFileId)
                                    else null
                                    if (avatarUrl != null) {
                                        coil.compose.AsyncImage(
                                            model = avatarUrl,
                                            contentDescription = null,
                                            modifier = Modifier.size(50.dp).clip(CircleShape),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(50.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = (if (user.username.isNotEmpty()) user.username else if (user.displayName.isNotEmpty()) user.displayName else user.userId).take(1).uppercase(),
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Column {
                                    if (user.isGroup) {
                                        Text(text = user.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                                        Text(text = "Группа/Канал", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        if (user.username.isNotEmpty()) {
                                            Text(text = "@${user.username}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                                            if (user.displayName.isNotEmpty()) {
                                                Text(text = user.displayName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        } else {
                                            Text(text = if (user.displayName.isNotEmpty()) user.displayName else user.userId, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
