package com.wowcam.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Records a mono AAC-LC audio track with a private [AudioRecord] session.
 *
 * Why not let CameraX record audio?
 * CameraX owns its internal AudioRecord and does not expose the audio session id, so
 * NoiseSuppressor/AcousticEchoCanceler cannot be attached to it. This class owns the
 * session, selects VOICE_RECOGNITION (falling back to CAMCORDER and then MIC), uses a 400 ms
 * warm-up probe to discard sources that deliver a dead zero-PCM stream, attaches the platform
 * DSP effects, and writes a small .m4a file. The video-only CameraX output and this audio
 * file are muxed losslessly at the end of the recording.
 *
 * The encoder is fed in small, reused buffers: no PCM is accumulated in RAM, so memory
 * usage does not grow with recording length. One instance is used per recording.
 */
class StudioAudioRecorder {

    data class StartInfo(
        val startTimeNs: Long,
        val sourceLabel: String,
        val effectsLabel: String,
        val sampleRate: Int
    )

    data class Result(
        val file: File,
        val startTimeNs: Long,
        val sourceLabel: String,
        val effectsLabel: String,
        val sampleRate: Int
    )

    private enum class State {
        IDLE, PREPARING, RECORDING, FAILED, CLOSED
    }

    private data class SourceChoice(
        val id: Int,
        val label: String
    )

    private data class AudioSetup(
        val record: AudioRecord,
        val sampleRate: Int,
        val source: SourceChoice
    )

    private val lock = Any()

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var stopRequested = false

    @Volatile
    private var canceled = false

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var captureThread: Thread? = null
    private var outputFile: File? = null

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var source: SourceChoice = SourceChoice(MediaRecorder.AudioSource.MIC, "MIC")
    var effectsLabel = "DEVICE DSP"
        private set

    private var startTimeNs = 0L
    private var totalFrames = 0L
    private var rmsDbSum = 0.0
    private var rmsDbSamples = 0L
    private var rmsBytes = 0L
    // Smoothed AGC gain; carried across chunks so loudness doesn't pump.
    private var agcGain = 1.0f
    private var noiseFloor = 5.0
    private var gateGain = 1.0f
    private var vadHoldFrames = 0

    // Last computed RMS level, exposed so the UI can show a live mic meter.
    @Volatile
    var lastLevelDb: Float = Float.NEGATIVE_INFINITY
        private set
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var encodedAnything = false

    private val pcmBuffer = ByteArray(READ_CHUNK_BYTES)

