package org.groktest.securemessenger

import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
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
import android.content.Context
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

private fun androidx.navigation.NavHostController.navigateSingle(route: String) {
    navigate(route) { launchSingleTop = true }
}

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
        repo?.setAppActive(true)
        org.groktest.securemessenger.data.AppLock.onResumed(this)
    }

    override fun onPause() {
        super.onPause()
        AetherService.appInForeground = false
        repo?.setAppActive(false)
        org.groktest.securemessenger.data.AppLock.onPaused()
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Один общий загрузчик умеет брать лёгкий первый кадр видео без запуска плеера.
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(applicationContext)
                .components { add(coil.decode.VideoFrameDecoder.Factory()) }
                .build()
        )

        // Замок приложения: если включён PIN — блокируем сразу при старте
        org.groktest.securemessenger.data.AppLock.onStart(this)
        // Чёрный список — загрузить в память для фильтрации входящих
        org.groktest.securemessenger.data.BlockStore.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        
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
                val serverRegistry = remember {
                    org.groktest.securemessenger.data.ServerRegistry(this@MainActivity)
                }
                val sessionPrefs = remember {
                    org.groktest.securemessenger.data.SessionPrefs(this@MainActivity, serverRegistry)
                }
                val initialSession = remember { sessionPrefs.load() }
                var currentSpace by remember { mutableStateOf(initialSession) }
                val initialStoreAccount = initialSession?.username?.lowercase() ?: "__anonymous__"
                val initialStoreServer = initialSession?.let { session ->
                    serverRegistry.server(session.serverId)?.storageServerId ?: session.serverId
                } ?: org.groktest.securemessenger.data.ServerRecord.OFFICIAL_PLACEHOLDER_ID
                var storeSpace by remember { mutableStateOf("$initialStoreServer|$initialStoreAccount") }
                var store by remember {
                    mutableStateOf(
                        org.groktest.securemessenger.data.CoreStore.create(
                            this@MainActivity,
                            initialStoreAccount,
                            initialStoreServer,
                        )
                    )
                }
                var trustStore by remember {
                    mutableStateOf(org.groktest.securemessenger.crypto.KeyTrustStore(store))
                }
                var olmTrustStore by remember {
                    mutableStateOf(org.groktest.securemessenger.crypto.KeyTrustStore(store, "olm"))
                }
                var myOlmIdentity by remember { mutableStateOf("") }

                // Активный звонок: (peerId, isVideoCall, isIncoming).
                // Оверлей поверх NavHost — звонок можно свернуть и пользоваться приложением.
                var activeCall by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }
                // Свёрнут ли звонок в мини-пилюлю: тогда контент приложения
                // сдвигается вниз, чтобы пилюля не перекрывала шапки экранов.
                var callMinimized by remember { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(activeCall) {
                    if (activeCall != null) callMinimized = false
                }

                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    val callBarVisible = activeCall != null && callMinimized
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = 600.dp)
                    ) {
                        // Резерв под мини-пилюлю звонка: контент уезжает вниз,
                        // как в Telegram, и шапки экранов не перекрываются.
                        if (callBarVisible) {
                            androidx.compose.foundation.layout.Spacer(
                                Modifier.statusBarsPadding().height(56.dp)
                            )
                        }
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (callBarVisible) {
                                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                        NavHost(
                            navController = navController,
                            startDestination = "login",
                            enterTransition = {
                                androidx.compose.animation.slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = androidx.compose.animation.core.tween(
                                        themeSettings.motionDuration(300),
                                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                                    )
                                )
                            },
                            exitTransition = { androidx.compose.animation.ExitTransition.None },
                            popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                            popExitTransition = {
                                androidx.compose.animation.slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = androidx.compose.animation.core.tween(
                                        themeSettings.motionDuration(300),
                                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                                    )
                                )
                            }
                        ) {
                            composable(
                                route = "login?custom={custom}",
                                arguments = listOf(
                                    navArgument("custom") {
                                        type = NavType.BoolType
                                        defaultValue = false
                                    }
                                )
                            ) { entry ->
                        val forceCustom = entry.arguments?.getBoolean("custom") ?: false
                        val savedSession = remember(forceCustom) {
                            if (forceCustom) null else sessionPrefs.load()
                        }
                        val legacyLogin = remember(forceCustom) {
                            if (forceCustom) null else sessionPrefs.legacyLogin()
                        }

                        LoginScreen(
                            registry = serverRegistry,
                            savedSession = savedSession,
                            legacyLogin = legacyLogin,
                            forceCustomServer = forceCustom,
                            onLoginSuccess = { serverRecord, kp, apiInstance, id, rememberMe ->
                                val accountId = id.lowercase()
                                val nextStoreSpace = "${serverRecord.storageServerId}|$accountId"
                                if (storeSpace != nextStoreSpace) {
                                    repo?.shutdown()
                                    store = org.groktest.securemessenger.data.CoreStore.create(
                                        this@MainActivity,
                                        accountId,
                                        serverRecord.storageServerId,
                                    )
                                    trustStore = org.groktest.securemessenger.crypto.KeyTrustStore(store)
                                    olmTrustStore = org.groktest.securemessenger.crypto.KeyTrustStore(store, "olm")
                                    myOlmIdentity = ""
                                    storeSpace = nextStoreSpace
                                }
                                keys = kp
                                api = apiInstance
                                myId = id
                                trustStore.keyFetcher = { apiInstance.getPublicKey(it) }
                                olmTrustStore.keyFetcher = {
                                    apiInstance.claimOlmKeys(id, it).identityKeyB64
                                }
                                olmTrustStore.onKeyAccepted = {
                                    store.metaSet("olm_session_reset.${it.lowercase()}", "1")
                                }

                                // Пароль не сохраняем: только server|username + токен сессии
                                val token = apiInstance.token
                                if (rememberMe && token != null) {
                                    sessionPrefs.save(
                                        serverId = serverRecord.id,
                                        server = serverRecord.apiUrl,
                                        serverName = serverRecord.displayName,
                                        username = id,
                                        token = token,
                                    )
                                } else {
                                    sessionPrefs.remove(serverRecord.id, id)
                                }
                                currentSpace = org.groktest.securemessenger.data.SessionPrefs.Session(
                                    serverId = serverRecord.id,
                                    server = serverRecord.apiUrl,
                                    serverName = serverRecord.displayName,
                                    username = id,
                                    token = token.orEmpty(),
                                )
                                if (legacyLogin?.username?.equals(id, ignoreCase = true) == true) {
                                    sessionPrefs.clearLegacyLogin()
                                }

                                org.groktest.securemessenger.api.ServerConfig.baseUrl = serverRecord.apiUrl

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
                                                            name = p.displayName.ifBlank {
                                                                p.username.ifBlank {
                                                                    chat.name.takeUnless { it == "null" } ?: chat.peerId
                                                                }
                                                            },
                                                            avatarFileId = p.avatarFileId,
                                                            statusEmoji = p.statusEmoji,
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
                                    serverId = serverRecord.id,
                                    keys = kp,
                                    myId = id,
                                    store = store,
                                    trustStore = trustStore,
                                    olmTrustStore = olmTrustStore,
                                    resolver = contentResolver,
                                    cacheRoot = cacheDir
                                )
                                repo = r
                                r.setAppActive(true)
                                // Пуш по WebSocket → мгновенная синхронизация
                                AetherService.onNewMessage = { r.onPushReceived() }
                                // Имя/мьют чата для уведомлений (из ядрового хранилища).
                                // Колбэк дергается с фонового WS-потока ядра — runBlocking безопасен.
                                AetherService.chatLookup = { pid ->
                                    try { kotlinx.coroutines.runBlocking { store.getChat(pid) } } catch (e: Exception) { null }
                                }
                                r.start()
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching { r.myOlmIdentity() }.getOrNull()?.let { identity ->
                                        withContext(Dispatchers.Main) { myOlmIdentity = identity }
                                    }
                                }


                                val serverUrl = serverRecord.apiUrl
                                val serviceIntent = Intent(this@MainActivity, AetherService::class.java).apply {
                                    putExtra("server_url", serverUrl)
                                    putExtra("token", apiInstance.token)
                                    putExtra("my_id", id)
                                }
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                
                                navController.navigate("main") {
                                    popUpTo(if (forceCustom) "main" else "login") { inclusive = true }
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
                            activeSpace = currentSpace,
                            spaces = (sessionPrefs.sessions() + listOfNotNull(currentSpace))
                                .distinctBy { "${it.serverId}|${it.username.lowercase()}" },
                            chatListFlow = store.getChatList(),
                            onChatSelected = { peerId ->
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) { store.preloadMessages(peerId) }
                                    navController.navigateSingle("chat/$peerId")
                                }
                            },
                            onLogout = {
                                sessionPrefs.clear()
                                currentSpace = sessionPrefs.load()
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
                                navController.navigateSingle("search")
                            },
                            onNavigateToCustomization = {
                                navController.navigateSingle("customization")
                            },
                            onNavigateToSearch = {
                                navController.navigateSingle("search")
                            },
                            onCreateGroupClick = { asChannel ->
                                navController.navigateSingle("create_group?name=&channel=$asChannel")
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
                            onSwitchSpace = { target ->
                                if (sessionPrefs.activate(target.serverId, target.username) != null) {
                                    currentSpace = target
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
                                }
                            },
                            onAddServer = { navController.navigateSingle("login?custom=true") },
                            onNavigateToProfileSettings = { navController.navigateSingle("profile_settings") },
                            onNavigateToNotificationsSettings = { navController.navigateSingle("notifications_settings") },
                            onNavigateToPrivacySettings = { navController.navigateSingle("privacy_settings") },
                            onNavigateToSecuritySettings = { navController.navigateSingle("security_settings") },
                            onNavigateToAboutApp = { navController.navigateSingle("about_app") },
                            onNavigateToExperiments = { navController.navigateSingle("secret_settings") },
                            onStartAudioCall = { peerId -> activeCall = Triple(peerId, false, false) },
                            onStartVideoCall = { peerId -> activeCall = Triple(peerId, true, false) }
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

                    composable("security_settings") {
                        val apiSafe = api
                        if (apiSafe == null) {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                            return@composable
                        }
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.SecurityScreen(
                                api = apiSafe,
                                myId = myId,
                                onBack = { navController.popBackStack() },
                                onPairDevice = { navController.navigateSingle("pair_device") },
                                onLoggedOut = {
                                    // Сервер уже отозвал сессию этого устройства («Выйти»
                                    // на экране безопасности) — локально гасим всё, как при logout.
                                    sessionPrefs.clear()
                                    currentSpace = sessionPrefs.load()
                                    repo?.shutdown()
                                    repo = null
                                    AetherService.onNewMessage = null
                                    AetherService.chatLookup = null
                                    stopService(Intent(this@MainActivity, AetherService::class.java))
                                    api = null
                                    keys = null
                                    myId = ""
                                    navController.navigate("login") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }
                    }

                    composable("pair_device") {
                        val apiSafe = api
                        val keysSafe = keys
                        if (apiSafe == null || keysSafe == null) {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                            return@composable
                        }
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.PairDeviceScreen(
                                api = apiSafe,
                                myId = myId,
                                keys = keysSafe,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }

                    composable("servers") {
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.ServersScreen(
                                registry = serverRegistry,
                                activeServerId = currentSpace?.serverId,
                                onBack = { navController.popBackStack() },
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
                        val apiSafe = api ?: return@composable
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            org.groktest.securemessenger.ui.screens.SettingsScreen(
                                api = apiSafe,
                                myId = myId,
                                onBack = { navController.popBackStack() },
                                onNavigateToProfile = { navController.navigateSingle("profile_settings") },
                                onNavigateToNotifications = { navController.navigateSingle("notifications_settings") },
                                onNavigateToPrivacy = { navController.navigateSingle("privacy_settings") },
                                onNavigateToSecurity = { navController.navigateSingle("security_settings") },
                                onNavigateToServers = { navController.navigateSingle("servers") },
                                onNavigateToAbout = { navController.navigateSingle("about_app") },
                                onNavigateToCustomization = { navController.navigateSingle("customization") },
                                onNavigateToExperiments = { navController.navigateSingle("secret_settings") }
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
                                onResultSelected = { result ->
                                    coroutineScope.launch {
                                        repo?.let { repoSafe ->
                                            withContext(Dispatchers.IO) {
                                                repoSafe.ensureChatExists(
                                                    result.userId,
                                                    forceGroup = result.isGroup
                                                )
                                            }
                                        }
                                        navController.popBackStack()
                                        navController.navigateSingle("chat/${Uri.encode(result.userId)}")
                                    }
                                },
                                onCreateChannel = { name ->
                                    navController.navigateSingle("create_group?name=${Uri.encode(name)}&channel=true")
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
                                    navController.navigateSingle("chat/$groupId")
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
                        val peerChat = store.cachedChat(peerId)
                        val peerName = peerChat?.name?.takeIf { it.isNotBlank() } ?: peerId
                        val peerType = peerChat?.type ?: 0
                        org.groktest.securemessenger.utils.SwipeToBackWrapper(onBack = { navController.popBackStack() }) {
                            ChatScreen(
                                peerId = peerId,
                                peerDisplayName = peerName,
                                chatType = peerType,
                                messagesFlow = store.getMessagesForPeer(peerId),
                                onBack = { navController.popBackStack() },
                                onAudioCall = { activeCall = Triple(peerId, false, false) },
                                onVideoCall = { activeCall = Triple(peerId, true, false) },
                                onOpenSafety = if (peerType == 1 || peerType == 2) null else ({
                                    navController.navigateSingle("safety/$peerId")
                                }),
                                fetchPeerPresence = {
                                    withContext(Dispatchers.IO) {
                                        api?.getUserProfile(peerId)?.let { profile ->
                                            store.getChat(peerId)?.let { chat ->
                                                store.updateChat(
                                                    chat.copy(
                                                        name = profile.displayName.ifBlank {
                                                            profile.username.ifBlank { chat.name }
                                                        },
                                                        avatarFileId = profile.avatarFileId,
                                                        statusEmoji = profile.statusEmoji,
                                                    )
                                                )
                                            }
                                            profile.lastActive to profile.statusEmoji
                                        }
                                    }
                                },
                                // Кол-во участников/подписчиков для шапки группы/канала
                                fetchMemberCount = {
                                    withContext(Dispatchers.IO) { api?.getGroupMembers(peerId)?.size }
                                },
                                // Тап по шапке: личный чат → профиль контакта,
                                // группа/канал → экран управления группой.
                                onOpenProfile = {
                                    if (peerType == 1 || peerType == 2) navController.navigateSingle("group/$peerId")
                                    else navController.navigateSingle("contact/$peerId")
                                },
                                // (#A3) Плашка для легаси-каналов без E2E
                                checkNotE2e = { repoSafe.isPeerNotE2E(peerId) },
                                // (#A6) Канал read-only для не-админов
                                checkCanPost = { repoSafe.canPostTo(peerId) },
                                // (#A6) Кнопка «Обсуждение» под постами канала
                                onOpenDiscussion = discussionGroupId?.let { gid ->
                                    { _: org.groktest.securemessenger.data.MessageEntity ->
                                        navController.navigateSingle("chat/$gid")
                                    }
                                },
                                // (#A2) Optimistic send: запись в Room мгновенно (status=0),
                                // сеть — в фоновой очереди репозитория с ретраями.
                                onSendMessage = { text, replyToId, replyToText, ephemeral ->
                                    repoSafe.enqueueText(peerId, text, replyToId, replyToText, ephemeral)
                                },
                                onSendMedia = { uris, caption ->
                                    repoSafe.sendMedia(peerId, uris, caption)
                                },
                                onSendFiles = { uris, caption ->
                                    repoSafe.sendFiles(peerId, uris, caption)
                                },
                                onSendRecording = { file, mime, kind, durationMs, waveform ->
                                    repoSafe.sendRecording(peerId, file, mime, kind, durationMs, waveform = waveform)
                                },
                                onDownloadMedia = { jsonText ->
                                    repoSafe.downloadMedia(jsonText)
                                },
                                cachedMediaFile = { jsonText ->
                                    repoSafe.cachedMediaFile(jsonText)
                                },
                                onDeleteMessage = { mid, deleteEverywhere ->
                                    if (deleteEverywhere) {
                                        repoSafe.deleteForEveryone(peerId, mid)
                                    } else {
                                        repoSafe.deleteForMe(mid)
                                    }
                                },
                                myId = myId,
                                onReact = { targetMsgId, emoji ->
                                    coroutineScope.launch(Dispatchers.IO) { repoSafe.react(peerId, targetMsgId, emoji) }
                                },
                                onRetryMessage = { msgId -> repoSafe.retryMessage(msgId) },
                                onOpenEphemeral = { message -> repoSafe.openEphemeral(message) },
                                onCloseEphemeral = { message -> repoSafe.closeEphemeral(message) },
                                loadMessageDeliveryInfo = { msgId -> store.messageDeliveryInfo(msgId) },
                                loadDeliveryPolicy = { targetPeer -> store.deliveryPolicy(targetPeer) },
                                saveDeliveryPolicy = { targetPeer, mode, storageMode ->
                                    store.setDeliveryPolicy(targetPeer, mode, storageMode)
                                },
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
                                            val messageId = uniffi.sm_core.newMessageId()
                                            val wire = uniffi.sm_core.payloadWithMessageId(
                                                org.json.JSONObject().put("type", "text").put("text", text).toString(),
                                                messageId,
                                            )
                                            val env = repoSafe.encryptForPeer(peerId, wire)
                                            val space = currentSpace ?: return@launch
                                            val storageServerId = serverRegistry.server(space.serverId)
                                                ?.storageServerId ?: space.serverId
                                            val data = androidx.work.Data.Builder()
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_SERVER_URL, org.groktest.securemessenger.api.ServerConfig.baseUrl)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_SERVER_ID, space.serverId)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_STORAGE_SERVER_ID, storageServerId)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_TOKEN, api?.token)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_MY_ID, myId)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_PEER_ID, peerId)
                                                .putString(org.groktest.securemessenger.workers.ScheduledMessageWorker.KEY_MESSAGE_ID, messageId)
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
                                myPublicKeyB64 = myOlmIdentity,
                                peerId = peerId,
                                trustStore = olmTrustStore,
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
                                onMemberClick = { uid -> navController.navigateSingle("contact/$uid") }
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
                                onOpenSafety = { navController.navigateSingle("safety/$peerId") }
                            )
                        }
                    }

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
                                    // Приложение в фоне: Compose на паузе, оверлей звонка
                                    // не смонтируется и рингтон не заиграет — будим
                                    // пользователя системным уведомлением о звонке.
                                    if (!AetherService.appInForeground) {
                                        try {
                                            val callerName = runCatching {
                                                AetherService.chatLookup?.invoke(callerId)?.name
                                            }.getOrNull()?.takeIf { it.isNotBlank() } ?: callerId
                                            val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            }
                                            val pending = android.app.PendingIntent.getActivity(
                                                this@MainActivity, 2, intent,
                                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                                            )
                                            val notif = androidx.core.app.NotificationCompat.Builder(this@MainActivity, "AETHER_MESSAGES")
                                                .setSmallIcon(R.drawable.ic_stat_aether)
                                                .setContentTitle(callerName)
                                                .setContentText(if (isVideoCall) "Входящий видеозвонок" else "Входящий звонок")
                                                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                                                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                                                .setContentIntent(pending)
                                                .setAutoCancel(true)
                                                .build()
                                            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                                                .notify("incoming_call".hashCode(), notif)
                                        } catch (_: Exception) {}
                                    }
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
                        minimized = callMinimized,
                        onMinimizedChange = { callMinimized = it },
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
