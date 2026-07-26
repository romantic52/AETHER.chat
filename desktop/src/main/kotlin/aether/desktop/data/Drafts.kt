package aether.desktop.data

import java.io.File
import org.json.JSONObject

/**
 * Недописанные сообщения по чатам. В Telegram текст остаётся в поле при переходе
 * в другой чат и переживает перезапуск, у нас поле обнулялось при каждой смене.
 *
 * Черновики не шифруются: это то же самое, что пользователь видит на экране, а
 * ключ базы под DPAPI нужен для истории, а не для полуфразы в поле ввода.
 */
object Drafts {

    private val file: File by lazy { File(DesktopPrefs.defaultDir(), "drafts.json") }
    private val cache: MutableMap<String, String> by lazy { read() }

    fun get(peerId: String): String = cache[key(peerId)].orEmpty()

    fun set(peerId: String, text: String) {
        val trimmed = text.trim()
        val previous = cache[key(peerId)].orEmpty()
        if (trimmed == previous) return
        if (trimmed.isEmpty()) cache.remove(key(peerId)) else cache[key(peerId)] = text
        write()
    }

    fun clear(peerId: String) = set(peerId, "")

    private fun key(peerId: String) = peerId.trim().lowercase()

    private fun read(): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            json.keys().asSequence().associateWithTo(mutableMapOf()) { json.optString(it) }
        }.getOrElse { mutableMapOf() }
    }

    private fun write() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JSONObject(cache as Map<*, *>).toString(), Charsets.UTF_8)
        }
    }
}
