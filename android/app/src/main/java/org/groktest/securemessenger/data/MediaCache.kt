package org.groktest.securemessenger.data

import android.content.Context
import coil.Coil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@OptIn(coil.annotation.ExperimentalCoilApi::class)
object MediaCache {
    private const val MEDIA_DIR = "aether_media_cache"
    private const val OUTBOX_DIR = "outbox_media"

    fun mediaDir(context: Context): File = File(context.cacheDir, MEDIA_DIR).apply { mkdirs() }

    fun mediaDir(cacheRoot: File): File = File(cacheRoot, MEDIA_DIR).apply { mkdirs() }

    fun fileFor(cacheRoot: File, key: String): File = File(mediaDir(cacheRoot), sha256(key))

    /**
     * Папка НЕОТПРАВЛЕННЫХ записей — во внутреннем filesDir (сосед cacheDir в
     * приватной директории приложения), а не в кеше: её не трогают ни
     * «Очистить кеш» ([clearAll] чистит только aether_media_cache), ни
     * авто-очистка cacheDir системой при нехватке места.
     */
    fun outboxDir(cacheRoot: File): File =
        File(File(cacheRoot.parentFile ?: cacheRoot, "files"), OUTBOX_DIR).apply { mkdirs() }

    fun outboxFileFor(cacheRoot: File, key: String): File = File(outboxDir(cacheRoot), sha256(key))

    suspend fun clearAll(context: Context): Long = withContext(Dispatchers.IO) {
        val before = cacheSize(context)
        mediaDir(context).deleteRecursively()
        mediaDir(context).mkdirs()
        runCatching { Coil.imageLoader(context).memoryCache?.clear() }
        runCatching { Coil.imageLoader(context).diskCache?.clear() }
        before
    }

    suspend fun cacheSize(context: Context): Long = withContext(Dispatchers.IO) {
        directorySize(mediaDir(context)) + runCatching {
            Coil.imageLoader(context).diskCache?.size ?: 0L
        }.getOrDefault(0L)
    }

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
