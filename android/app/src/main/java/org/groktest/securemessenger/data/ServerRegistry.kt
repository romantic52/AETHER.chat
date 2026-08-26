package org.groktest.securemessenger.data

import android.content.Context
import org.groktest.securemessenger.api.ServerConfig
import org.json.JSONArray
import org.json.JSONObject
import uniffi.sm_core.ServerInfo
import java.io.File

enum class ServerKind { OFFICIAL, CUSTOM }

enum class ServerRegistrationMode {
    OPEN, APPROVAL, INVITE_ONLY, CLOSED;

    companion object {
        fun fromWire(value: String): ServerRegistrationMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CLOSED
    }
}

data class ServerPin(
    val serverId: String,
    val publicKeyB64: String,
    val fingerprintB64: String,
    val firstSeenAt: Long,
    val lastVerifiedAt: Long,
)

data class ServerAccount(
    val userId: String,
    val displayName: String = "",
    val lastLoginAt: Long? = null,
)

data class ServerRecord(
    val id: String,
    val kind: ServerKind,
    val displayName: String,
    val declaredName: String,
    val origin: String,
    val apiUrl: String,
    val websocketUrl: String,
    val protocolVersion: Int = 1,
    val registrationMode: ServerRegistrationMode = ServerRegistrationMode.CLOSED,
    val capabilities: List<String> = emptyList(),
    val cleartext: Boolean = false,
    val pin: ServerPin? = null,
    val accounts: List<ServerAccount> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
) {
    val isOfficial: Boolean get() = kind == ServerKind.OFFICIAL
    val hostLabel: String
        get() = origin.removePrefix("https://").removePrefix("http://").trimEnd('/')

    fun account(userId: String): ServerAccount? =
        accounts.firstOrNull { it.userId.equals(userId, ignoreCase = true) }

    /** Стабильная область старой базы: настоящий id официального сервера её не переименовывает. */
    val storageServerId: String get() = if (isOfficial) OFFICIAL_PLACEHOLDER_ID else id

    companion object {
        const val OFFICIAL_PLACEHOLDER_ID = "official-legacy"
    }
}

sealed interface ServerInspection {
    data class Fresh(val info: ServerInfo) : ServerInspection
    data class Known(val info: ServerInfo, val record: ServerRecord) : ServerInspection
    data class IdentityChanged(
        val info: ServerInfo,
        val record: ServerRecord,
        val oldPin: ServerPin,
    ) : ServerInspection
}

/** Реестр не содержит секретов и читается до открытия базы любого пространства. */
class ServerRegistry(context: Context) {
    private val file = File(context.applicationContext.filesDir, "servers.json")
    private var records: MutableList<ServerRecord> = load().toMutableList()

    init {
        ensureOfficial()
    }

    @Synchronized
    fun servers(): List<ServerRecord> = records.sortedWith(
        compareByDescending<ServerRecord> { it.isOfficial }.thenBy { it.addedAt }
    )

    @Synchronized
    fun server(id: String): ServerRecord? = records.firstOrNull { it.id == id }

    @Synchronized
    fun official(): ServerRecord = records.first { it.isOfficial }

    @Synchronized
    fun matchingOrigin(origin: String): ServerRecord? {
        val needle = origin.trimEnd('/')
        return records.firstOrNull { it.origin.trimEnd('/').equals(needle, ignoreCase = true) }
    }

    @Synchronized
    fun inspect(info: ServerInfo): ServerInspection {
        val existing = server(info.serverId) ?: matchingOrigin(info.origin)
            ?: return ServerInspection.Fresh(info)
        val oldPin = existing.pin ?: return ServerInspection.Known(info, existing)
        return if (oldPin.serverId == info.serverId && oldPin.fingerprintB64 == info.fingerprintB64) {
            ServerInspection.Known(info, existing)
        } else {
            ServerInspection.IdentityChanged(info, existing, oldPin)
        }
    }

    @Synchronized
    fun trust(info: ServerInfo, kind: ServerKind = ServerKind.CUSTOM): ServerRecord {
        require(info.signatureValid) { "Подпись сервера не подтверждена" }
        require(info.endpointsMatchOrigin) { "Адреса API сервера не совпадают с найденным адресом" }
        val now = System.currentTimeMillis()
        val existing = server(info.serverId)
        val record = ServerRecord(
            id = info.serverId,
            kind = existing?.kind ?: kind,
            displayName = existing?.displayName?.takeIf(String::isNotBlank) ?: info.name,
            declaredName = info.name,
            origin = info.origin,
            apiUrl = info.apiUrl,
            websocketUrl = info.websocketUrl,
            protocolVersion = info.protocolVersion.toInt(),
            registrationMode = ServerRegistrationMode.fromWire(info.registrationMode),
            capabilities = info.capabilities,
            cleartext = info.cleartext,
            pin = existing?.pin?.copy(lastVerifiedAt = now) ?: ServerPin(
                serverId = info.serverId,
                publicKeyB64 = info.publicKeyB64,
                fingerprintB64 = info.fingerprintB64,
                firstSeenAt = now,
                lastVerifiedAt = now,
            ),
            accounts = existing?.accounts.orEmpty(),
            addedAt = existing?.addedAt ?: now,
            lastConnectedAt = now,
        )
        upsert(record)
        return record
    }

