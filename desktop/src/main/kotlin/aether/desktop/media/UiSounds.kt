package aether.desktop.media

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Короткие сигналы отправки и получения, как в Telegram Desktop.
 *
 * Тоны синтезируются, а не лежат файлами: так дистрибутив остаётся без
 * бинарных ресурсов, а звук одинаков на любой машине.
 */
object UiSounds {

    @Volatile var enabled: Boolean = true

    private val sent by lazy { render(listOf(660.0 to 0.05, 990.0 to 0.07)) }
    private val received by lazy { render(listOf(880.0 to 0.05, 587.0 to 0.09)) }

    fun playSent() = play(sent)

    fun playReceived() = play(received)

    private fun play(pcm: ByteArray) {
        if (!enabled) return
        Thread({
            runCatching {
                val info = DataLine.Info(SourceDataLine::class.java, FORMAT)
                (AudioSystem.getLine(info) as SourceDataLine).use { line ->
                    line.open(FORMAT)
                    line.start()
                    line.write(pcm, 0, pcm.size)
                    line.drain()
                }
            }
        }, "aether-ui-sound").apply { isDaemon = true }.start()
    }

    private inline fun <T : SourceDataLine> T.use(block: (T) -> Unit) {
        try {
            block(this)
        } finally {
            runCatching { stop() }
            runCatching { close() }
        }
    }

    /** Последовательность тонов с экспоненциальным затуханием — чтобы не щёлкало. */
    private fun render(tones: List<Pair<Double, Double>>): ByteArray {
        val rate = FORMAT.sampleRate.toDouble()
        val out = java.io.ByteArrayOutputStream()
        tones.forEach { (freq, seconds) ->
            val count = (rate * seconds).toInt()
            for (i in 0 until count) {
                val t = i / rate
                val envelope = exp(-t * 22.0) * (1.0 - exp(-t * 900.0))
                val value = (sin(2 * PI * freq * t) * envelope * 6000).toInt().toShort()
                out.write(value.toInt() and 0xFF)
                out.write((value.toInt() shr 8) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private val FORMAT = AudioFormat(44100f, 16, 1, true, false)
}
