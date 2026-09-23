@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.BundlePolicy
import com.shepeliev.webrtckmp.MediaDeviceKind
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.RtcpMuxPolicy
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * Phase 25 S3a — the desktop media-stack smoke test.
 *
 * Everything else about this phase's desktop path was verified at COMPILE time; this is the one
 * test that proves the vendored fork's JVM stack actually RUNS on this host: the PeerConnection
 * factory initialises (loading `webrtc-java`'s native library — the step the 0.8.0→0.17.0 bump
 * could plausibly have broken), a [PeerConnection] constructs, and a real SDP offer is produced
 * with our production configuration (empty iceServers — Flash is LAN/hotspot only, ADR-025).
 *
 * It deliberately does NOT touch capture: this host may have no microphone or camera, and the
 * point is the negotiation stack, not the hardware. ICE candidates ARE collected because a
 * host-candidate-only LAN design depends on them being generated at all.
 */
class DesktopMediaStackSmokeTest {

    /**
     * Hardware/native-dependent (audit B5): needs the native WebRTC build this project bundles (Windows) and real
     * audio devices. GitHub's Linux runner has neither, so every case failed there and kept CI red, which gated
     * nothing. Skipped ONLY on CI (`CI=true`, set by GitHub Actions); set `FLASH_HW_TESTS=1` to force it. A
     * "skip if the native library fails to load" probe would be worse: it would also hide a real native break on
     * a developer machine, which is exactly what this smoke test exists to catch.
     */
    @Before
    fun requireHardwareMediaStack() {
        assumeFalse(
            "native WebRTC smoke test skipped on CI (set FLASH_HW_TESTS=1 to run)",
            System.getenv("CI") == "true" && System.getenv("FLASH_HW_TESTS") != "1",
        )
    }

