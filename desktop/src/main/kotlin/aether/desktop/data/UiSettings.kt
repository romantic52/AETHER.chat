package aether.desktop.data

import java.io.File
import kotlin.math.abs
import org.json.JSONObject

/**
 * Настройки оболочки (не секреты): тема, геометрия окна, звуки и уведомления.
 * Лежат обычным JSON рядом с зашифрованными файлами сессии — шифровать нечего,
 * зато читаемо и переживает переустановку.
 */
class UiSettings(private val file: File = File(DesktopPrefs.defaultDir(), "settings.json")) {

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

    companion object {
        /** Масштабы интерфейса, между которыми выбирает окно настроек. */
        val UI_SCALES = listOf(0.9f, 1.0f, 1.1f, 1.25f, 1.5f)
    }

    // Настройки правит и UI-поток (геометрия окна при закрытии), и IO-потоки
    // окна настроек, поэтому и JSONObject, и запись файла идут под одним замком.
    private val lock = Any()

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
        get() = synchronized(lock) {
            runCatching { ThemeMode.valueOf(json.optString("theme", "SYSTEM")) }
                .getOrDefault(ThemeMode.SYSTEM)
        }
        set(value) {
            synchronized(lock) {
                json.put("theme", value.name)
                persist()
            }
        }

    /**
     * Масштаб интерфейса. Значение из файла подтягивается к ближайшему из
     * UI_SCALES: правка руками или файл от будущей версии не должны схлопнуть окно.
     */
    var uiScale: Float
        get() = synchronized(lock) {
            val stored = json.optDouble("ui_scale", 1.0).toFloat()
            UI_SCALES.minByOrNull { abs(it - stored) } ?: 1.0f
        }
        set(value) {
            synchronized(lock) {
                json.put("ui_scale", value.toDouble())
                persist()
            }
        }

    var notificationsEnabled: Boolean
        get() = synchronized(lock) { json.optBoolean("notifications", true) }
        set(value) {
            synchronized(lock) {
                json.put("notifications", value)
                persist()
            }
        }

    /** Показывать ли текст сообщения в уведомлении (иначе только имя чата). */
    var notificationPreview: Boolean
        get() = synchronized(lock) { json.optBoolean("notification_preview", true) }
        set(value) {
            synchronized(lock) {
                json.put("notification_preview", value)
                persist()
            }
        }

    var soundsEnabled: Boolean
        get() = synchronized(lock) { json.optBoolean("sounds", true) }
        set(value) {
            synchronized(lock) {
                json.put("sounds", value)
                persist()
            }
        }

    var closeToTray: Boolean
        get() = synchronized(lock) { json.optBoolean("close_to_tray", true) }
        set(value) {
            synchronized(lock) {
                json.put("close_to_tray", value)
                persist()
            }
        }

    /** Отправка по Ctrl+Enter вместо Enter (Enter тогда переносит строку). */
    var sendOnCtrlEnter: Boolean
        get() = synchronized(lock) { json.optBoolean("send_ctrl_enter", false) }
        set(value) {
            synchronized(lock) {
                json.put("send_ctrl_enter", value)
                persist()
            }
        }

    fun windowBounds(): WindowBounds? = synchronized(lock) {
        val w = json.optJSONObject("window") ?: return null
        val width = w.optInt("width", 0)
        val height = w.optInt("height", 0)
        if (width <= 200 || height <= 200) return null
        WindowBounds(
            x = w.optInt("x", Int.MIN_VALUE),
            y = w.optInt("y", Int.MIN_VALUE),
            width = width,
            height = height,
            maximized = w.optBoolean("maximized", false),
        )
    }

    fun saveWindowBounds(bounds: WindowBounds) {
        synchronized(lock) {
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
}
