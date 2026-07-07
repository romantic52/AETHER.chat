package org.groktest.securemessenger.ui.screens

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Обрезка видео-кружка (mp4) по диапазону [startMs, endMs] без перекодирования:
 * копируем сэмплы видео+аудио через MediaExtractor → MediaMuxer. Старт прижимается
 * к ближайшему ключевому кадру (SEEK_TO_PREVIOUS_SYNC) — иначе видео «рассыплется».
 */
object VideoUtils {

    fun durationMs(file: File): Long = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release(); d
    } catch (e: Exception) { 0L }

    fun trim(input: File, out: File, startMs: Long, endMs: Long): Boolean {
        val total = durationMs(input)
        if (startMs <= 0 && (endMs <= 0 || endMs >= total)) {
            return try { input.copyTo(out, overwrite = true); true } catch (e: Exception) { false }
        }
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(input.absolutePath)
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Добавляем все видео/аудио дорожки, запоминаем маппинг extractor→muxer.
            val trackMap = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    ex.selectTrack(i)
                    trackMap[i] = muxer.addTrack(fmt)
                }
            }
            if (trackMap.isEmpty()) { ex.release(); return false }
            muxer.start()

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val base = ex.sampleTime.coerceAtLeast(0)

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val trackIdx = ex.sampleTrackIndex
                if (trackIdx < 0) break
                val muxTrack = trackMap[trackIdx]
                val t = ex.sampleTime
                if (t > endUs) break
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) break
                if (muxTrack != null) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (t - base).coerceAtLeast(0)
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(muxTrack, buffer, info)
                }
                ex.advance()
            }
            muxer.stop(); muxer.release(); ex.release()
            true
        } catch (e: Exception) { false }
    }
}
