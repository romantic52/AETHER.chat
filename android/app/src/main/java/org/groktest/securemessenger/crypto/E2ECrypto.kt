package org.groktest.securemessenger.crypto

import android.util.Base64
import java.security.SecureRandom

/**
 * Тонкая обёртка над общим Rust-ядром (uniffi.sm_core): идентичность, личные
 * сообщения (crypto_box) и AES-GCM небольших payload (encryptFile/decryptFile,
 * бэкап ключа) считает ядро. Крупные медиа шифруются потоково через javax.crypto
 * (encryptStream/decryptBytes) — чтобы не копировать файл в память. Форматы
 * совместимы с web. lazysodium больше не используется.
 */
class E2ECrypto {
    private val random = SecureRandom()

    data class KeyPair(val privateB64: String, val publicB64: String)

    data class Envelope(
        val senderPubkeyB64: String,
        val nonceB64: String,
        val ciphertextB64: String,
    ) {
        fun toMap(): Map<String, String> = mapOf(
            "sender_pubkey_b64" to senderPubkeyB64,
            "nonce_b64" to nonceB64,
            "ciphertext_b64" to ciphertextB64,
        )
    }

    // Крипта считается общим Rust-ядром (uniffi.sm_core). Форматы идентичны
    // прежней реализации на LazySodium — проверено кросс-совместимостью.
    fun generateKeyPair(): KeyPair {
        val kp = uniffi.sm_core.generateKeypair()
        return KeyPair(kp.privateB64, kp.publicB64)
    }

    fun encrypt(plaintext: String, sender: KeyPair, recipientPublicB64: String): Envelope {
        // (#A3) Псевдошифрование CHANNEL_PUBLIC_KEY удалено: группы и каналы
        // шифруются общим ключом (см. MessageRepository.encryptWireFor).
        require(recipientPublicB64 != "CHANNEL_PUBLIC_KEY") {
            "CHANNEL_PUBLIC_KEY больше не поддерживается — используйте групповой ключ"
        }
        val e = uniffi.sm_core.boxEncrypt(plaintext, sender.privateB64, recipientPublicB64)
        return Envelope(e.senderPubkeyB64, e.nonceB64, e.ciphertextB64)
    }

    fun decrypt(envelope: Envelope, recipient: KeyPair): String {
        // Легаси: старые канальные сообщения хранились как plaintext-b64.
        // Оставлено только для ЧТЕНИЯ истории; новые сообщения так не шлются.
        if (envelope.nonceB64 == "CHANNEL_NONCE") {
            return String(b64d(envelope.ciphertextB64), Charsets.UTF_8)
        }
        return uniffi.sm_core.boxDecrypt(
            uniffi.sm_core.Envelope(envelope.senderPubkeyB64, envelope.nonceB64, envelope.ciphertextB64),
            recipient.privateB64,
        )
    }

    class SymmetricKey(val keyB64: String)

    fun generateSymmetricKey(): SymmetricKey {
        val key = ByteArray(32) // 256-bit AES
        random.nextBytes(key)
        return SymmetricKey(b64e(key))
    }

    // AES-GCM небольших payload (групповой wire) считает ядро. Стримовые методы
    // для крупных медиа остаются на javax (без копий в памяти — UniFFI копирует Vec<u8>).
    fun encryptFile(fileData: ByteArray, symKey: SymmetricKey): Envelope {
        val c = uniffi.sm_core.aesEncrypt(fileData, symKey.keyB64)
        return Envelope(senderPubkeyB64 = "SYM", nonceB64 = c.nonceB64, ciphertextB64 = c.ciphertextB64)
    }

    /**
     * (#A4) Потоковое шифрование: plaintext не загружается в память целиком.
     * Формат на проводе идентичен [encryptFile] (AES-GCM, тег в конце).
     * @return nonce (IV) в base64 — кладётся в media-JSON.
     */
    fun encryptStream(input: java.io.InputStream, output: java.io.OutputStream, symKey: SymmetricKey): String {
        val keyBytes = b64d(symKey.keyB64)
        val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        random.nextBytes(iv)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
        javax.crypto.CipherOutputStream(output, cipher).use { cos ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                cos.write(buf, 0, n)
            }
        }
        return b64e(iv)
    }

    /**
     * (#A4) Дешифровка сырых байтов с сервера без base64-прохода
     * (раньше 50 МБ файла превращались в ~115 МБ временных строк/копий).
     */
    fun decryptBytes(encrypted: ByteArray, nonceB64: String, symKey: SymmetricKey): ByteArray {
        val keyBytes = b64d(symKey.keyB64)
        val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, b64d(nonceB64)))
        return cipher.doFinal(encrypted)
    }

    fun decryptFile(envelope: Envelope, symKey: SymmetricKey): ByteArray {
        return uniffi.sm_core.aesDecrypt(symKey.keyB64, envelope.nonceB64, envelope.ciphertextB64)
    }

    private fun b64e(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP)

    private fun b64d(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE)

    // Бэкап приватного ключа (PBKDF2 100k + AES-GCM, формат salt:iv:ct) — на ядре.
    // Совместимо со старым форматом: ядро терпит паддинг base64, и наоборот.
    fun encryptPrivateKey(privateKeyB64: String, password: String): String =
        uniffi.sm_core.encryptPrivateKey(privateKeyB64, password)

    fun decryptPrivateKey(encryptedPrivateKeyB64: String, password: String): String =
        uniffi.sm_core.decryptPrivateKey(encryptedPrivateKeyB64, password)
}