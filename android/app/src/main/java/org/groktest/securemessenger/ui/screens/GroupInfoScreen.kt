package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.data.MessageRepository
import org.groktest.securemessenger.ui.components.AetherActionCircle
import org.groktest.securemessenger.ui.components.AetherSettingsTopBar
import org.groktest.securemessenger.ui.components.Avatar
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherField
import org.groktest.securemessenger.ui.theme.aetherIsland
import org.groktest.securemessenger.ui.theme.aetherTextFieldColors

/**
 * Экран информации и управления группой/каналом (телеграм-паттерн):
 * участники, изменить имя/описание (админ), добавить/удалить участника (админ),
 * выйти (не владелец), удалить (владелец).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    api: RelayApi,
    repo: MessageRepository,
    groupId: String,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onMemberClick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var group by remember { mutableStateOf<RelayApi.GroupInfo?>(null) }
    var members by remember { mutableStateOf<List<RelayApi.GroupMember>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<RelayApi.GroupMember?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Реальный онлайн участников: ISO last_active по userId (через профиль).
    var lastActiveById by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    LaunchedEffect(groupId, refreshKey) {
        try {
            withContext(Dispatchers.IO) {
                group = api.getMyGroups().firstOrNull { it.id.equals(groupId, ignoreCase = true) }
                members = api.getGroupMembers(groupId)
            }
        } catch (e: Exception) { error = e.message }
    }

    // Подтягиваем присутствие для каждого участника (небольшие группы — ок).
    LaunchedEffect(members) {
        if (members.isEmpty()) return@LaunchedEffect
        try {
            val map = withContext(Dispatchers.IO) {
                members.associate { m ->
                    m.userId to try { api.getUserProfile(m.userId).lastActive } catch (e: Exception) { null }
                }
            }
            lastActiveById = map
        } catch (e: Exception) { /* присутствие необязательно */ }
    }

    val isAdmin = group?.role == "admin"
    val isOwner = group != null && group!!.ownerId.equals(repo.myId, ignoreCase = true)
    val isChannel = group?.isChannel == true
    val onlineCount = members.count { isOnline(lastActiveById[it.userId]) }

    val listState = rememberLazyListState()
    // «Сворачивание»: при прокрутке за шапку имя проявляется в топбаре.
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 220 }
    }
    val name = group?.name ?: groupId

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical,
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
            ) {
                // --- Шапка: крупный аватар, имя, статус, описание ---
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Avatar(
                            name = name,
                            avatarFileId = null,
                            size = 96.dp,
                            type = if (isChannel) 2 else 1
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            name,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(2.dp))
                        Row {
                            Text(
                                if (isChannel) "${members.size} подписчиков" else "${members.size} участников",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (onlineCount > 0) {
                                Text(", ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$onlineCount онлайн", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                // --- Ряд круглых кнопок-действий (только реальные действия) ---
                if (isAdmin) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            AetherActionCircle(Icons.Default.PersonAdd, "Добавить", onClick = { showAdd = true })
                            Spacer(Modifier.width(24.dp))
                            AetherActionCircle(Icons.Default.Edit, "Изменить", onClick = { showEdit = true })
                        }
                    }
                }

                // --- Карточка «инфо»: описание + E2E ---
                item {
                    val desc = group?.description ?: ""
                    SectionCard {
                        if (desc.isNotBlank()) {
                            Text(
                                desc,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = AetherStyle.DividerAlpha), modifier = Modifier.padding(start = 16.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Сквозное шифрование", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                                Text("Сообщения видны только участникам", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // --- Карточка участников ---
                item {
                    Text(
                        if (isChannel) "ПОДПИСЧИКИ" else "УЧАСТНИКИ",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = AetherStyle.ScreenHorizontal + 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                }
                item {
                    SectionCard {
                        if (isAdmin) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showAdd = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(AetherStyle.SmallControlSize).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = AetherStyle.SelectedFillAlpha)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                Spacer(Modifier.width(12.dp))
                                Text("Добавить участника", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        members.forEach { m ->
                            val isThisOwner = m.userId.equals(group?.ownerId ?: "", ignoreCase = true)
                            val isSelf = m.userId.equals(repo.myId, ignoreCase = true)
                            val canRemove = isAdmin && !isSelf && !isThisOwner
                            // Роли назначает только владелец, и не самому себе.
                            val canToggleAdmin = isOwner && !isThisOwner
                            MemberRow(
                                member = m,
                                online = isOnline(lastActiveById[m.userId]),
                                isOwner = isThisOwner,
                                canRemove = canRemove,
                                canToggleAdmin = canToggleAdmin,
                                onClick = { onMemberClick(m.userId) },
                                onRemove = { confirmRemove = m },
                                onToggleAdmin = {
                                    val newRole = if (m.role == "admin") "member" else "admin"
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { api.setMemberRole(groupId, m.userId, newRole) }
                                            refreshKey++
                                        } catch (e: Exception) { error = e.message }
                                    }
                                }
                            )
                        }
                    }
                }

                // --- Карточка «опасные действия» ---
                item {
                    SectionCard {
                        if (isOwner) {
                            DangerRow(Icons.Default.Delete, "Удалить ${if (isChannel) "канал" else "группу"}") { confirmDelete = true }
                        } else {
                            DangerRow(Icons.AutoMirrored.Filled.ExitToApp, "Выйти из ${if (isChannel) "канала" else "группы"}") { confirmLeave = true }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
                }
            }
            AetherSettingsTopBar(
                title = if (collapsed) name else if (isChannel) "Канал" else "Группа",
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopCenter),
                actions = {
                    if (isAdmin) {
                        IconButton(onClick = { showEdit = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Изменить", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        }
    }

    // --- Диалог редактирования имени/описания ---
    if (showEdit) {
        var newName by remember { mutableStateOf(group?.name ?: "") }
        var newDesc by remember { mutableStateOf(group?.description ?: "") }
        AlertDialog(
            onDismissRequest = { showEdit = false },
            shape = RoundedCornerShape(AetherStyle.IslandRadius),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Изменить") },
            text = {
                Column {
                    TextField(value = newName, onValueChange = { newName = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.aetherField(shape = RoundedCornerShape(AetherStyle.FieldRadius)), shape = RoundedCornerShape(AetherStyle.FieldRadius), colors = aetherTextFieldColors(containerAlpha = 0f))
                    Spacer(Modifier.height(8.dp))
                    TextField(value = newDesc, onValueChange = { newDesc = it }, label = { Text("Описание") }, modifier = Modifier.aetherField(shape = RoundedCornerShape(AetherStyle.FieldRadius)), shape = RoundedCornerShape(AetherStyle.FieldRadius), colors = aetherTextFieldColors(containerAlpha = 0f))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.updateGroup(groupId, newName.trim(), newDesc.trim()) }
                            showEdit = false; refreshKey++
                        } catch (e: Exception) { error = e.message; showEdit = false }
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Отмена") } }
        )
    }

    // --- Диалог добавления участника (поиск) ---
    if (showAdd) {
        AddMemberDialog(
            api = api,
            repo = repo,
            groupId = groupId,
            existing = members.map { it.userId.lowercase() }.toSet(),
            onDone = { showAdd = false; refreshKey++ },
            onDismiss = { showAdd = false },
            onError = { error = it; showAdd = false }
        )
    }

    confirmRemove?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            shape = RoundedCornerShape(AetherStyle.IslandRadius),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Удалить участника?") },
            text = { Text(m.displayName.ifBlank { m.userId }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.removeGroupMember(groupId, m.userId) }
                            confirmRemove = null; refreshKey++
                        } catch (e: Exception) { error = e.message; confirmRemove = null }
                    }
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Отмена") } }
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            shape = RoundedCornerShape(AetherStyle.IslandRadius),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Выйти?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.leaveGroup(groupId) }
                            confirmLeave = false; onLeft()
                        } catch (e: Exception) { error = e.message; confirmLeave = false }
                    }
                }) { Text("Выйти", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Отмена") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RoundedCornerShape(AetherStyle.IslandRadius),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Удалить безвозвратно?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.deleteGroup(groupId) }
                            confirmDelete = false; onLeft()
                        } catch (e: Exception) { error = e.message; confirmDelete = false }
                    }
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemberDialog(
    api: RelayApi,
    repo: MessageRepository,
    groupId: String,
    existing: Set<String>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RelayApi.UserSearchResult>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        // Убираем ведущий «@» — поиск идёт по чистому имени/username
        val q = query.trim().removePrefix("@")
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        results = try {
            withContext(Dispatchers.IO) {
                api.searchUsers(q).filter { !it.isGroup && it.userId.lowercase() !in existing }
            }
        } catch (e: Exception) { emptyList() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AetherStyle.IslandRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Добавить участника") },
        text = {
            Column {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск по имени/@username") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aetherField(shape = RoundedCornerShape(AetherStyle.FieldRadius)),
                    shape = RoundedCornerShape(AetherStyle.FieldRadius),
                    colors = aetherTextFieldColors(containerAlpha = 0f)
                )
                Spacer(Modifier.height(8.dp))
                if (adding) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(results, key = { it.userId }) { user ->
                            val label = when {
                                user.username.isNotEmpty() -> "@${user.username}"
                                user.displayName.isNotEmpty() -> user.displayName
                                else -> user.userId
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        adding = true
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    val keyB64 = repo.groupKeyB64For(groupId)
                                                        ?: throw IllegalStateException("Нет ключа группы")
                                                    api.addGroupMember(groupId, user.userId, repo.wrapGroupKeyFor(user.userId, keyB64))
                                                }
                                                onDone()
                                            } catch (e: Exception) {
                                                onError(e.message ?: "Ошибка добавления")
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, color = MaterialTheme.colorScheme.onBackground)
                                // Явный признак «нажми, чтобы добавить»
                                Icon(
                                    Icons.Default.PersonAdd,
                                    contentDescription = "Добавить",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        if (results.isEmpty() && query.trim().length >= 2) {
                            item {
                                Text(
                                    "Никого не найдено",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

/** Карточка-секция (телеграм-паттерн): стеклянный остров aetherIsland. */
@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AetherStyle.ScreenHorizontal, vertical = 6.dp)
            .aetherIsland(
                shape = RoundedCornerShape(AetherStyle.IslandRadius),
                fillAlpha = AetherStyle.SoftIslandFillAlpha,
                strokeAlpha = AetherStyle.SoftStrokeAlpha
            ),
        content = content
    )
}

/** Строка участника: аватар с онлайн-точкой, имя + бейдж роли, @username, удаление. */
@Composable
private fun MemberRow(
    member: RelayApi.GroupMember,
    online: Boolean,
    isOwner: Boolean,
    canRemove: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    canToggleAdmin: Boolean = false,
    onToggleAdmin: () -> Unit = {}
) {
    val title = member.displayName.ifBlank { member.userId }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Avatar(name = title, avatarFileId = member.avatarFileId, size = AetherStyle.SmallControlSize, type = 0)
            if (online) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
                val badge = when { isOwner -> "владелец"; member.role == "admin" -> "админ"; else -> null }
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(badge, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            val sub = if (online) "в сети" else if (member.username.isNotBlank()) "@${member.username}" else ""
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    fontSize = 13.sp,
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (canToggleAdmin) {
            val admin = member.role == "admin"
            IconButton(onClick = onToggleAdmin) {
                Icon(
                    if (admin) Icons.Filled.Shield else Icons.Outlined.Shield,
                    contentDescription = if (admin) "Снять админа" else "Сделать админом",
                    tint = if (admin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Красная строка опасного действия (выйти/удалить). */
@Composable
private fun DangerRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
    }
}

/** Онлайн, если последняя активность < 75с назад (как порог в formatLastSeen). */
internal fun isOnline(iso: String?): Boolean {
    val ts = org.groktest.securemessenger.api.RelayApi.parseUtcIso(iso)
    return ts > 0L && System.currentTimeMillis() - ts < 75_000L
}
