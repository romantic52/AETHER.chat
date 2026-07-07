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
    private val prefs = encryptedPrefs(context, "aether_session")

    data class Session(val server: String, val username: String, val token: String)

    fun save(server: String, username: String, token: String) {
        prefs.edit()
            .putString("server", server)
            .putString("username", username)
            .putString("token", token)
            .apply()
    }

    fun load(): Session? {
        val server = prefs.getString("server", null) ?: return null
        val username = prefs.getString("username", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return Session(server, username, token)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
