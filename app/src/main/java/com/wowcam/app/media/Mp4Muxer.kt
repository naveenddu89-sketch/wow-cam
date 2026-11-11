package com.wowcam.app.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Losslessly combines the CameraX video track and the separately captured studio audio
 * track into one MP4. No video or audio is re-encoded.
 *
 * Samples are streamed through two small reusable buffers and written in presentation-time
 * order, so peak memory stays flat regardless of clip length.
 */
object Mp4Muxer {

    data class Result(
        val hasAudio: Boolean,
        val durationUs: Long
    )

    private const val TAG = "WowCamMuxer"
    private const val MIN_BUFFER_BYTES = 1 * 1024 * 1024
    private const val MAX_BUFFER_BYTES = 32 * 1024 * 1024

    /**
     * @param audioOffsetUs (audioStartNs - videoStartNs) / 1000. A negative value means the
     *                      microphone started before the first video frame; leading audio is
     *                      trimmed so the first kept sample aligns with video time zero.
     */
    @Throws(IOException::class)
    fun mux(
        videoFile: File,
        audioFile: File?,
        audioOffsetUs: Long,
        destination: VideoDestination
    ): Result {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            throw IOException("Video file is empty")
        }

        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrackIn = findTrack(videoExtractor, "video/")
            if (videoTrackIn < 0) throw IOException("No video track in CameraX output")
            videoExtractor.selectTrack(videoTrackIn)

            val videoFormat = videoExtractor.getTrackFormat(videoTrackIn)
            if (videoExtractor.sampleTime < 0) throw IOException("Video track has no samples")

            val rotation = readRotation(videoFormat, videoFile)
            val videoDurationUs = readDurationUs(videoFormat)

