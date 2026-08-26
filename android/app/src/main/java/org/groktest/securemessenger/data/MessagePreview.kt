package org.groktest.securemessenger.data

import org.json.JSONObject

/** Human-readable one-line preview for chat and contact lists. */
fun messagePreview(raw: String?, emptyFallback: String = "Нет сообщений"): String {
    if (raw.isNullOrBlank()) return emptyFallback
    if (!raw.trimStart().startsWith("{")) return raw

    return try {
        val payload = JSONObject(raw)
        if (payload.optString("type") != "media") return raw
        payload.optString("caption").takeIf(String::isNotBlank) ?: when (payload.optString("kind")) {
            "image" -> "Фото"
            "video" -> "Видео"
            "voice" -> "Голосовое сообщение"
            "video_msg", "video_note", "circle" -> "Видеосообщение"
            "file", "document" -> payload.optString("file_name").takeIf(String::isNotBlank) ?: "Файл"
            else -> "Вложение"
        }
    } catch (_: Exception) {
        raw
    }
}
