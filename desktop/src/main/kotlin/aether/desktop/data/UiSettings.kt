package aether.desktop.data

import java.io.File
import org.json.JSONObject

/**
 * Настройки оболочки (не секреты): тема, геометрия окна, звуки и уведомления.
 * Лежат обычным JSON рядом с зашифрованными файлами сессии — шифровать нечего,
 * зато читаемо и переживает переустановку.
 */
class UiSettings(private val file: File = File(DesktopPrefs.defaultDir(), "settings.json")) {

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

    private var json: JSONObject = load()

    private fun load(): JSONObject = runCatching {
        if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(json.optString("theme", "SYSTEM")) }
            .getOrDefault(ThemeMode.SYSTEM)
        set(value) {
            json.put("theme", value.name)
            persist()
        }

    var notificationsEnabled: Boolean
        get() = json.optBoolean("notifications", true)
        set(value) {
            json.put("notifications", value)
            persist()
        }

    var soundsEnabled: Boolean
        get() = json.optBoolean("sounds", true)
        set(value) {
            json.put("sounds", value)
            persist()
        }

    var closeToTray: Boolean
        get() = json.optBoolean("close_to_tray", true)
        set(value) {
            json.put("close_to_tray", value)
            persist()
        }

    fun windowBounds(): WindowBounds? {
        val w = json.optJSONObject("window") ?: return null
        val width = w.optInt("width", 0)
        val height = w.optInt("height", 0)
        if (width <= 200 || height <= 200) return null
        return WindowBounds(
            x = w.optInt("x", Int.MIN_VALUE),
            y = w.optInt("y", Int.MIN_VALUE),
            width = width,
            height = height,
            maximized = w.optBoolean("maximized", false),
        )
    }

    fun saveWindowBounds(bounds: WindowBounds) {
        json.put(
            "window",
            JSONObject()
                .put("x", bounds.x)
                .put("y", bounds.y)
                .put("width", bounds.width)
                .put("height", bounds.height)
                .put("maximized", bounds.maximized),
        )
        persist()
    }
}
