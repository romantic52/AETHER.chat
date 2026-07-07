package org.groktest.securemessenger.ui.screens

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Работа с записанными голосовыми (m4a/AAC): склейка сегментов (для «дописать»)
 * и обрезка диапазона (для «обрезать»). Перекодирования нет — копируем сэмплы
 * через MediaExtractor → MediaMuxer (быстро, без потерь).
 */
object VoiceUtils {

    fun durationMs(file: File): Long = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release(); d
    } catch (e: Exception) { 0L }

    private fun audioTrackOf(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio") == true) return i
        }
        return -1
    }

    /** Склейка нескольких сегментов в один файл (по порядку). */
    fun concat(segments: List<File>, out: File): Boolean {
        val files = segments.filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) return false
        if (files.size == 1) { return try { files[0].copyTo(out, overwrite = true); true } catch (e: Exception) { false } }
        return try {
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var ptsOffsetUs = 0L
            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            for (seg in files) {
                val ex = MediaExtractor()
                ex.setDataSource(seg.absolutePath)
                val at = audioTrackOf(ex)
                if (at < 0) { ex.release(); continue }
                ex.selectTrack(at)
                if (trackIndex < 0) {
                    trackIndex = muxer.addTrack(ex.getTrackFormat(at))
                    muxer.start()
                }
                var maxPts = 0L
                while (true) {
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    val t = ex.sampleTime
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = ptsOffsetUs + t
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(trackIndex, buffer, info)
                    if (t > maxPts) maxPts = t
                    ex.advance()
                }
                // ~один AAC-кадр (1024 сэмпла @44.1кГц) между сегментами
                ptsOffsetUs += maxPts + 23_220
                ex.release()
            }
            muxer.stop(); muxer.release()
            true
        } catch (e: Exception) { false }
    }

    /** Обрезка файла до диапазона [startMs, endMs]. */
    fun trim(input: File, out: File, startMs: Long, endMs: Long): Boolean {
        if (startMs <= 0 && endMs >= durationMs(input)) {
            return try { input.copyTo(out, overwrite = true); true } catch (e: Exception) { false }
        }
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(input.absolutePath)
            val at = audioTrackOf(ex)
            if (at < 0) { ex.release(); return false }
            ex.selectTrack(at)
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndex = muxer.addTrack(ex.getTrackFormat(at))
            muxer.start()
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val baseUs = ex.sampleTime.coerceAtLeast(0)
            val endUs = endMs * 1000
            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t > endUs) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (t - baseUs).coerceAtLeast(0)
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(trackIndex, buffer, info)
                ex.advance()
            }
            muxer.stop(); muxer.release(); ex.release()
            true
        } catch (e: Exception) { false }
    }
}
