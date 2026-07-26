package aether.desktop.api

import java.io.ByteArrayInputStream
import java.io.File
import java.io.SequenceInputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject

/** JVM-адаптер над общим Rust relay-клиентом (порт Android RelayApi, без OkHttp). */
class RelayApi(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val core = uniffi.sm_core.ApiClient(base)

    var token: String? = null
        set(value) {
            field = value
            value?.let(core::setToken)
        }

    private fun restoreSession(userId: String) {
        token?.let { core.setSession(it, userId) }
    }

    class LoginResult(val token: String, val encryptedPrivateKeyB64: String)

    class TotpRequired(val invalid: Boolean) :
        RuntimeException(if (invalid) "Неверный код 2FA" else "Нужен код 2FA")

    class HttpError(val code: Int, message: String) : RuntimeException(message)

    fun register(
        userId: String,
        publicKeyB64: String,
        encryptedPrivateKeyB64: String,
        password: String,
    ): LoginResult {
        try {
            val result = core.register(userId, password, publicKeyB64, encryptedPrivateKeyB64)
            token = result.token
            return LoginResult(result.token, result.encryptedPrivateKeyB64)
        } catch (e: Exception) {
            throw IllegalStateException("Регистрация: ${apiErrorDetail(e)}")
        }
    }

    fun login(userId: String, password: String, totpCode: String? = null): LoginResult {
        try {
            val result = core.loginTotp(userId, password, totpCode)
            token = result.token
            return LoginResult(result.token, result.encryptedPrivateKeyB64)
        } catch (e: Exception) {
            when (apiErrorDetail(e)) {
                "totp_required" -> throw TotpRequired(invalid = false)
                "totp_invalid" -> throw TotpRequired(invalid = true)
            }
            throw IllegalStateException("Вход: ${apiErrorDetail(e)}")
        }
    }

    private fun apiErrorDetail(error: Throwable): String = when (error) {
        is uniffi.sm_core.CoreException.Api -> {
            try {
                JSONObject(error.msg).optString("detail").ifBlank { "код ${error.status}" }
            } catch (_: Exception) {
                error.msg.ifBlank { "код ${error.status}" }
            }
        }
        is uniffi.sm_core.CoreException.Network -> "нет связи"
        is uniffi.sm_core.CoreException.BadInput -> error.msg
        is uniffi.sm_core.CoreException.Crypto -> error.msg
        is uniffi.sm_core.CoreException.Store -> error.msg
        is uniffi.sm_core.CoreException.Ws -> error.msg
        else -> error.message ?: "неизвестная ошибка"
    }

    private fun asHttpError(action: String, error: Exception): RuntimeException =
        if (error is uniffi.sm_core.CoreException.Api) {
            HttpError(error.status.toInt(), "$action: ${apiErrorDetail(error)}")
        } else {
            IllegalStateException("$action: ${apiErrorDetail(error)}", error)
        }

    private fun requestJson(request: HttpRequest): JSONObject {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val raw = response.body().orEmpty()
        if (response.statusCode() !in 200..299) {
            val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull().orEmpty()
            throw HttpError(response.statusCode(), detail.ifBlank { "Ошибка сервера (${response.statusCode()})" })
        }
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun authorizedRequest(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url)).apply {
            token?.let { header("Authorization", "Bearer $it") }
            timeout(Duration.ofSeconds(60))
        }

    fun logout() {
        try {
            core.logout()
        } catch (_: Exception) {
        } finally {
            token = null
        }
    }

    fun heartbeat(): Boolean = try {
        core.heartbeat()
        true
    } catch (_: Exception) {
        false
    }

    fun getPublicKey(userId: String): String = try {
        core.getPublicKey(userId)
    } catch (e: Exception) {
        throw IllegalStateException("Публичный ключ не найден: ${apiErrorDetail(e)}")
    }

    fun sendMessage(
        senderId: String,
        recipientId: String,
        envelope: Map<String, Any>,
        clientMsgId: String? = null,
    ): String {
        restoreSession(senderId)
        val envelopeJson = JSONObject().apply {
            envelope.forEach { (key, value) -> put(key, value) }
        }.toString()
        try {
            return core.sendMessage(recipientId, envelopeJson, clientMsgId)
        } catch (e: uniffi.sm_core.CoreException.Api) {
            val detail = try {
                JSONObject(e.msg).optString("detail").ifBlank { e.msg.take(200) }
            } catch (_: Exception) {
                e.msg.take(200)
            }
            throw HttpError(e.status.toInt(), "Отправка [$senderId -> $recipientId]: $detail")
        }
    }

    // --- Multi-device: device-aware варианты. ---

    fun listDevices(userId: String, peerId: String): List<uniffi.sm_core.DeviceInfo> {
        restoreSession(userId)
        try {
            return core.listDevices(peerId.lowercase())
        } catch (e: Exception) {
            throw asHttpError("Список устройств", e)
        }
    }

    fun uploadOlmKeysDevice(userId: String, identityKeyB64: String, oneTimeKeysJson: String, deviceId: String) {
        restoreSession(userId)
        try {
            core.uploadKeysDevice(identityKeyB64, oneTimeKeysJson, deviceId)
        } catch (e: Exception) {
            throw asHttpError("Публикация защищённых ключей", e)
        }
    }

    fun olmKeysCountDevice(userId: String, deviceId: String): UInt {
        restoreSession(userId)
        try {
            return core.keysCountDevice(deviceId)
        } catch (e: Exception) {
            throw asHttpError("Проверка защищённых ключей", e)
        }
    }

    fun claimOlmKeysDevice(userId: String, peerId: String, deviceId: String): uniffi.sm_core.PrekeyBundle {
        restoreSession(userId)
        try {
            return core.claimKeysDevice(peerId.lowercase(), deviceId)
        } catch (e: Exception) {
            throw asHttpError("Собеседник не поддерживает защищённое шифрование", e)
        }
    }

    fun sendMessageDevice(
        senderId: String,
        recipientId: String,
        envelope: Map<String, Any>,
        clientMsgId: String?,
        targetDeviceId: String,
    ): String {
        restoreSession(senderId)
        val envelopeJson = JSONObject().apply {
            envelope.forEach { (key, value) -> put(key, value) }
        }.toString()
        try {
            return core.sendMessageDevice(recipientId, envelopeJson, clientMsgId, targetDeviceId)
        } catch (e: uniffi.sm_core.CoreException.Api) {
            val detail = try {
                JSONObject(e.msg).optString("detail").ifBlank { e.msg.take(200) }
            } catch (_: Exception) {
                e.msg.take(200)
            }
            throw HttpError(e.status.toInt(), "Отправка [$senderId -> $recipientId]: $detail")
        }
    }

    data class InboxMessage(
        val id: String,
        val senderId: String,
        val recipientId: String,
        val envelopeJson: String,
        val senderPubkeyB64: String,
        val nonceB64: String,
        val ciphertextB64: String,
        val createdAtMs: Long = 0L,
        val isGroupEnvelope: Boolean = false,
        val isRatchetEnvelope: Boolean = false,
    )

    fun fetchInboxDevice(userId: String, deviceId: String): List<InboxMessage> {
        restoreSession(userId)
        return core.fetchInboxDevice(null, deviceId).map { item ->
            val envelope = JSONObject(item.envelope)
            val isGroup = envelope.optBoolean("is_group", false) ||
                envelope.optString("is_group") == "1"
            InboxMessage(
                id = item.id,
                senderId = item.senderId,
                recipientId = item.recipientId,
                envelopeJson = item.envelope,
                senderPubkeyB64 = envelope.optString("sender_pubkey_b64"),
                nonceB64 = envelope.optString("nonce_b64"),
                ciphertextB64 = envelope.optString("ciphertext_b64"),
                createdAtMs = parseUtcIso(item.createdAt),
                isGroupEnvelope = isGroup,
                isRatchetEnvelope = envelope.optString("ratchet") == "1",
            )
        }
    }

    fun ackMessagesDevice(messageIds: List<String>, deviceId: String) {
        if (messageIds.isEmpty()) return
        try {
            core.ackMessagesDevice(messageIds, deviceId)
        } catch (e: Exception) {
            throw IllegalStateException("Подтверждение сообщений: ${apiErrorDetail(e)}")
        }
    }

    // --- Безопасность: сессии устройств, 2FA, «удалить всё». ---

    data class DeviceSession(
        val deviceId: String,
        val createdAt: String,
        val sessions: Int,
        val current: Boolean,
    )

    data class SessionsInfo(
        val devices: List<DeviceSession>,
        val unboundSessions: Int,
        val canKick: Boolean,
        val kickMinHours: Int,
    )

    data class TotpSetup(val secret: String, val otpauthUri: String)

    fun bindSessionDevice(userId: String, deviceId: String) {
        restoreSession(userId)
        try {
            core.bindSessionDevice(deviceId)
        } catch (e: Exception) {
            throw asHttpError("Привязка сессии", e)
        }
    }

    fun listSessions(userId: String): SessionsInfo {
        restoreSession(userId)
        val raw = try {
            core.listSessions()
        } catch (e: Exception) {
            throw asHttpError("Список сессий", e)
        }
        val json = JSONObject(raw)
        val devices = json.optJSONArray("devices") ?: JSONArray()
        return SessionsInfo(
            devices = (0 until devices.length()).map { i ->
                val d = devices.getJSONObject(i)
                DeviceSession(
                    deviceId = d.optString("device_id"),
                    createdAt = d.optString("device_created_at"),
                    sessions = d.optInt("sessions"),
                    current = d.optBoolean("current"),
                )
            },
            unboundSessions = json.optInt("unbound_sessions"),
            canKick = json.optBoolean("can_kick"),
            kickMinHours = json.optInt("kick_min_hours", 12),
        )
    }

    fun kickDevice(userId: String, deviceId: String) {
        restoreSession(userId)
        try {
            core.kickDevice(deviceId)
        } catch (e: Exception) {
            throw asHttpError("Выкинуть устройство", e)
        }
    }

    fun totpStatus(userId: String): Boolean {
        restoreSession(userId)
        try {
            return core.totpStatus()
        } catch (e: Exception) {
            throw asHttpError("Статус 2FA", e)
        }
    }

    fun totpSetup(userId: String): TotpSetup {
        restoreSession(userId)
        val raw = try {
            core.totpSetup()
        } catch (e: Exception) {
            throw asHttpError("Настройка 2FA", e)
        }
        val json = JSONObject(raw)
        return TotpSetup(secret = json.optString("secret"), otpauthUri = json.optString("otpauth_uri"))
    }

    fun totpEnable(userId: String, code: String) {
        restoreSession(userId)
        try {
            core.totpEnable(code)
        } catch (e: Exception) {
            throw asHttpError("Включение 2FA", e)
        }
    }

    fun totpDisable(userId: String, code: String) {
        restoreSession(userId)
        try {
            core.totpDisable(code)
        } catch (e: Exception) {
            throw asHttpError("Выключение 2FA", e)
        }
    }

    fun wipeAccount(userId: String, password: String) {
        restoreSession(userId)
        try {
            core.wipeAccount(password)
        } catch (e: Exception) {
            throw asHttpError("Удаление данных", e)
        }
    }

    // --- Группы ---

    data class GroupInfo(
        val id: String,
        val name: String,
        val isChannel: Boolean,
        val encryptedKeyB64: String,
        val role: String,
        val linkedGroupId: String? = null,
        val ownerId: String = "",
        val description: String = "",
    )

    fun getMyGroups(): List<GroupInfo> {
        val root = JSONObject(core.getMyGroups())
        val groups = root.optJSONArray("groups") ?: JSONArray()
        return buildList {
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                val id = group.optString("id")
                if (id.isBlank()) continue
                add(
                    GroupInfo(
                        id = id,
                        name = group.optString("name", id),
                        isChannel = jsonBoolean(group, "is_channel"),
                        encryptedKeyB64 = group.optString("encrypted_key_b64"),
                        role = group.optString("role", "member"),
                        linkedGroupId = group.optString("linked_group_id").takeIf(String::isNotBlank),
                        ownerId = group.optString("owner_id", group.optString("owner")),
                        description = group.optString("description"),
                    )
                )
            }
        }
    }

    data class GroupMember(
        val userId: String,
        val username: String,
        val displayName: String,
        val avatarFileId: String?,
        val role: String,
    )

    fun getGroupMembers(groupId: String): List<GroupMember> = try {
        val root = JSONObject(core.getGroupMembers(groupId))
        val members = root.optJSONArray("members") ?: root.optJSONArray("users") ?: JSONArray()
        buildList {
            for (index in 0 until members.length()) {
                val member = members.optJSONObject(index) ?: continue
                val userId = jsonString(member, "user_id") ?: jsonString(member, "id") ?: continue
                val username = jsonString(member, "username").orEmpty()
                add(
                    GroupMember(
                        userId = userId,
                        username = username,
                        displayName = jsonString(member, "display_name") ?: username.ifBlank { userId },
                        avatarFileId = jsonString(member, "avatar_file_id"),
                        role = jsonString(member, "role") ?: "member",
                    )
                )
            }
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

    fun addGroupMember(
        groupId: String,
        userId: String,
        encryptedKeyB64: String,
        role: String = "member",
    ) {
        try {
            core.addGroupMember(groupId, userId, encryptedKeyB64, role)
        } catch (e: Exception) {
            throw IllegalStateException("Добавление участника: ${apiErrorDetail(e)}")
        }
    }

    fun createGroup(
        groupId: String,
        name: String,
        description: String,
        isChannel: Boolean,
        encryptedKeyB64: String,
        linkedGroupId: String? = null,
    ): String = try {
        core.createGroup(groupId, name, description, isChannel, encryptedKeyB64, linkedGroupId)
    } catch (e: Exception) {
        throw IllegalStateException("Создание группы: ${apiErrorDetail(e)}")
    }

    fun joinGroup(groupId: String) {
        requestJson(
            authorizedRequest("$base/groups/${urlEncode(groupId)}/join")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
        )
    }

    fun setMemberRole(groupId: String, userId: String, role: String) {
        requestJson(
            authorizedRequest("$base/groups/${urlEncode(groupId)}/members/${urlEncode(userId)}/role?role=$role")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build()
        )
    }

    // --- Поиск и профили ---

    data class UserSearchResult(
        val userId: String,
        val username: String,
        val displayName: String,
        val avatarFileId: String?,
        val isGroup: Boolean = false,
        val isChannel: Boolean = false,
        val publicJoin: Boolean = false,
        val description: String = "",
        val statusEmoji: String? = null,
    )

    fun searchDirectory(query: String): List<UserSearchResult> = try {
        val root = requestJson(
            authorizedRequest("$base/users/search?q=${urlEncode(query.trim())}").GET().build()
        )
        buildList {
            val users = root.optJSONArray("users") ?: JSONArray()
            for (index in 0 until users.length()) {
                val user = users.optJSONObject(index) ?: continue
                val id = jsonString(user, "user_id").orEmpty()
                if (id.isBlank()) continue
                add(
                    UserSearchResult(
                        userId = id.lowercase(),
                        username = jsonString(user, "username").orEmpty(),
                        displayName = jsonString(user, "display_name").orEmpty(),
                        avatarFileId = jsonString(user, "avatar_file_id"),
                        statusEmoji = jsonString(user, "status_emoji"),
                    )
                )
            }
            val groups = root.optJSONArray("groups") ?: JSONArray()
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                val id = jsonString(group, "id").orEmpty()
                if (id.isBlank()) continue
                add(
                    UserSearchResult(
                        userId = id.lowercase(),
                        username = jsonString(group, "username").orEmpty(),
                        displayName = jsonString(group, "name") ?: id,
                        avatarFileId = jsonString(group, "avatar_file_id"),
                        isGroup = true,
                        isChannel = jsonBoolean(group, "is_channel"),
                        publicJoin = jsonBoolean(group, "public_join"),
                        description = jsonString(group, "description").orEmpty(),
                    )
                )
            }
        }
    } catch (_: Exception) {
        runCatching {
            core.searchUsers(query).map { profile ->
                UserSearchResult(
                    userId = profile.userId,
                    username = profile.username.orEmpty(),
                    displayName = profile.displayName.orEmpty(),
                    avatarFileId = profile.avatarFileId,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun searchUsers(query: String): List<UserSearchResult> =
        searchDirectory(query).filterNot(UserSearchResult::isGroup)

    data class UserProfile(
        val userId: String,
        val username: String,
        val displayName: String,
        val avatarFileId: String?,
        val bio: String?,
        val lastActive: String?,
        val statusEmoji: String? = null,
    )

    fun getUserProfile(userId: String): UserProfile {
        try {
            val profile = requestJson(
                authorizedRequest("$base/users/${urlEncode(userId)}/profile").GET().build()
            )
            return UserProfile(
                userId = profile.optString("user_id", userId),
                username = jsonString(profile, "username").orEmpty(),
                displayName = jsonString(profile, "display_name").orEmpty(),
                avatarFileId = jsonString(profile, "avatar_file_id"),
                bio = jsonString(profile, "bio"),
                lastActive = jsonString(profile, "last_active"),
                statusEmoji = jsonString(profile, "status_emoji"),
            )
        } catch (_: Exception) {
        }
        try {
            val profile = core.getUserProfile(userId)
            return UserProfile(
                userId = profile.userId,
                username = profile.username.orEmpty(),
                displayName = profile.displayName.orEmpty(),
                avatarFileId = profile.avatarFileId,
                bio = profile.bio,
                lastActive = profile.lastActive,
                statusEmoji = null,
            )
        } catch (e: Exception) {
            throw IllegalStateException("Профиль не найден: ${apiErrorDetail(e)}")
        }
    }

    fun updateProfile(
        username: String?,
        displayName: String?,
        avatarFileId: String?,
        bio: String?,
    ) {
        try {
            core.updateProfile(username, displayName, avatarFileId, bio)
        } catch (e: Exception) {
            throw IllegalStateException("Обновление профиля: ${apiErrorDetail(e)}")
        }
    }

    fun setStatusEmoji(emoji: String) {
        requestJson(
            authorizedRequest("$base/users/me/profile")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSONObject().put("status_emoji", emoji).toString()))
                .build()
        )
    }

    /** Диалоги аккаунта (раскладка чатов для нового устройства, истории нет). */
    fun getMyDialogs(): List<String> = try {
        val root = requestJson(authorizedRequest("$base/users/me/dialogs").GET().build())
        val dialogs = root.optJSONArray("dialogs") ?: JSONArray()
        buildList {
            for (index in 0 until dialogs.length()) {
                dialogs.optJSONObject(index)
                    ?.let { jsonString(it, "peer_id") }
                    ?.let { add(it.lowercase()) }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    // --- Медиа ---

    fun uploadFile(fileBytes: ByteArray): String = try {
        core.upload(fileBytes)
    } catch (e: Exception) {
        throw IllegalStateException("Upload failed: ${apiErrorDetail(e)}")
    }

    /** Стриминговая загрузка больших шифрованных файлов (multipart вручную). */
    fun uploadFile(file: File): String {
        val boundary = "aether-" + java.util.UUID.randomUUID().toString()
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"upload.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val length = head.size.toLong() + file.length() + tail.size
        val request = authorizedRequest("$base/upload")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(
                HttpRequest.BodyPublishers.fromPublisher(
                    HttpRequest.BodyPublishers.ofInputStream {
                        SequenceInputStream(
                            java.util.Collections.enumeration(
                                listOf(
                                    ByteArrayInputStream(head),
                                    file.inputStream().buffered(),
                                    ByteArrayInputStream(tail),
                                )
                            )
                        )
                    },
                    length,
                )
            )
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Upload failed: ${response.statusCode()}")
        }
        return JSONObject(response.body()).getString("file_id")
    }

    fun uploadAvatar(fileBytes: ByteArray): String = try {
        core.uploadAvatar(fileBytes, "image/jpeg")
    } catch (e: Exception) {
        throw IllegalStateException("Avatar upload failed: ${apiErrorDetail(e)}")
    }

    fun downloadFile(fileId: String): ByteArray = try {
        core.download(fileId)
    } catch (e: Exception) {
        throw IllegalStateException("Download failed: ${apiErrorDetail(e)}")
    }

    companion object {
        fun parseUtcIso(iso: String?): Long {
            if (iso.isNullOrBlank()) return 0L
            return try {
                val format = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss",
                    java.util.Locale.US,
                )
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val base = format.parse(iso.take(19))?.time ?: return 0L
                val millis = Regex("\\.(\\d{1,6})").find(iso)
                    ?.groupValues?.get(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
                base + millis
            } catch (_: Exception) {
                0L
            }
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun jsonBoolean(obj: JSONObject, key: String): Boolean {
            val value = obj.opt(key)
            return value == true || value == 1 || value == "1" ||
                value?.toString()?.equals("true", ignoreCase = true) == true
        }

        private fun jsonString(obj: JSONObject, key: String): String? =
            if (!obj.has(key) || obj.isNull(key)) null else obj.optString(key).takeIf(String::isNotBlank)
    }
}
