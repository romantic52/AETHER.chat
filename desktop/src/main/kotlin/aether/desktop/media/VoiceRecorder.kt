package aether.desktop.media

import de.sciss.jump3r.lowlevel.LameEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Запись голосовых с микрофона. Захват — javax.sound.sampled, кодирование — MP3
 * (jump3r, чистая Java): его играют и Android, и iOS, и веб, в отличие от WAV,
 * который раздувает трафик в тридцать раз.
 *
 * Волна считается так же, как на Android: столбик 0..31 на каждые 100 мс.
 */
class VoiceRecorder {

    data class State(
        val recording: Boolean = false,
        val millis: Long = 0,
        val level: Float = 0f,
        val waveform: List<Int> = emptyList(),
    )

    data class Recording(val file: File, val durationMs: Long, val waveform: List<Int>)

    /** Сырой захват: кодирование из него делается уже вне UI-потока. */
    data class Captured(val pcm: ByteArray, val waveform: List<Int>)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    @Volatile private var stopping = false
    private val pcm = ByteArrayOutputStream()

    /** true — микрофон доступен и запись пошла. */
    fun start(): Boolean {
        if (_state.value.recording) return true
        val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) return false
        val opened = runCatching {
            (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(format)
                start()
            }
        }.getOrNull() ?: return false

        line = opened
        pcm.reset()
        stopping = false
        _state.value = State(recording = true)
        worker = Thread({ capture(opened) }, "aether-voice").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * Останавливает запись и отдаёт сырой PCM. Кодирование здесь НЕ делается:
     * минута звука жмётся в MP3 несколько секунд, и на UI-потоке окно бы
     * замирало ровно в момент отправки.
     */
    fun stop(): Captured? {
        if (!_state.value.recording) return null
        stopping = true
        worker?.join(600)
        worker = null
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null

        val snapshot = _state.value
        _state.value = State()
        val raw = pcm.toByteArray()
        pcm.reset()
        // Меньше полусекунды — считаем случайным нажатием.
        if (raw.size < SAMPLE_RATE) return null
        return Captured(raw, snapshot.waveform)
    }

    /** Отмена без отправки (свайп/Esc в UI). */
    fun cancel() {
        if (!_state.value.recording) return
        stopping = true
        worker?.join(1000)
        worker = null
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
        pcm.reset()
        _state.value = State()
    }

    private fun capture(source: TargetDataLine) {
        val chunk = ByteArray(source.bufferSize / 5)
        val startedAt = System.currentTimeMillis()
        val wave = ArrayList<Int>()
        var bucketPeak = 0
        var bucketBytes = 0
        val bucketSize = (SAMPLE_RATE * 2 * 0.1).toInt() // 100 мс
        try {
            while (!stopping) {
                val read = source.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                pcm.write(chunk, 0, read)
                var index = 0
                while (index + 1 < read) {
                    val sample = ((chunk[index + 1].toInt() shl 8) or (chunk[index].toInt() and 0xFF)).toShort()
                    bucketPeak = maxOf(bucketPeak, abs(sample.toInt()))
                    index += 2
                }
                bucketBytes += read
                if (bucketBytes >= bucketSize) {
                    // Логарифмическая шкала — как в Android-клиенте.
                    val level = (31.0 * ln(1.0 + bucketPeak) / ln(1.0 + 32767.0)).toInt().coerceIn(0, 31)
                    wave.add(level)
                    _state.value = State(
                        recording = true,
                        millis = System.currentTimeMillis() - startedAt,
                        level = level / 31f,
                        waveform = ArrayList(wave),
                    )
                    bucketPeak = 0
                    bucketBytes = 0
                }
            }
        } catch (_: Exception) {
            // Микрофон отобрали — просто заканчиваем, UI увидит recording=false.
        }
    }

    companion object {
        const val SAMPLE_RATE = 48000f
        // 64 кбит/с — как на Android. На 48 LAME сам роняет частоту до 32 кГц,
        // и голос звучит заметно глуше.
        const val BITRATE_KBPS = 64

        /** Тяжёлая часть: вызывать только вне UI-потока. */
        fun encode(captured: Captured): Recording {
            val file = File.createTempFile("aether_voice", ".mp3")
            file.writeBytes(encodeMp3(captured.pcm))
            val durationMs = (captured.pcm.size / (SAMPLE_RATE * 2.0) * 1000).toLong()
            return Recording(file, durationMs, normalize(captured.waveform))
        }

        /**
         * Тихий микрофон даёт волну почти в ноль, и сообщение выглядит пустой
         * полоской. Растягиваем до полной высоты, но не больше чем вчетверо,
         * иначе шум тишины превращается в бурю.
         */
        private fun normalize(waveform: List<Int>): List<Int> {
            val peak = waveform.maxOrNull() ?: return waveform
            if (peak < 2) return waveform
            val gain = minOf(31.0 / peak, 4.0)
            if (gain <= 1.0) return waveform
            return waveform.map { (it * gain).toInt().coerceIn(0, 31) }
        }

        /** PCM s16le mono 48 кГц → MP3. Вынесено, чтобы смоук гонял ровно тот же кодек. */
        fun encodeMp3(raw: ByteArray): ByteArray {
            val encoder = LameEncoder(
                AudioFormat(SAMPLE_RATE, 16, 1, true, false),
                BITRATE_KBPS,
                LameEncoder.CHANNEL_MODE_MONO,
                LameEncoder.QUALITY_MIDDLE,
                false,
            )
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(encoder.getMP3BufferSize())
            var offset = 0
            while (offset < raw.size) {
                val size = minOf(encoder.pcmBufferSize, raw.size - offset)
                val written = encoder.encodeBuffer(raw, offset, size, buffer)
                if (written > 0) out.write(buffer, 0, written)
                offset += size
            }
            val tail = ByteArray(maxOf(7200, encoder.getMP3BufferSize()))
            val flushed = encoder.encodeFinish(tail)
            if (flushed > 0) out.write(tail, 0, flushed)
            encoder.close()
            return out.toByteArray()
        }
    }
}