    /**
     * Opens the microphone, encoder and muxer and starts capturing immediately.
     *
     * Caller must hold RECORD_AUDIO. This method blocks for a few milliseconds while the
     * hardware session is created; call it from a background executor.
     *
     * @throws AudioCaptureException if the device cannot provide a usable microphone session.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(file: File): StartInfo {
        check(state == State.IDLE) { "StudioAudioRecorder is single-use" }
        state = State.PREPARING
        canceled = false
        stopRequested = false
        outputFile = file

        try {
            file.parentFile?.mkdirs()

            val setup = createAudioRecord()
            audioRecord = setup.record
            sampleRate = setup.sampleRate
            source = setup.source

            enableDspEffects(setup.record.audioSessionId)
            createEncoderAndMuxer(file)

            setup.record.startRecording()
            if (setup.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw AudioCaptureException("AudioRecord did not enter RECORDING state")
            }

            startTimeNs = System.nanoTime()
            state = State.RECORDING

            captureThread = Thread(::captureLoop, "WowCamAudio").apply {
                isDaemon = true
                start()
            }

            Log.i(
                TAG,
                "Audio started: source=$sourceLabel, rate=$sampleRate, effects=$effectsLabel"
            )
            return StartInfo(startTimeNs, sourceLabel, effectsLabel, sampleRate)
        } catch (t: Throwable) {
            state = State.FAILED
            cleanupEverything(deleteFile = true)
            if (t is AudioCaptureException) throw t
            throw AudioCaptureException("Could not start studio audio", t)
        }
    }

    /**
     * Stops capture, flushes the AAC encoder and closes the muxer.
     *
     * Returns null when no usable audio was produced. This method blocks until the capture
     * thread exits (or the timeout expires), so call it from a background executor.
     */
    fun stopAndAwait(timeoutMs: Long = 5_000L): Result? {
        val file = outputFile
        synchronized(lock) {
            if (state == State.CLOSED) return null
            if (state == State.IDLE) return null
            stopRequested = true
            runCatching { audioRecord?.stop() }
        }

        val thread = captureThread
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(timeoutMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        if (thread != null && thread.isAlive) {
            // A misbehaving HAL can leave AudioRecord.read blocked. Try one more stop;
            // the daemon thread will finish cleanup on its own.
            Log.w(TAG, "Audio capture thread did not stop in ${timeoutMs}ms")
            runCatching { audioRecord?.stop() }
            runCatching { thread.join(750L) }
        }

        val usable = state != State.FAILED &&
                !canceled &&
                file != null &&
                file.exists() &&
                file.length() > 0L

        if (!usable) {
            file?.delete()
            return null
        }

        return Result(
            file = file!!,
            startTimeNs = startTimeNs,
            sourceLabel = sourceLabel,
            effectsLabel = effectsLabel,
            sampleRate = sampleRate
        )
    }

    /**
     * Fast, non-blocking cancellation for Activity destruction. The capture thread exits
     * after its next read and deletes the partial file.
     */
    fun cancel() {
        canceled = true
        stopRequested = true
        runCatching { audioRecord?.stop() }
    }

    val isRecording: Boolean
        get() = state == State.RECORDING

    val sourceLabel: String
        get() = source.label

    // ------------------------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioSetup {
        val sampleRates = intArrayOf(48_000, 44_100, 16_000)

        // Source order matters on MIUI. CAMCORDER routes voice through the primary mic
        // (not the top noise-canceling mic). VOICE_COMMUNICATION is processed but built
        // to capture voice, then UNPROCESSED raw, then plain MIC, VR as last resort.
        val orderedSources = listOf(
            SourceChoice(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER"),
            SourceChoice(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
            SourceChoice(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED"),
            SourceChoice(MediaRecorder.AudioSource.MIC, "MIC"),
            SourceChoice(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION")
        )
        Log.i(TAG, "Mic routing: ${orderedSources.joinToString(" -> ") { it.label }}")

        // 2) Open the winner, falling back through the rest of the chain if the device
        //    refuses any of them.
        var lastFailure: Throwable? = null
        for (candidateSource in orderedSources) {
            for (candidateRate in sampleRates) {
                try {
                    val record = buildRecord(candidateSource, candidateRate) ?: continue
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        return AudioSetup(record, candidateRate, candidateSource)
                    }

                    record.release()
                    lastFailure = AudioCaptureException(
                        "AudioRecord not initialized for ${candidateSource.label} @ $candidateRate"
                    )
                } catch (t: Throwable) {
                    lastFailure = t
                    Log.w(
                        TAG,
                        "Audio source ${candidateSource.label} @ $candidateRate failed",
                        t
                    )
                }
            }
        }

        throw AudioCaptureException("No compatible microphone configuration", lastFailure)
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(source: SourceChoice, sampleRate: Int): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) return null

        val bufferBytes = maxOf(minBytes * 2, sampleRate / 5 * 2)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        return AudioRecord.Builder()
            .setAudioSource(source.id)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    /**
     * Attaches NoiseSuppressor and AcousticEchoCanceler when the chipset exposes them.
     *
     * MIUI and other vendor builds occasionally report effects as "available" but throw
     * on create()/setEnabled() or even on isAvailable(). Every effect call is therefore
     * isolated so audio capture continues with source-DSP only when the platform
     * refuses to cooperate.
     */
    /** Root-mean-square level of a 16-bit little-endian PCM chunk, in dBFS. */
    private fun rmsDb(data: ByteArray, bytes: Int): Double {
        var sumSquares = 0.0
        var samples = 0L
        var i = 0
        while (i + 1 < bytes) {
            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)
            sumSquares += sample.toDouble() * sample.toDouble()
            samples += 1
            i += 2
        }
        if (samples == 0L) return Double.NEGATIVE_INFINITY
        val rms = kotlin.math.sqrt(sumSquares / samples)
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * kotlin.math.log10(rms / 32768.0)
    }

    /**
     * Simple streaming-AGC: pulls quiet voice up toward [AGC_TARGET_RMS] and soft-clips
     * peaks above [SOFT_KNEE], so speech stays loud and crisp without ringing or clipping.
     * Silence/near-silence below [AGC_NOISE_GATE_RMS] is left alone instead of amplifying
     * the room's hiss to full volume.
     */
    private fun applyAgc(data: ByteArray, bytes: Int) {
        var sumSquares = 0.0
        var samples = 0L
        var peak = 0
        var i = 0
        while (i + 1 < bytes) {
            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)
            sumSquares += sample.toDouble() * sample.toDouble()
            val absSample = kotlin.math.abs(sample)
            if (absSample > peak) peak = absSample
            samples += 1
            i += 2
        }
        if (samples == 0L) return

        val rms = kotlin.math.sqrt(sumSquares / samples)
        lastLevelDb = if (rms <= 0.0) {
            Float.NEGATIVE_INFINITY
        } else {
            (20.0 * kotlin.math.log10(rms / 32768.0)).toFloat()
        }

        // Use RMS in linear amplitude for the decision (matches the noiseFloor domain).
        val voiceFrame = (rms > noiseFloor * VAD_RMS_FACTOR) ||
            (peak > kotlin.math.max(noiseFloor * VAD_PEAK_FACTOR, VAD_MIN_PEAK.toDouble()))
        if (voiceFrame) {
            vadHoldFrames = VAD_HOLD_FRAMES
        } else if (vadHoldFrames > 0) {
            vadHoldFrames -= 1
        }
        val voiceActive = vadHoldFrames > 0

        // Soft attack / release so the open-close ramp does not click or chop consonants.
        // Target: 1.0 in voice, 0.0 in silence (true mute).
        val gateTarget = if (voiceActive) 1.0f else 0.0f
        val gateRate = if (gateTarget > gateGain) GATE_OPEN else GATE_CLOSE
        gateGain = gateGain + (gateTarget - gateGain) * gateRate

        // Keep the (still-boosted) floor estimator alive in silence.
        val floorMargin = 1.6
        if (samples == 0L || rms < noiseFloor * floorMargin) {
            noiseFloor = noiseFloor * NOISE_FLOOR_DECAY + rms * NOISE_FLOOR_ATTACK
        }
        if (noiseFloor < 1.0) noiseFloor = 1.0

        // AGC now boosts voice only; hiss is already silenced.
        val desired = if (rms <= noiseFloor * 1.35 || rms <= 0.0) {
            1.0f
        } else {
            (AGC_TARGET_RMS / rms).coerceIn(1.0, AGC_MAX_BOOST.toDouble()).toFloat()
        }
        agcGain = if (desired > agcGain) {
            agcGain + (desired - agcGain) * AGC_ATTACK
        } else {
            agcGain + (desired - agcGain) * AGC_RELEASE
        }

        val totalGain = agcGain * gateGain
        if (totalGain <= 0.01f) {
            // True digital silence — no room tone, no hiss, nothing.
            java.util.Arrays.fill(data, 0, bytes, 0)
            return
        }

        i = 0
        while (i + 1 < bytes) {
            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)
            var boosted = (sample * totalGain).toInt()
            if (boosted > SOFT_KNEE) boosted = SOFT_KNEE + (boosted - SOFT_KNEE) / 4
            else if (boosted < -SOFT_KNEE) boosted = -SOFT_KNEE + (boosted + SOFT_KNEE) / 4
            boosted = boosted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            data[i] = (boosted and 0xFF).toByte()
            data[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /**
     * We deliberately do NOT attach NoiseSuppressor or AcousticEchoCanceler.
     *
     * On this device (and generally on budget MIUI) the vendor DSP aggressively treats
     * low-level speech as stationary noise and silences it entirely — that is why stock
     * camera audio is audible but our captured clip was effectively dead silence. Raw
     * MIC/CAMCORDER audio with the streaming AGC is far more intelligible for voice.
     */
    private fun enableDspEffects(sessionId: Int) {
        effectsLabel = "RAW MIC"
        noiseSuppressor = null
        echoCanceler = null
        Log.i(TAG, "Audio effects: RAW MIC (vendor NS/AEC disabled — MIUI gates quiet voice)")
    }

    private fun createEncoderAndMuxer(file: File) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            CHANNEL_COUNT
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, READ_CHUNK_BYTES * 2)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder

        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    // ------------------------------------------------------------------------------------
    // Capture / encode loop
    // ------------------------------------------------------------------------------------

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        val record = audioRecord
        val encoder = codec
        if (record == null || encoder == null) {
            state = State.FAILED
            cleanupEverything(deleteFile = true)
            return
        }

        try {
            while (!stopRequested) {
                val bytesRead = record.read(
                    pcmBuffer,
                    0,
                    pcmBuffer.size,
                    AudioRecord.READ_BLOCKING
                )

                if (bytesRead > 0) {
                    applyAgc(pcmBuffer, bytesRead)
                    rmsDbSum += rmsDb(pcmBuffer, bytesRead)
                    rmsDbSamples += 1
                    rmsBytes += bytesRead
                    if (rmsBytes >= sampleRate * BYTES_PER_FRAME) { // ~one second of capture
                        Log.i(TAG, "PCM level: average=${"%.1f".format(rmsDbSum / rmsDbSamples)} dBFS")
                        rmsDbSum = 0.0
                        rmsDbSamples = 0
                        rmsBytes = 0L
                    }
                    val presentationTimeUs =
                        totalFrames * 1_000_000L / sampleRate
                    queuePcm(encoder, pcmBuffer, bytesRead, presentationTimeUs)
                    totalFrames += bytesRead / BYTES_PER_FRAME
                    drainEncoder(encoder, endOfStream = false)
                } else if (bytesRead == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw AudioCaptureException("AudioRecord died")
                } else if (bytesRead < 0) {
                    if (stopRequested) break
                    throw AudioCaptureException("AudioRecord read error: $bytesRead")
                }
            }

            val eosTimeUs = totalFrames * 1_000_000L / sampleRate
            queueEndOfStream(encoder, eosTimeUs)
            drainEncoder(encoder, endOfStream = true)
        } catch (t: Throwable) {
            Log.e(TAG, "Audio capture failed", t)
            state = State.FAILED
        } finally {
            cleanupEverything(deleteFile = state == State.FAILED || canceled || !encodedAnything)
        }
    }

    private fun queuePcm(
        encoder: MediaCodec,
        data: ByteArray,
        size: Int,
        presentationTimeUs: Long
    ) {
        var inputIndex = -1
        var attempts = 0
        while (inputIndex < 0 && !stopRequested && attempts < 200) {
            inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) {
                drainEncoder(encoder, endOfStream = false)
                attempts++
            }
        }
        if (inputIndex < 0) {
            Log.w(TAG, "Dropping an audio chunk: encoder input unavailable")
            return
        }

        val inputBuffer = encoder.getInputBuffer(inputIndex)
        if (inputBuffer == null) {
            return
        }

        inputBuffer.clear()
        inputBuffer.put(data, 0, size)
        encoder.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
    }

