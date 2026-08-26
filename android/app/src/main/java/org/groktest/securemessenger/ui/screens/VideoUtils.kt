package org.groktest.securemessenger.ui.screens

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Обрезка MP4-кружка без перекодирования. */
object VideoUtils {
    fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    fun trim(input: File, out: File, startMs: Long, endMs: Long): Boolean {
        val total = durationMs(input)
        val lastMs = if (endMs <= 0L) total else endMs.coerceAtMost(total)
        if (startMs <= 0L && lastMs >= total) {
            return try {
                input.copyTo(out, overwrite = true)
                true
            } catch (_: Exception) {
                false
            }
        }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }
            muxer = MediaMuxer(
                out.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            val trackMap = HashMap<Int, Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(index)
                    trackMap[index] = muxer.addTrack(format)
                }
            }
            if (trackMap.isEmpty()) {
                out.delete()
                return false
            }

            muxer.start()
            muxerStarted = true
            val startUs = startMs.coerceAtLeast(0L) * 1000L
            val endUs = lastMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // База PTS — самый ранний сэмпл после seek (первый в порядке чтения, обычно
            // ключевой кадр видео). База ОБЩАЯ для всех треков: независимый ноль на каждый
            // трек сдвинул бы аудио относительно видео на величину до целого GOP —
            // MPEG4Writer сохраняет взаимные смещения дорожек, поэтому синхронизацию
            // держит именно единая точка отсчёта.
            var baseUs = -1L
            // Треки, чьи сэмплы уже вышли за endUs: пропускаем их, но цикл не рвём —
            // иначе из-за интерливинга второй трек обрезался бы раньше первого.
            val finishedTracks = HashSet<Int>()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break // сэмплы кончились во всех треках
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) {
                    finishedTracks.add(trackIndex)
                    // Завершаем, только когда ВСЕ треки прошли endUs.
                    if (finishedTracks.size >= trackMap.size) break
                    extractor.advance()
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                trackMap[trackIndex]?.let { muxTrack ->
                    if (baseUs < 0L) baseUs = sampleTimeUs.coerceAtLeast(0L)
                    val codecFlags = if (
                        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    ) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    info.set(
                        0,
                        size,
                        (sampleTimeUs - baseUs).coerceAtLeast(0L),
                        codecFlags,
                    )
                    muxer.writeSampleData(muxTrack, buffer, info)
                }
                extractor.advance()
            }

            muxer.stop()
            muxerStarted = false
            if (out.exists() && out.length() > 0L) {
                true
            } else {
                // Пустой результат не оставляем в cacheDir.
                out.delete()
                false
            }
        } catch (_: Exception) {
            out.delete()
            false
        } finally {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
}
