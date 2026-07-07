package org.groktest.securemessenger.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONArray
import org.json.JSONObject

data class ChatFolder(
    val id: String,
    val name: String,
    val includeKeywords: List<String> = emptyList(), // e.g. "channel_", "@"
    val excludeKeywords: List<String> = emptyList()
)

class ChatFolderSettings(context: Context) {
    private val prefs = context.getSharedPreferences("chat_folders", Context.MODE_PRIVATE)

    val folders = mutableStateListOf<ChatFolder>()

    init {
        loadFolders()
    }

    private fun loadFolders() {
        folders.clear()
        val jsonString = prefs.getString("folders_json", null)
        if (jsonString != null) {
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    val name = obj.getString("name")
                    val incArray = obj.optJSONArray("includeKeywords")
                    val excArray = obj.optJSONArray("excludeKeywords")
                    
                    val includes = mutableListOf<String>()
                    if (incArray != null) {
                        for (j in 0 until incArray.length()) includes.add(incArray.getString(j))
                    }
                    
                    val excludes = mutableListOf<String>()
                    if (excArray != null) {
                        for (j in 0 until excArray.length()) excludes.add(excArray.getString(j))
                    }
                    
                    folders.add(ChatFolder(id, name, includes, excludes))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                setupDefaultFolders()
            }
        } else {
            setupDefaultFolders()
        }
    }

    private fun setupDefaultFolders() {
        folders.add(ChatFolder("1", "Личные", excludeKeywords = listOf("channel_", "@")))
        folders.add(ChatFolder("2", "Каналы", includeKeywords = listOf("channel_", "@")))
        saveFolders()
    }

    fun saveFolders() {
        val array = JSONArray()
        folders.forEach { f ->
            val obj = JSONObject()
            obj.put("id", f.id)
            obj.put("name", f.name)
            obj.put("includeKeywords", JSONArray(f.includeKeywords))
            obj.put("excludeKeywords", JSONArray(f.excludeKeywords))
            array.put(obj)
        }
        prefs.edit().putString("folders_json", array.toString()).apply()
    }

    fun addFolder(name: String, include: List<String>, exclude: List<String>) {
        val f = ChatFolder(java.util.UUID.randomUUID().toString(), name, include, exclude)
        folders.add(f)
        saveFolders()
    }
    
    fun removeFolder(id: String) {
        folders.removeAll { it.id == id }
        saveFolders()
    }
}

val LocalChatFolderSettings = staticCompositionLocalOf<ChatFolderSettings> {
    error("No ChatFolderSettings provided")
}
