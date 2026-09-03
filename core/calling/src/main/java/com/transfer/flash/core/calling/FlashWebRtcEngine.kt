package com.transfer.flash.core.calling

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shepeliev.webrtckmp.WebRtc
import com.transfer.flash.core.common.logging.FlashLog
import kotlin.concurrent.Volatile
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One-shot WebRTC engine setup: installs a low-latency audio device module (ADR-025) whose
 * capture source has been *proven* to deliver frames on this device (ERROR-032).
 *
 * webrtc-kmp builds its `PeerConnectionFactory` lazily and never sets an audio device
 * module, so libwebrtc creates a default `JavaAudioDeviceModule` on first use — and that
 * default has `useLowLatency = false`, i.e. `AudioTrack` runs in the normal performance
 * mode with the standard output buffer. On the playout path that is tens of milliseconds
 * of buffering that nothing else in the stack can claw back: it is upstream of the jitter
 * buffer, upstream of the decoder, and not reachable from any per-call API.
 *
 * [configureOnce] is the only window to change it. `WebRtc.configure` builds the factory
 * immediately and throws if a factory already exists, so this must run before the first
 * `MediaDevices`/`PeerConnection` touch — in practice, at engine construction, long before
 * a call exists. That one-shot nature is also why the capture source is decided *here*
 * rather than per call: the ADM is process-wide and permanent once the factory exists.
 *
 * ### Why the capture source is probed (ERROR-032)
 *
 * `VOICE_COMMUNICATION` is the right default: it is what routes the mic through the
 * platform's echo-cancellation path, and it is what the hardware AEC/NS effects attach to.
 * But it is a *request*, not a contract. On some OEM audio HALs — rugged/PTT handsets in
 * particular, which carry a vendor radio stack on the voice path — the source opens
 * cleanly, `AudioRecord.getState()` reports INITIALIZED, libwebrtc's own
 * `verifyAudioConfig` passes against `TYPE_BUILTIN_MIC`, and then `read()` never returns a
 * single frame. There is no exception and no error callback; the far end simply hears
 * silence. Measured on a BelFone SCP810 (Android 8.1, qcom): capture stalls inside the HAL
 * until libwebrtc's `stopThread()` gives up on its 2 s join, and the blocking
 * `stop()`/`read()` that follow stall the process long enough to ANR it.
 *
 * So [configureOnce] opens the mic once, briefly, and keeps the first source the HAL actually
 * streams frames on: `VOICE_COMMUNICATION`, then `MIC`, then `DEFAULT`. The winner is cached in
 * `SharedPreferences`, so this costs one sub-second mic touch on the first launch and nothing
 * afterwards. The test is *frame delivery*, never loudness — see [deliversAudio] for why that
 * distinction is the difference between a probe that works and a probe that is a coin flip.
 *
 * Two consequences worth stating:
 *
 * - **Hardware AEC/NS are only enabled for `VOICE_COMMUNICATION`.** On a raw `MIC` fallback
 *   there is no platform echo-cancellation path to attach them to, and stacking them on one
 *   anyway is the well-known way to get a capture stream that is present but useless.
 *   libwebrtc's software APM handles AEC/NS on the fallback instead.
 * - **The probe needs `RECORD_AUDIO`.** At engine construction the grant may not exist yet,
 *   in which case the probe is skipped, nothing is cached, and `VOICE_COMMUNICATION` is used
 *   as before. A device that both prompts for the mic *and* needs the fallback therefore
 *   gets it from the next process start, not the first call.
 *
 * Everything else here is the default made explicit and pinned: voice-communication audio
 * attributes on the output track, and libwebrtc's volume logger disabled — a 30 s polling
 * timer that exists only to write log lines.
 *
 * Best-effort by design: a device whose native library or ADM refuses to initialise still
 * gets a working call on libwebrtc's defaults.
 */
@OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)
public object FlashWebRtcEngine {

    @Volatile
    private var configured = false

