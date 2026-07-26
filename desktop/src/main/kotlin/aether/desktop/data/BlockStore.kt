package aether.desktop.data

import androidx.compose.runtime.mutableStateListOf
import java.io.File
import org.json.JSONArray

/**
 * Локальный чёрный список (порт Android BlockStore): входящие от заблокированных
 * отбрасываются и подтверждаются серверу. Хранится в %APPDATA%/Aether/blocked.json.
 */
object BlockStore {
    val blocked = mutableStateListOf<String>()
    private var file: File? = null

    fun init(baseDir: File) {
        val target = File(baseDir, "blocked.json")
        file = target
        blocked.clear()
        if (target.isFile) {
            runCatching {
                val arr = JSONArray(target.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf(String::isNotBlank)?.let { blocked.add(it.lowercase()) }
                }
            }
        }
    }

    fun isBlocked(peerId: String): Boolean = blocked.contains(peerId.lowercase())

    fun block(peerId: String) {
        val id = peerId.lowercase()
        if (!blocked.contains(id)) blocked.add(id)
        persist()
    }

    fun unblock(peerId: String) {
        blocked.remove(peerId.lowercase())
        persist()
    }

    fun toggle(peerId: String) {
        if (isBlocked(peerId)) unblock(peerId) else block(peerId)
    }

    private fun persist() {
        val target = file ?: return
        runCatching {
            target.writeText(JSONArray(blocked.toList()).toString(), Charsets.UTF_8)
        }
    }
}
