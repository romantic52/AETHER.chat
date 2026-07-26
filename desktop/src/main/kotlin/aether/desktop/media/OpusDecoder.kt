package aether.desktop.media

import io.github.jaredmdobson.concentus.OpusDecoder as ConcentusDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.ebml.io.FileDataSource
import org.ebml.matroska.MatroskaFile

/**
 * Распаковка голосовых из веба: контейнер WebM (Matroska), кодек Opus.
 *
 * javax.sound.sampled такого не умеет, а SPI от jaad ошибочно берёт эти файлы
 * на себя и падает на первом же кадре («too many bands»). Поэтому
 * демультиплексируем сами (jebml) и декодируем сами (Concentus) — обе
 * библиотеки на чистой Java, потому что дистрибутив собирается jpackage/jlink
 * и нативные библиотеки в него не положить.
 */
object OpusDecoder {

    /** Распакованный звук: PCM s16le, каналы чередуются. */
    class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

    /** Opus всегда отдаёт 48 кГц независимо от того, на чём его кодировали. */
    private const val OUTPUT_RATE = 48000

    /** Один пакет Opus несёт не больше 120 мс. */
    private const val MAX_FRAME_SAMPLES = OUTPUT_RATE / 1000 * 120

    private const val OPUS_CODEC = "A_OPUS"

    /** Заголовок OpusHead из CodecPrivate дорожки (RFC 7845). */
    private class Head(val channels: Int, val preSkip: Int)

    fun decode(file: File): Pcm {
        FileDataSource(file.absolutePath).use { source ->
            val matroska = MatroskaFile(source)
            matroska.readFile()
            val track = matroska.trackList.firstOrNull { it.codecID.orEmpty().startsWith(OPUS_CODEC) }
                ?: throw IllegalStateException("в контейнере WebM нет дорожки Opus")
            val head = readHead(track.codecPrivate, track.audio?.channels?.toInt() ?: 1)

            val decoder = ConcentusDecoder(OUTPUT_RATE, head.channels)
            val samples = ShortArray(MAX_FRAME_SAMPLES * head.channels)
            val scratch = ByteArray(samples.size * 2)
            val pcm = ByteArrayOutputStream()
            var skipLeft = head.preSkip

            while (true) {
                val block = matroska.nextFrame ?: break
                if (block.trackNo != track.trackNo) continue
                val data = block.data ?: continue
                val packet = ByteArray(data.remaining())
                if (packet.isEmpty()) continue
                data.get(packet)

                val decoded = decoder.decode(packet, 0, packet.size, samples, 0, MAX_FRAME_SAMPLES, false)
                if (decoded <= 0) continue
                // Кодировщик дописывает в начало служебные сэмплы (pre-skip):
                // если их не отрезать, запись начинается со щелчка.
                val first = minOf(skipLeft, decoded)
                skipLeft -= first

                var offset = 0
                for (i in first * head.channels until decoded * head.channels) {
                    val value = samples[i].toInt()
                    scratch[offset++] = (value and 0xFF).toByte()
                    scratch[offset++] = ((value shr 8) and 0xFF).toByte()
                }
                pcm.write(scratch, 0, offset)
            }

            val bytes = pcm.toByteArray()
            if (bytes.isEmpty()) throw IllegalStateException("дорожка Opus распаковалась в пустой поток")
            return Pcm(bytes, OUTPUT_RATE, head.channels)
        }
    }

    /**
     * Число каналов берём из OpusHead, а не из дорожки Matroska: декодер обязан
     * быть настроен ровно на то, чем кодировали, иначе он рассыпает пакеты.
     */
    private fun readHead(codecPrivate: ByteBuffer?, trackChannels: Int): Head {
        val head = codecPrivate?.duplicate()?.rewind() ?: return Head(trackChannels, 0)
        if (head.remaining() < HEAD_SIZE) return Head(trackChannels, 0)
        val channels = head.get(CHANNELS_OFFSET).toInt() and 0xFF
        val low = head.get(PRE_SKIP_OFFSET).toInt() and 0xFF
        val high = head.get(PRE_SKIP_OFFSET + 1).toInt() and 0xFF
        return Head(channels.coerceIn(1, 2), (high shl 8) or low)
    }

    private const val HEAD_SIZE = 19
    private const val CHANNELS_OFFSET = 9
    private const val PRE_SKIP_OFFSET = 10
}
