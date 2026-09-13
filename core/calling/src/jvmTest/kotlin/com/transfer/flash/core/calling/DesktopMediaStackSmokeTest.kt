@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.BundlePolicy
import com.shepeliev.webrtckmp.MediaDeviceKind
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.RtcpMuxPolicy
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.onIceCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
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
}
