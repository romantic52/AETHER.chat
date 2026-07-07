package org.groktest.securemessenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class AetherService : Service() {

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
            try { wsClient?.sendWebrtcSignal(signal.toString()) } catch (e: Exception) {}
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
        wsClient?.disconnect()
        val ws = uniffi.sm_core.WsClient(serverUrl)
        wsClient = ws
        ws.connect(token, coreWsListener)
        return START_STICKY
    }

    /** Мост из realtime-событий ядра в статические слушатели приложения. */
    private val coreWsListener = object : uniffi.sm_core.WsListener {
        override fun onConnected() {}
        override fun onDisconnected() {}

        override fun onNewMessage(senderId: String) {
            // Мгновенно подтягиваем сообщение, не дожидаясь поллинга
            onNewMessage?.invoke()
            if (!appInForeground) {
                val chat = if (senderId.isNotBlank()) try { chatLookup?.invoke(senderId) } catch (e: Exception) { null } else null
                if (chat?.isMuted == true) return // замьюченный чат не шумит
                // Имя — в шторке; на экране блокировки система покажет publicVersion без имени (#A4).
                // Если чата ещё нет локально (первое сообщение) — показываем хотя бы id отправителя.
                showNewMessageNotification(
                    title = chat?.name?.takeIf { it.isNotBlank() }
                        ?: senderId.takeIf { it.isNotBlank() } ?: "Aether",
                    text = "Новое сообщение",
                    peerId = senderId
                )
            }
        }

        override fun onTyping(senderId: String) {
            onTyping?.invoke(senderId)
        }

        override fun onWebrtcSignal(json: String) {
            try {
                val obj = JSONObject(json)
                val type = obj.optString("type")
                if (type == "webrtc_offer") lastOffer = obj
                val active = callListener
                if (active != null) {
                    active.invoke(obj)
                } else if (type == "webrtc_offer") {
                    val incoming = incomingOfferListener
                    if (incoming != null) {
                        incoming.invoke(obj)
                    } else {
                        // Приложение в фоне — уведомление о входящем звонке
                        // (#A4) Приватность: без имени звонящего
                        showNewMessageNotification(title = "Aether", text = "Входящий звонок", peerId = "call")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
