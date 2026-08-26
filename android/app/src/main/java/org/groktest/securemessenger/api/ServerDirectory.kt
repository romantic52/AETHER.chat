package org.groktest.securemessenger.api

import android.util.Base64
import org.groktest.securemessenger.data.ServerInspection
import org.groktest.securemessenger.data.ServerRegistry
import java.security.SecureRandom

/** Обнаружение и TOFU-сверка. Сеть и проверку Ed25519 выполняет общее Rust-ядро. */
class ServerDirectory(private val registry: ServerRegistry) {
    fun inspect(input: String, allowCleartext: Boolean = false): ServerInspection {
        val nonceBytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = Base64.encodeToString(
            nonceBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val info = uniffi.sm_core.discoverServer(input, nonce, allowCleartext)
        if (!info.endpointsMatchOrigin) {
            throw IllegalStateException("Адреса API сервера не совпадают с найденным адресом")
        }
        return registry.inspect(info)
    }
}
