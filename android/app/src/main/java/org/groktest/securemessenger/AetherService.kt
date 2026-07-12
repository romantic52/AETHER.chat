package org.groktest.securemessenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class AetherService : Service() {

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var websocketUrl: String? = null
    private var connectionGeneration = 0
    private var reconnectAttempt = 0

    companion object {
        // Realtime-канал теперь в ядре (Rust tungstenite): авто-реконнект и ping
        // внутри ядра, платформа лишь реализует WsListener и шлёт исходящие.
        var wsClient: uniffi.sm_core.WsClient? = null
        // Слушатель активного звонка (CallOverlay). Когда null — звонка нет.
        var callListener: ((JSONObject) -> Unit)? = null
        // Глобальный слушатель входящих offer (MainActivity). Не затирается звонком.
        var incomingOfferListener: ((JSONObject) -> Unit)? = null
        var lastOffer: JSONObject? = null
        // Дёргается при WS-пуше new_message — для мгновенной доставки (без ожидания поллинга)
        var onNewMessage: (() -> Unit)? = null
        // Имя/мьют чата для уведомления (ставит MainActivity: peerId -> ChatEntity из ядра)
        var chatLookup: ((String) -> org.groktest.securemessenger.data.ChatEntity?)? = null
        // Индикатор «печатает...» от собеседника
        var onTyping: ((String) -> Unit)? = null
        // Приложение на переднем плане — тогда системные уведомления не нужны
        var appInForeground = false

        fun sendTyping(recipientId: String) {
            try { wsClient?.sendTyping(recipientId) } catch (e: Exception) {}
        }

        fun sendWebRtcSignal(signal: JSONObject) {
            try {
                val type = signal.optString("type")
                val recipientId = signal.optString("recipient_id")
                if (type.isNotBlank() && recipientId.isNotBlank()) {
                    wsClient?.sendWebrtcSignal(type, recipientId, signal.toString())
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = intent?.getStringExtra("server_url") ?: return START_NOT_STICKY
        val token = intent.getStringExtra("token") ?: return START_NOT_STICKY

        // Старое соединение гасим, новое поднимаем через ядро.
        val wsBase = serverUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val encodedToken = java.net.URLEncoder.encode(token, "UTF-8")
        websocketUrl = "$wsBase/ws?token=$encodedToken"
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectAttempt = 0
        connectWebSocket()
        return START_STICKY
    }

    /** Мост из realtime-событий ядра в статические слушатели приложения. */
    private fun connectWebSocket() {
        val url = websocketUrl ?: return
        val generation = connectionGeneration + 1
        connectionGeneration = generation
        wsClient?.disconnect()

        val client = uniffi.sm_core.WsClient()
        wsClient = client
        try {
            client.connect(url, websocketListener(generation))
        } catch (_: Exception) {
            scheduleReconnect(generation)
        }
    }

    private fun websocketListener(generation: Int) = object : uniffi.sm_core.WsListener {
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
                val type = event.optString("type")
                val senderId = event.optString("sender_id")
                when (type) {
                    "new_message" -> handleNewMessage(senderId)
                    "typing" -> onTyping?.invoke(senderId)
                    "webrtc_offer", "webrtc_answer", "webrtc_ice",
                    "webrtc_hangup", "webrtc_busy" -> handleWebRtcEvent(type, event)
                }
            } catch (e: Exception) {
                android.util.Log.w("AetherService", "Invalid websocket event", e)
            }
        }
    }

    private fun scheduleReconnect(generation: Int) {
        if (generation != connectionGeneration || websocketUrl == null) return
        val delays = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
        val delay = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(delays.lastIndex)
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            if (generation == connectionGeneration && websocketUrl != null) {
                connectWebSocket()
            }
        }, delay)
    }
    private fun handleNewMessage(senderId: String) {
        onNewMessage?.invoke()
        if (appInForeground) return

        val chat = if (senderId.isNotBlank()) {
            try {
                chatLookup?.invoke(senderId)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (chat?.isMuted == true) return
        showNewMessageNotification(
            title = chat?.name?.takeIf { it.isNotBlank() }
                ?: senderId.takeIf { it.isNotBlank() }
                ?: "Aether",
            text = "Новое сообщение",
            peerId = senderId,
        )
    }

    private fun handleWebRtcEvent(type: String, event: JSONObject) {
        if (type == "webrtc_offer") lastOffer = event
        callListener?.let {
            it.invoke(event)
            return
        }
        if (type != "webrtc_offer") return
        incomingOfferListener?.let {
            it.invoke(event)
            return
        }
        showNewMessageNotification(
            title = "Aether",
            text = "Входящий звонок",
            peerId = "call",
        )
    }
    private fun showNewMessageNotification(title: String, text: String, peerId: String) {
        val isCall = text.contains("звонок", ignoreCase = true)
        // Настройки уведомлений (те же prefs, что у ThemeSettings). Звонки всегда уведомляем.
        val sp = getSharedPreferences("theme_settings", Context.MODE_PRIVATE)
        if (!isCall && !sp.getBoolean("notif_previews", true)) return
        val sound = sp.getBoolean("notif_sound", true)
        val vibration = sp.getBoolean("notif_vibration", true)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // (#A4) На экране блокировки — публичная версия БЕЗ имени отправителя.
        val publicVersion = NotificationCompat.Builder(this, "AETHER_MESSAGES")
            .setSmallIcon(R.drawable.ic_stat_aether)
            .setContentTitle("Aether")
            .setContentText(if (isCall) "Входящий звонок" else "Новое сообщение")
            .build()

        val builder = NotificationCompat.Builder(this, "AETHER_MESSAGES")
            .setSmallIcon(R.drawable.ic_stat_aether)        // фирменная иконка вместо системной
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setGroup("aether_messages") // группировка пушей приложения в шторке

        // Сообщения уважают тумблеры; звонки звенят всегда.
        if (!isCall) {
            when {
                !sound -> builder.setSilent(true) // без звука и вибрации (надёжно на всех версиях)
                !vibration -> builder.setVibrate(longArrayOf(0L)) // только звук, без вибро
            }
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Один пуш на чат: повторные сообщения обновляют его, а не спамят шторку
        manager.notify(peerId.hashCode(), builder.build())
    }

    private fun createForegroundNotification() = NotificationCompat.Builder(this, "AETHER_SERVICE")
        .setContentTitle("Aether")
        .setContentText("Синхронизация сообщений...")
        .setSmallIcon(R.drawable.ic_stat_aether)           // фирменная иконка
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "AETHER_SERVICE",
                "Фоновая работа",
                NotificationManager.IMPORTANCE_LOW
            )
            val msgChannel = NotificationChannel(
                "AETHER_MESSAGES",
                "Новые сообщения",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(msgChannel)
        }
    }

    override fun onDestroy() {
        onNewMessage = null
        onTyping = null
        wsClient?.disconnect()
        wsClient = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