            if (audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
                try {
                    val extractor = MediaExtractor()
                    extractor.setDataSource(audioFile.absolutePath)
                    val audioTrackIn = findTrack(extractor, "audio/")
                    if (audioTrackIn >= 0) {
                        extractor.selectTrack(audioTrackIn)
                        if (extractor.sampleTime >= 0) {
                            audioExtractor = extractor
                        } else {
                            extractor.release()
                        }
                    } else {
                        extractor.release()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Audio track could not be opened; muxing video only", t)
                    audioExtractor = null
                }
            }

            val encoderMuxer = destination.openMuxer()
            muxer = encoderMuxer

            if (rotation != 0) {
                encoderMuxer.setOrientationHint(rotation)
            }

            val videoTrackOut = encoderMuxer.addTrack(videoFormat)
            val audioTrackOut = if (audioExtractor != null) {
                val audioFormat = audioExtractor.getTrackFormat(audioTrackIn(audioExtractor))
                encoderMuxer.addTrack(audioFormat)
            } else {
                -1
            }

            encoderMuxer.start()
            muxerStarted = true

            val videoBaseUs = videoExtractor.sampleTime
            val audioShiftUs = audioOffsetUs - videoBaseUs
            val videoBuffer = SampleBuffer(videoFormat)
            val audioBuffer = audioExtractor?.let {
                SampleBuffer(it.getTrackFormat(audioTrackIn(it)))
            }

            var videoTimeUs = videoExtractor.sampleTime
            var audioTimeUs = audioExtractor?.let {
                it.sampleTime + audioShiftUs
            } ?: -1L

            // Drop audio that happened before the first encoded video frame.
            while (audioExtractor != null && audioTimeUs < 0L) {
                audioExtractor.advance()
                val next = audioExtractor.sampleTime
                audioTimeUs = if (next < 0L) -1L else next + audioShiftUs
            }

            var lastVideoPts = -1L
            var lastAudioPts = -1L
            var lastWrittenUs = 0L
            var wroteVideo = false
            var wroteAudio = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (videoTimeUs >= 0L || audioTimeUs >= 0L) {
                val videoAdjusted = if (videoTimeUs >= 0L) {
                    (videoTimeUs - videoBaseUs).coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }

                val takeVideo = videoTimeUs >= 0L &&
                        (audioTimeUs < 0L || videoAdjusted <= audioTimeUs)

                if (takeVideo) {
                    val size = videoBuffer.read(videoExtractor)
                    if (size < 0) break

                    val rawPts = (videoExtractor.sampleTime - videoBaseUs).coerceAtLeast(0L)
                    val flags = videoExtractor.sampleFlags
                    val pts = monotonic(rawPts, lastVideoPts)
                    lastVideoPts = pts

                    writeSample(
                        encoderMuxer,
                        videoTrackOut,
                        videoBuffer.buffer,
                        size,
                        pts,
                        bufferInfo,
                        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                    )
                    lastWrittenUs = maxOf(lastWrittenUs, pts)
                    wroteVideo = true

                    videoExtractor.advance()
                    videoTimeUs = videoExtractor.sampleTime
                } else {
                    val extractor = audioExtractor
                    val buffer = audioBuffer
                    if (extractor == null || buffer == null || audioTimeUs < 0L) {
                        break
                    }

                    val size = buffer.read(extractor)
                    if (size < 0) {
                        audioExtractor.release()
                        audioExtractor = null
                        audioTimeUs = -1L
                        continue
                    }

                    val pts = monotonic(audioTimeUs, lastAudioPts)
                    lastAudioPts = pts
                    writeSample(
                        encoderMuxer,
                        audioTrackOut,
                        buffer.buffer,
                        size,
                        pts,
                        bufferInfo,
                        0
                    )
                    lastWrittenUs = maxOf(lastWrittenUs, pts)
                    wroteAudio = true

                    extractor.advance()
                    val next = extractor.sampleTime
                    audioTimeUs = if (next < 0L) -1L else next + audioShiftUs
                }
            }

            if (!wroteVideo) throw IOException("No video samples were written")

            encoderMuxer.stop()
            muxerStarted = false

            return Result(
                hasAudio = wroteAudio,
                durationUs = maxOf(lastWrittenUs, videoDurationUs)
            )
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            audioExtractor?.let { runCatching { it.release() } }
            runCatching { videoExtractor.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith(mimePrefix)) {
                return index
            }
        }
        return -1
    }

    private fun audioTrackIn(extractor: MediaExtractor): Int {
        val selected = findTrack(extractor, "audio/")
        if (selected < 0) throw IOException("Audio track disappeared")
        return selected
    }

    private fun readDurationUs(format: MediaFormat): Long {
        return if (format.containsKey(MediaFormat.KEY_DURATION)) {
            runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        } else {
            0L
        }
    }

    private fun readRotation(format: MediaFormat, file: File): Int {
        var rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
        } else {
            0
        }

        if (rotation == 0) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                    ?: 0
            } catch (t: Throwable) {
                Log.w(TAG, "Could not read video rotation metadata", t)
            } finally {
                runCatching { retriever.release() }
            }
        }

        val normalized = ((rotation % 360) + 360) % 360
        return if (normalized == 90 || normalized == 180 || normalized == 270) normalized else 0
    }

    private fun monotonic(candidate: Long, previous: Long): Long {
        val safe = candidate.coerceAtLeast(0L)
        return if (safe > previous) safe else previous + 1L
    }

    private fun writeSample(
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        size: Int,
        presentationTimeUs: Long,
        info: MediaCodec.BufferInfo,
        flags: Int
    ) {
        if (trackIndex < 0 || size <= 0) return
        info.set(0, size, presentationTimeUs, flags)
        buffer.position(0)
        buffer.limit(size)
        muxer.writeSampleData(trackIndex, buffer, info)
    }

    private class SampleBuffer(format: MediaFormat) {
        var buffer: ByteBuffer = ByteBuffer.allocateDirect(initialCapacity(format))
            private set

        fun read(extractor: MediaExtractor): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sampleSize = extractor.sampleSize
                if (sampleSize > buffer.capacity()) {
                    buffer = allocate(sampleSize)
                }
            }

            var size = extractor.readSampleData(buffer, 0)
            if (size >= 0 && size >= buffer.capacity()) {
                // Some extractors report a size equal to the buffer capacity when data was
                // truncated. Grow once and re-read the same sample.
                val sampleTime = extractor.sampleTime
                buffer = allocate(maxOf(buffer.capacity() * 2, size * 2).toLong())
                extractor.seekTo(sampleTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                size = extractor.readSampleData(buffer, 0)
            }
            return size
        }

        private fun initialCapacity(format: MediaFormat): Int {
            val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                    .getOrDefault(0)
            } else {
                0
            }
            return declared.coerceIn(MIN_BUFFER_BYTES, MAX_BUFFER_BYTES)
        }

        private fun allocate(size: Long): ByteBuffer {
            if (size > MAX_BUFFER_BYTES) {
                throw IOException("Media sample exceeds $MAX_BUFFER_BYTES bytes")
            }
            return ByteBuffer.allocateDirect(size.coerceAtLeast(MIN_BUFFER_BYTES.toLong()).toInt())
        }
    }
}