    @Test
    fun `jvm peer connection factory initialises and produces a real offer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Flash's own configuration: no STUN/TURN. If the native library were absent or
        // mis-versioned, this construction is where it throws UnsatisfiedLinkError.
        val pc = PeerConnection(
            RtcConfiguration(
                bundlePolicy = BundlePolicy.MaxBundle,
                iceServers = emptyList(),
                rtcpMuxPolicy = RtcpMuxPolicy.Require,
            ),
        )
        try {
            val candidates = mutableListOf<String>()
            val collector = scope.launch { pc.onIceCandidate.collect { candidates += it.candidate } }

            // A data channel, so the offer carries a REAL media section without needing a
            // microphone or camera. Modern libwebrtc emits no `m=` line for a connection with no
            // transceivers — `offerToReceiveAudio` is legacy and ignored under Unified Plan — so
            // this is what makes the SDP assertion meaningful on a headless box.
            pc.createDataChannel("smoke")

            // The same options the production caller uses (ADR-025: only the caller offers).
            val offer = pc.createOffer(
                OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = false),
            )
            assertTrue("offer must be typed OFFER", offer.type == SessionDescriptionType.Offer)
            assertTrue("offer SDP must be non-empty", offer.sdp.isNotBlank())
            assertTrue(
                "offer must carry the data-channel media section: " + offer.sdp,
                offer.sdp.contains("m=application"),
            )

            pc.setLocalDescription(offer)
            // Host candidates prove ICE is actually running on the JVM backend. A LAN-only
            // product whose stack cannot gather host candidates cannot carry a call at all.
            withTimeout(20_000) {
                while (candidates.isEmpty()) delay(100)
            }
            assertTrue(
                "expected a gathered ICE candidate, got $candidates",
                candidates.any { it.contains("candidate:") },
            )
            collector.cancel()
        } finally {
            runCatching { pc.close() }
            scope.cancel()
        }
    }

    @Test
    fun `jvm MediaDevices enumerates without throwing - the desktop audio seam exists`() = runBlocking {
        // Enumerate-only: a headless box legitimately has zero devices, so the assertion is that
        // the call returns (the JVM audio layer is present and did not throw), not that a device
        // exists. The desktop engine's audio wiring depends on this call working.
        val devices = runCatching { MediaDevices.enumerateDevices() }
        assertTrue(
            "enumerateDevices must not throw on the JVM backend: ${devices.exceptionOrNull()}",
            devices.isSuccess,
        )
        devices.getOrNull()?.forEach { device ->
            assertTrue(
                "unexpected device kind ${device.kind}",
                device.kind == MediaDeviceKind.AudioInput ||
                    device.kind == MediaDeviceKind.AudioOutput ||
                    device.kind == MediaDeviceKind.VideoInput,
            )
        }
        // Explicit Unit: JUnit 4 requires void test methods, and runBlocking would otherwise
        // infer Boolean from the trailing assertTrue (InvalidTestClassError).
        Unit
    }

    @Test
    fun `webrtc accepts video offer with H264 stripped`() = runBlocking {
        val pc = PeerConnection(
            RtcConfiguration(
                bundlePolicy = BundlePolicy.MaxBundle,
                iceServers = emptyList(),
                rtcpMuxPolicy = RtcpMuxPolicy.Require,
            ),
        )
        try {
            val stream = MediaDevices.getUserMedia {
                audio { echoCancellation(true) }
                video { width(640); height(480) }
            }
            stream.tracks.forEach { pc.addTrack(it, stream) }
            val offer = pc.createOffer(OfferAnswerOptions())
            val strippedSdp = CallSdp.stripH264(offer.sdp)
            assertTrue("stripped SDP must not contain H264", !strippedSdp.contains("H264"))
            pc.setLocalDescription(SessionDescription(SessionDescriptionType.Offer, strippedSdp))
        } finally {
            pc.close()
        }
    }

    @Test
    fun inspectCapabilities() {
        val factory = dev.onvoid.webrtc.PeerConnectionFactory()
        try {
            val recvCaps = factory.getRtpReceiverCapabilities(dev.onvoid.webrtc.media.MediaType.VIDEO)
            println("Receiver video codecs:")
            recvCaps.codecs.forEach { println("  name=${it.name} mime=${it.mimeType} fmtp=${it.sdpFmtp}") }
            val sendCaps = factory.getRtpSenderCapabilities(dev.onvoid.webrtc.media.MediaType.VIDEO)
            println("Sender video codecs:")
            sendCaps.codecs.forEach { println("  name=${it.name} mime=${it.mimeType} fmtp=${it.sdpFmtp}") }
        } finally {
            factory.dispose()
        }
    }

    @Test
    fun `two peer connections can stream video locally and decode frames`() = runBlocking {
        val pc1 = PeerConnection(RtcConfiguration(bundlePolicy = BundlePolicy.MaxBundle, iceServers = emptyList(), rtcpMuxPolicy = RtcpMuxPolicy.Require))
        val pc2 = PeerConnection(RtcConfiguration(bundlePolicy = BundlePolicy.MaxBundle, iceServers = emptyList(), rtcpMuxPolicy = RtcpMuxPolicy.Require))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            scope.launch { pc1.onIceCandidate.collect { runCatching { pc2.addIceCandidate(it) } } }
            scope.launch { pc2.onIceCandidate.collect { runCatching { pc1.addIceCandidate(it) } } }

            val stream = MediaDevices.getUserMedia {
                video { width(640); height(480) }
            }
            stream.tracks.forEach { pc1.addTrack(it, stream) }

            var remoteFrameReceived = false
            scope.launch {
                pc2.onTrack.collect { event ->
                    val track = event.track
                    println("PC2 received track kind=${track?.kind} track=$track")
                    if (track is com.shepeliev.webrtckmp.VideoStreamTrack) {
                        track.addSink(object : dev.onvoid.webrtc.media.video.VideoTrackSink {
                            override fun onVideoFrame(frame: dev.onvoid.webrtc.media.video.VideoFrame) {
                                val buf = frame.buffer ?: return
                                println("PC2 DECODED VIDEO FRAME: ${buf.width}x${buf.height}")
                                remoteFrameReceived = true
                            }
                        })
                    }
                }
            }

            val offer = pc1.createOffer(OfferAnswerOptions(offerToReceiveVideo = true))
            println("Offer video line: " + offer.sdp.lines().firstOrNull { it.startsWith("m=video") })
            val strippedOffer = CallSdp.enforceVp8Only(offer.sdp)
            println("Stripped offer video line: " + strippedOffer.lines().firstOrNull { it.startsWith("m=video") })

            pc1.setLocalDescription(SessionDescription(SessionDescriptionType.Offer, strippedOffer))
            pc2.setRemoteDescription(SessionDescription(SessionDescriptionType.Offer, strippedOffer))

            val answer = pc2.createAnswer(OfferAnswerOptions(offerToReceiveVideo = true))
            println("Answer video line: " + answer.sdp.lines().firstOrNull { it.startsWith("m=video") })
            val strippedAnswer = CallSdp.enforceVp8Only(answer.sdp)

            pc2.setLocalDescription(SessionDescription(SessionDescriptionType.Answer, strippedAnswer))
            pc1.setRemoteDescription(SessionDescription(SessionDescriptionType.Answer, strippedAnswer))

            withTimeout(15_000) {
                while (!remoteFrameReceived) {
                    delay(200)
                }
            }
            assertTrue("remote frame must be received and decoded", remoteFrameReceived)
        } finally {
            pc1.close()
            pc2.close()
            scope.cancel()
        }
    }

    @Test
    fun `verify tuneLocal with x-google params causes NullVideoDecoder vs clean VP8`() = runBlocking {
        val pc1 = PeerConnection(RtcConfiguration(bundlePolicy = BundlePolicy.MaxBundle, iceServers = emptyList(), rtcpMuxPolicy = RtcpMuxPolicy.Require))
        val pc2 = PeerConnection(RtcConfiguration(bundlePolicy = BundlePolicy.MaxBundle, iceServers = emptyList(), rtcpMuxPolicy = RtcpMuxPolicy.Require))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            scope.launch { pc1.onIceCandidate.collect { runCatching { pc2.addIceCandidate(it) } } }
            scope.launch { pc2.onIceCandidate.collect { runCatching { pc1.addIceCandidate(it) } } }

            val stream = MediaDevices.getUserMedia {
                video { width(640); height(480) }
            }
            stream.tracks.forEach { pc1.addTrack(it, stream) }

            var remoteFrameReceived = false
            scope.launch {
                pc2.onTrack.collect { event ->
                    val track = event.track
                    if (track is com.shepeliev.webrtckmp.VideoStreamTrack) {
                        track.addSink(object : dev.onvoid.webrtc.media.video.VideoTrackSink {
                            override fun onVideoFrame(frame: dev.onvoid.webrtc.media.video.VideoFrame) {
                                remoteFrameReceived = true
                            }
                        })
                    }
                }
            }

            val offer = pc1.createOffer(OfferAnswerOptions(offerToReceiveVideo = true))
            val tunedOffer = CallSdp.tuneLocal(offer.sdp, com.transfer.flash.core.common.perf.FlashPerformanceMode.HIGH)
            val cleanOffer = CallSdp.enforceVp8Only(tunedOffer)

            pc1.setLocalDescription(SessionDescription(SessionDescriptionType.Offer, cleanOffer))
            pc2.setRemoteDescription(SessionDescription(SessionDescriptionType.Offer, cleanOffer))

            val answer = pc2.createAnswer(OfferAnswerOptions(offerToReceiveVideo = true))
            val tunedAnswer = CallSdp.tuneLocal(answer.sdp, com.transfer.flash.core.common.perf.FlashPerformanceMode.HIGH)
            val cleanAnswer = CallSdp.enforceVp8Only(tunedAnswer)

            pc2.setLocalDescription(SessionDescription(SessionDescriptionType.Answer, cleanAnswer))
            pc1.setRemoteDescription(SessionDescription(SessionDescriptionType.Answer, cleanAnswer))

            val decodedWithoutFmtp = runCatching {
                withTimeout(6_000) {
                    while (!remoteFrameReceived) delay(200)
                }
                true
            }.getOrDefault(false)
            println("DECODED THROUGH TUNELOCAL + ENFORCEVP8ONLY: $decodedWithoutFmtp")
            assertTrue("VP8 filtered through enforceVp8Only must decode successfully", decodedWithoutFmtp)
        } finally {
            pc1.close()
            pc2.close()
            scope.cancel()
        }
    }
}
