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

    fun concat(inputs: List<File>, out: File): Boolean {
        val files = inputs.filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) return false
        if (files.size == 1) {
            return try { files.first().copyTo(out, overwrite = true); true } catch (e: Exception) { false }
        }

        var muxer: MediaMuxer? = null
        val muxTracks = HashMap<String, Int>()
        var offsetUs = 0L
        return try {
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            MediaExtractor().useExtractor(files.first()) { first ->
                for (i in 0 until first.trackCount) {
                    val fmt = first.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    val kind = when {
                        mime.startsWith("video/") -> "video"
                        mime.startsWith("audio/") -> "audio"
                        else -> null
                    } ?: continue
                    muxTracks[kind] = muxer!!.addTrack(fmt)
                }
            }
            if (muxTracks.isEmpty()) return false
            muxer!!.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for (file in files) {
                MediaExtractor().useExtractor(file) { ex ->
                    val trackKinds = HashMap<Int, String>()
                    for (i in 0 until ex.trackCount) {
                        val fmt = ex.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                        val kind = when {
                            mime.startsWith("video/") -> "video"
                            mime.startsWith("audio/") -> "audio"
                            else -> null
                        } ?: continue
                        if (muxTracks.containsKey(kind)) {
                            ex.selectTrack(i)
                            trackKinds[i] = kind
                        }
                    }
                    var baseUs = -1L
                    while (true) {
                        val trackIdx = ex.sampleTrackIndex
                        if (trackIdx < 0) break
                        val kind = trackKinds[trackIdx]
                        val muxTrack = if (kind != null) muxTracks[kind] else null
                        val size = ex.readSampleData(buffer, 0)
                        if (size < 0) break
                        val sampleTime = ex.sampleTime.coerceAtLeast(0)
                        if (baseUs < 0) baseUs = sampleTime
                        if (muxTrack != null) {
                            info.offset = 0
                            info.size = size
                            info.presentationTimeUs = offsetUs + (sampleTime - baseUs).coerceAtLeast(0)
                            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            muxer!!.writeSampleData(muxTrack, buffer, info)
                        }
                        ex.advance()
                    }
                }
                offsetUs += (durationMs(file).coerceAtLeast(1L) * 1000L)
            }
            muxer!!.stop()
            true
        } catch (e: Exception) {
            false
        } finally {
            try { muxer?.release() } catch (e: Exception) {}
        }
    }

    private inline fun MediaExtractor.useExtractor(file: File, block: (MediaExtractor) -> Unit) {
        try {
            setDataSource(file.absolutePath)
            block(this)
        } finally {
            release()
        }
    }
}
