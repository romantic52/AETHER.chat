package aether.desktop.data

import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Куда идут байты: наружу (отправка) или к нам (скачивание). */
enum class TransferDirection { UPLOAD, DOWNLOAD }

/** Состояние одной передачи медиа. */
data class Transfer(
    val id: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val direction: TransferDirection,
    val done: Boolean = false,
    val failed: Boolean = false,
) {
    /** null, когда размер неизвестен: индикатор рисуется бесконечным. */
    val fraction: Float?
        get() = if (bytesTotal > 0L) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else null

    val percent: Int
        get() = fraction?.let { (it * 100f).toInt() } ?: 0
}

/**
 * Реестр идущих сейчас передач медиа. Ключ — clientId исходящего сообщения
 * либо file_id входящего: по нему пузырь в ленте находит свою передачу.
 *
 * Синглтон, потому что передача переживает и пересоздание композиции, и уход
 * из чата, а UI должен подхватить её прогресс при возвращении.
 */
object TransferProgress {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers: StateFlow<Map<String, Transfer>> = _transfers

    fun start(id: String, bytesTotal: Long, direction: TransferDirection) {
        _transfers.update { current ->
            current + (id to Transfer(id, 0L, bytesTotal.coerceAtLeast(0L), direction))
        }
    }

    fun update(id: String, bytesDone: Long, bytesTotal: Long) {
        _transfers.update { current ->
            // Завершённую или ещё не начатую передачу не воскрешаем: иначе
            // запоздавший колбэк потока вернёт индикатор на экран.
            val before = current[id]?.takeIf { !it.done && !it.failed } ?: return@update current
            if (!worthEmitting(before, bytesDone, bytesTotal)) return@update current
            current + (id to before.copy(
                bytesDone = bytesDone.coerceAtLeast(0L),
                bytesTotal = bytesTotal.coerceAtLeast(0L),
            ))
        }
    }

    fun finish(id: String) {
        _transfers.update { current ->
            val before = current[id] ?: return@update current
            current + (id to before.copy(
                bytesDone = if (before.bytesTotal > 0L) before.bytesTotal else before.bytesDone,
                done = true,
            ))
        }
        scheduleRemoval(id, DONE_LINGER_MS)
    }

    fun fail(id: String) {
        _transfers.update { current ->
            val before = current[id] ?: return@update current
            current + (id to before.copy(failed = true))
        }
        scheduleRemoval(id, FAILED_LINGER_MS)
    }

    /**
     * Колбэк потока зовётся на каждый прочитанный буфер: без отсечки мелких
     * шагов файл на сотню мегабайт даст тысячи перерисовок ленты. Возврат той
     * же карты StateFlow не публикует, так что отсечка ничего не стоит.
     */
    private fun worthEmitting(before: Transfer, bytesDone: Long, bytesTotal: Long): Boolean {
        if (bytesTotal != before.bytesTotal) return true
        if (bytesTotal > 0L && bytesDone >= bytesTotal) return true
        val step = (bytesTotal / 100L).coerceAtLeast(MIN_STEP_BYTES)
        return bytesDone - before.bytesDone >= step
    }

    /**
     * Запись снимаем с задержкой, чтобы подписчик успел увидеть конечное
     * состояние, но карта не росла до конца сессии. Сверяем по идентичности:
     * если за время ожидания под тем же ключом началась новая передача, её
     * запись трогать нельзя.
     */
    private fun scheduleRemoval(id: String, delayMs: Long) {
        val snapshot = _transfers.value[id] ?: return
        scope.launch {
            delay(delayMs)
            _transfers.update { current -> if (current[id] === snapshot) current - id else current }
        }
    }

    private const val MIN_STEP_BYTES = 64L * 1024L
    private const val DONE_LINGER_MS = 500L
    private const val FAILED_LINGER_MS = 4_000L
}

/**
 * Размер по-русски: «1,4 МБ». Локаль задана явно, иначе на английской системе
 * разделителем дробной части станет точка.
 */
fun formatBytes(bytes: Long): String {
    val ru = Locale.forLanguageTag("ru")
    return when {
        bytes <= 0L -> "0 Б"
        bytes < 1024L -> "$bytes Б"
        bytes < 1024L * 1024L -> String.format(ru, "%.0f КБ", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(ru, "%.1f МБ", bytes / (1024.0 * 1024.0))
        else -> String.format(ru, "%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
