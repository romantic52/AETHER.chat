package org.groktest.securemessenger.pairing

import android.net.Uri

/**
 * Содержимое QR привязки:
 * aether://pair?v=1&pid=<pairing_id>&sec=<secret>&pub=<eph_pub_b64>&host=<server>
 */
data class PairingLink(
    val pairingId: String,
    val secret: String,
    val ephPubB64: String,
    val host: String,
) {
    companion object {
        fun parse(raw: String): PairingLink? {
            val value = raw.trim()
            if (!value.startsWith("aether://pair", ignoreCase = true)) return null
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
            if (uri.getQueryParameter("v") != "1") return null
            val pid = uri.getQueryParameter("pid")?.takeIf(String::isNotBlank) ?: return null
            val sec = uri.getQueryParameter("sec")?.takeIf(String::isNotBlank) ?: return null
            val pub = uri.getQueryParameter("pub")?.takeIf(String::isNotBlank) ?: return null
            val host = uri.getQueryParameter("host")?.takeIf(String::isNotBlank) ?: return null
            if (!host.startsWith("http://") && !host.startsWith("https://")) return null
            return PairingLink(pid, sec, pub, host.trimEnd('/'))
        }
    }
}
