package com.transfer.flash.core.ptt

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone capture loop for PTT transmission (ADR-032, Phase 1).
 *
 * Pulls fixed-size PCM16 mono chunks off [AudioRecord] on a dedicated thread and hands
 * them to [onPacket] (still on the capture thread — the engine forwards them without
 * blocking this loop). Deliberately NOT `MediaRecorder` (file-oriented, wrong tool) and
 * NOT the WebRTC ADM (call-owned, mic-exclusive).
 *
 * Source order follows the proven probe (`docs/android-platform-notes.md`):
 * `VOICE_COMMUNICATION` first (HW NS path), `MIC` fallback. Rate falls back
 * 16000 → 8000 when the HAL refuses the requested rate. Any unexpected loop exit while
 * still armed reports [onCaptureLost] (read error, system silence); the engine treats it
 * like `MicDenied` and releases the floor. All callbacks may arrive on any thread — the
 * engine serializes them.
 */
public class PttCapture(
    private val requestedRateHz: Int,
    private val packetMs: Int,
    private val onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit,
    private val onCaptureLost: () -> Unit,
) {
    public sealed interface StartResult {
        public data class Started(
            val actualRateHz: Int,
            val actualPacketMs: Int,
        ) : StartResult
        public data object Failed : StartResult
    }

    private data class OpenedRecorder(
        val recorder: AudioRecord,
        val rateHz: Int,
        val packetMs: Int,
    )

    private val running = AtomicBoolean(false)
    private val packetsEnabled = AtomicBoolean(false)
    private val lossReported = AtomicBoolean(false)

    @Volatile
    private var executor: java.util.concurrent.ExecutorService? = null

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    @Volatile
    private var actualRateHz: Int = requestedRateHz

    @Volatile
    private var actualPacketMs: Int = packetMs

    /** Starts capture. Idempotent while running. Never blocks the caller on audio I/O. */
    public fun start(): StartResult {
        if (running.get()) return StartResult.Started(actualRateHz, actualPacketMs)
        val built = openRecorder() ?: return StartResult.Failed
        recorder = built.recorder
        actualRateHz = built.rateHz
        actualPacketMs = built.packetMs
        packetsEnabled.set(false)
        lossReported.set(false)
        running.set(true)
        val service = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ptt-capture").apply { isDaemon = true }
        }
        executor = service
        val bytesPerPacket = actualRateHz * actualPacketMs / 1000 * 2
        service.execute {
            val chunk = ByteArray(bytesPerPacket)
            var offset = 0
            try {
                while (running.get()) {
                    val read = runCatching {
                        recorder?.read(chunk, offset, chunk.size - offset) ?: -1
                    }.getOrDefault(-1)
                    if (!running.get()) break
                    when {
                        read > 0 -> {
                            offset += read
                            if (offset == chunk.size) {
                                if (packetsEnabled.get()) {
                                    onPacket(chunk.copyOf(), android.os.SystemClock.elapsedRealtime())
                                }
                                offset = 0
                            }
                        }
                        read < 0 -> {
                            Log.w(TAG, "AudioRecord read error=$read — ending capture")
                            break
                        }
                        else -> Thread.yield()
                    }
                }
            } finally {
                val unexpected = running.getAndSet(false)
                closeRecorder()
                shutdownExecutorFromWorker()
                if (unexpected && lossReported.compareAndSet(false, true)) {
                    Log.w(TAG, "Capture loop exited while armed")
                    onCaptureLost()
                }
            }
        }
        return StartResult.Started(actualRateHz, actualPacketMs)
    }

    /**
     * Opens packet delivery after the session Start control frame has been sent. AudioRecord
     * may warm up before this, but no binary frame can overtake the receiver's format claim.
     */
    public fun enablePackets() {
        if (running.get()) packetsEnabled.set(true)
    }

    /** Stops capture and frees the mic. Safe from any thread, never reports loss. */
    public fun stop() {
        packetsEnabled.set(false)
        running.set(false)
        runCatching { recorder?.stop() }
        closeRecorder()
        val service = executor
        if (service != null) {
            service.shutdown()
            runCatching { service.awaitTermination(1L, TimeUnit.SECONDS) }
            service.shutdownNow()
            if (executor === service) executor = null
        }
    }

    private fun openRecorder(): OpenedRecorder? {
        val rates = listOf(requestedRateHz, FALLBACK_RATE_HZ).distinct()
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        for (rate in rates) {
            val effectivePacketMs = if (rate <= FALLBACK_RATE_HZ) LOW_PACKET_MS else packetMs
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                Log.w(TAG, "Rate $rate unsupported (minBuffer=$minBuffer)")
                continue
            }
            for (source in sources) {
                val candidate = runCatching {
                    AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(
                            maxOf(minBuffer, rate * effectivePacketMs / 1000 * 2 * BUFFER_PACKETS),
                        )
                        .build()
                }.getOrNull()
                if (candidate == null || candidate.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { candidate?.release() }
                    continue
                }
                runCatching { candidate.startRecording() }
                if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    runCatching { candidate.release() }
                    continue
                }
                watchSilenceLocked(candidate)
                Log.i(TAG, "Capture started rate=$rate packetMs=$effectivePacketMs source=$source")
                return OpenedRecorder(candidate, rate, effectivePacketMs)
            }
        }
        Log.w(TAG, "No workable AudioRecord (rates=$rates)")
        return null
    }

    /**
     * System-mute watchdog (API 29+): a silenced client receives zeros that are
     * indistinguishable from a quiet room downstream, so silence must surface here.
     * Transient (assistant ducking) and permanent (higher-priority capture) look alike;
     * PTT releases the floor either way — the user re-presses to talk.
     */
    private fun watchSilenceLocked(candidate: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val sessionId = candidate.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val silenced = configs.any {
                    it.clientAudioSessionId == sessionId && it.isClientSilenced
                }
                if (silenced && running.getAndSet(false) && lossReported.compareAndSet(false, true)) {
                    Log.w(TAG, "System silenced our capture — releasing floor")
                    onCaptureLost()
                }
            }
        }
        runCatching {
            candidate.registerAudioRecordingCallback(
                Executor { command -> command.run() },
                callback,
            )
            recordingCallback = callback
        }.onFailure { Log.w(TAG, "Silence watch unavailable", it) }
    }

    private fun closeRecorder() {
        val current = recorder ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recordingCallback?.let { callback ->
                runCatching { current.unregisterAudioRecordingCallback(callback) }
            }
        }
        recordingCallback = null
        runCatching { current.release() }
        if (recorder === current) recorder = null
    }

    private fun shutdownExecutorFromWorker() {
        val service = executor ?: return
        service.shutdown()
        if (executor === service) executor = null
    }

    private companion object {
        const val TAG = "PTT_CAP"
        const val FALLBACK_RATE_HZ = 8000
        const val LOW_PACKET_MS = 60
        const val BUFFER_PACKETS = 4
    }
}
