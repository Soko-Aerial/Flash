package com.transfer.flash.desktop

import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.audioTracks
import com.shepeliev.webrtckmp.videoTracks
import kotlin.test.Test
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Phase 33a — the product runtime actually ships the WebRTC natives.
 *
 * `:core:calling` declares the per-OS/arch `webrtc-java` classified artifact test-only,
 * which is why `DesktopMediaStackSmokeTest` passed while `:desktop:run` died in
 * `NativeLoader.loadLibrary` (NPE inside `Files.copy`: classes present, natives absent).
 * This goes through the exact live path (`webrtc-kmp` `MediaDevices` → `MediaDevicesImpl`
 * static init → native load) on the DESKTOP runtime classpath. Enumeration itself is
 * hardware-free — it lists devices (possibly none) without capturing — so this is
 * headless-safe everywhere.
 *
 * Linux needs PulseAudio's client library (`libpulse.so.0`): webrtc-java's native audio module aborts class
 * initialisation with `java.lang.Error: PulseAudio: Library not found on system` without it (CI 2026-10-08, Ubuntu
 * runner, which does not ship it; the workflow now installs `libpulse0`). On a Linux machine without it these tests
 * SKIP with that reason, except under CI (`CI=true`) where a missing library is a failure: the runner must provide it,
 * otherwise the test would pass by never running. Windows and macOS are not affected.
 */
class DesktopMediaDevicesTest {

    private fun requireNativeAudioRuntime() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) return
        val dirs = listOf(
            "/usr/lib", "/usr/lib64", "/lib", "/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu",
            "/lib/x86_64-linux-gnu", "/lib/aarch64-linux-gnu",
        )
        val present = dirs.any { File(it, "libpulse.so.0").exists() }
        if (present) return
        val message = "libpulse.so.0 not found: install libpulse0 (apt) or pulseaudio-libs; webrtc-java cannot load its audio module without it"
        if (System.getenv("CI") == "true") throw AssertionError(message)
        assumeTrue(message, false)
    }

    @Test
    fun `webrtc natives load and device enumeration answers on the desktop runtime`() = runBlocking {
        requireNativeAudioRuntime()
        val devices = MediaDevices.enumerateDevices()
        // No assertion on content: a headless CI box may have zero devices. Reaching this
        // line IS the assertion — before the fix it threw ExceptionInInitializerError.
        println("webrtc devices: ${devices.size}")
    }

    /**
     * Desktop one-way audio (2026-09-15): calls connected with `bytesOut=0` and
     * `audioLevel=0` forever. First suspect was a missing `startRecording()` (ERROR-056);
     * logs then proved ANY manual ADM start races the voice engine's audio-transport
     * registration and bricks the call (ERROR-058/060) — and the follow-up "select + init
     * per acquire" still died on every 2nd+ acquire with "Set recording device failed"
     * because init is sticky: stop does not un-initialize, and set-after-init throws
     * (ERROR-061). So the rule is the official order: the builder selects + initializes
     * both directions once, before the factory is built, and `getUserMedia` never touches
     * the ADM — the engine owns all start/stop from stream lifetime. This goes through
     * the production capture path (track creation, stream release) twice in one process
     * with production constraints. Headless-safe: with no capture device it exercises the
     * empty path; reaching the println without throwing IS the assertion either way.
     */
    @Test
    fun `audio capture starts and releases without throwing on the desktop runtime`() = runBlocking {
        requireNativeAudioRuntime()
        repeat(2) { i ->
            val stream = MediaDevices.getUserMedia {
                audio {
                    echoCancellation(true)
                    noiseSuppression(true)
                    autoGainControl(true)
                }
            }
            try {
                println("webrtc audio tracks (acquire ${i + 1}): ${stream.audioTracks.size}")
            } finally {
                stream.release()
            }
        }
    }

    @Test
    fun `video capture starts and releases without throwing on the desktop runtime`() = runBlocking {
        requireNativeAudioRuntime()
        val devices = MediaDevices.enumerateDevices()
        devices.forEach { println("device: kind=${it.kind} label='${it.label}' id='${it.deviceId}'") }
        val stream = MediaDevices.getUserMedia {
            audio {
                echoCancellation(true)
                noiseSuppression(true)
                autoGainControl(true)
            }
            video {
                width(1280)
                height(720)
                frameRate(30.0)
            }
        }
        try {
            val videoTrack = stream.videoTracks.firstOrNull()
            println("audio tracks: ${stream.audioTracks.size}, video tracks: ${stream.videoTracks.size}")
            videoTrack?.switchCamera()
            println("switchCamera() completed without error")
        } finally {
            stream.release()
        }
    }
}
