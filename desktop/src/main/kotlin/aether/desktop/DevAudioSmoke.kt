package aether.desktop

import aether.desktop.media.VoiceRecorder
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Проверка звукового тракта без UI: .\gradlew.bat audiosmoke --args="<file> <file> ..."
 *
 * Голосовые приходят в трёх контейнерах — MP4/AAC (Android, iOS), WebM/Opus
 * (веб на Chrome/Firefox) и MP3 (десктоп). Смоук говорит, какие из них
 * действительно распаковываются установленными SPI-декодерами, а какие
 * пришлось бы отдавать системному плееру.
 */
fun main(args: Array<String>) {
    println("=== SPI-декодеры ===")
    AudioSystem.getAudioFileTypes().forEach { print("$it ") }
    println()

    args.forEach { path -> probe(File(path)) }

    println("=== round-trip записи (jump3r → SPI) ===")
    roundTrip()
}

private fun probe(file: File) {
    if (!file.isFile) {
        println("${file.name}: НЕТ ФАЙЛА")
        return
    }
    val header = file.inputStream().use { stream ->
        ByteArray(12).also { stream.read(it) }
    }
    val container = when {
        header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() -> "webm/matroska"
        String(header, 4, 4, Charsets.US_ASCII) == "ftyp" -> "mp4"
        String(header, 0, 4, Charsets.US_ASCII) == "OggS" -> "ogg"
        header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() -> "mp3 (id3)"
        else -> "?"
    }
    listOf("file" to false, "buffered" to true).forEach { (label, buffered) ->
        println("  [$label] " + decodeVia(file, buffered))
    }
}

private fun decodeVia(file: File, buffered: Boolean): String {
    val result = runCatching {
        val source = if (buffered) {
            AudioSystem.getAudioInputStream(java.io.BufferedInputStream(file.inputStream(), 1 shl 18))
        } else {
            AudioSystem.getAudioInputStream(file)
        }
        source.use { encoded ->
            val base = encoded.format
            val rate = if (base.sampleRate > 0) base.sampleRate else 48000f
            val channels = base.channels.coerceAtLeast(1)
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, 2 * channels, rate, false,
            )
            AudioSystem.getAudioInputStream(target, encoded).use { pcm ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = pcm.read(chunk)
                    if (read < 0) break
                    out.write(chunk, 0, read)
                }
                val bytes = out.toByteArray()
                "OK ${base.encoding} ${rate.toInt()}Hz ch=$channels → " +
                    "${bytes.size} байт PCM = ${"%.2f".format(bytes.size / (rate * 2 * channels))} c"
            }
        }
    }.getOrElse { "ОШИБКА ${it.javaClass.simpleName}: ${it.message}" }
    return result
}

private fun roundTrip() {
    // Синтезируем 2 секунды 440 Гц и прогоняем через тот же кодировщик,
    // которым пишутся голосовые с микрофона.
    val rate = 48000
    val pcm = ByteArray(rate * 2 * 2)
    for (i in 0 until rate * 2) {
        val v = (Math.sin(2 * Math.PI * 440 * i / rate) * 12000).toInt().toShort()
        pcm[i * 2] = (v.toInt() and 0xFF).toByte()
        pcm[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }
    val mp3 = VoiceRecorder.encodeMp3(pcm)
    val file = File.createTempFile("aether_roundtrip", ".mp3")
    file.writeBytes(mp3)
    println("закодировано ${mp3.size} байт mp3 из ${pcm.size} байт PCM")
    probe(file)
    file.delete()
}
