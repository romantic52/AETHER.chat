package aether.desktop.data

object ServerConfig {
    private const val PRODUCTION_BASE_URL = "https://144-31-181-10.nip.io"

    /** Адрес по умолчанию; AETHER_SERVER переопределяет его (локальный стенд, self-hosted). */
    val DEFAULT_BASE_URL: String =
        System.getenv("AETHER_SERVER")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: PRODUCTION_BASE_URL

    fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        return if (normalized.isBlank()) DEFAULT_BASE_URL else normalized
    }
}
