package aether.desktop

import aether.desktop.media.VideoPlayback
import java.io.File

/**
 * Смоук видеодекодера без UI: .\gradlew.bat videosmoke --args="<file.mp4>"
 *
 * Проверяет ровно тот путь, которым идут кружки и видео в ленте: открытие
 * через VideoPlayback (метаданные + постер), затем декодирование первых
 * 30 кадров грабером в той же конфигурации, что у плеера.
 */
fun main(args: Array<String>) {
    val file = File(args.firstOrNull() ?: error("нужен путь к видеофайлу"))
    if (!file.isFile) error("${file.path}: нет файла")
    println("=== ${file.name}, ${file.length()} байт ===")

    val playback = VideoPlayback()
    val meta = playback.open(file) ?: error("VideoPlayback.open не открыл файл")
    println(
        "метаданные: ${meta.width}x${meta.height}, ${meta.durationMs} мс, " +
            if (meta.hasAudio) "со звуком" else "без звука",
    )
    val poster = playback.frame.value
    println("постер: " + (poster?.let { "${it.width}x${it.height}" } ?: "НЕТ"))
    playback.close()

    val grabber = VideoPlayback.newGrabber(file)
    grabber.start()
    println("fps по контейнеру: ${"%.2f".format(grabber.frameRate)}")
    var decoded = 0
    val startedAt = System.nanoTime()
    while (decoded < 30) {
        val frame = grabber.grabImage() ?: break
        val bitmap = VideoPlayback.toImageBitmap(frame)
        if (bitmap == null) {
            println("кадр ${decoded + 1}: НЕ СКОНВЕРТИРОВАЛСЯ")
            break
        }
        decoded++
        if (decoded == 1 || decoded == 30) {
            println("кадр $decoded: ${bitmap.width}x${bitmap.height}, pts=${frame.timestamp / 1000} мс")
        }
    }
    val elapsed = (System.nanoTime() - startedAt) / 1e9
    println(
        "декодировано $decoded кадров за ${"%.2f".format(elapsed)} с" +
            if (elapsed > 0) " (${"%.1f".format(decoded / elapsed)} кадр/с)" else "",
    )
    grabber.stop()
    grabber.release()
}
