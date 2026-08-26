package org.groktest.securemessenger.api

/** Глобальный адрес сервера — устанавливается при логине, используется UI (аватарки и т.п.). */
object ServerConfig {
    const val DEFAULT_BASE_URL = "https://144-31-181-10.nip.io"
    private const val RETIRED_BASE_URL = "https://178-236-243-36.sslip.io:8181"

    var baseUrl: String = DEFAULT_BASE_URL

    fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        return when {
            normalized.isBlank() -> DEFAULT_BASE_URL
            normalized.equals(RETIRED_BASE_URL, ignoreCase = true) -> DEFAULT_BASE_URL
            normalized.equals("https://your-server.example.com", ignoreCase = true) -> DEFAULT_BASE_URL
            else -> normalized
        }
    }

    /** Зашифрованные медиа — требует Authorization (см. RelayApi.downloadFile). */
    fun fileUrl(fileId: String): String = "${baseUrl.trimEnd('/')}/download/$fileId"

    /** Аватарки — публичный неймспейс, отдаются без авторизации. */
    fun avatarUrl(fileId: String): String = "${baseUrl.trimEnd('/')}/avatars/$fileId"
}
