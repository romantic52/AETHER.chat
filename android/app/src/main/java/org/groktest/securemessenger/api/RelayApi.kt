package org.groktest.securemessenger.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Android-facing adapter for the shared Rust relay client. */
class RelayApi(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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

    fun login(userId: String, password: String): LoginResult {
        try {
            val result = core.login(userId, password)
            token = result.token
            return LoginResult(result.token, result.encryptedPrivateKeyB64)
        } catch (e: Exception) {
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

    private fun requestJson(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull().orEmpty()
            throw HttpError(response.code, detail.ifBlank { "Ошибка сервера (${response.code})" })
        }
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun authorizedRequest(url: String): Request.Builder = Request.Builder().url(url).apply {
        token?.let { header("Authorization", "Bearer $it") }
    }

    fun logout() {
        try {
            core.logout()
        } catch (_: Exception) {
            // Logging out remains best effort when the relay is unavailable.
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

    class HttpError(val code: Int, message: String) : RuntimeException(message)

    fun uploadOlmKeys(userId: String, identityKeyB64: String, oneTimeKeysJson: String) {
        restoreSession(userId)
        try {
            core.uploadKeys(identityKeyB64, oneTimeKeysJson)
        } catch (e: Exception) {
            throw asHttpError("Публикация защищённых ключей", e)
        }
    }

    fun olmKeysCount(userId: String): UInt {
        restoreSession(userId)
        try {
            return core.keysCount()
        } catch (e: Exception) {
            throw asHttpError("Проверка защищённых ключей", e)
        }
    }

    fun olmKeysState(userId: String): uniffi.sm_core.PrekeyState {
        restoreSession(userId)
        try {
            return core.keysState()
        } catch (e: Exception) {
            throw asHttpError("Проверка защищённых ключей", e)
        }
    }

    fun claimOlmKeys(userId: String, peerId: String): uniffi.sm_core.PrekeyBundle {
        restoreSession(userId)
        try {
            return core.claimKeys(peerId.lowercase())
        } catch (e: Exception) {
            throw asHttpError("Собеседник не поддерживает защищённое шифрование", e)
        }
    }

    private fun asHttpError(action: String, error: Exception): RuntimeException =
        if (error is uniffi.sm_core.CoreException.Api) {
            HttpError(error.status.toInt(), "$action: ${apiErrorDetail(error)}")
        } else {
            IllegalStateException("$action: ${apiErrorDetail(error)}", error)
        }

    fun fetchInbox(userId: String): List<InboxMessage> {
        restoreSession(userId)
        return core.fetchInbox(null).map { item ->
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

    fun ackMessages(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        try {
            core.ackMessages(messageIds)
        } catch (e: Exception) {
            throw IllegalStateException("Подтверждение сообщений: ${apiErrorDetail(e)}")
        }
    }

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
                val userId = member.optString("user_id", member.optString("id"))
                if (userId.isBlank()) continue
                val username = member.optString("username")
                add(
                    GroupMember(
                        userId = userId,
                        username = username,
                        displayName = member.optString("display_name").ifBlank {
                            username.ifBlank { userId }
                        },
                        avatarFileId = member.optString("avatar_file_id").takeIf(String::isNotBlank),
                        role = member.optString("role", "member"),
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

    fun searchDirectory(query: String): List<UserSearchResult> = try {
        val encoded = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val root = requestJson(authorizedRequest("$base/users/search?q=$encoded").get().build())
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

    fun joinGroup(groupId: String) {
        val body = "{}".toRequestBody("application/json".toMediaType())
        requestJson(authorizedRequest("$base/groups/$groupId/join").post(body).build())
    }

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
            val encoded = java.net.URLEncoder.encode(userId, Charsets.UTF_8.name())
            val profile = requestJson(authorizedRequest("$base/users/$encoded/profile").get().build())
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
            // Older/self-hosted relays still work through the shared core.
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

    fun setStatusEmoji(emoji: String) {
        val body = JSONObject().put("status_emoji", emoji).toString()
            .toRequestBody("application/json".toMediaType())
        requestJson(authorizedRequest("$base/users/me/profile").put(body).build())
    }

    fun uploadFile(fileBytes: ByteArray): String = try {
        core.upload(fileBytes)
    } catch (e: Exception) {
        throw IllegalStateException("Upload failed: ${apiErrorDetail(e)}")
    }

    /** Streams large encrypted files without copying the whole payload into Kotlin memory. */
    fun uploadFile(file: java.io.File): String {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "upload.bin",
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$base/upload")
            .post(body)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Upload failed: ${response.code}")
            return JSONObject(response.body!!.string()).getString("file_id")
        }
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

        private fun jsonBoolean(obj: JSONObject, key: String): Boolean {
            val value = obj.opt(key)
            return value == true || value == 1 || value == "1" ||
                value?.toString()?.equals("true", ignoreCase = true) == true
        }

        private fun jsonString(obj: JSONObject, key: String): String? =
            if (!obj.has(key) || obj.isNull(key)) null else obj.optString(key).takeIf(String::isNotBlank)
    }
}
