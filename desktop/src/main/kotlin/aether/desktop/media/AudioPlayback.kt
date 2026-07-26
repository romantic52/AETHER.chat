package aether.desktop.media

import java.io.BufferedInputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Проигрывание голосовых внутри приложения (раньше открывался системный плеер).
 *
 * Форматы: AAC/MP4 с Android и MP3 — через SPI-декодеры javax.sound.sampled,
 * WAV — штатно. Голосовые короткие, поэтому распаковываем целиком в память:
 * это даёт мгновенную перемотку по клику на волне.
 *
 * Одновременно играет ровно один трек: старт нового останавливает предыдущий.
 */
object AudioPlayback {

    data class State(
        val id: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    ) {
        val progress: Float
            get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val lock = Any()
    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    @Volatile private var seekToMs: Long? = null

    /** true — формат распознан и играется внутри приложения. */
    fun isSupported(file: File): Boolean = runCatching {
        AudioSystem.getAudioFileFormat(file)
        true
    }.getOrDefault(false)

    /** Пуск/пауза для сообщения [id]. Возвращает false, если формат не поддержан. */
    fun toggle(id: String, file: File): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (current.id == id && worker?.isAlive == true) {
                paused = !paused
                _state.value = current.copy(playing = !paused)
                return true
            }
            stopInternal()
            val decoded = runCatching { decode(file) }.getOrNull() ?: return false
            stopRequested = false
            paused = false
            seekToMs = null
            _state.value = State(id = id, playing = true, positionMs = 0, durationMs = decoded.durationMs)
            worker = Thread({ play(id, decoded) }, "aether-audio").apply {
                isDaemon = true
                start()
            }
            return true
        }
    }

    fun seek(id: String, fraction: Float) {
        val current = _state.value
        if (current.id != id || current.durationMs <= 0) return
        seekToMs = (current.durationMs * fraction.coerceIn(0f, 1f)).toLong()
    }

    fun stop() {
        synchronized(lock) { stopInternal() }
    }

    private fun stopInternal() {
        stopRequested = true
        paused = false
        worker?.join(400)
        worker = null
        _state.value = State()
    }

    private class Decoded(val pcm: ByteArray, val format: AudioFormat) {
        val bytesPerMs: Double = format.frameRate.toDouble() * format.frameSize / 1000.0
        val durationMs: Long = if (bytesPerMs > 0) (pcm.size / bytesPerMs).toLong() else 0
    }

    private fun decode(file: File): Decoded {
        // Именно BufferedInputStream, а не File: у mp3-SPI на файловом потоке
        // распаковывается ровно один кадр (0,05 с вместо 10 с) — ему нужен
        // поток с mark/reset достаточной глубины.
        val source = AudioSystem.getAudioInputStream(BufferedInputStream(file.inputStream(), 1 shl 18))
        source.use { encoded ->
            val base = encoded.format
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                if (base.sampleRate > 0) base.sampleRate else 48000f,
                16,
                base.channels.coerceAtLeast(1),
                2 * base.channels.coerceAtLeast(1),
                if (base.sampleRate > 0) base.sampleRate else 48000f,
                false,
            )
            AudioSystem.getAudioInputStream(target, encoded).use { pcmStream ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(1 shl 16)
                while (true) {
                    val read = pcmStream.read(chunk)
                    if (read < 0) break
                    out.write(chunk, 0, read)
                }
                val bytes = out.toByteArray()
                if (bytes.isEmpty()) throw IllegalStateException("декодер вернул пустой поток")
                return Decoded(bytes, target)
            }
        }
    }

    private fun play(id: String, decoded: Decoded) {
        val info = DataLine.Info(SourceDataLine::class.java, decoded.format)
        val line = runCatching { AudioSystem.getLine(info) as SourceDataLine }.getOrNull() ?: run {
            _state.value = State()
            return
        }
        try {
            line.open(decoded.format)
            line.start()
            val frameSize = decoded.format.frameSize.coerceAtLeast(1)
            val chunk = ByteArray(frameSize * 1024)
            var offset = 0
            while (offset < decoded.pcm.size && !stopRequested) {
                seekToMs?.let { target ->
                    seekToMs = null
                    val bytes = (target * decoded.bytesPerMs).toInt()
                    offset = (bytes - bytes % frameSize).coerceIn(0, decoded.pcm.size)
                    line.flush()
                }
                if (paused) {
                    Thread.sleep(60)
                    continue
                }
                val size = minOf(chunk.size, decoded.pcm.size - offset)
                System.arraycopy(decoded.pcm, offset, chunk, 0, size)
                line.write(chunk, 0, size)
                offset += size
                val positionMs = (offset / decoded.bytesPerMs).toLong()
                val snapshot = _state.value
                if (snapshot.id == id) {
                    _state.value = snapshot.copy(positionMs = positionMs)
                }
            }
            if (!stopRequested) line.drain()
        } catch (_: Exception) {
            // Формат оказался неиграбельным уже на линии — молча выходим,
            // UI покажет кнопку «открыть во внешнем плеере».
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
            if (_state.value.id == id) _state.value = State()
        }
    }
}
