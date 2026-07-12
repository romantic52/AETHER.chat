package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
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
    onResultSelected: (RelayApi.UserSearchResult) -> Unit,
    onCreateChannel: (String) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RelayApi.UserSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var memberGroupIds by remember { mutableStateOf<Set<String>?>(null) }
    var joiningId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(api) {
        memberGroupIds = withContext(Dispatchers.IO) {
            runCatching { api.getMyGroups().map { it.id.lowercase() }.toSet() }.getOrDefault(emptySet())
        }
    }

    fun performSearch(q: String) {
        val normalized = q.trim().removePrefix("@")
        searchJob?.cancel()
        if (normalized.length < 2) {
            results = emptyList()
            isLoading = false
            return
        }
        searchJob = coroutineScope.launch {
            delay(250)
            isLoading = true
            try {
                val found = withContext(Dispatchers.IO) { api.searchDirectory(normalized) }
                if (query.trim().removePrefix("@") == normalized) results = found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (query.trim().removePrefix("@") == normalized) {
                    snackbarHostState.showSnackbar("Не удалось выполнить поиск")
                }
            } finally {
                if (query.trim().removePrefix("@") == normalized) isLoading = false
            }
        }
    }

    fun selectResult(result: RelayApi.UserSearchResult) {
        coroutineScope.launch {
            if (result.isGroup) {
                val id = result.userId.lowercase()
                if (memberGroupIds == null) {
                    memberGroupIds = withContext(Dispatchers.IO) {
                        runCatching { api.getMyGroups().map { it.id.lowercase() }.toSet() }.getOrDefault(emptySet())
                    }
                }
                if (id !in memberGroupIds.orEmpty()) {
                    if (!result.publicJoin) {
                        snackbarHostState.showSnackbar("Это закрытое сообщество")
                        return@launch
                    }
                    joiningId = id
                    val error = withContext(Dispatchers.IO) {
                        runCatching { api.joinGroup(result.userId) }.exceptionOrNull()
                    }
                    joiningId = null
                    if (error != null) {
                        snackbarHostState.showSnackbar(error.message ?: "Не удалось вступить")
                        return@launch
                    }
                    memberGroupIds = memberGroupIds.orEmpty() + id
                }
            }
            onResultSelected(result)
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
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        items(results, key = { "${it.isGroup}:${it.userId}" }) { result ->
                            val isMember = memberGroupIds?.contains(result.userId.lowercase()) == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = joiningId == null) { selectResult(result) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                    val avatarUrl = if (result.avatarFileId != null && org.groktest.securemessenger.api.ServerConfig.baseUrl.isNotBlank())
                                        org.groktest.securemessenger.api.ServerConfig.avatarUrl(result.avatarFileId)
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
                                                text = result.displayName.ifBlank { result.username.ifBlank { result.userId } }.take(1).uppercase(),
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = result.displayName.ifBlank { result.username.ifBlank { result.userId } },
                                            modifier = Modifier.weight(1f, fill = false),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!result.isGroup && !result.statusEmoji.isNullOrBlank()) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(result.statusEmoji!!, fontSize = 15.sp)
                                        }
                                    }
                                    val subtitle = if (result.isGroup) {
                                        listOfNotNull(
                                            result.username.takeIf { it.isNotBlank() }?.let { "@$it" },
                                            if (result.isChannel) "Канал" else "Группа"
                                        ).joinToString(" · ")
                                    } else {
                                        result.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: result.userId
                                    }
                                    Text(
                                        text = subtitle,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (joiningId == result.userId.lowercase()) {
                                    Spacer(Modifier.width(10.dp))
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else if (result.isGroup && memberGroupIds != null && !isMember) {
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = if (result.publicJoin) {
                                            if (result.isChannel) "Подписаться" else "Вступить"
                                        } else {
                                            "Закрытая"
                                        },
                                        fontSize = 13.sp,
                                        color = if (result.publicJoin) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
