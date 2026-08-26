package org.groktest.securemessenger.nearby

import android.content.Context
import android.content.SharedPreferences

/**
 * Приватность обнаружения.
 *
 * Четыре НЕЗАВИСИМЫЕ оси, и сводить их в один флаг «виден/невиден» нельзя
 * (docs/TRANSPORT_LAYER_DESIGN.md, раздел 12.2):
 *
 *   DISCOVERY   может ли человек вообще понять, что я рядом
 *   VISIBILITY  что он при этом увидит
 *   INTERACTION что он может сделать
 *   DELIVERY    может ли прислать сообщение напрямую
 *
 * Осмысленная комбинация: виден всем, профиль скрыт до «пользователь Aether»,
 * писать нельзя.
 *
 * Настройки принадлежат УСТРОЙСТВУ, а не пространству: радио одно на всех,
 * и «невидим» обязан означать невидим везде.
 */
enum class NearbyAudience(val title: String) {
    NOBODY("Никто"),
    CONTACTS("Контакты"),
    EVERYONE("Все пользователи Aether");

    companion object {
        fun from(value: String?): NearbyAudience =
            entries.firstOrNull { it.name == value } ?: CONTACTS
    }
}

/** Что видит тот, кто нас нашёл, но контактом не является. */
enum class StrangerVisibility(val title: String) {
    AETHER_USER_ONLY("Только «Пользователь Aether»"),
    NAME_AND_AVATAR("Имя и аватар"),
    PUBLIC_PROFILE("Публичный профиль");

    companion object {
        fun from(value: String?): StrangerVisibility =
            entries.firstOrNull { it.name == value } ?: AETHER_USER_ONLY
    }
}

class NearbyPrivacy private constructor(private val prefs: SharedPreferences) {

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var bluetoothVisible: Boolean
        get() = prefs.getBoolean(KEY_BLUETOOTH, true)
        set(value) = prefs.edit().putBoolean(KEY_BLUETOOTH, value).apply()

    /** Умолчание — «Контакты», а не «Все»: безопасный дефолт по разделу 12.3. */
    var audience: NearbyAudience
        get() = NearbyAudience.from(prefs.getString(KEY_AUDIENCE, null))
        set(value) = prefs.edit().putString(KEY_AUDIENCE, value.name).apply()

    var strangerVisibility: StrangerVisibility
        get() = StrangerVisibility.from(prefs.getString(KEY_STRANGER, null))
        set(value) = prefs.edit().putString(KEY_STRANGER, value.name).apply()

    // Что незнакомцу позволено сделать. Видимость и разрешение — разные вещи.
    var strangersCanOpenProfile: Boolean
        get() = prefs.getBoolean(KEY_CAN_PROFILE, true)
        set(value) = prefs.edit().putBoolean(KEY_CAN_PROFILE, value).apply()

    var strangersCanOpenChat: Boolean
        get() = prefs.getBoolean(KEY_CAN_CHAT, true)
        set(value) = prefs.edit().putBoolean(KEY_CAN_CHAT, value).apply()

    var strangersCanMessage: Boolean
        get() = prefs.getBoolean(KEY_CAN_MESSAGE, false)
        set(value) = prefs.edit().putBoolean(KEY_CAN_MESSAGE, value).apply()

    /**
     * Ключ обнаружения устройства. Секрет: по нему нас узнают знакомые,
     * поэтому наружу он уходит только тем, кого мы сами выбрали.
     */
    fun discoveryKey(): String {
        prefs.getString(KEY_DISCOVERY_KEY, null)?.let { return it }
        val fresh = uniffi.sm_core.nearbyNewDiscoveryKey()
        prefs.edit().putString(KEY_DISCOVERY_KEY, fresh).apply()
        return fresh
    }

    /** Смена ключа обрывает узнавание всеми, кому старый уже отдан. */
    fun rotateDiscoveryKey(): String {
        val fresh = uniffi.sm_core.nearbyNewDiscoveryKey()
        prefs.edit().putString(KEY_DISCOVERY_KEY, fresh).apply()
        return fresh
    }

    /** Временная видимость: включить на срок и откатиться самому. */
    fun makeVisible(seconds: Long?) {
        enabled = true
        val until = seconds?.let { System.currentTimeMillis() + it * 1000L } ?: 0L
        prefs.edit().putLong(KEY_VISIBLE_UNTIL, until).apply()
    }

    val visibleUntil: Long?
        get() = prefs.getLong(KEY_VISIBLE_UNTIL, 0L).takeIf { it > 0L }

    /**
     * Действительно ли мы сейчас должны объявлять о себе.
     *
     * Проверяет и срок временной видимости: истёк — молчим, не дожидаясь,
     * пока человек вспомнит. В этом и смысл временного режима.
     */
    fun shouldAdvertise(): Boolean {
        if (!enabled || audience == NearbyAudience.NOBODY) return false
        visibleUntil?.let { if (it < System.currentTimeMillis()) return false }
        return bluetoothVisible
    }

    /** Снять истёкшую временную видимость. Зовётся сервисом обнаружения. */
    fun expireTemporaryVisibilityIfNeeded() {
        val until = visibleUntil ?: return
        if (until >= System.currentTimeMillis()) return
        prefs.edit().putLong(KEY_VISIBLE_UNTIL, 0L).putBoolean(KEY_ENABLED, false).apply()
    }

    companion object {
        private const val KEY_ENABLED = "nearby.enabled"
        private const val KEY_BLUETOOTH = "nearby.bluetooth"
        private const val KEY_AUDIENCE = "nearby.audience"
        private const val KEY_STRANGER = "nearby.stranger"
        private const val KEY_CAN_PROFILE = "nearby.canOpenProfile"
        private const val KEY_CAN_CHAT = "nearby.canOpenChat"
        private const val KEY_CAN_MESSAGE = "nearby.canMessage"
        private const val KEY_VISIBLE_UNTIL = "nearby.visibleUntil"
        private const val KEY_DISCOVERY_KEY = "nearby.discoveryKey"

        @Volatile
        private var instance: NearbyPrivacy? = null

        fun get(context: Context): NearbyPrivacy = instance ?: synchronized(this) {
            instance ?: NearbyPrivacy(
                context.applicationContext
                    .getSharedPreferences("nearby_privacy", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
