package aether.desktop.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Проигрывание видео (кружки и обычные ролики) внутри приложения.
 *
 * Декодер — ffmpeg через bytedeco: контейнеры те же, что шлют остальные
 * клиенты (MP4/H.264+AAC, WebM/VP8+Opus), а SPI-декодеров видео, в отличие
 * от звука, в JVM нет вовсе.
 *
 * Каждому сообщению — свой экземпляр: в ленте у кружка живёт только постер
 * (первый кадр из [open]), декодирует лишь тот, что реально играет. Кадры
 * уходят в [frame], звук — в SourceDataLine; темп задаёт звук (блокирующая
 * запись в линию), кадры показываются по своим PTS и догоняют при отставании.
 */
class VideoPlayback {

    data class Metadata(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val hasAudio: Boolean,
    )

    data class State(
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    ) {
        val progress: Float
            get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /** Текущий кадр; до пуска — постер (первый кадр файла). */
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Флаги воркера живут в объекте конкретного запуска: stop() не ждёт
     * завершения потока (иначе close() из DisposableEffect подвесил бы UI),
     * а новый пуск не может «воскресить» уже остановленный воркер.
     */
    private class Control {
        @Volatile var stop = false
        @Volatile var paused = false
        @Volatile var seekToMs = -1L
    }

    private val lock = Any()
    private var file: File? = null
    private var metadata: Metadata? = null
    private var poster: ImageBitmap? = null
    private var control: Control? = null
    private var worker: Thread? = null
    private var startFromMs = 0L

    /**
     * Читает метаданные и постер (первый кадр). Только с Dispatchers.IO.
     * Null — файл не открылся или в нём нет видеодорожки.
     */
    fun open(source: File): Metadata? = synchronized(lock) {
        stopLocked()
        val grabber = newGrabber(source)
        try {
            grabber.start()
            val meta = Metadata(
                width = grabber.imageWidth,
                height = grabber.imageHeight,
                durationMs = grabber.lengthInTime / 1000,
                hasAudio = grabber.audioChannels > 0,
            )
            if (meta.width <= 0 || meta.height <= 0) return null
            poster = grabber.grabImage()?.let { toImageBitmap(it) }
            file = source
            metadata = meta
            startFromMs = 0
            _frame.value = poster
            _state.value = State(durationMs = meta.durationMs)
            return meta
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    /** Пуск с начала (или с места seek/паузы). Сам по себе не блокирует UI. */
    fun play() {
        synchronized(lock) {
            val ctl = control
            if (ctl != null && worker?.isAlive == true) {
                ctl.paused = false
                _state.value = _state.value.copy(playing = true)
                return
            }
            val source = file ?: return
            val meta = metadata ?: return
            val fresh = Control()
            fresh.seekToMs = if (startFromMs > 0) startFromMs else -1
            startFromMs = 0
            control = fresh
            _state.value = _state.value.copy(playing = true)
            worker = Thread({ decodeLoop(fresh, source, meta) }, "aether-video").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun pause() {
        control?.paused = true
        _state.value = _state.value.copy(playing = false)
    }

    fun seek(ms: Long) {
        val clamped = ms.coerceAtLeast(0)
        val ctl = control
        if (ctl != null && worker?.isAlive == true) {
            ctl.seekToMs = clamped
        } else {
            startFromMs = clamped
            _state.value = _state.value.copy(positionMs = clamped)
        }
    }

    /** Останов с возвратом постера — файл остаётся открытым для нового пуска. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /** Полное освобождение при выходе из композиции. */
    fun close() {
        synchronized(lock) {
            control?.stop = true
            control = null
            worker = null
            file = null
            metadata = null
            poster = null
            _frame.value = null
            _state.value = State()
        }
    }

    private fun stopLocked() {
        control?.stop = true
        control = null
        worker = null
        _frame.value = poster
        _state.value = State(durationMs = metadata?.durationMs ?: 0)
    }

    private fun decodeLoop(ctl: Control, source: File, meta: Metadata) {
        val grabber = newGrabber(source)
        var line: SourceDataLine? = null
        try {
            grabber.start()
            if (grabber.audioChannels > 0) {
                val rate = grabber.sampleRate.toFloat()
                val channels = grabber.audioChannels
                val format = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, 2 * channels, rate, false,
                )
                line = runCatching {
                    (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine).apply {
                        // Буфер ~0,4 с: при большем звук заметно убегает вперёд
                        // кадров сразу после паузы и перемотки.
                        open(format, (rate * 0.4).toInt() * format.frameSize)
                        start()
                    }
                }.getOrNull()
            }
            var anchorNanos = 0L
            var anchorPtsUs = -1L
            var pauseApplied = false
            while (!ctl.stop) {
                val target = ctl.seekToMs
                if (target >= 0) {
                    ctl.seekToMs = -1
                    runCatching { grabber.timestamp = target * 1000 }
                    line?.flush()
                    anchorPtsUs = -1
                    if (!ctl.stop) _state.value = _state.value.copy(positionMs = target)
                }
                if (ctl.paused) {
                    if (!pauseApplied) {
                        runCatching { line?.stop() }
                        pauseApplied = true
                    }
                    Thread.sleep(50)
                    continue
                }
                if (pauseApplied) {
                    runCatching { line?.start() }
                    pauseApplied = false
                    anchorPtsUs = -1
                }
                val grabbed = grabber.grab() ?: break
                if (grabbed.image != null) {
                    val ptsUs = grabbed.timestamp
                    if (anchorPtsUs < 0) {
                        anchorPtsUs = ptsUs
                        anchorNanos = System.nanoTime()
                    }
                    // Кадр не раньше своего PTS; опоздавший — сразу. Спим
                    // короткими кусками, чтобы stop() отзывался мгновенно.
                    while (!ctl.stop && !ctl.paused && ctl.seekToMs < 0) {
                        val waitNs = anchorNanos + (ptsUs - anchorPtsUs) * 1000 - System.nanoTime()
                        if (waitNs < 1_000_000) break
                        Thread.sleep((waitNs / 1_000_000).coerceAtMost(50))
                    }
                    if (ctl.stop) break
                    toImageBitmap(grabbed)?.let { _frame.value = it }
                    _state.value = _state.value.copy(playing = true, positionMs = ptsUs / 1000)
                } else if (grabbed.samples != null && line != null) {
                    val bytes = pcmBytes(grabbed.samples)
                    line.write(bytes, 0, bytes.size)
                }
            }
            if (!ctl.stop) {
                runCatching { line?.drain() }
                _frame.value = poster ?: _frame.value
                _state.value = State(durationMs = meta.durationMs)
            }
        } catch (_: Exception) {
            // Битый файл или кодек без декодера — просто останавливаемся,
            // постер и кнопка пуска остаются на месте.
            if (!ctl.stop) _state.value = State(durationMs = meta.durationMs)
        } finally {
            runCatching { line?.stop() }
            runCatching { line?.close() }
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    /** ShortBuffer S16 от ffmpeg → little-endian байты для SourceDataLine. */
    private fun pcmBytes(samples: Array<java.nio.Buffer>): ByteArray {
        val source = (samples.getOrNull(0) as? ShortBuffer)?.duplicate() ?: return ByteArray(0)
        val out = ByteArray(source.remaining() * 2)
        var i = 0
        while (source.hasRemaining()) {
            val v = source.get().toInt()
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        /** Одна конфигурация грабера на плеер и смоук. */
        internal fun newGrabber(file: File): FFmpegFrameGrabber = FFmpegFrameGrabber(file).apply {
            // BGRA ложится в Skia без пиксельных перестановок,
            // S16 — сразу формат SourceDataLine.
            pixelFormat = avutil.AV_PIX_FMT_BGRA
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
        }

        /** Кадр ffmpeg (BGRA) → ImageBitmap напрямую через Skia, минуя AWT. */
        internal fun toImageBitmap(frame: Frame): ImageBitmap? = runCatching {
            val width = frame.imageWidth
            val height = frame.imageHeight
            if (width <= 0 || height <= 0) return null
            val source = (frame.image?.getOrNull(0) as? ByteBuffer)?.duplicate() ?: return null
            val rowBytes = width * 4
            // imageStride — байт в строке буфера: у ffmpeg строка бывает
            // выровнена шире, чем width*4, копируем построчно.
            val stride = frame.imageStride
            val pixels = ByteArray(rowBytes * height)
            for (y in 0 until height) {
                source.position(y * stride)
                source.get(pixels, y * rowBytes, rowBytes)
            }
            val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            Image.makeRaster(info, pixels, rowBytes).toComposeImageBitmap()
        }.getOrNull()
    }
}
