package aether.desktop.data

object ServerConfig {
    const val DEFAULT_BASE_URL = "https://144-31-181-10.nip.io"

    fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        return if (normalized.isBlank()) DEFAULT_BASE_URL else normalized
    }
}
