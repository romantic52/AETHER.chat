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
class SecurePrefs private constructor(
    context: Context,
    serverId: String,
    userId: String,
    migrateLegacy: Boolean,
) {
    constructor(context: Context, userId: String) : this(
        context,
        ServerRecord.OFFICIAL_PLACEHOLDER_ID,
        userId,
        true,
    )

    constructor(context: Context, serverId: String, userId: String) : this(
        context,
        serverId,
        userId,
        serverId == ServerRecord.OFFICIAL_PLACEHOLDER_ID,
    )

    private val prefs = encryptedPrefs(context, "secure_messenger_${spaceHash(serverId, userId)}")

    private val privKey = "private_b64"
    private val pubKey = "public_b64"

    init {
        if (migrateLegacy && prefs.getString(privKey, null) == null) {
            val legacy = encryptedPrefs(context, "secure_messenger_${userId.lowercase()}")
            val privateValue = legacy.getString(privKey, null)
            val publicValue = legacy.getString(pubKey, null)
            if (privateValue != null && publicValue != null) {
                prefs.edit().putString(privKey, privateValue).putString(pubKey, publicValue).apply()
            }
        }
    }

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
 * Сессии пространств: serverId + username + token (токен живёт 30 дней).
 * Пароль НЕ хранится. Хранилище зашифровано.
 */
class SessionPrefs(context: Context, private val registry: ServerRegistry? = null) {
    private val appContext = context.applicationContext
    private val prefs = encryptedPrefs(context, "aether_session")

    data class Session(
        val serverId: String,
        val server: String,
        val serverName: String,
        val username: String,
        val token: String,
    )
    data class LegacyLogin(val server: String, val username: String, val password: String)

    fun save(server: String, username: String, token: String) {
        save(
            serverId = ServerRecord.OFFICIAL_PLACEHOLDER_ID,
            server = server,
            serverName = "Aether Cloud",
            username = username,
            token = token,
        )
    }

    fun save(serverId: String, server: String, serverName: String, username: String, token: String) {
        val id = username.trim().lowercase()
        val key = spaceKey(serverId, id)
        val spaces = prefs.getStringSet("spaces", emptySet()).orEmpty().toMutableSet()
        spaces += key
        prefs.edit()
            .putString("server", server)
            .putString("username", id)
            .putString("token", token)
            .putString("active_space", key)
            .putString(spaceField(key, "server_id"), serverId)
            .putString(spaceField(key, "server"), server)
            .putString(spaceField(key, "server_name"), serverName)
            .putString(spaceField(key, "username"), id)
            .putString(spaceField(key, "token"), token)
            .putStringSet("spaces", spaces)
            .apply()
        registry?.addAccount(serverId, id)
    }

    fun load(): Session? {
        val active = prefs.getString("active_space", null)
        if (active != null) loadSpace(active)?.let { return it }

        val server = prefs.getString("server", null)
        val username = prefs.getString("username", null)
        val token = prefs.getString("token", null)
        if (server != null && username != null && token != null) {
            return Session(
                ServerRecord.OFFICIAL_PLACEHOLDER_ID,
                server,
                "Aether Cloud",
                username.lowercase(),
                token,
            ).also {
                save(it.serverId, it.server, it.serverName, it.username, it.token)
            }
        }
        migrateLegacyAccounts()
        return prefs.getStringSet("spaces", emptySet()).orEmpty()
            .firstNotNullOfOrNull(::loadSpace)
            ?.also { activate(it) }
    }

    fun sessions(): List<Session> {
        migrateLegacyAccounts()
        return prefs.getStringSet("spaces", emptySet()).orEmpty()
            .mapNotNull(::loadSpace)
            .sortedWith(compareBy<Session> { it.serverName }.thenBy { it.username })
    }

    fun activate(serverId: String, username: String): Session? =
        loadSpace(spaceKey(serverId, username))?.also(::activate)

    fun activate(username: String): Session? = sessions()
        .firstOrNull { it.username.equals(username, ignoreCase = true) }
        ?.also(::activate)

    fun clear() {
        val current = prefs.getString("active_space", null)
        val session = current?.let(::loadSpace)
        if (session != null) remove(session.serverId, session.username)
    }

    fun remove(serverId: String, username: String) {
        val current = spaceKey(serverId, username)
        val session = loadSpace(current)
        val wasActive = prefs.getString("active_space", null) == current
        val spaces = prefs.getStringSet("spaces", emptySet()).orEmpty().toMutableSet()
        spaces.remove(current)
        val editor = prefs.edit()
            .putStringSet("spaces", spaces)
        if (wasActive) {
            editor.remove("server").remove("username").remove("token").remove("active_space")
        }
        listOf("server_id", "server", "server_name", "username", "token").forEach {
            editor.remove(spaceField(current, it))
        }
        editor.apply()
        if (session != null) registry?.removeAccount(session.serverId, session.username)
        if (wasActive) spaces.firstNotNullOfOrNull(::loadSpace)?.let(::activate)
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

    private fun migrateLegacyAccounts() {
        val legacy = prefs.getStringSet("accounts", emptySet()).orEmpty()
        legacy.forEach { username ->
            val id = username.trim().lowercase()
            val server = prefs.getString(accountKey(id, "server"), null) ?: return@forEach
            val token = prefs.getString(accountKey(id, "token"), null) ?: return@forEach
            if (loadSpace(spaceKey(ServerRecord.OFFICIAL_PLACEHOLDER_ID, id)) == null) {
                save(server, id, token)
            }
        }
    }

    private fun loadSpace(key: String): Session? {
        val serverId = prefs.getString(spaceField(key, "server_id"), null) ?: return null
        val server = prefs.getString(spaceField(key, "server"), null) ?: return null
        val username = prefs.getString(spaceField(key, "username"), null) ?: return null
        val token = prefs.getString(spaceField(key, "token"), null) ?: return null
        val serverName = prefs.getString(spaceField(key, "server_name"), null) ?: server
        return Session(serverId, server, serverName, username, token)
    }

    private fun activate(session: Session) {
        prefs.edit()
            .putString("server", session.server)
            .putString("username", session.username)
            .putString("token", session.token)
            .putString("active_space", spaceKey(session.serverId, session.username))
            .apply()
    }

    private fun accountKey(username: String, field: String) = "account.$username.$field"
    private fun spaceField(space: String, field: String) = "space.$space.$field"
}

private fun spaceKey(serverId: String, userId: String): String = spaceHash(serverId, userId)

private fun spaceHash(serverId: String, userId: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest("${serverId.trim().lowercase()}|${userId.trim().lowercase()}".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .take(32)
