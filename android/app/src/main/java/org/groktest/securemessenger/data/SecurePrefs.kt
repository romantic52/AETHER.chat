package org.groktest.securemessenger.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.groktest.securemessenger.crypto.E2ECrypto

private fun encryptedPrefs(context: Context, name: String) =
    EncryptedSharedPreferences.create(
        context,
        name,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

/**
 * Хранение E2E-ключей пользователя на устройстве (EncryptedSharedPreferences,
 * мастер-ключ в Android Keystore). Приватный ключ не покидает устройство и не
 * требует расшифровки паролем при каждом логине.
 */
class SecurePrefs(context: Context, userId: String) {
    private val prefs = encryptedPrefs(context, "secure_messenger_${userId.lowercase()}")

    private val privKey = "private_b64"
    private val pubKey = "public_b64"

    fun saveKeys(kp: E2ECrypto.KeyPair) {
        prefs.edit()
            .putString(privKey, kp.privateB64)
            .putString(pubKey, kp.publicB64)
            .apply()
    }

    fun loadKeys(): E2ECrypto.KeyPair? {
        val priv = prefs.getString(privKey, null) ?: return null
        val pub = prefs.getString(pubKey, null) ?: return null
        return E2ECrypto.KeyPair(priv, pub)
    }

    fun clearKeys() {
        prefs.edit().remove(privKey).remove(pubKey).apply()
    }
}

/**
 * Сессия "Запомнить меня": server|username|token (токен живёт 30 дней).
 * Пароль НЕ хранится. Хранилище зашифровано.
 */
class SessionPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = encryptedPrefs(context, "aether_session")

    data class Session(val server: String, val username: String, val token: String)
    data class LegacyLogin(val server: String, val username: String, val password: String)

    fun save(server: String, username: String, token: String) {
        val id = username.trim().lowercase()
        val accounts = prefs.getStringSet("accounts", emptySet()).orEmpty().toMutableSet()
        accounts += id
        prefs.edit()
            .putString("server", server)
            .putString("username", id)
            .putString("token", token)
            .putString("active_account", id)
            .putString(accountKey(id, "server"), server)
            .putString(accountKey(id, "token"), token)
            .putStringSet("accounts", accounts)
            .apply()
    }

    fun load(): Session? {
        val active = prefs.getString("active_account", null)
        if (active != null) loadAccount(active)?.let { return it }

        val server = prefs.getString("server", null)
        val username = prefs.getString("username", null)
        val token = prefs.getString("token", null)
        if (server != null && username != null && token != null) {
            return Session(server, username.lowercase(), token).also {
                save(it.server, it.username, it.token)
            }
        }
        return prefs.getStringSet("accounts", emptySet()).orEmpty()
            .firstNotNullOfOrNull(::loadAccount)
            ?.also { activate(it) }
    }

    fun sessions(): List<Session> = prefs.getStringSet("accounts", emptySet()).orEmpty()
        .mapNotNull(::loadAccount)

    fun activate(username: String): Session? = loadAccount(username)?.also(::activate)

    fun clear() {
        val current = prefs.getString("active_account", null)
            ?: prefs.getString("username", null)?.lowercase()
        val accounts = prefs.getStringSet("accounts", emptySet()).orEmpty().toMutableSet()
        if (current != null) accounts.remove(current)
        val editor = prefs.edit()
            .remove("server")
            .remove("username")
            .remove("token")
            .remove("active_account")
            .putStringSet("accounts", accounts)
        if (current != null) {
            editor.remove(accountKey(current, "server")).remove(accountKey(current, "token"))
        }
        editor.apply()
        accounts.firstNotNullOfOrNull(::loadAccount)?.let(::activate)
    }

    fun legacyLogin(): LegacyLogin? {
        val value = appContext.getSharedPreferences("AetherPrefs", Context.MODE_PRIVATE)
            .getString("saved_login", null)
            ?: return null
        val parts = value.split('|', limit = 3)
        if (parts.size != 3 || parts.any(String::isBlank)) return null
        return LegacyLogin(parts[0], parts[1].trim().lowercase(), parts[2])
    }

    fun clearLegacyLogin() {
        appContext.getSharedPreferences("AetherPrefs", Context.MODE_PRIVATE)
            .edit().remove("saved_login").apply()
    }

    private fun loadAccount(username: String): Session? {
        val id = username.trim().lowercase()
        val server = prefs.getString(accountKey(id, "server"), null) ?: return null
        val token = prefs.getString(accountKey(id, "token"), null) ?: return null
        return Session(server, id, token)
    }

    private fun activate(session: Session) {
        prefs.edit()
            .putString("server", session.server)
            .putString("username", session.username)
            .putString("token", session.token)
            .putString("active_account", session.username)
            .apply()
    }

    private fun accountKey(username: String, field: String) = "account.$username.$field"
}
