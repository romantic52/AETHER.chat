package aether.desktop.auth

import aether.desktop.data.DesktopPrefs
import aether.desktop.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.sm_core.ApiClient
import uniffi.sm_core.CoreException
import uniffi.sm_core.decryptPrivateKey
import uniffi.sm_core.encryptPrivateKey
import uniffi.sm_core.generateKeypair

/** Аккаунт с активной сессией: всё, что нужно остальному приложению. */
class ActiveAccount(
    val server: String,
    val username: String,
    val token: String,
    val api: ApiClient,
    val keys: DesktopPrefs.AccountKeys,
)

class TotpRequired(val invalid: Boolean) : Exception(
    if (invalid) "Неверный код 2FA" else "Требуется код 2FA"
)

/**
 * Аутентификация десктопа: пароль/TOTP как fallback, автовход по токену.
 * Все вызовы ядра блокирующие — только Dispatchers.IO.
 */
class AuthRepository(private val prefs: DesktopPrefs) {

    /** Автовход по сохранённому токену; null — надо показывать логин. */
    suspend fun restore(): ActiveAccount? = withContext(Dispatchers.IO) {
        val session = prefs.loadSession() ?: return@withContext null
        val keys = prefs.loadKeys(session.username) ?: return@withContext null
        val api = ApiClient(session.server)
        api.setSession(session.token, session.username)
        runCatching { api.heartbeat() }.getOrElse { return@withContext null }
        ActiveAccount(session.server, session.username, session.token, api, keys)
    }

    suspend fun login(
        server: String,
        username: String,
        password: String,
        totpCode: String?,
    ): ActiveAccount = withContext(Dispatchers.IO) {
        val base = ServerConfig.normalizeBaseUrl(server)
        val user = username.trim().lowercase()
        val api = ApiClient(base)
        val session = try {
            if (totpCode.isNullOrBlank()) api.login(user, password)
            else api.loginTotp(user, password, totpCode.trim())
        } catch (e: CoreException.Api) {
            val msg = e.message.orEmpty()
            when {
                "totp_invalid" in msg -> throw TotpRequired(invalid = true)
                "totp_required" in msg -> throw TotpRequired(invalid = false)
                else -> throw e
            }
        }
        val keys = if (session.encryptedPrivateKeyB64.isNotEmpty() &&
            session.encryptedPrivateKeyB64 != "null"
        ) {
            val priv = decryptPrivateKey(session.encryptedPrivateKeyB64, password)
            val pub = api.getPublicKey(user)
            DesktopPrefs.AccountKeys(publicB64 = pub, privateB64 = priv)
        } else {
            prefs.loadKeys(user) ?: throw IllegalStateException(
                "На сервере нет резервной копии ключа, локальные ключи не найдены."
            )
        }
        prefs.saveKeys(user, keys)
        prefs.saveSession(DesktopPrefs.Session(base, user, session.token))
        ActiveAccount(base, user, session.token, api, keys)
    }

    suspend fun register(
        server: String,
        username: String,
        password: String,
    ): ActiveAccount = withContext(Dispatchers.IO) {
        val base = ServerConfig.normalizeBaseUrl(server)
        val user = username.trim().lowercase()
        val api = ApiClient(base)
        val kp = generateKeypair()
        val encrypted = encryptPrivateKey(kp.privateB64, password)
        val session = api.register(user, password, kp.publicB64, encrypted)
        val keys = DesktopPrefs.AccountKeys(publicB64 = kp.publicB64, privateB64 = kp.privateB64)
        prefs.saveKeys(user, keys)
        prefs.saveSession(DesktopPrefs.Session(base, user, session.token))
        ActiveAccount(base, user, session.token, api, keys)
    }

    suspend fun logout(account: ActiveAccount) = withContext(Dispatchers.IO) {
        runCatching { account.api.logout() }
        prefs.clearSession()
    }
}
