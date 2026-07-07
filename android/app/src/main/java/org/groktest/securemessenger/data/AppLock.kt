package org.groktest.securemessenger.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Настройки блокировки приложения (PIN/биометрия). Хранилище —
 * EncryptedSharedPreferences (мастер-ключ в Android Keystore). PIN хранится
 * как соль + SHA-256(соль+pin), сам PIN на диск не попадает.
 */
class LockPrefs(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "sm_app_lock",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    var biometricEnabled: Boolean
        get() = prefs.getBoolean("biometric", false)
        set(v) { prefs.edit().putBoolean("biometric", v).apply() }

    /** Авто-блок после ухода в фон, мс (по умолчанию 30 с). */
    val autoLockMs: Long get() = prefs.getLong("auto_lock_ms", 30_000L)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("salt", b64(salt))
            .putString("hash", b64(hash(salt, pin)))
            .putBoolean("enabled", true)
            .apply()
    }

    fun verify(pin: String): Boolean {
        val saltB64 = prefs.getString("salt", null) ?: return false
        val hashB64 = prefs.getString("hash", null) ?: return false
        return b64(hash(b64d(saltB64), pin)) == hashB64
    }

    fun disable() {
        prefs.edit()
            .remove("salt").remove("hash").remove("biometric")
            .putBoolean("enabled", false)
            .apply()
    }

    private fun hash(salt: ByteArray, pin: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray(Charsets.UTF_8))
    }

    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun b64d(s: String) = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
}

/**
 * Глобальное состояние блокировки. MainActivity наблюдает [isLocked] и
 * показывает поверх всего LockScreen, пока пользователь не разблокирует.
 */
object AppLock {
    var isLocked by mutableStateOf(false)
        private set

    private var pausedAt = 0L

    /**
     * При старте. Холодный запуск процесса (pausedAt == 0) — блокируем сразу.
     * Пересоздание Activity при живом процессе (возврат из системного пикера
     * файлов/камеры, смена конфигурации) — по общему авто-таймауту, иначе PIN
     * выскакивал при каждом прикреплении файла.
     */
    fun onStart(context: Context) {
        val p = LockPrefs(context)
        if (!p.enabled) return
        if (pausedAt == 0L || System.currentTimeMillis() - pausedAt > p.autoLockMs) {
            isLocked = true
        }
    }

    fun onPaused() { pausedAt = System.currentTimeMillis() }

    /** При возврате: блокируем, если были в фоне дольше авто-таймаута. */
    fun onResumed(context: Context) {
        val p = LockPrefs(context)
        if (p.enabled && pausedAt > 0 && System.currentTimeMillis() - pausedAt > p.autoLockMs) {
            isLocked = true
        }
    }

    fun unlock() { isLocked = false }
}
