package org.groktest.securemessenger.api

/** Глобальный адрес сервера — устанавливается при логине, используется UI (аватарки и т.п.). */
object ServerConfig {
    var baseUrl: String = ""

    /** Зашифрованные медиа — требует Authorization (см. RelayApi.downloadFile). */
    fun fileUrl(fileId: String): String = "${baseUrl.trimEnd('/')}/download/$fileId"

    /** Аватарки — публичный неймспейс, отдаются без авторизации. */
    fun avatarUrl(fileId: String): String = "${baseUrl.trimEnd('/')}/avatars/$fileId"
}
