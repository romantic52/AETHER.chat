package aether.desktop.data

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * Секреты десктопа: файлы в %APPDATA%\Aether, каждый зашифрован DPAPI.
 * Пароль не хранится никогда — только токен сессии (30 дней) и ключи аккаунта,
 * как в Android SessionPrefs/SecurePrefs.
 */
class DesktopPrefs(baseDir: File = defaultDir()) {
    private val dir = baseDir.apply { mkdirs() }

    data class Session(val server: String, val username: String, val token: String)
    data class AccountKeys(val publicB64: String, val privateB64: String)

    fun saveSession(session: Session) {
        val json = JSONObject()
            .put("server", session.server)
            .put("username", session.username.trim().lowercase())
            .put("token", session.token)
        writeProtected(File(dir, "session.dpapi"), json.toString().toByteArray(Charsets.UTF_8))
    }

    fun loadSession(): Session? {
        val raw = readProtected(File(dir, "session.dpapi")) ?: return null
        return runCatching {
            val json = JSONObject(String(raw, Charsets.UTF_8))
            Session(
                server = json.getString("server"),
                username = json.getString("username"),
                token = json.getString("token"),
            )
        }.getOrNull()
    }

    fun clearSession() {
        File(dir, "session.dpapi").delete()
    }

    fun saveKeys(username: String, keys: AccountKeys) {
        val json = JSONObject()
            .put("public_b64", keys.publicB64)
            .put("private_b64", keys.privateB64)
        writeProtected(keysFile(username), json.toString().toByteArray(Charsets.UTF_8))
    }

    fun loadKeys(username: String): AccountKeys? {
        val raw = readProtected(keysFile(username)) ?: return null
        return runCatching {
            val json = JSONObject(String(raw, Charsets.UTF_8))
            AccountKeys(json.getString("public_b64"), json.getString("private_b64"))
        }.getOrNull()
    }

    /** Ключ SQLCipher-БД: генерируется один раз, живёт только под DPAPI. */
    fun dbKeyB64(): String {
        val file = File(dir, "dbkey.dpapi")
        readProtected(file)?.let { return String(it, Charsets.UTF_8) }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        writeProtected(file, key.toByteArray(Charsets.UTF_8))
        return key
    }

    fun databaseFile(username: String): File = File(dir, "sm_core_store_${accountKey(username)}.db")

    private fun keysFile(username: String) = File(dir, "keys_${accountKey(username)}.dpapi")

    private fun writeProtected(file: File, plain: ByteArray) {
        file.writeBytes(Dpapi.protect(plain))
    }

    private fun readProtected(file: File): ByteArray? {
        if (!file.isFile) return null
        return runCatching { Dpapi.unprotect(file.readBytes()) }.getOrNull()
    }

    companion object {
        fun defaultDir(): File {
            val appData = System.getenv("APPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Roaming")
            return File(appData, "Aether")
        }

        /** Как на Android: sha256(user).hex.take(24) — имя БД и файлов per-account. */
        fun accountKey(username: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(username.trim().lowercase().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .take(24)
    }
}