    /** Осознанное принятие новой личности создаёт новое пространство без старых аккаунтов. */
    @Synchronized
    fun acceptChangedIdentity(info: ServerInfo, previous: ServerRecord): ServerRecord {
        records.removeAll { it.id == previous.id }
        save()
        val replacement = trust(info, previous.kind)
        return if (previous.id == info.serverId) {
            replacement.copy(accounts = previous.accounts).also(::upsert)
        } else {
            replacement
        }
    }

    @Synchronized
    fun addAccount(serverId: String, userId: String, displayName: String = "") {
        val record = server(serverId) ?: return
        val normalized = userId.trim().lowercase()
        val accounts = record.accounts.toMutableList()
        val account = ServerAccount(normalized, displayName, System.currentTimeMillis())
        val index = accounts.indexOfFirst { it.userId.equals(normalized, ignoreCase = true) }
        if (index >= 0) accounts[index] = account else accounts += account
        upsert(record.copy(accounts = accounts, lastConnectedAt = System.currentTimeMillis()))
    }

    @Synchronized
    fun removeAccount(serverId: String, userId: String) {
        val record = server(serverId) ?: return
        upsert(record.copy(accounts = record.accounts.filterNot {
            it.userId.equals(userId, ignoreCase = true)
        }))
    }

    @Synchronized
    private fun upsert(record: ServerRecord) {
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) records[index] = record else records += record
        save()
    }

    private fun ensureOfficial() {
        if (records.any { it.isOfficial }) return
        records += ServerRecord(
            id = ServerRecord.OFFICIAL_PLACEHOLDER_ID,
            kind = ServerKind.OFFICIAL,
            displayName = "Aether Cloud",
            declaredName = "Aether Cloud",
            origin = ServerConfig.DEFAULT_BASE_URL,
            apiUrl = ServerConfig.DEFAULT_BASE_URL,
            websocketUrl = ServerConfig.DEFAULT_BASE_URL.replaceFirst("https://", "wss://") + "/ws",
            registrationMode = ServerRegistrationMode.OPEN,
            capabilities = listOf("e2ee", "ratchet", "groups", "calls", "multi_device"),
        )
        save()
    }

    private fun load(): List<ServerRecord> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val root = JSONObject(file.readText())
        val array = root.optJSONArray("servers") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) add(array.getJSONObject(index).toRecord())
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val root = JSONObject().put("servers", JSONArray().apply {
            records.forEach { put(it.toJson()) }
        })
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(root.toString())
            temporary.delete()
        }
    }
}

private fun ServerRecord.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("kind", kind.name)
    .put("display_name", displayName)
    .put("declared_name", declaredName)
    .put("origin", origin)
    .put("api_url", apiUrl)
    .put("websocket_url", websocketUrl)
    .put("protocol_version", protocolVersion)
    .put("registration_mode", registrationMode.name)
    .put("capabilities", JSONArray(capabilities))
    .put("cleartext", cleartext)
    .put("pin", pin?.let { pin -> JSONObject()
        .put("server_id", pin.serverId)
        .put("public_key_b64", pin.publicKeyB64)
        .put("fingerprint_b64", pin.fingerprintB64)
        .put("first_seen_at", pin.firstSeenAt)
        .put("last_verified_at", pin.lastVerifiedAt)
    })
    .put("accounts", JSONArray().apply {
        accounts.forEach { account -> put(JSONObject()
            .put("user_id", account.userId)
            .put("display_name", account.displayName)
            .put("last_login_at", account.lastLoginAt)
        ) }
    })
    .put("added_at", addedAt)
    .put("last_connected_at", lastConnectedAt)

private fun JSONObject.toRecord(): ServerRecord {
    val pinJson = optJSONObject("pin")
    val accountJson = optJSONArray("accounts") ?: JSONArray()
    val capabilityJson = optJSONArray("capabilities") ?: JSONArray()
    return ServerRecord(
        id = getString("id"),
        kind = runCatching { ServerKind.valueOf(getString("kind")) }.getOrDefault(ServerKind.CUSTOM),
        displayName = optString("display_name"),
        declaredName = optString("declared_name"),
        origin = getString("origin"),
        apiUrl = optString("api_url", getString("origin")),
        websocketUrl = optString("websocket_url"),
        protocolVersion = optInt("protocol_version", 1),
        registrationMode = ServerRegistrationMode.fromWire(optString("registration_mode")),
        capabilities = buildList {
            for (index in 0 until capabilityJson.length()) add(capabilityJson.getString(index))
        },
        cleartext = optBoolean("cleartext", false),
        pin = pinJson?.let { pin -> ServerPin(
            serverId = pin.getString("server_id"),
            publicKeyB64 = pin.optString("public_key_b64"),
            fingerprintB64 = pin.getString("fingerprint_b64"),
            firstSeenAt = pin.optLong("first_seen_at"),
            lastVerifiedAt = pin.optLong("last_verified_at"),
        ) },
        accounts = buildList {
            for (index in 0 until accountJson.length()) {
                val account = accountJson.getJSONObject(index)
                add(ServerAccount(
                    userId = account.getString("user_id"),
                    displayName = account.optString("display_name"),
                    lastLoginAt = account.optLong("last_login_at").takeIf { it > 0L },
                ))
            }
        },
        addedAt = optLong("added_at").takeIf { it > 0L } ?: System.currentTimeMillis(),
        lastConnectedAt = optLong("last_connected_at").takeIf { it > 0L },
    )
}
