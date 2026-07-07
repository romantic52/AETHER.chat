package org.groktest.securemessenger

import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.crypto.E2ECrypto
import org.groktest.securemessenger.ui.screens.ChatListScreen
import org.groktest.securemessenger.ui.screens.ChatScreen
import org.groktest.securemessenger.ui.screens.CallOverlay
import org.groktest.securemessenger.ui.screens.LoginScreen
import org.groktest.securemessenger.ui.theme.SecureMessengerTheme
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import org.groktest.securemessenger.ui.theme.ThemeSettings
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

class MainActivity : FragmentActivity() {

    // (#A2) Вся логика сообщений — в MessageRepository (создаётся после логина,
    // гасится при logout). Activity осталась тонкой обвязкой: навигация + делегация.
    private var repo: org.groktest.securemessenger.data.MessageRepository? = null
    private var api: RelayApi? = null
    private var keys: E2ECrypto.KeyPair? = null
    private var myId: String = ""

    override fun onResume() {
        super.onResume()
        AetherService.appInForeground = true
        org.groktest.securemessenger.data.AppLock.onResumed(this)
    }

    override fun onPause() {
        super.onPause()
        AetherService.appInForeground = false
        org.groktest.securemessenger.data.AppLock.onPaused()
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Замок приложения: если включён PIN — блокируем сразу при старте
        org.groktest.securemessenger.data.AppLock.onStart(this)
        // Чёрный список — загрузить в память для фильтрации входящих
        org.groktest.securemessenger.data.BlockStore.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        
        // Локальное хранилище — в ядре (SQLite в Rust, зашифровано ключом из
        // Keystore). Единый объект играет роль messageDao+chatDao+pinnedKeyDao.
        val store = org.groktest.securemessenger.data.CoreStore.create(this)
        // P6: единая точка доверия к публичным ключам (TOFU-пиннинг).
        // Все получения ключей собеседников идут через trustStore, не через api напрямую.
        val trustStore = org.groktest.securemessenger.crypto.KeyTrustStore(store)

        setContent {
            val themeSettings = remember { ThemeSettings(this@MainActivity) }
            val folderSettings = remember { org.groktest.securemessenger.ui.theme.ChatFolderSettings(this@MainActivity) }
            CompositionLocalProvider(
                LocalThemeSettings provides themeSettings,
                org.groktest.securemessenger.ui.theme.LocalChatFolderSettings provides folderSettings,
                // Глобально отключаем stretch/bounce-эффект списков
                androidx.compose.foundation.LocalOverscrollConfiguration provides null
            ) {
                SecureMessengerTheme(themeSettings = themeSettings) {
                val navController = rememberNavController()
                val coroutineScope = rememberCoroutineScope()
                val sessionPrefs = remember { org.groktest.securemessenger.data.SessionPrefs(this@MainActivity) }

                // Активный звонок: (peerId, isVideoCall, isIncoming).
                // Оверлей поверх NavHost — звонок можно свернуть и пользоваться приложением.
                var activeCall by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }

                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = 600.dp)
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = "login",
                            enterTransition = {
                                androidx.compose.animation.slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                )
                            },
                            exitTransition = {
                                androidx.compose.animation.slideOutHorizontally(
                                    targetOffsetX = { -it / 3 },
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                ) + androidx.compose.animation.fadeOut(
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                )
                            },
                            popEnterTransition = {
                                androidx.compose.animation.slideInHorizontally(
                                    initialOffsetX = { -it / 3 },
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                ) + androidx.compose.animation.fadeIn(
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                )
                            },
                            popExitTransition = {
                                androidx.compose.animation.slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                )
                            }
                        ) {
                            composable("login") {
                        // Миграция: удаляем старый небезопасный "server|username|password"
                        val legacyPrefs = getSharedPreferences("AetherPrefs", MODE_PRIVATE)
                        if (legacyPrefs.contains("saved_login")) {
                            legacyPrefs.edit().remove("saved_login").apply()
                        }
                        val savedSession = remember { sessionPrefs.load() }

                        LoginScreen(
                            savedSession = savedSession,
                            onLoginSuccess = { prefs, kp, apiInstance, id, rememberMe ->
                                keys = kp
                                api = apiInstance
                                myId = id
                                trustStore.keyFetcher = { apiInstance.getPublicKey(it) }

                                // Пароль не сохраняем: только server|username + токен сессии
                                val token = apiInstance.token
                                if (rememberMe && token != null) {
                                    sessionPrefs.save(prefs.substringBefore("|"), id, token)
                                } else {
                                    sessionPrefs.clear()
                                }

                                org.groktest.securemessenger.api.ServerConfig.baseUrl = prefs.substringBefore("|")

                                // Ensure Saved Messages chat exists
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (store.getChat(id) == null) {
                                        store.insertChat(
                                            org.groktest.securemessenger.data.ChatEntity(
                                                peerId = id,
                                                name = "Избранное",
                                                type = 3,
                                                isPinned = true
                                            )
                                        )
                                    }
                                }

                                // Разовое обновление имён и аватарок существующих личных чатов
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        for (chat in store.getAllChatsOnce()) {
                                            if (chat.type == 0 && !chat.peerId.equals(id, ignoreCase = true)) {
                                                try {
                                                    val p = apiInstance.getUserProfile(chat.peerId)
                                                    store.updateChat(
                                                        chat.copy(
                                                            name = p.displayName.ifBlank { chat.name },
                                                            avatarFileId = p.avatarFileId
                                                        )
                                                    )
                                                } catch (e: Exception) {}
                                            }
                                        }
                                    } catch (e: Exception) {}
                                }

                                // (#A2) Репозиторий: инбокс (ACK-протокол #A1), outbox
                                // (optimistic send), крипта и контролы — вся логика там.
                                // Повторный вход: старый репозиторий гасим, дублей циклов нет.
                                repo?.shutdown()
                                val r = org.groktest.securemessenger.data.MessageRepository(
                                    api = apiInstance,
                                    keys = kp,
                                    myId = id,
                                    store = store,
                                    trustStore = trustStore,
                                    resolver = contentResolver
                                )
                                repo = r
                                // Пуш по WebSocket → мгновенная синхронизация
                                AetherService.onNewMessage = { r.onPushReceived() }
                                // Имя/мьют чата для уведомлений (из ядрового хранилища).
                                // Колбэк дергается с фонового WS-потока ядра — runBlocking безопасен.
                                AetherService.chatLookup = { pid ->
                                    try { kotlinx.coroutines.runBlocking { store.getChat(pid) } } catch (e: Exception) { null }
                                }
                                r.start()


                                val serverUrl = prefs.substringBefore("|")
                                val serviceIntent = Intent(this@MainActivity, AetherService::class.java).apply {
                                    putExtra("server_url", serverUrl)
                                    putExtra("token", apiInstance.token)
                                    putExtra("my_id", id)
                                }
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                
                                navController.navigate("main") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("main") {
                        // Восстановление после убийства процесса: api/keys уже null,
                        // нав-стек восстановлен на "main" → уходим на login вместо краша.
                        val apiSafe = api
                        if (apiSafe == null) {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                            return@composable
                        }
                        org.groktest.securemessenger.ui.screens.MainScreen(
                            api = apiSafe,
                            myId = myId,
                            chatListFlow = store.getChatList(),
                            onChatSelected = { peerId ->
                                navController.navigate("chat/$peerId")
                            },
                            onLogout = {
                                sessionPrefs.clear()
                                // (#A4) Отзываем токен на сервере (best effort)
                                val apiForLogout = api
                                coroutineScope.launch(Dispatchers.IO) {
                                    try { apiForLogout?.logout() } catch (e: Exception) {}
                                }
                                // Полный выход: гасим синхронизацию и фоновый сервис,
                                // иначе поллинг и WS продолжают жить со старым токеном.
                                repo?.shutdown()
                                repo = null
                                AetherService.onNewMessage = null
                                AetherService.chatLookup = null
                                stopService(Intent(this@MainActivity, AetherService::class.java))
                                api = null
                                keys = null
                                myId = ""
                                navController.navigate("login") {
                                    popUpTo("main") { inclusive = true }
                                }
                            },
                            onNewChat = {
                                navController.navigate("search")
                            },
                            onNavigateToCustomization = {
                                navController.navigate("customization")
                            },
                            onNavigateToSearch = {
                                navController.navigate("search")
                            },
                            onCreateGroupClick = { asChannel ->
                                navController.navigate("create_group?name=&channel=$asChannel")
                            },
                            onAction = { peerId, action ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    val chat = store.getChat(peerId) ?: return@launch
                                    when (action) {
                                        "mute" -> store.setMuted(peerId, !chat.isMuted)
                                        "archive" -> store.setArchived(peerId, !chat.isArchived)
                                        "pin" -> store.setPinned(peerId, !chat.isPinned)
                                        "delete" -> store.deleteChatAndMessages(peerId)
                                    }
                                }
                            },
                            onNavigateToProfileSettings = { navController.navigate("profile_settings") },
                            onNavigateToNotificationsSettings = { navController.navigate("notifications_settings") },
                            onNavigateToPrivacySettings = { navController.navigate("privacy_settings") },
                            onNavigateToAboutApp = { navController.navigate("about_app") },
                            onNavigateToSecret = { navController.navigate("secret_settings") }
                        )
                    }

                    composable("secret_settings") {
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.SecretSettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    composable("profile_settings") {
                        val apiSafe = api
                        if (apiSafe == null) {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                            return@composable
                        }
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.ProfileSettingsScreen(
                                myId = myId,
                                api = apiSafe,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    composable("notifications_settings") {
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.NotificationsSettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    composable("privacy_settings") {
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.PrivacySettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    composable("about_app") {
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.AboutAppScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    composable("settings") {
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onNavigateToProfile = { navController.navigate("profile_settings") },
                                onNavigateToNotifications = { navController.navigate("notifications_settings") },
                                onNavigateToPrivacy = { navController.navigate("privacy_settings") },
                                onNavigateToAbout = { navController.navigate("about_app") },
                                onNavigateToCustomization = { navController.navigate("customization") }
                            )
                        }
                    }

                    composable("customization") {
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.CustomizationScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    composable("search") {
                        val apiSafe = api
                        if (apiSafe == null) {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                            return@composable
                        }
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.SearchScreen(
                                api = apiSafe,
                                onBack = { navController.popBackStack() },
                                onUserSelected = { peerId ->
                                    // Pop search and open chat
                                    navController.popBackStack()
                                    navController.navigate("chat/$peerId")
                                },
                                onCreateChannel = { name ->
                                    navController.navigate("create_group?name=${Uri.encode(name)}&channel=true")
                                }
                            )
                        }
                    }

                    composable(
                        "create_group?name={name}&channel={channel}",
                        arguments = listOf(
                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                            navArgument("channel") { type = NavType.BoolType; defaultValue = false }
                        )
                    ) { backStackEntry ->
                        val name = backStackEntry.arguments?.getString("name") ?: ""
                        val asChannel = backStackEntry.arguments?.getBoolean("channel") ?: false
                        val apiSafe = api
                        val repoSafe = repo
                        if (apiSafe == null || repoSafe == null) {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                            return@composable
                        }
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.CreateGroupScreen(
                                api = apiSafe,
                                repo = repoSafe,
                                initialName = name,
                                initialIsChannel = asChannel,
                                onBack = { navController.popBackStack() },
                                onGroupCreated = { groupId ->
                                    navController.popBackStack()
                                    navController.navigate("chat/$groupId")
                                }
                            )
                        }
                    }

                    composable(
                        "chat/{peerId}",
                        arguments = listOf(navArgument("peerId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
                        // После восстановления процесса repo == null —
                        // уходим на login вместо отложенного краша.
                        val repoSafe = repo
                        if (repoSafe == null) {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                            return@composable
                        }
                        // (#A6) Группа обсуждений канала — для кнопки под постами
                        val discussionGroupId by androidx.compose.runtime.produceState<String?>(initialValue = null, peerId) {
                            value = try {
                                kotlinx.coroutines.withContext(Dispatchers.IO) { repoSafe.discussionGroupFor(peerId) }
                            } catch (e: Exception) { null }
                        }
                        // Человеческое имя чата для шапки (вместо технического group_/channel_ id)
                        val peerName by androidx.compose.runtime.produceState(initialValue = peerId, peerId) {
                            value = try {
                                kotlinx.coroutines.withContext(Dispatchers.IO) { store.getChat(peerId)?.name?.takeIf { it.isNotBlank() } ?: peerId }
                            } catch (e: Exception) { peerId }
                        }
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            ChatScreen(
                                peerId = peerId,
                                peerDisplayName = peerName,
                                messagesFlow = store.getMessagesForPeer(peerId),
                                onBack = { navController.popBackStack() },
                                onAudioCall = { activeCall = Triple(peerId, false, false) },
                                onVideoCall = { activeCall = Triple(peerId, true, false) },
                                onOpenSafety = if (peerId.startsWith("channel_", ignoreCase = true)) null else ({
                                    navController.navigate("safety/$peerId")
                                }),
                                // Статус «был(а) в сети»: last_active из профиля собеседника
                                fetchPeerLastActive = {
                                    withContext(Dispatchers.IO) { api?.getUserProfile(peerId)?.lastActive }
                                },
                                // Кол-во участников/подписчиков для шапки группы/канала
                                fetchMemberCount = {
                                    withContext(Dispatchers.IO) { api?.getGroupMembers(peerId)?.size }
                                },
                                // Тап по шапке: личный чат → профиль контакта,
                                // группа/канал → экран управления группой.
                                onOpenProfile = {
                                    val groupLike = peerId.startsWith("channel_", ignoreCase = true) ||
                                        peerId.startsWith("group_", ignoreCase = true)
                                    if (groupLike) navController.navigate("group/$peerId")
                                    else navController.navigate("contact/$peerId")
                                },
                                // (#A3) Плашка для легаси-каналов без E2E
                                checkNotE2e = { repoSafe.isPeerNotE2E(peerId) },
                                // (#A6) Канал read-only для не-админов
                                checkCanPost = { repoSafe.canPostTo(peerId) },
                                // (#A6) Кнопка «Обсуждение» под постами канала
                                onOpenDiscussion = discussionGroupId?.let { gid ->
                                    { _: org.groktest.securemessenger.data.MessageEntity ->
                                        navController.navigate("chat/$gid")
                                    }
                                },
                                // (#A2) Optimistic send: запись в Room мгновенно (status=0),
                                // сеть — в фоновой очереди репозитория с ретраями.
                                onSendMessage = { text, replyToId, replyToText ->
                                    repoSafe.enqueueText(peerId, text, replyToId, replyToText)
                                },
                                onSendMedia = { uris, caption ->
                                    repoSafe.sendMedia(peerId, uris, caption)
                                },
                                onSendFiles = { uris, caption ->
                                    repoSafe.sendFiles(peerId, uris, caption)
                                },
                                onSendRecording = { bytes, mime, kind, durationMs ->
                                    repoSafe.sendRecording(peerId, bytes, mime, kind, durationMs)
                                },
                                onDownloadMedia = { jsonText ->
                                    repoSafe.downloadMedia(jsonText)
                                },
                                onDeleteMessage = { mid ->
                                    coroutineScope.launch(Dispatchers.IO) { store.deleteByMsgId(mid) }
                                },
                                myId = myId,
                                onReact = { targetMsgId, emoji ->
                                    coroutineScope.launch(Dispatchers.IO) { repoSafe.react(peerId, targetMsgId, emoji) }
                                },
                                onRetryMessage = { msgId -> repoSafe.retryMessage(msgId) },
                                onSeen = {
                                    coroutineScope.launch(Dispatchers.IO) { repoSafe.sendReadReceipt(peerId) }
                                },
                                onEditMessage = { msgId, newText ->
                                    repoSafe.editMessage(peerId, msgId, newText)
                                },
                                onForwardMessage = { targetPeerId, msg ->
                                    repoSafe.enqueueForward(targetPeerId, msg)
                                },
                                onScheduleMessage = { text, sendAt ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            // Шифруем сразу — worker получает только готовый конверт
                                            val wire = org.json.JSONObject().put("type", "text").put("text", text)
                                            val env = repoSafe.encryptForPeer(peerId, wire.toString())
                                            val data = androidx.work.Data.Builder()
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_SERVER_URL, org.groktest.securemessenger.api.ServerConfig.baseUrl)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_TOKEN, api?.token)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_MY_ID, myId)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_PEER_ID, peerId)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_TEXT, text)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_ENVELOPE_JSON, org.json.JSONObject(env).toString())
                                                .build()
                                            val request = androidx.work.OneTimeWorkRequest.Builder(org.groktest.securemessenger.workers.ScheduledMessageWorker::class.java)
                                                .setInitialDelay(
                                                    (sendAt - System.currentTimeMillis()).coerceAtLeast(0L),
                                                    java.util.concurrent.TimeUnit.MILLISECONDS
                                                )
                                                .setInputData(data)
                                                .setConstraints(
                                                    androidx.work.Constraints.Builder()
                                                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                                                        .build()
                                                )
                                                .build()
                                            androidx.work.WorkManager.getInstance(this@MainActivity).enqueue(request)
                                        } catch (e: Exception) {}
                                    }
                                },
                                forwardChatsFlow = store.getAllChats()
                            )
                        }
                    }

                    // P6: экран сверки «цифр безопасности»
                    composable(
                        "safety/{peerId}",
                        arguments = listOf(navArgument("peerId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.SafetyNumberScreen(
                                myId = myId,
                                myPublicKeyB64 = keys?.publicB64 ?: "",
                                peerId = peerId,
                                trustStore = trustStore,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    // Управление группой/каналом (тап по шапке группового чата)
                    composable(
                        "group/{peerId}",
                        arguments = listOf(navArgument("peerId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
                        val apiSafe = api ?: return@composable
                        val repoSafe = repo ?: return@composable
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.GroupInfoScreen(
                                api = apiSafe,
                                repo = repoSafe,
                                groupId = peerId,
                                onBack = { navController.popBackStack() },
                                onLeft = {
                                    // После выхода/удаления — убрать локальный чат и назад в список
                                    coroutineScope.launch(Dispatchers.IO) { store.deleteChatAndMessages(peerId) }
                                    navController.navigate("main") { popUpTo("main") { inclusive = true } }
                                },
                                onMemberClick = { uid -> navController.navigate("contact/$uid") }
                            )
                        }
                    }

                    // Профиль собеседника (тап по шапке чата)
                    composable(
                        "contact/{peerId}",
                        arguments = listOf(navArgument("peerId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
                        val apiSafe = api ?: return@composable
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.ContactProfileScreen(
                                api = apiSafe,
                                peerId = peerId,
                                onBack = { navController.popBackStack() },
                                onMessage = { navController.popBackStack() },
                                onAudioCall = { activeCall = Triple(peerId, false, false) },
                                onVideoCall = { activeCall = Triple(peerId, true, false) },
                                onOpenSafety = { navController.navigate("safety/$peerId") }
                            )
                        }
                    }

                }

                // Входящие звонки: глобальный слушатель (не затирается активным звонком)
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    AetherService.incomingOfferListener = { signal ->
                        if (signal.optString("type") == "webrtc_offer") {
                            val callerId = signal.optString("sender_id")
                            val isVideoCall = signal.optBoolean("isVideoCall", false)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (activeCall == null) {
                                    activeCall = Triple(callerId, isVideoCall, true)
                                }
                            }
                        }
                    }
                }

                // Оверлей звонка поверх всего приложения
                activeCall?.let { (callPeer, callVideo, callIncoming) ->
                    CallOverlay(
                        peerId = callPeer,
                        isIncoming = callIncoming,
                        isVideoCall = callVideo,
                        onClose = {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                activeCall = null
                            }
                        }
                    )
                }

                // Замок приложения — поверх всего (PIN/биометрия)
                if (org.groktest.securemessenger.data.AppLock.isLocked) {
                    org.groktest.securemessenger.ui.screens.LockScreen(
                        onUnlocked = { org.groktest.securemessenger.data.AppLock.unlock() }
                    )
                }
            }
        }
    }
}
}
}
}