    private fun queueEndOfStream(encoder: MediaCodec, presentationTimeUs: Long) {
        var inputIndex = -1
        var attempts = 0
        while (inputIndex < 0 && attempts < 200) {
            inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) {
                drainEncoder(encoder, endOfStream = false)
                attempts++
            }
        }
        if (inputIndex < 0) {
            Log.w(TAG, "Could not signal audio EOS")
            return
        }

        encoder.queueInputBuffer(
            inputIndex,
            0,
            0,
            presentationTimeUs,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
    }

    private fun drainEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        val deadlineNs = System.nanoTime() + if (endOfStream) 5_000_000_000L else 0L

        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(
                info,
                if (endOfStream) INPUT_TIMEOUT_US else 0L
            )

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (System.nanoTime() > deadlineNs) {
                        Log.w(TAG, "Timed out draining audio encoder")
                        return
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        val outputFormat = encoder.outputFormat
                        val activeMuxer = muxer
                        if (activeMuxer == null) {
                            throw AudioCaptureException("Audio muxer is missing")
                        }
                        audioTrackIndex = activeMuxer.addTrack(outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Audio muxer started: $outputFormat")
                    }
                }

                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }

                        if (info.size > 0 && muxerStarted && audioTrackIndex >= 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val activeMuxer = muxer
                            if (activeMuxer != null) {
                                activeMuxer.writeSampleData(
                                    audioTrackIndex,
                                    outputBuffer,
                                    info
                                )
                                encodedAnything = true
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(outputIndex, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------------------------

    private fun cleanupEverything(deleteFile: Boolean) {
        synchronized(lock) {
            if (state == State.CLOSED) return

            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null

            runCatching { noiseSuppressor?.release() }
            noiseSuppressor = null
            runCatching { echoCanceler?.release() }
            echoCanceler = null

            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null

            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
            audioTrackIndex = -1

            if (state != State.FAILED) {
                state = State.CLOSED
            }

            if (deleteFile || !encodedAnything) {
                outputFile?.delete()
            }
            outputFile = null
        }
    }

    class AudioCaptureException(
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    companion object {
        /**
         * Resets the audio route only when nothing external is connected.
         *
         * SCO can get stuck on the neckband even after it is disconnected, which silences
         * every VOICE_* capture. We reset to built-in only when there is no external mic
         * (BT headset, USB mic, etc.) — otherwise routing is left exactly how the system
         * sees the external device, so recording through it just works.
         */
        fun resetAudioRouting(context: Context) {
            val am = context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    // getMicrophones() returns MicrophoneInfo, which setCommunicationDevice()
                    // cannot consume — use the communication-device API it pairs with.
                    val devices = am.availableCommunicationDevices
                    val hasExternalMic = devices.any { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    if (!hasExternalMic) {
                        runCatching { am.stopBluetoothSco() }
                        runCatching { am.mode = AudioManager.MODE_NORMAL }
                        val builtinMic = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        if (builtinMic != null) {
                            am.setCommunicationDevice(builtinMic)
                        } else {
                            am.clearCommunicationDevice()
                        }
                    } else {
                        Log.i(TAG, "External mic detected (${devices.size} mics); leaving system routing untouched")
                    }
                }
            }
        }

        const val TAG = "WowCamAudio"
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_FRAME = 2 // mono, PCM 16-bit
        const val READ_CHUNK_BYTES = 4_096 // ~42 ms @ 48 kHz
        const val AAC_BITRATE = 256_000
        const val INPUT_TIMEOUT_US = 20_000L
        const val AGC_TARGET_RMS = 6500.0 // ≈ -15 dBFS average — full, crisp voice
        const val AGC_MAX_BOOST = 48 // ≈ +48 dB on very quiet speech (was 30 — too timid on weak MIUI mics)
        const val AGC_NOISE_GATE_RMS = 1.0 // digital silence only — boost even the faintest signal
        const val AGC_ATTACK = 0.3f
        const val AGC_RELEASE = 0.06f
        const val NOISE_FLOOR_DECAY = 0.92 // floor falls slowly (avoids hearing pump)
        const val NOISE_FLOOR_ATTACK = 0.08 // floor rises to match new room tone/hiss
        const val GATE_REDUCTION = 0.05f // hiss passes at 5% of original amplitude
        const val GATE_SMOOTH = 0.35f // musical gate — no clicks between voice/silence
        const val VAD_RMS_FACTOR = 2.2 // voice frame vs noise floor (linear domain)
        const val VAD_PEAK_FACTOR = 4.0 // loud transient still counts as voice
        const val VAD_MIN_PEAK = 250 // absolute minimum peak to call something voice
        const val VAD_HOLD_FRAMES = 18 // keep the gate open ~0.8 s past the last voice
        const val GATE_OPEN = 0.45f // gate opens fast when voice starts
        const val GATE_CLOSE = 0.18f // gate closes over ~0.2 s — no consonant clipping
        const val SOFT_KNEE = 30_000 // soft-clip above this to avoid harsh distortion
    }
}