    /**
     * Configures the engine if it has not been configured yet. Idempotent and thread-safe;
     * returns true when the low-latency ADM is in place.
     *
     * Must be called before any call is placed or answered. Calling it later is harmless —
     * it will fail cleanly and log — but the ADM will already be the default one.
     */
    public fun configureOnce(context: Context): Boolean {
        if (configured) return true
        synchronized(this) {
            if (configured) return true
            configured = true
            val appContext = context.applicationContext
            val source = captureSource(appContext)
            // Only the voice source has a platform AEC path for these to attach to; see the
            // class KDoc. On the MIC/DEFAULT fallback libwebrtc's software APM does the work.
            val voicePath = source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
            val hwAec = voicePath && JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
            val hwNs = voicePath && JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
            return try {
                val adm = JavaAudioDeviceModule.builder(appContext)
                    // The point of this whole object: PERFORMANCE_MODE_LOW_LATENCY and a
                    // smaller output buffer on the playout AudioTrack.
                    .setUseLowLatency(true)
                    .setAudioSource(source)
                    .setUseHardwareAcousticEchoCanceler(hwAec)
                    .setUseHardwareNoiseSuppressor(hwNs)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setEnableVolumeLogger(false)
                    // Capture failures are otherwise only visible as libwebrtc's own
                    // `WebRtcAudioRecordExternal` lines, which carry no app context.
                    .setAudioRecordErrorCallback(CaptureErrorLog)
                    .createAudioDeviceModule()
                WebRtc.configure(
                    peerConnectionFactoryBuilder = WebRtc.createPeerConnectionFactoryBuilder()
                        .setAudioDeviceModule(adm),
                )
                FlashLog.i(
                    "CALL",
                    "WebRTC engine configured: low-latency ADM, " +
                        "source=${sourceName(source)} hwAec=$hwAec hwNs=$hwNs",
                )
                true
            } catch (t: Throwable) {
                // Throwable: the first factory touch loads the native library, so a bad ABI
                // surfaces as UnsatisfiedLinkError. Calls still work on the defaults.
                FlashLog.w("CALL", "WebRTC engine configuration skipped: ${t.message}")
                false
            }
        }
    }

    // ------------------------------------------------------------ capture source

