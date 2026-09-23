// Modified by the Flash project (Apache-2.0 §4(b)); see third_party/webrtc-kmp/MODIFICATIONS.md.
@file:JvmName("WebRtcKmpJVM")

package com.shepeliev.webrtckmp

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.logging.Logging
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioDevice
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioProcessing
import java.util.Optional

typealias AudioDeviceModuleBuilder = () -> AudioDeviceModule?

object WebRtc {

    private var _peerConnectionFactory: PeerConnectionFactory? = null
    internal val peerConnectionFactory: PeerConnectionFactory
        get() {
            if (_peerConnectionFactory == null) initialize()
            return checkNotNull(_peerConnectionFactory)
        }

    private var _audioDeviceModule: AudioDeviceModule? = null
    internal val audioDeviceModule: AudioDeviceModule
        get() {
            if (_audioDeviceModule == null) initialize()
            return checkNotNull(_audioDeviceModule)
        }

    private val builder by lazy {
        WebRtcBuilder()
    }

    /** Guards factory/ADM init: the null-check-then-build in both getters is not atomic, and a
     * double init would orphan a whole ADM (running WASAPI threads, never bound, GC'd later —
     * exactly the "second instance torn down at call start" shape). */
    private val initLock = Any()

    fun configureBuilder(block: WebRtcBuilder.() -> Unit = {}) {
        block(builder)
    }

    private fun initialize() {
        synchronized(initLock) {
            if (_peerConnectionFactory != null) return
            initLogging()
            initializePeerConnectionFactory()
        }
    }

    private fun initializePeerConnectionFactory() {
        with(builder) {
            val adm = audioModuleBuilder()
                ?: error("audioModuleBuilder returned null")
            _audioDeviceModule = adm
            _peerConnectionFactory = PeerConnectionFactory(
                adm,
                audioProcessing,
            ).also { factory ->
                println(
                    "[webrtc-jvm] factory bound @${System.identityHashCode(factory)} " +
                        "adm=@${System.identityHashCode(adm)}",
                )
            }
        }
    }

    private fun initLogging() {
        with(builder) {
            loggingSeverity?.let {
                Logging.addLogSink(it) { _, message ->
                    println(message)
                }
            }
        }
    }

    fun addDeviceChangeListener(listener: MediaDeviceListener) {
        MediaDevicesImpl.addDeviceChangeListener(listener)
    }

    fun removeDeviceChangeListener(listener: MediaDeviceListener) {
        MediaDevicesImpl.removeDeviceChangeListener(listener)
    }

    /**
     * Selects + initializes the render device. PRE-FACTORY ONLY: `SetPlayoutDevice` on an
     * initialized side throws ("Set playout device failed"), and init state is sticky —
     * `stopPlayout()` stops streaming but does NOT un-initialize (ERROR-061). After the
     * factory exists the engine owns the device exclusively, so a re-select is a loud no-op.
     * In practice the default builder below already selected both directions before the
     * factory was built; this entry point exists for a settings-driven choice made before
     * the first call. Never starts media: the engine starts render when receive streams run.
     */
    fun setAudioOutputDevice(device: AudioDevice) {
        synchronized(initLock) {
            if (_peerConnectionFactory != null) {
                println(
                    "[webrtc-jvm] playout re-select '${device.name}' IGNORED: device selection " +
                        "is pre-factory-only (change it before the first call)",
                )
                return
            }
            logDeviceMatch("playout", device, runCatching { audioDeviceModule.getPlayoutDevices() }.getOrNull().orEmpty())
            audioDeviceModule.setPlayoutDevice(device)
            audioDeviceModule.initPlayout()
            println("[webrtc-jvm] playout selected '${device.name}' adm=@${System.identityHashCode(audioDeviceModule)}")
        }
    }

    fun setAudioOutputDevice(device: MediaDeviceInfo) {
        MediaDevices.getAudioRenderDevices().firstOrNull {
            it.descriptor == device.deviceId
        }?.let {
            setAudioOutputDevice(it)
        }
    }

