package org.groktest.securemessenger.ui.screens

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Быстрая склейка и обрезка AAC/M4A без перекодирования. */
object VoiceUtils {
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

    private fun audioTrackOf(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            ) return index
        }
        return -1
    }

    fun concat(segments: List<File>, out: File): Boolean {
        val files = segments.filter { it.exists() && it.length() > 0L }
        if (files.isEmpty()) return false
        if (files.size == 1) {
            return try {
                files.first().copyTo(out, overwrite = true)
                true
            } catch (_: Exception) {
                false
            }
        }

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            muxer = MediaMuxer(
                out.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            var muxTrack = -1
            var offsetUs = 0L
            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()

            for (segment in files) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.absolutePath)
                    val audioTrack = audioTrackOf(extractor)
                    if (audioTrack < 0) continue
                    extractor.selectTrack(audioTrack)
                    if (muxTrack < 0) {
                        muxTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
                        muxer.start()
                        muxerStarted = true
                    }

                    var firstPtsUs = -1L
                    var lastPtsUs = 0L
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                        if (firstPtsUs < 0L) firstPtsUs = sampleTimeUs
                        val normalizedUs = (sampleTimeUs - firstPtsUs).coerceAtLeast(0L)
                        info.set(
                            0,
                            size,
                            offsetUs + normalizedUs,
                            extractor.sampleFlags
                        )
                        muxer.writeSampleData(muxTrack, buffer, info)
                        lastPtsUs = normalizedUs
                        extractor.advance()
                    }
                    offsetUs += lastPtsUs + 23_220L
                } finally {
                    extractor.release()
                }
            }

            if (!muxerStarted) return false
            muxer.stop()
            muxerStarted = false
            out.exists() && out.length() > 0L
        } catch (_: Exception) {
            out.delete()
            false
        } finally {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    fun trim(input: File, out: File, startMs: Long, endMs: Long): Boolean {
        val duration = durationMs(input)
        if (startMs <= 0L && endMs >= duration) {
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
            val audioTrack = audioTrackOf(extractor)
            if (audioTrack < 0) return false
            extractor.selectTrack(audioTrack)

            muxer = MediaMuxer(
                out.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val muxTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
            muxer.start()
            muxerStarted = true

            val startUs = startMs.coerceAtLeast(0L) * 1000L
            val endUs = endMs.coerceAtMost(duration) * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val baseUs = extractor.sampleTime.coerceAtLeast(0L)
            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break
                info.set(
                    0,
                    size,
                    (sampleTimeUs - baseUs).coerceAtLeast(0L),
                    extractor.sampleFlags
                )
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
            }

            muxer.stop()
            muxerStarted = false
            out.exists() && out.length() > 0L
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