    /**
     * The capture source to hand the ADM: the cached probe winner, or the result of probing
     * now, or [MediaRecorder.AudioSource.VOICE_COMMUNICATION] when we cannot tell.
     *
     * Only a *success* is cached. A probe that fails every candidate — which is what a mic
     * held by another app at app start looks like — must not pin the fallback forever, so it
     * writes nothing and the next process start tries again.
     */
    private fun captureSource(context: Context): Int {
        val prefs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
        prefs?.getInt(KEY_CAPTURE_SOURCE, NO_SOURCE)?.let { cached ->
            if (cached != NO_SOURCE) return cached
        }
        // Context.checkSelfPermission is API 23; minSdk here is 24, so no androidx needed.
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            FlashLog.i("CALL", "capture probe skipped: RECORD_AUDIO not granted yet")
            return MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        // The stall this probe catches is mode-dependent on some HALs: VOICE_COMMUNICATION can
        // read fine in MODE_NORMAL and deliver nothing in MODE_IN_COMMUNICATION, which is the
        // mode every call actually runs in (FlashCallAudioRouter.attach). Measuring in
        // MODE_NORMAL would then cache the source that is about to fail. Best-effort: from
        // API 31 the platform refuses the mode change without audio focus, and a probe that
        // ends up measuring MODE_NORMAL is still no worse than not probing at all.
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val previousMode = audioManager?.mode
        runCatching { audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION }
        try {
            for (candidate in CANDIDATE_SOURCES) {
                if (deliversAudio(candidate)) {
                    if (candidate != CANDIDATE_SOURCES.first()) {
                        FlashLog.w(
                            "CALL",
                            "capture probe fell back to ${sourceName(candidate)} — " +
                                "this device does not deliver audio on VOICE_COMMUNICATION",
                        )
                    }
                    runCatching { prefs?.edit()?.putInt(KEY_CAPTURE_SOURCE, candidate)?.apply() }
                    return candidate
                }
                FlashLog.w("CALL", "capture probe: ${sourceName(candidate)} delivered no frames")
            }
        } finally {
            runCatching { previousMode?.let { audioManager?.mode = it } }
        }
        FlashLog.w(
            "CALL",
            "capture probe inconclusive — keeping VOICE_COMMUNICATION, will retry next start",
        )
        return MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    /**
     * Opens [source] briefly and reports whether the HAL actually **delivers frames** on it.
     *
     * The criterion is frame delivery, deliberately *not* loudness. An earlier version looked for
     * a non-zero PCM sample, which cannot tell the failure apart from a quiet room: a healthy mic
     * sitting on a desk in silence returns buffers of zeros, and the broken HAL returns nothing at
     * all — identical answers to a "did I hear anything" test. Measured on two identical BelFone
     * SCP810 units, that flaw made the probe correctly fall back on the one with ambient noise and
     * reject *every* candidate on the quiet one, which then kept the source that was about to
     * fail. So the test is [PROBE_MIN_FRAMES] frames accumulated from `read()`, and silence
     * passes. Whether anything was audible is logged, never gated on.
     *
     * Reads are `READ_NON_BLOCKING`. That is the other half of the trick: the failure this probe
     * exists to catch is a HAL that accepts the source and then never completes a read, and a
     * *blocking* read would strand this thread inside it exactly as it strands libwebrtc's
     * capture thread. A non-blocking read returns 0 instead, which also keeps the subsequent
     * `stop()` from deadlocking against an in-flight read.
     */
    private fun deliversAudio(source: Int): Boolean {
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(
                PROBE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }.getOrDefault(AudioRecord.ERROR)
        if (minBuffer <= 0) return false
        val record = runCatching {
            AudioRecord(
                source,
                PROBE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        }.getOrNull() ?: return false
        return try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return false
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
            val buffer = ShortArray(minBuffer / 2)
            val deadline = System.nanoTime() + PROBE_BUDGET_MS * 1_000_000L
            // Mono 16-bit, so one short is one frame.
            var frames = 0
            var audible = false
            while (frames < PROBE_MIN_FRAMES && System.nanoTime() < deadline) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                if (read < 0) return false
                frames += read
                if (!audible) {
                    for (i in 0 until read) {
                        if (buffer[i].toInt() != 0) {
                            audible = true
                            break
                        }
                    }
                }
                if (read == 0) Thread.sleep(PROBE_POLL_MS)
            }
            FlashLog.i(
                "CALL",
                "capture probe ${sourceName(source)}: frames=$frames " +
                    "need=$PROBE_MIN_FRAMES audible=$audible",
            )
            frames >= PROBE_MIN_FRAMES
        } catch (t: Throwable) {
            // A HAL that throws from open/start/read is a source we cannot use, which is
            // exactly the answer this function is being asked for.
            FlashLog.w("CALL", "capture probe ${sourceName(source)} threw: ${t.message}")
            false
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        else -> "source($source)"
    }

    /**
     * Surfaces libwebrtc's capture errors into the Flash log. Note that the silent-capture
     * failure this class guards against does **not** reach here — that is the point of the
     * probe — but a refused open or a hard read error does, and used to be invisible.
     */
    private object CaptureErrorLog : JavaAudioDeviceModule.AudioRecordErrorCallback {
        override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
            FlashLog.w("CALL", "capture init error: $errorMessage")
        }

        override fun onWebRtcAudioRecordStartError(
            errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
            errorMessage: String?,
        ) {
            FlashLog.w("CALL", "capture start error: $errorCode $errorMessage")
        }

        override fun onWebRtcAudioRecordError(errorMessage: String?) {
            FlashLog.w("CALL", "capture error: $errorMessage")
        }
    }

    private const val PREFS_NAME = "flash_webrtc_engine"

    /** Bump the suffix to invalidate every cached probe result in the field. */
    private const val KEY_CAPTURE_SOURCE = "capture_source_v1"

    private const val NO_SOURCE = -1

    /** Matches what libwebrtc actually opens, so the probe measures the real path. */
    private const val PROBE_SAMPLE_RATE = 48_000

    /**
     * Per-source ceiling. A healthy source reaches [PROBE_MIN_FRAMES] in ~60 ms and returns
     * early; only a source that streams nothing burns the whole budget.
     */
    private const val PROBE_BUDGET_MS = 300L

    /** ~50 ms of 48 kHz mono: two buffers, enough to prove the HAL is streaming rather than warm. */
    private const val PROBE_MIN_FRAMES = 2_400

    private const val PROBE_POLL_MS = 10L

    /**
     * Probe order. `VOICE_COMMUNICATION` first because it is the only one with a platform
     * echo-cancellation path; `DEFAULT` last because it is `MIC` on almost every device and is
     * here only for a HAL that special-cases the explicit constant.
     */
    private val CANDIDATE_SOURCES = intArrayOf(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT,
    )
}
