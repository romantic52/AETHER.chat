package org.groktest.securemessenger.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RelayApi(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Сетевой клиент ядра (Rust ureq+rustls). JSON-эндпоинты постепенно
    // переносятся на него; бинарные upload/download пока на OkHttp.
    private val core = uniffi.sm_core.ApiClient(base)

    var token: String? = null
        set(value) {
            field = value
            if (value != null) core.setToken(value)
        }

    fun register(userId: String, publicKeyB64: String, encryptedPrivateKeyB64: String, password: String) {
        // Через ядро (Rust ureq+rustls)
        try {
            core.register(userId, publicKeyB64, encryptedPrivateKeyB64, password)
        } catch (e: Exception) {
            throw IllegalStateException("Регистрация: ${apiErrorDetail(e)}")
        }
    }

    class LoginResult(val token: String, val encryptedPrivateKeyB64: String)

    fun login(userId: String, password: String): LoginResult {
        // Через ядро: ядро само сохраняет токен внутри ApiClient, дублируем в Kotlin-поле
        try {
            val r = core.login(userId, password)
            token = r.token  // setter синхронизирует и наш токен, и токен ядра
            return LoginResult(r.token, r.encryptedPrivateKeyB64)
        } catch (e: Exception) {
            throw IllegalStateException("Вход: ${apiErrorDetail(e)}")
        }
    }

    /**
     * Достаёт человекочитаемую причину из ошибки ядра. Тело HTTP-ошибки сервера —
     * JSON `{"detail": "..."}`; ApiException.Http.msg содержит это тело целиком.
     */
    private fun apiErrorDetail(e: Throwable): String {
        if (e is uniffi.sm_core.ApiException.Http) {
            return try { JSONObject(e.msg).getString("detail") } catch (x: Exception) { "code ${e.code}" }
        }
        if (e is uniffi.sm_core.ApiException.Network) return "нет связи"
        return e.message ?: "ошибка"
    }

    /** Проверка валидности токена сессии (для авто-входа без пароля). */
    /** (#A4) Отзыв токена на сервере — logout перестаёт быть «только локальным». */
    fun logout() {
        // Через ядро (best effort): ядро отзывает токен на сервере и чистит свой токен
        try { core.logout() } catch (e: Exception) { /* best effort */ }
    }

    fun heartbeat(): Boolean = try {
        core.heartbeat()  // через ядро
        true
    } catch (e: Exception) {
        false
    }

    fun getPublicKey(userId: String): String = try {
        core.getPublicKey(userId)  // через ядро
    } catch (e: Exception) {
        throw IllegalStateException("Публичный ключ не найден: ${e.message}")
    }

    /**
     * (#A2) clientMsgId — идемпотентность: при ретрае после обрыва сети сервер
     * не создаёт дубликат, а отвечает тем же message_id (random_id-паттерн Telegram).
     */
    fun sendMessage(senderId: String, recipientId: String, envelope: Map<String, String>, clientMsgId: String? = null): String {
        // Через ядро. HttpError несёт код: outbox по нему отличает постоянную ошибку
        // (4xx — не ретраим) от временной (5xx/сеть — ретраим).
        try {
            return core.sendMessage(senderId, recipientId, HashMap(envelope), clientMsgId)
        } catch (e: uniffi.sm_core.ApiException.Http) {
            val detail = try { JSONObject(e.msg).getString("detail") } catch (x: Exception) { e.msg.take(200) }
            throw HttpError(e.code.toInt(), "Отправка [$senderId -> $recipientId]: $detail (${e.code})")
        }
        // ApiException.Network/Parse пробрасываются как есть → outbox считает их временными
    }

    /** HTTP-ошибка с кодом ответа (для классификации retry в outbox). */
    class HttpError(val code: Int, message: String) : RuntimeException(message)

    fun fetchInbox(userId: String): List<InboxMessage> {
        // Через ядро: разбор JSON и конвертов внутри ядра
        return core.fetchInbox(userId).map { m ->
            InboxMessage(
                id = m.id,
                senderId = m.senderId,
                recipientId = m.recipientId,
                // (#A3) Групповые конверты симметричные — pubkey отправителя нет
                senderPubkeyB64 = m.senderPubkeyB64,
                nonceB64 = m.nonceB64,
                ciphertextB64 = m.ciphertextB64,
                createdAtMs = parseUtcIso(m.createdAt),
                isGroupEnvelope = m.isGroupEnvelope,
            )
        }
    }

    /**
     * (#A1) Подтверждение приёма: сообщения сохранены в локальной БД.
     * Пока ACK не отправлен, сервер продолжает отдавать их в inbox —
     * так сообщения не теряются при краше между fetch и записью.
     */
    fun ackMessages(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        // Через ядро
        try {
            core.ackMessages(messageIds)
        } catch (e: Exception) {
            throw IllegalStateException("ACK сообщений: ${apiErrorDetail(e)}")
        }
    }

    /**
     * (#A3) Мои группы/каналы вместе с МОЕЙ копией группового ключа.
     * encryptedKeyB64 — box-конверт (JSON) для новых групп или
     * легаси raw-base64 для старых каналов (не E2E).
     */
    fun getMyGroups(): List<GroupInfo> {
        // Через ядро: список групп + моя копия группового ключа
        return core.getMyGroups().map { g ->
            GroupInfo(
                id = g.id,
                name = g.name,
                isChannel = g.isChannel,
                encryptedKeyB64 = g.encryptedKeyB64,
                role = g.role,
                linkedGroupId = g.linkedGroupId,
                ownerId = g.ownerId,
                description = g.description,
            )
        }
    }

    data class GroupInfo(
        val id: String,
        val name: String,
        val isChannel: Boolean,
        val encryptedKeyB64: String,
        val role: String,
        /** (#A6) Группа обсуждений канала (комментарии) */
        val linkedGroupId: String? = null,
        val ownerId: String = "",
        val description: String = "",
    )

    data class GroupMember(
        val userId: String,
        val username: String,
        val displayName: String,
        val avatarFileId: String?,
        val role: String,
    )

    /** Участники группы/канала (через ядро). */
    fun getGroupMembers(groupId: String): List<GroupMember> = try {
        core.getGroupMembers(groupId).map { m ->
            GroupMember(m.userId, m.username, m.displayName, m.avatarFileId, m.role)
        }
    } catch (e: Exception) {
        throw IllegalStateException("Участники: ${apiErrorDetail(e)}")
    }

    fun removeGroupMember(groupId: String, userId: String) = try {
        core.removeGroupMember(groupId, userId)
    } catch (e: Exception) {
        throw IllegalStateException("Удаление участника: ${apiErrorDetail(e)}")
    }

    fun updateGroup(groupId: String, name: String?, description: String?) = try {
        core.updateGroup(groupId, name, description)
    } catch (e: Exception) {
        throw IllegalStateException("Изменение группы: ${apiErrorDetail(e)}")
    }

    fun leaveGroup(groupId: String) = try {
        core.leaveGroup(groupId)
    } catch (e: Exception) {
        throw IllegalStateException("Выход из группы: ${apiErrorDetail(e)}")
    }

    fun deleteGroup(groupId: String) = try {
        core.deleteGroup(groupId)
    } catch (e: Exception) {
        throw IllegalStateException("Удаление группы: ${apiErrorDetail(e)}")
    }

    /**
     * (#A3) Добавление участника с ЕГО копией группового ключа:
     * админ заворачивает ключ box'ом на публичный ключ участника.
     */
    fun addGroupMember(groupId: String, userId: String, encryptedKeyB64: String, role: String = "member") {
        // Через ядро
        try {
            core.addGroupMember(groupId, userId, encryptedKeyB64, role)
        } catch (e: Exception) {
            throw IllegalStateException("Добавление участника: ${apiErrorDetail(e)}")
        }
    }

    fun createGroup(groupId: String, name: String, description: String, isChannel: Boolean, encryptedKeyB64: String, linkedGroupId: String? = null): String {
        // Через ядро
        try {
            return core.createGroup(groupId, name, description, isChannel, encryptedKeyB64, linkedGroupId)
        } catch (e: Exception) {
            throw IllegalStateException("Создание группы: ${apiErrorDetail(e)}")
        }
    }

    data class InboxMessage(
        val id: String,
        val senderId: String,
        val recipientId: String,
        val senderPubkeyB64: String,
        val nonceB64: String,
        val ciphertextB64: String,
        /** Серверное время создания (UTC, мс); 0 — не удалось распарсить. */
        val createdAtMs: Long = 0L,
        /** (#A3) true — конверт зашифрован общим ключом группы (AES-GCM), не box. */
        val isGroupEnvelope: Boolean = false,
    )

    companion object {
        /**
         * Парсит ISO-8601 UTC ("2026-06-12T12:34:56.789012+00:00") в epoch-мс.
         * Без java.time (minSdk 24, без desugaring). Сервер всегда отдаёт UTC.
         */
        fun parseUtcIso(iso: String?): Long {
            if (iso.isNullOrBlank()) return 0L
            return try {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val base = fmt.parse(iso.substring(0, 19))?.time ?: return 0L
                // Доли секунды (микросекунды Python) → мс
                val millis = Regex("\\.(\\d{1,6})").find(iso)
                    ?.groupValues?.get(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
                base + millis
            } catch (e: Exception) {
                0L
            }
        }
    }

    fun searchUsers(query: String): List<UserSearchResult> {
        // Через ядро: URL-кодирование и разбор users+groups внутри ядра
        return try {
            core.searchUsers(query).map { r ->
                UserSearchResult(
                    userId = r.userId,
                    username = r.username,
                    displayName = r.displayName,
                    avatarFileId = r.avatarFileId,
                    isGroup = r.isGroup
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class UserSearchResult(
        val userId: String,
        val username: String,
        val displayName: String,
        val avatarFileId: String?,
        val isGroup: Boolean = false
    )
    
    fun updateProfile(username: String?, displayName: String?, avatarFileId: String?, bio: String?) {
        // Через ядро: пустая строка → JSON null (очистка поля), логика внутри ядра
        try {
            core.updateProfile(username, displayName, avatarFileId, bio)
        } catch (e: Exception) {
            throw IllegalStateException("Ошибка обновления профиля: ${apiErrorDetail(e)}")
        }
    }
    
    data class UserProfile(
        val userId: String,
        val username: String,
        val displayName: String,
        val avatarFileId: String?,
        val bio: String?,
        val lastActive: String?
    )
    
    fun getUserProfile(userId: String): UserProfile {
        // Через ядро (Rust): JSON-null корректно превращается в ""/null (без ловушки optString)
        try {
            val p = core.getUserProfile(userId)
            return UserProfile(
                userId = p.userId,
                username = p.username,
                displayName = p.displayName,
                avatarFileId = p.avatarFileId,
                bio = p.bio,
                lastActive = p.lastActive
            )
        } catch (e: Exception) {
            throw IllegalStateException("Профиль не найден: ${e.message}")
        }
    }
    fun uploadFile(fileBytes: ByteArray): String = try {
        // Через ядро (multipart собирается в Rust)
        core.upload("/upload", "upload.bin", "application/octet-stream", fileBytes)
    } catch (e: Exception) {
        throw IllegalStateException("Upload failed: ${apiErrorDetail(e)}")
    }

    /** (#A4) Загрузка из файла стримингом — без копии содержимого в памяти. */
    fun uploadFile(file: java.io.File): String {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart(
                "file", "upload.bin",
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()
        val builder = Request.Builder()
            .url("$base/upload")
            .post(body)
        token?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Upload failed: ${resp.code}")
            return JSONObject(resp.body!!.string()).getString("file_id")
        }
    }

    /** Загрузка аватарки в публичный неймспейс /avatars (отдаётся без авторизации). */
    fun uploadAvatar(fileBytes: ByteArray): String = try {
        // Через ядро
        core.upload("/avatars", "avatar.jpg", "image/jpeg", fileBytes)
    } catch (e: Exception) {
        throw IllegalStateException("Avatar upload failed: ${apiErrorDetail(e)}")
    }

    fun downloadFile(fileId: String): ByteArray = try {
        // Через ядро
        core.download(fileId)
    } catch (e: Exception) {
        throw IllegalStateException("Download failed: ${apiErrorDetail(e)}")
    }
}