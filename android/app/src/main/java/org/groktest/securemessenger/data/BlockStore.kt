package org.groktest.securemessenger.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf

/**
 * Локальный чёрный список: входящие сообщения от заблокированных просто
 * отбрасываются (и подтверждаются серверу, чтобы не приходили снова).
 * Хранится в SharedPreferences; кэш в памяти для быстрого фильтра из
 * репозитория (без контекста) и реактивного UI (mutableStateList).
 */
object BlockStore {
    private const val PREF = "block_store"
    private const val KEY = "blocked"

    /** Заблокированные peerId (lowercase). Реактивно для UI. */
    val blocked = mutableStateListOf<String>()

    fun init(context: Context) {
        val set = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()
        blocked.clear()
        blocked.addAll(set.map { it.lowercase() })
    }

    fun isBlocked(peerId: String): Boolean = blocked.contains(peerId.lowercase())

    fun block(context: Context, peerId: String) {
        val id = peerId.lowercase()
        if (!blocked.contains(id)) blocked.add(id)
        persist(context)
    }

    fun unblock(context: Context, peerId: String) {
        blocked.remove(peerId.lowercase())
        persist(context)
    }

    fun toggle(context: Context, peerId: String) {
        if (isBlocked(peerId)) unblock(context, peerId) else block(context, peerId)
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, blocked.toSet()).apply()
    }
}
