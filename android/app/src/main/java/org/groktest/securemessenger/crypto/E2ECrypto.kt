package org.groktest.securemessenger.crypto

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom

/** Thin Android adapter over the shared Rust cryptography core. */
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

        fun toJson(): String = JSONObject(toMap()).toString()
    }

    fun generateKeyPair(): KeyPair {
        val pair = uniffi.sm_core.generateKeypair()
        return KeyPair(pair.privateB64, pair.publicB64)
    }

    fun encrypt(plaintext: String, sender: KeyPair, recipientPublicB64: String): Envelope {
        require(recipientPublicB64 != "CHANNEL_PUBLIC_KEY") {
            "CHANNEL_PUBLIC_KEY is no longer supported; use a group key"
        }
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
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun b64d(text: String): ByteArray = Base64.decode(text, Base64.URL_SAFE)

    fun encryptPrivateKey(privateKeyB64: String, password: String): String =
        uniffi.sm_core.encryptPrivateKey(privateKeyB64, password)

    fun decryptPrivateKey(encryptedPrivateKeyB64: String, password: String): String =
        uniffi.sm_core.decryptPrivateKey(encryptedPrivateKeyB64, password)
}
