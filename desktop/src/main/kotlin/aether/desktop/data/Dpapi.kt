package aether.desktop.data

import com.sun.jna.platform.win32.Crypt32Util

/**
 * Windows DPAPI (CryptProtectData): секреты шифруются ключом,
 * привязанным к учётной записи Windows. Аналог Android Keystore
 * для ключа SQLCipher-БД, токена сессии и аккаунтных ключей.
 */
object Dpapi {
    fun protect(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain)

    fun unprotect(blob: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(blob)
}
