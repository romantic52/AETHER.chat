package aether.desktop

import aether.desktop.api.RelayApi
import aether.desktop.auth.ActiveAccount
import aether.desktop.crypto.E2ECrypto
import aether.desktop.crypto.KeyTrustStore
import aether.desktop.data.BlockStore
import aether.desktop.data.DesktopCoreStore
import aether.desktop.data.DesktopPrefs
import aether.desktop.data.MessageRepository
import aether.desktop.service.RealtimeClient
import java.io.File

/**
 * Всё окружение залогиненного аккаунта: хранилище, репозиторий, realtime.
 * Создаётся после входа, [close] гасит всё при logout.
 */
class AppSession private constructor(
    val account: ActiveAccount,
    val api: RelayApi,
    val store: DesktopCoreStore,
    val repository: MessageRepository,
    val realtime: RealtimeClient,
    val trustStore: KeyTrustStore,
    val olmTrustStore: KeyTrustStore,
) {
    val myId: String get() = account.username

    fun close() {
        realtime.close()
        repository.shutdown()
        store.close()
    }

    companion object {
        fun create(
            account: ActiveAccount,
            prefs: DesktopPrefs,
            onTyping: (String) -> Unit = {},
            onWsEvent: (org.json.JSONObject) -> Unit = {},
        ): AppSession {
            val baseDir = DesktopPrefs.defaultDir()
            BlockStore.init(baseDir)

            val api = RelayApi(account.server)
            api.token = account.token

            val store = DesktopCoreStore.create(
                dbFile = prefs.databaseFile(account.username),
                encKeyB64 = prefs.dbKeyB64(),
            )

            val trustStore = KeyTrustStore(store)
            val olmTrustStore = KeyTrustStore(store, namespace = "olm")
            trustStore.keyFetcher = { api.getPublicKey(it) }

            val cacheRoot = File(baseDir, "cache_${DesktopPrefs.accountKey(account.username)}")
            val repository = MessageRepository(
                api = api,
                keys = E2ECrypto.KeyPair(account.keys.privateB64, account.keys.publicB64),
                myId = account.username,
                store = store,
                trustStore = trustStore,
                olmTrustStore = olmTrustStore,
                cacheRoot = cacheRoot,
            )

            val realtime = RealtimeClient(
                serverUrl = account.server,
                token = account.token,
                onNewMessage = { repository.onPushReceived() },
                onTyping = onTyping,
                onEvent = onWsEvent,
            )

            repository.start()
            return AppSession(account, api, store, repository, realtime, trustStore, olmTrustStore)
        }
    }
}
