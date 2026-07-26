package aether.desktop.service

import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask
import org.json.JSONObject

/**
 * Realtime поверх ядрового WsClient (порт логики AetherService): ядро само не
 * переподключается — реконнект с экспоненциальным backoff здесь.
 */
class RealtimeClient(
    serverUrl: String,
    token: String,
    private val onNewMessage: () -> Unit,
    private val onTyping: (String) -> Unit = {},
    private val onEvent: (JSONObject) -> Unit = {},
) {
    private val url: String
    private val timer = Timer("aether-ws-reconnect", true)
    @Volatile private var closed = false
    @Volatile private var wsClient: uniffi.sm_core.WsClient? = null
    @Volatile private var connectionGeneration = 0
    @Volatile private var reconnectAttempt = 0

    init {
        val wsBase = serverUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val encodedToken = URLEncoder.encode(token, Charsets.UTF_8.name())
        url = "$wsBase/ws?token=$encodedToken"
        connect()
    }

    fun isActive(): Boolean = wsClient?.isActive() ?: false

    fun sendTyping(recipientId: String) {
        runCatching { wsClient?.sendTyping(recipientId) }
    }

    fun sendRaw(json: String) {
        runCatching { wsClient?.sendRaw(json) }
    }

    fun close() {
        closed = true
        timer.cancel()
        runCatching { wsClient?.disconnect() }
        wsClient = null
    }

    @Synchronized
    private fun connect() {
        if (closed) return
        val generation = connectionGeneration + 1
        connectionGeneration = generation
        runCatching { wsClient?.disconnect() }

        val client = uniffi.sm_core.WsClient()
        wsClient = client
        try {
            client.connect(url, listener(generation))
        } catch (_: Exception) {
            scheduleReconnect(generation)
        }
    }

    private fun listener(generation: Int) = object : uniffi.sm_core.WsListener {
        override fun onOpen() {
            if (generation == connectionGeneration) reconnectAttempt = 0
        }

        override fun onClose() {
            scheduleReconnect(generation)
        }

        override fun onEvent(json: String) {
            if (generation != connectionGeneration) return
            try {
                val event = JSONObject(json)
                when (event.optString("type")) {
                    "new_message" -> onNewMessage()
                    "typing" -> onTyping(event.optString("sender_id"))
                }
                onEvent(event)
            } catch (_: Exception) {
            }
        }
    }

    private fun scheduleReconnect(generation: Int) {
        if (closed || generation != connectionGeneration) return
        val delays = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
        val delay = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(delays.lastIndex)
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (!closed && generation == connectionGeneration) connect()
            }
        }, delay)
    }
}
