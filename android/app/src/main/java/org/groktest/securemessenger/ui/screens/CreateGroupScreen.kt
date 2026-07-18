package org.groktest.securemessenger.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.data.MessageRepository
import org.groktest.securemessenger.ui.components.Avatar
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

/**
 * (#A3) Создание группы или канала с честным E2E. Телеграм-флоу в два шага:
 *   1) тип + название (+описание, превью-аватар буквой);
 *   2) выбор участников (поиск + чипы) и создание.
 * Генерируется общий симметричный ключ, для каждого участника он заворачивается
 * crypto_box на его публичный ключ. Сервер видит только зашифрованные копии ключа.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateGroupScreen(
    api: RelayApi,
    repo: MessageRepository,
    initialName: String = "",
    initialIsChannel: Boolean = false,
    onBack: () -> Unit,
    onGroupCreated: (String) -> Unit
) {
    val appearance = LocalThemeSettings.current
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf("") }
    var isChannel by remember { mutableStateOf(initialIsChannel) }
    // (#A6) Телеграм-паттерн «комментарии»: к каналу подвязывается группа обсуждений
    var withDiscussion by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Поиск и выбор участников
    var memberQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<RelayApi.UserSearchResult>>(emptyList()) }
    val selectedMembers = remember { mutableStateMapOf<String, String>() } // userId -> отображаемое имя

    val coroutineScope = rememberCoroutineScope()

    // Живой поиск с дебаунсом
    LaunchedEffect(memberQuery) {
        val q = memberQuery.trim().removePrefix("@")
        if (q.length < 2) { searchResults = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        searchResults = try {
            withContext(Dispatchers.IO) {
                api.searchUsers(q).filter { !it.isGroup && !it.userId.equals(repo.myId, ignoreCase = true) }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun doCreate() {
        if (name.isBlank()) return
        isLoading = true
        errorText = null
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val failed = mutableListOf<String>()

                /** Создаёт группу/канал с E2E-ключом и участниками. */
                suspend fun createOne(prefix: String, gName: String, asChannel: Boolean, linkedId: String?): String {
                    val gid = prefix + java.util.UUID.randomUUID().toString().take(8)
                    val keyB64 = repo.newGroupKeyB64()
                    api.createGroup(
                        groupId = gid,
                        name = gName,
                        description = description,
                        isChannel = asChannel,
                        encryptedKeyB64 = repo.wrapGroupKeyFor(repo.myId, keyB64),
                        linkedGroupId = linkedId
                    )
                    for ((memberId, label) in selectedMembers) {
                        try {
                            api.addGroupMember(gid, memberId, repo.wrapGroupKeyFor(memberId, keyB64))
                        } catch (e: Exception) {
                            failed.add(label)
                        }
                    }
                    repo.registerCreatedGroup(gid, gName, keyB64, asChannel)
                    repo.cacheGroupKey(gid, keyB64, linkedId)
                    return gid
                }

                // (#A6) Канал с обсуждениями: сначала группа, потом канал со ссылкой
                val discussionId = if (isChannel && withDiscussion) {
                    createOne("group_", "$name · обсуждение", asChannel = false, linkedId = null)
                } else null

                val mainId = createOne(
                    if (isChannel) "channel_" else "group_",
                    name,
                    asChannel = isChannel,
                    linkedId = discussionId
                )

                withContext(Dispatchers.Main) {
                    if (failed.isNotEmpty()) {
                        errorText = "Не добавлены: ${failed.distinct().joinToString(", ")}"
                        isLoading = false
                    } else {
                        onGroupCreated(mainId)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorText = e.message ?: "Ошибка создания"
                    isLoading = false
                }
            }
        }
    }

    GlassBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                step == 1 -> "Добавить участников"
                                isChannel -> "Новый канал"
                                else -> "Новая группа"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (step > 0) step = 0 else onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val move = tween<IntOffset>(appearance.motionDuration(260), easing = FastOutSlowInEasing)
                    val fade = tween<Float>(appearance.motionDuration(170))
                    if (targetState > initialState)
                        (slideInHorizontally(animationSpec = move) { it } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(animationSpec = move) { -it } + fadeOut(fade))
                    else
                        (slideInHorizontally(animationSpec = move) { -it } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(animationSpec = move) { it } + fadeOut(fade))
                },
                label = "step",
                modifier = Modifier.fillMaxSize().padding(padding)
            ) { s ->
                if (s == 0) {
                    // ===== ШАГ 1: тип + название =====
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        // Превью-аватар буквой (как в Telegram до загрузки фото)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Avatar(
                                name = name.ifBlank { "?" },
                                avatarFileId = null,
                                size = 64.dp,
                                type = if (isChannel) 2 else 1
                            )
                            Spacer(Modifier.width(12.dp))
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text(if (isChannel) "Название канала" else "Название группы") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                )
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Описание (необязательно)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )

                        Spacer(Modifier.height(16.dp))

                        // Сегмент Группа / Канал
                        TypeSelector(isChannel = isChannel, onChange = { isChannel = it })

                        if (isChannel) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Обсуждения", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                                        Text("Группа для комментариев под постами", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = withDiscussion, onCheckedChange = { withDiscussion = it })
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Сквозное шифрование: общий ключ передаётся каждому участнику в зашифрованном виде",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick = { step = 1 },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            enabled = name.isNotBlank()
                        ) { Text("Далее") }
                    }
                } else {
                    // ===== ШАГ 2: выбор участников =====
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        // Чипы выбранных
                        if (selectedMembers.isNotEmpty()) {
                            Text("Выбрано: ${selectedMembers.size}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                selectedMembers.entries.toList().forEach { (id, label) ->
                                    InputChip(
                                        selected = true,
                                        onClick = { selectedMembers.remove(id) },
                                        label = { Text(label, maxLines = 1) },
                                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Убрать", modifier = Modifier.size(16.dp)) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        OutlinedTextField(
                            value = memberQuery,
                            onValueChange = { memberQuery = it },
                            label = { Text("Поиск по имени / @username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )

                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(searchResults, key = { it.userId }) { user ->
                                val label = when {
                                    user.username.isNotEmpty() -> "@${user.username}"
                                    user.displayName.isNotEmpty() -> user.displayName
                                    else -> user.userId
                                }
                                val selected = selectedMembers.containsKey(user.userId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (selected) selectedMembers.remove(user.userId)
                                            else selectedMembers[user.userId] = label
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(name = user.displayName.ifBlank { user.userId }, avatarFileId = null, size = 40.dp, type = 0)
                                    Spacer(Modifier.width(12.dp))
                                    Text(label, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                                    if (selected) {
                                        Box(
                                            modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Filled.Check, contentDescription = "Выбран", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                                    }
                                }
                            }
                            if (searchResults.isEmpty() && memberQuery.trim().length >= 2) {
                                item {
                                    Text("Никого не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                                }
                            }
                        }

                        errorText?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                        }

                        Button(
                            onClick = { doCreate() },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            enabled = !isLoading && name.isNotBlank()
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                            } else {
                                Text(if (isChannel) "Создать канал" else "Создать группу")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Сегмент-переключатель Группа / Канал (две карточки). */
@Composable
private fun TypeSelector(isChannel: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        TypeCard(
            title = "Группа",
            subtitle = "Пишут все участники",
            selected = !isChannel,
            modifier = Modifier.weight(1f)
        ) { onChange(false) }
        TypeCard(
            title = "Канал",
            subtitle = "Посты только у админов",
            selected = isChannel,
            modifier = Modifier.weight(1f)
        ) { onChange(true) }
    }
}

@Composable
private fun TypeCard(title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, border),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
