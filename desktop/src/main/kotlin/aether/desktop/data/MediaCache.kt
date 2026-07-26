package aether.desktop.data

import java.io.File
import java.security.MessageDigest

/** Кеш расшифрованных медиа (порт Android MediaCache, без Coil). */
object MediaCache {
    private const val MEDIA_DIR = "aether_media_cache"
    private const val OUTBOX_DIR = "outbox_media"

    fun mediaDir(cacheRoot: File): File = File(cacheRoot, MEDIA_DIR).apply { mkdirs() }

    fun fileFor(cacheRoot: File, key: String): File = File(mediaDir(cacheRoot), sha256(key))

    /** Неотправленные записи: отдельная папка, не задевается очисткой кеша. */
    fun outboxDir(cacheRoot: File): File = File(cacheRoot, OUTBOX_DIR).apply { mkdirs() }

    fun outboxFileFor(cacheRoot: File, key: String): File = File(outboxDir(cacheRoot), sha256(key))

    fun clearAll(cacheRoot: File): Long {
        val before = cacheSize(cacheRoot)
        mediaDir(cacheRoot).deleteRecursively()
        mediaDir(cacheRoot).mkdirs()
        return before
    }

    fun cacheSize(cacheRoot: File): Long = directorySize(mediaDir(cacheRoot))

    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> "0 Б"
        bytes < 1024L -> "$bytes Б"
        bytes < 1024L * 1024L -> String.format("%.0f КБ", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