    /**
     * Selects + initializes the capture device. PRE-FACTORY ONLY, same reason as render above:
     * init is sticky and `SetRecordingDevice` on an initialized side throws ("Set recording
     * device failed" — the deterministic 2nd-call failure of the ERROR-060 approach, where a
     * stop-first hygiene could not help because stop does not un-initialize). Never starts
     * media: the engine starts capture when send streams run (createAudioSource/track +
     * negotiated SDP), which is also why ANY app-side start permanently breaks transport
     * registration ("Failed to set audio transport since media was active", ERROR-060).
     */
    fun setAudioInputDevice(device: AudioDevice) {
        synchronized(initLock) {
            if (_peerConnectionFactory != null) {
                println(
                    "[webrtc-jvm] recording re-select '${device.name}' IGNORED: device selection " +
                        "is pre-factory-only (change it before the first call)",
                )
                return
            }
            logDeviceMatch("recording", device, runCatching { audioDeviceModule.getRecordingDevices() }.getOrNull().orEmpty())
            audioDeviceModule.setRecordingDevice(device)
            audioDeviceModule.initRecording()
            val muted = runCatching { audioDeviceModule.isMicrophoneMuted() }.getOrNull()
            val vol = runCatching {
                "${audioDeviceModule.getMicrophoneVolume()}" +
                    " [${audioDeviceModule.getMinMicrophoneVolume()}..${audioDeviceModule.getMaxMicrophoneVolume()}]"
            }.getOrNull()
            println("[webrtc-jvm] recording selected '${device.name}' muted=$muted micVolume=$vol adm=@${System.identityHashCode(audioDeviceModule)}")
        }
    }

    /**
     * Replicates the native GUID match (`JNI_AudioDeviceModuleBase::setRecordingDevice` /
     * `setPlayoutDevice`) for diagnosis: on no match the native code silently falls back to
     * index 0, so the requested mic/speaker is NOT what opens — e.g. a dead device that
     * delivers digital silence looks exactly like "recording started, audioLevel 0".
     */
    internal fun logDeviceMatch(kind: String, device: AudioDevice, admList: List<AudioDevice>) {
        val match = admList.indexOfFirst { it.descriptor == device.descriptor }
        val names = admList.mapIndexed { i, d -> "$i='${d.name}'" }
        println(
            "[webrtc-jvm] $kind select '${device.name}' desc='${device.descriptor}' " +
                "matchIndex=$match of ${admList.size} $names",
        )
    }

    fun setAudioInputDevice(device: MediaDeviceInfo) {
        MediaDevices.getAudioCaptureDevices().firstOrNull {
            it.descriptor == device.deviceId
        }?.let {
            setAudioInputDevice(it)
        }
    }

    fun getDefaultAudioOutput(): MediaDeviceInfo? {
        return MediaDevices.getDefaultAudioRenderDevice()?.let {
            MediaDeviceInfo(
                deviceId = it.descriptor,
                label = it.name,
                kind = MediaDeviceKind.AudioOutput,
            )
        }
    }

    fun disposePeerConnectionFactory() {
        peerConnectionFactory.dispose()
    }
}

/**
 * Selects and initializes (but never starts) BOTH directions, then the factory is built on
 * exactly this ADM. This is the official order (audio guide + PeerConnectionExample): select +
 * init BEFORE `PeerConnectionFactory(ADM)`, then never touch the ADM again — the engine owns
 * every start/stop from stream lifetime.
 *
 * Two hard-won rules live here (ERROR-060/061):
 * - Starting media anywhere outside the engine is FORBIDDEN: `RegisterAudioCallback` refuses
 *   transport registration while media is active, and the engine registers around factory /
 *   track setup — an eager/manual start permanently bricks both directions ("Invalid audio
 *   transport" on every callback).
 * - Re-selecting per call is IMPOSSIBLE, not just racy: init state is sticky (stop does not
 *   un-initialize) and set-after-init throws, so the 2nd acquire of every process died with
 *   "Set recording device failed". Selection happens here, once.
 */
internal val defaultAudioDeviceModuleBuilder: AudioDeviceModuleBuilder = {
    AudioDeviceModule().apply {
        println("[webrtc-jvm] ADM created @${System.identityHashCode(this)}")
        MediaDevices.getDefaultAudioRenderDevice()?.let {
            WebRtc.logDeviceMatch("playout", it, runCatching { getPlayoutDevices() }.getOrNull().orEmpty())
            setPlayoutDevice(it)
            initPlayout()
        }
        MediaDevices.getDefaultAudioCaptureDevice()?.let {
            WebRtc.logDeviceMatch("recording", it, runCatching { getRecordingDevices() }.getOrNull().orEmpty())
            setRecordingDevice(it)
            initRecording()
        }
    }
}

class WebRtcBuilder(
    var loggingSeverity: Logging.Severity? = null,
    var audioModuleBuilder: AudioDeviceModuleBuilder = defaultAudioDeviceModuleBuilder,
    var audioProcessing: AudioProcessing? = null,
)
