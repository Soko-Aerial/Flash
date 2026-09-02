package com.transfer.flash.core.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import com.shepeliev.webrtckmp.WebRtc
import com.transfer.flash.core.common.logging.FlashLog
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One-shot WebRTC engine setup: installs a low-latency audio device module (ADR-025).
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
 * a call exists.
 *
 * Everything else here is the default made explicit and pinned: `VOICE_COMMUNICATION`
 * capture (which is what routes the mic through the platform's echo-cancellation path),
 * hardware AEC/NS when the device offers them, and voice-communication audio attributes on
 * the output track. Also disables libwebrtc's volume logger, a 30 s polling timer that
 * exists only to write log lines.
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
            return try {
                val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                    // The point of this whole object: PERFORMANCE_MODE_LOW_LATENCY and a
                    // smaller output buffer on the playout AudioTrack.
                    .setUseLowLatency(true)
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setUseHardwareAcousticEchoCanceler(
                        JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(),
                    )
                    .setUseHardwareNoiseSuppressor(
                        JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported(),
                    )
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setEnableVolumeLogger(false)
                    .createAudioDeviceModule()
                WebRtc.configure(
                    peerConnectionFactoryBuilder = WebRtc.createPeerConnectionFactoryBuilder()
                        .setAudioDeviceModule(adm),
                )
                FlashLog.i(
                    "CALL",
                    "WebRTC engine configured: low-latency ADM, " +
                        "hwAec=${JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()} " +
                        "hwNs=${JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()}",
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
}
