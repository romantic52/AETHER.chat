package aether.desktop.pairing

import aether.desktop.data.ServerConfig
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.json.JSONObject

/**
 * Клиентская сторона QR-привязки (новое устройство).
 *
 * Ключевая часть: аккаунтный приватник приезжает в bundle, завёрнутом
 * crypto_box на ЭФЕМЕРНЫЙ паблик этого устройства. Приватник эфемерной пары
 * не покидает процесс, сервер видит только шифртекст.
 */
class PairingClient(server: String) {
    private val base = ServerConfig.normalizeBaseUrl(server)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    data class Started(val pairingId: String, val secret: String, val qrPayload: String, val expiresIn: Int)

    data class Approved(
        val userId: String,
        val deviceId: String,
        val sessionToken: String,
        val encryptedBundleB64: String,
    )

    /** Аккаунтные ключи из расшифрованного bundle. */
    data class Bundle(val publicB64: String, val privateB64: String)

    /** Эфемерная пара этого устройства: живёт только пока показан QR. */
    private var ephemeral: uniffi.sm_core.Keypair? = null

    fun start(): Started {
        val kp = uniffi.sm_core.generateKeypair()
        ephemeral = kp
        val response = post("/pairing/start", JSONObject().put("eph_pub_b64", kp.publicB64))
        val pairingId = response.getString("pairing_id")
        val secret = response.getString("pairing_secret")
        val payload = buildString {
            append("aether://pair?v=1")
            append("&pid=").append(enc(pairingId))
            append("&sec=").append(enc(secret))
            append("&pub=").append(enc(kp.publicB64))
            append("&host=").append(enc(base))
        }
        return Started(pairingId, secret, payload, response.optInt("expires_in", 120))
    }

    /** Один long-poll (сервер держит до 25 с). null — ещё pending. */
    fun poll(started: Started): Approved? {
        val url = "$base/pairing/${enc(started.pairingId)}/status?sec=${enc(started.secret)}"
        val response = get(url)
        return when (response.optString("status")) {
            "approved" -> Approved(
                userId = response.getString("user_id"),
                deviceId = response.getString("device_id"),
                sessionToken = response.getString("session_token"),
                encryptedBundleB64 = response.getString("encrypted_bundle_b64"),
            )
            "expired" -> throw PairingExpired()
            "claimed" -> throw IllegalStateException("Этот код уже использован")
            else -> null
        }
    }

    /** Расшифровка bundle эфемерным приватником этого устройства. */
    fun openBundle(approved: Approved): Bundle {
        val kp = ephemeral ?: throw IllegalStateException("Нет эфемерной пары — начните заново")
        val envelopeJson = String(
            java.util.Base64.getUrlDecoder().decode(approved.encryptedBundleB64.trimEnd('=')),
            Charsets.UTF_8,
        )
        val opened = uniffi.sm_core.openEnvelope(envelopeJson, kp.privateB64, null)
        val payload = JSONObject(opened.plaintext)
        return Bundle(
            publicB64 = payload.getString("public_b64"),
            privateB64 = payload.getString("private_b64"),
        )
    }

    fun forget() {
        ephemeral = null
    }

    class PairingExpired : Exception("Код истёк — обновите QR")

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun post(path: String, body: JSONObject): JSONObject {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return send(request)
    }

    private fun get(url: String): JSONObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(40))
            .GET()
            .build()
        return send(request)
    }

    private fun send(request: HttpRequest): JSONObject {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val raw = response.body().orEmpty()
        if (response.statusCode() !in 200..299) {
            val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull().orEmpty()
            if (response.statusCode() == 410 || detail == "pairing_expired") throw PairingExpired()
            throw IllegalStateException(detail.ifBlank { "Ошибка сервера (${response.statusCode()})" })
        }
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }
}
