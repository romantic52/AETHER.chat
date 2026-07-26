package aether.desktop.crypto

import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/** Тонкая JVM-обёртка над общим Rust-криптоядром (порт Android E2ECrypto). */
class E2ECrypto {
    private val random = SecureRandom()

    data class KeyPair(val privateB64: String, val publicB64: String)

    data class Envelope(
        val senderPubkeyB64: String,
        val nonceB64: String,
        val ciphertextB64: String,
    ) {
        fun toJson(): String = JSONObject()
            .put("sender_pubkey_b64", senderPubkeyB64)
            .put("nonce_b64", nonceB64)
            .put("ciphertext_b64", ciphertextB64)
            .toString()
    }

    fun generateKeyPair(): KeyPair {
        val pair = uniffi.sm_core.generateKeypair()
        return KeyPair(pair.privateB64, pair.publicB64)
    }

    fun encrypt(plaintext: String, sender: KeyPair, recipientPublicB64: String): Envelope {
        val json = uniffi.sm_core.sealDirect(
            plaintextJson = plaintext,
            recipientPubB64 = recipientPublicB64,
            senderPubB64 = sender.publicB64,
            senderPrivB64 = sender.privateB64,
        )
        val envelope = JSONObject(json)
        return Envelope(
            senderPubkeyB64 = envelope.getString("sender_pubkey_b64"),
            nonceB64 = envelope.getString("nonce_b64"),
            ciphertextB64 = envelope.getString("ciphertext_b64"),
        )
    }

    fun decrypt(envelope: Envelope, recipient: KeyPair): String {
        if (envelope.nonceB64 == "CHANNEL_NONCE") {
            return String(b64d(envelope.ciphertextB64), Charsets.UTF_8)
        }
        return uniffi.sm_core.openEnvelope(
            envelopeJson = envelope.toJson(),
            myPrivB64 = recipient.privateB64,
            groupKeyB64 = null,
        ).plaintext
    }

    class SymmetricKey(val keyB64: String)

    fun generateSymmetricKey(): SymmetricKey {
        val key = ByteArray(32)
        random.nextBytes(key)
        return SymmetricKey(b64e(key))
    }

    fun encryptFile(fileData: ByteArray, symKey: SymmetricKey): Envelope {
        val sealed = uniffi.sm_core.aesEncrypt(symKey.keyB64, fileData)
        return Envelope("SYM", sealed.nonceB64, b64e(sealed.ciphertext))
    }

    fun encryptStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        symKey: SymmetricKey,
    ): String {
        val secretKey = javax.crypto.spec.SecretKeySpec(b64d(symKey.keyB64), "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also(random::nextBytes)
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            secretKey,
            javax.crypto.spec.GCMParameterSpec(128, iv),
        )
        javax.crypto.CipherOutputStream(output, cipher).use { encrypted ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                encrypted.write(buffer, 0, count)
            }
        }
        return b64e(iv)
    }

    fun decryptBytes(encrypted: ByteArray, nonceB64: String, symKey: SymmetricKey): ByteArray {
        val secretKey = javax.crypto.spec.SecretKeySpec(b64d(symKey.keyB64), "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            secretKey,
            javax.crypto.spec.GCMParameterSpec(128, b64d(nonceB64)),
        )
        return cipher.doFinal(encrypted)
    }

    fun decryptFile(envelope: Envelope, symKey: SymmetricKey): ByteArray =
        uniffi.sm_core.aesDecrypt(symKey.keyB64, envelope.nonceB64, b64d(envelope.ciphertextB64))

    private fun b64e(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** Лояльный декодер: url-safe и стандартный алфавит, с паддингом и без. */
    private fun b64d(text: String): ByteArray {
        val trimmed = text.trim().trimEnd('=')
        return try {
            Base64.getUrlDecoder().decode(trimmed)
        } catch (_: IllegalArgumentException) {
            val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
            Base64.getDecoder().decode(padded)
        }
    }
}
