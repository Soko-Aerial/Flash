package com.transfer.flash.core.calling

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [CallSdp] rewriting tests (C7, ADR-025; made tier-aware by ERROR-033).
 *
 * SDP is the only place this stack exposes Opus packetization and the encoder's bitrate window, so
 * those two latency knobs are set by string surgery on a text protocol. The failure modes are all
 * silent — a malformed fmtp line does not throw, it just makes the peer ignore the codec — and the
 * alternative to unit tests is measuring bitrate policy on a phone. Hence: exact expected lines,
 * and an explicit guard on every payload type that must NOT be touched (`rtx`, `red`, `ulpfec`,
 * PCMU).
 *
 * The tier tests carry a load the old symmetric `tune()` could not. A sender takes its
 * packetization and bitrate from the description it *receives*, so [CallSdp.tuneLocal] and
 * [CallSdp.tuneRemote] have to do different jobs — and the property that matters is that two
 * differently-tiered endpoints compute the *same* effective parameters no matter which of them
 * offered.
 */
class CallSdpTest {

    private val low = FlashPerformanceMode.LOW
    private val medium = FlashPerformanceMode.MEDIUM
    private val high = FlashPerformanceMode.HIGH

    private fun sdp(vararg lines: String): String = lines.joinToString("\r\n")

    /** The lines of one m-section, `m=` line included. */
    private fun section(body: String, kind: String): List<String> {
        val lines = body.split("\r\n", "\n")
        val start = lines.indexOfFirst { it.startsWith("m=$kind ") }
        if (start < 0) return emptyList()
        return listOf(lines[start]) + lines.drop(start + 1).takeWhile { !it.startsWith("m=") }
    }

    /** One payload type's `;`-separated fmtp parameter list, keyed by parameter name. */
    private fun fmtpParams(body: String, kind: String, payloadType: String): Map<String, String> =
        section(body, kind)
            .single { it.startsWith("a=fmtp:$payloadType ") }
            .substringAfter(' ')
            .split(';')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    /** The one `a=ptime:` value of an audio section. */
    private fun ptime(body: String): String =
        section(body, "audio").single { it.startsWith("a=ptime:") }.removePrefix("a=ptime:")

    /** The fmtp parameter list [mode] writes for VP8, in the order [CallSdp] emits it. */
    private fun videoParamsOf(mode: FlashPerformanceMode): String =
        "x-google-start-bitrate=${mode.video.startBitrateKbps};" +
            "x-google-min-bitrate=${mode.video.minBitrateKbps};" +
            "x-google-max-bitrate=${mode.video.maxBitrateKbps}"

    /** The fmtp parameter list [mode] writes for Opus, in the order [CallSdp] emits it. */
    private fun opusParamsOf(mode: FlashPerformanceMode): String =
        "minptime=${mode.voice.ptimeMs};useinbandfec=1;usedtx=${if (mode.voice.useDtx) 1 else 0};maxaveragebitrate=${mode.voice.maxBitrateBps}"

    /** A trimmed but structurally faithful libwebrtc offer: bundled audio + video. */
    private val offer = sdp(
        "v=0",
        "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "a=group:BUNDLE 0 1",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111 63 0",
        "c=IN IP4 0.0.0.0",
        "a=mid:0",
        "a=rtpmap:111 opus/48000/2",
        "a=rtcp-fb:111 transport-cc",
        "a=fmtp:111 minptime=20;useinbandfec=1",
        "a=rtpmap:63 red/48000/2",
        "a=fmtp:63 111/111",
        "a=rtpmap:0 PCMU/8000",
        "a=ptime:20",
        "a=maxptime:120",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99 100",
        "c=IN IP4 0.0.0.0",
        "a=mid:1",
        "a=rtpmap:96 VP8/90000",
        "a=rtcp-fb:96 nack pli",
        "a=rtpmap:97 rtx/90000",
        "a=fmtp:97 apt=96",
        "a=rtpmap:98 H264/90000",
        "a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
        "a=rtpmap:99 red/90000",
        "a=rtpmap:100 ulpfec/90000",
    )

    // ------------------------------------------------------------------
    // Audio packetization
    // ------------------------------------------------------------------

    /**
     * `a=ptime:20` is what libwebrtc generates, and the *sender* reads packetization from the
     * description it receives — so a value left at libwebrtc's default is a tier that never reached
     * the wire. Exactly one ptime line may survive, whichever way the tier moves it.
     */
    @Test
    fun local_forcesOurTiersPtime() {
        for (mode in FlashPerformanceMode.entries) {
            val audio = section(CallSdp.tuneLocal(offer, mode), "audio")

            assertEquals(listOf("a=ptime:${mode.voice.ptimeMs}"),
                audio.filter { it.startsWith("a=ptime:") }, "tier ${mode.key}")
            assertTrue("a=maxptime:120" in audio, "maxptime is not ours to change: $audio")
        }
    }

    /** An audio section with no ptime at all gets one, after the last attribute line. */
    @Test
    fun local_insertsPtimeWhenAbsent() {
        val body = sdp(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "c=IN IP4 0.0.0.0",
            "a=mid:0",
            "a=rtpmap:111 opus/48000/2",
        )

        val audio = section(CallSdp.tuneLocal(body, high), "audio")

        assertEquals(audio.last(), "a=ptime:${high.voice.ptimeMs}")
    }

    /**
     * Several `a=ptime:` lines are read at their largest, so the result cannot depend on the order
     * the peer happened to list them in. Both permutations must land on the same value.
     */
    @Test
    fun remote_readsSeveralPtimeLinesAtTheirLargestWhateverTheOrder() {
        fun body(vararg ptimes: Int) = sdp(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            *ptimes.map { "a=ptime:$it" }.toTypedArray(),
        )

        // MEDIUM asks for 20; the peer's largest declaration is 40, and the envelope takes theirs.
        assertEquals(ptime(CallSdp.tuneRemote(body(20, 40), medium)), "40")
        assertEquals(ptime(CallSdp.tuneRemote(body(40, 20), medium)), "40")
    }

    /** Merging must not drop parameters the peer negotiated; only our own keys move. */
    @Test
    fun local_mergesOpusFmtpInPlace() {
        val audio = section(CallSdp.tuneLocal(offer, high), "audio")

        assertEquals(audio.single { it.startsWith("a=fmtp:111 ") }, "a=fmtp:111 ${opusParamsOf(high)}")
    }

    /** No fmtp line for Opus means the params have to be created next to the rtpmap. */
    @Test
    fun local_createsOpusFmtpWhenAbsent() {
        val body = sdp(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=rtcp-fb:111 transport-cc",
        )

        val audio = section(CallSdp.tuneLocal(body, high), "audio")
        val rtpmapAt = audio.indexOfFirst { it.startsWith("a=rtpmap:111 ") }

        assertEquals(audio[rtpmapAt + 1], "a=fmtp:111 ${opusParamsOf(high)}")
    }

    /**
     * Opus carries parameters this stack has no policy about (`stereo`, `cbr`, `maxaveragebitrate`).
     * Dropping or reordering them is how you turn a rewrite into a renegotiation: they stay where
     * the peer put them, and only the values of our own keys change.
     */
    @Test
    fun local_preservesParametersWeHaveNoOpinionAbout() {
        val body = sdp(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 maxplaybackrate=16000;minptime=20;stereo=0;useinbandfec=1;cbr=1",
        )

        val audio = section(CallSdp.tuneLocal(body, low), "audio")

        assertEquals(
            "a=fmtp:111 maxplaybackrate=16000;minptime=60;stereo=0;useinbandfec=1;cbr=1;usedtx=1;maxaveragebitrate=${low.voice.maxBitrateBps}",
            audio.single { it.startsWith("a=fmtp:111 ") },
        )
    }

    @Test
    fun withPtime_clampsToMaxPtimeWhenPresent() {
        val body = sdp(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=ptime:20",
            "a=maxptime:40",
        )

        val tuned = CallSdp.tuneLocal(body, low)
        // LOW requests 60ms, but maxptime:40 clamps it to 40ms
        assertEquals(ptime(tuned), "40")
    }

    /** `red` and PCMU are not Opus: their payload types must come out untouched. */
    @Test
    fun audio_leavesNonOpusPayloadTypesAlone() {
        val audio = section(CallSdp.tuneLocal(offer, low), "audio")

        assertTrue("a=fmtp:63 111/111" in audio, "red's fmtp is a payload list, not params: $audio")
        assertTrue(audio.none { it.startsWith("a=fmtp:0 ") }, "PCMU needs no fmtp: $audio")
    }

    /** Bitrate hints in an audio section would be nonsense — and are silently ignored. */
    @Test
    fun audio_carriesNoVideoBitrateHints() {
        val audio = section(CallSdp.tuneLocal(offer, low), "audio")

        assertTrue(audio.none { it.contains("x-google-") })
    }

    // ------------------------------------------------------------------
    // Video: the encoder's bitrate window
    // ------------------------------------------------------------------

    /** Every real video codec in the section is seeded, whether or not it had an fmtp line. */
    @Test
    fun local_seedsBitrateOnEveryCodec() {
        val video = section(CallSdp.tuneLocal(offer, high), "video")

        assertEquals(video.single { it.startsWith("a=fmtp:96 ") }, "a=fmtp:96 ${videoParamsOf(high)}")
        assertEquals(
            "a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;" +
                "profile-level-id=42e01f;${videoParamsOf(high)}",
            video.single { it.startsWith("a=fmtp:98 ") },
        )
    }

    /**
     * Retransmission and FEC payload types carry structural parameters (`apt=96`), not codec
     * ones. Writing a bitrate hint into `a=fmtp:97` is how you lose retransmissions.
     */
    @Test
    fun video_leavesRtxRedAndUlpfecAlone() {
        val video = section(CallSdp.tuneLocal(offer, low), "video")

        assertEquals(video.single { it.startsWith("a=fmtp:97 ") }, "a=fmtp:97 apt=96")
        assertTrue(video.none { it.startsWith("a=fmtp:99 ") })
        assertTrue(video.none { it.startsWith("a=fmtp:100 ") })
    }

    /** ptime is an audio attribute; a video section must not acquire one. */
    @Test
    fun video_hasNoPtime() {
        for (mode in FlashPerformanceMode.entries) {
            val video = section(CallSdp.tuneLocal(offer, mode), "video")

            assertTrue(video.none { it.startsWith("a=ptime:") }, "tier ${mode.key}")
        }
    }

    /**
     * The literal numbers of each tier, pinned (D8). Every other assertion in this class
     * interpolates the profiles, so all of them would have passed just as happily at the old
     * 8 Mbit/s — and 8 Mbit/s of video on a phone hotspot is what made voice unintelligible. The
     * numbers are the fix, so the numbers are what the test names.
     *
     * HIGH's triple is also the pre-tiering constant set, which is what makes that tier a provable
     * no-op rather than a fourth set of numbers to keep in sync.
     */
    @Test
    fun local_capsBitratePerTier() {
        val expected = mapOf(
            low to Triple(200, 100, 350),
            medium to Triple(500, 250, 900),
            high to Triple(1200, 600, 2500),
        )

        for ((mode, triple) in expected) {
            val params = fmtpParams(CallSdp.tuneLocal(offer, mode), "video", "96")
            val (start, min, max) = triple

            assertEquals("$start", params["x-google-start-bitrate"], "tier ${mode.key} start")
            assertEquals("$min", params["x-google-min-bitrate"], "tier ${mode.key} min")
            assertEquals("$max", params["x-google-max-bitrate"], "tier ${mode.key} max")
        }
    }

    /** And the voice numbers, likewise pinned: 100 pps at HIGH down to ~16 at LOW. */
    @Test
    fun local_setsPacketizationPerTier() {
        assertEquals(ptime(CallSdp.tuneLocal(offer, low)), "60")
        assertEquals(ptime(CallSdp.tuneLocal(offer, medium)), "20")
        assertEquals(ptime(CallSdp.tuneLocal(offer, high)), "20")

        assertEquals(fmtpParams(CallSdp.tuneLocal(offer, low), "audio", "111")["usedtx"], "1")
        assertEquals(fmtpParams(CallSdp.tuneLocal(offer, medium), "audio", "111")["usedtx"], "1")
        assertEquals(fmtpParams(CallSdp.tuneLocal(offer, high), "audio", "111")["usedtx"], "1")
    }

    // ------------------------------------------------------------------
    // Mixed tiers: the envelope
    // ------------------------------------------------------------------

    /**
     * The bug that made two functions necessary. A HIGH-tier phone that merely forced its own
     * numbers onto a LOW-tier handset's offer would rewrite `ptime:60` straight back to `ptime:20`
     * and defeat the whole request — its sender would go on emitting packets into the
     * radio that could not take them.
     */
    @Test
    fun local_overridesTheBodyWhereRemote_respectsIt() {
        val lowOffer = CallSdp.tuneLocal(offer, low)
        assertEquals(ptime(lowOffer), "60")

        // Same input, same tier, opposite verdict — which is exactly why the split exists.
        assertEquals(ptime(CallSdp.tuneLocal(lowOffer, high)), "20")
        assertEquals(ptime(CallSdp.tuneRemote(lowOffer, high)), "60")
        assertNotEquals(CallSdp.tuneLocal(lowOffer, high), CallSdp.tuneRemote(lowOffer, high))
    }

    /**
     * The invariant the envelope buys: both endpoints derive the *same* effective parameters, and
     * which of them happened to offer does not enter into it. Anything weaker and a mixed-tier call
     * runs one direction at the constrained tier and the other at the capable one.
     */
    @Test
    fun bothEndpointsComputeTheSameParametersWhicheverOfThemOffered() {
        // LOW dials: its offer declares 60 ms / 350 kbps, and HIGH folds that in on receipt.
        val highSees = CallSdp.tuneRemote(CallSdp.tuneLocal(offer, low), high)
        // HIGH dials: its offer declares 10 ms / 2500 kbps, and LOW folds that in on receipt.
        val lowSees = CallSdp.tuneRemote(CallSdp.tuneLocal(offer, high), low)

        assertEquals(highSees, lowSees)

        // Spelled out, so a failure says which knob moved rather than just "strings differ".
        assertEquals(ptime(highSees), "60")
        assertEquals(fmtpParams(highSees, "audio", "111")["usedtx"], "1")
        assertEquals(fmtpParams(highSees, "audio", "111")["minptime"], "60")
        assertEquals(fmtpParams(highSees, "audio", "111")["maxaveragebitrate"], "20000")
        assertEquals(fmtpParams(highSees, "video", "96")["x-google-max-bitrate"], "350")
        assertEquals(fmtpParams(highSees, "video", "96")["x-google-min-bitrate"], "100")
        assertEquals(fmtpParams(highSees, "video", "96")["x-google-start-bitrate"], "200")
    }

    /** Longer Opus frames win, the tighter bitrate ceiling wins — for every tier pairing. */
    @Test
    fun remote_takesTheLongerFrameAndTheSmallerCeiling() {
        for (theirs in FlashPerformanceMode.entries) {
            for (ours in FlashPerformanceMode.entries) {
                val seen = CallSdp.tuneRemote(CallSdp.tuneLocal(offer, theirs), ours)
                val label = "${theirs.key} → ${ours.key}"
                val video = fmtpParams(seen, "video", "96")

                assertEquals(
                    "${maxOf(theirs.voice.ptimeMs, ours.voice.ptimeMs)}",
                    ptime(seen),
                    label,
                )
                assertEquals(
                    "${minOf(theirs.video.maxBitrateKbps, ours.video.maxBitrateKbps)}",
                    video["x-google-max-bitrate"],
                    label,
                )
            }
        }
    }

    /**
     * The seed must stay inside the window in every pairing too. Seeding above the cap would ask
     * congestion control to walk *down* from an illegal rate on the first frame; seeding below the
     * floor makes the seed a no-op. Taking MIN of all three independently only preserves the
     * ordering because the tiers are monotonic in all three fields — this is the test that says so.
     */
    @Test
    fun remote_keepsTheSeedInsideTheWindowForEveryPairing() {
        for (theirs in FlashPerformanceMode.entries) {
            for (ours in FlashPerformanceMode.entries) {
                val video = fmtpParams(
                    CallSdp.tuneRemote(CallSdp.tuneLocal(offer, theirs), ours),
                    "video",
                    "96",
                )
                val floor = video.getValue("x-google-min-bitrate").toInt()
                val start = video.getValue("x-google-start-bitrate").toInt()
                val ceiling = video.getValue("x-google-max-bitrate").toInt()
                val label = "${theirs.key} → ${ours.key}: $floor/$start/$ceiling"

                assertTrue(floor <= start, label)
                assertTrue(start < ceiling, label)
            }
        }
    }

    /**
     * DTX is MAX rather than "ours", because the airtime it saves is on the link and both ends
     * share the link. Even if a peer attempted to disable DTX, MAX turns it on when either asks.
     */
    @Test
    fun remote_turnsDtxOnWhenEitherEndAsksForIt() {
        assertTrue(high.voice.useDtx)

        val fromLow = CallSdp.tuneRemote(CallSdp.tuneLocal(offer, low), high)

        assertEquals(fmtpParams(fromLow, "audio", "111")["usedtx"], "1")
        // ...and inband FEC survives the fold in both directions; it is on at every tier.
        assertEquals(fmtpParams(fromLow, "audio", "111")["useinbandfec"], "1")
    }

    /**
     * A peer declaration we cannot read as a number is not a constraint we could honour, so our own
     * value stands rather than being dropped. Silently emitting the peer's garbage back would make
     * the codec unusable at both ends.
     */
    @Test
    fun remote_keepsOurValueWhenThePeerDeclarationIsNotANumber() {
        val body = sdp(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=wide;useinbandfec=1",
            "a=ptime:tiny",
        )

        val tuned = CallSdp.tuneRemote(body, low)

        assertEquals(ptime(tuned), "60")
        assertEquals(fmtpParams(tuned, "audio", "111")["minptime"], "60")
    }

    // ------------------------------------------------------------------
    // Shape preservation
    // ------------------------------------------------------------------

    /**
     * Both entry points can legitimately see the same body twice — a local description is tuned on
     * create and can be re-tuned on an ICE restart, and a remote one is re-offered on
     * renegotiation. A second pass that appended a duplicate `usedtx` or a second ptime line would
     * be a parse error on the peer.
     */
    @Test
    fun bothDirectionsAreIdempotent() {
        for (mode in FlashPerformanceMode.entries) {
            val local = CallSdp.tuneLocal(offer, mode)
            assertEquals(local, CallSdp.tuneLocal(local, mode), "tier ${mode.key}")

            val remote = CallSdp.tuneRemote(offer, mode)
            assertEquals(remote, CallSdp.tuneRemote(remote, mode), "tier ${mode.key}")
        }
    }

    /** CRLF is what libwebrtc emits, and mixing terminators inside one body breaks parsers. */
    @Test
    fun preservesCrlf() {
        val tuned = CallSdp.tuneLocal(offer, low)

        assertTrue(tuned.contains("\r\n"))
        assertTrue(tuned.split("\r\n").none { it.contains("\n") }, "no bare LF may survive")
    }

    /** An LF-only body stays LF-only — the terminator is detected, not imposed. */
    @Test
    fun preservesBareLf() {
        val body = listOf(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
        ).joinToString("\n")

        val tuned = CallSdp.tuneLocal(body, low)

        assertFalse(tuned.contains("\r"))
        assertTrue(tuned.contains("a=ptime:${low.voice.ptimeMs}"))
    }

    /** A body with nothing to tune comes back byte-identical rather than reformatted. */
    @Test
    fun passesThroughWhatItDoesNotRecognise() {
        val sessionOnly = sdp("v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0")

        assertEquals(sessionOnly, CallSdp.tuneLocal(sessionOnly, low))
        assertEquals(sessionOnly, CallSdp.tuneRemote(sessionOnly, low))
        assertEquals(CallSdp.tuneLocal("", low), "")
        assertEquals(CallSdp.tuneRemote("", low), "")
    }

    /** A data-only m-section (the transfer path's SCTP negotiation) is not audio or video. */
    @Test
    fun ignoresApplicationSections() {
        val body = sdp(
            "v=0",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
            "a=mid:2",
            "a=sctp-port:5000",
        )

        assertEquals(body, CallSdp.tuneLocal(body, low))
        assertEquals(body, CallSdp.tuneRemote(body, low))
    }

    @Test
    fun enforceVp8Only_removesAllNonVp8CodecsFromVideoSection() {
        val input = sdp(
            "v=0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99 100 101",
            "a=rtpmap:96 VP8/90000",
            "a=rtcp-fb:96 nack pli",
            "a=rtpmap:97 rtx/90000",
            "a=fmtp:97 apt=96",
            "a=rtpmap:98 H264/90000",
            "a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1",
            "a=rtpmap:99 rtx/90000",
            "a=fmtp:99 apt=98",
            "a=rtpmap:100 VP9/90000",
            "a=rtpmap:101 AV1/90000",
        )
        val stripped = CallSdp.enforceVp8Only(input)

        val videoLines = section(stripped, "video")
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 96", videoLines.first())
        assertFalse(stripped.contains("H264"))
        assertFalse(stripped.contains("VP9"))
        assertFalse(stripped.contains("AV1"))
        assertFalse(stripped.contains("a=rtpmap:97"))
        assertFalse(stripped.contains("a=fmtp:97"))
        assertFalse(stripped.contains("a=rtpmap:98"))
        assertFalse(stripped.contains("a=fmtp:98"))
        assertFalse(stripped.contains("a=rtpmap:99"))
        assertFalse(stripped.contains("a=fmtp:99"))
        assertFalse(stripped.contains("a=rtpmap:100"))
        assertFalse(stripped.contains("a=rtpmap:101"))
        assertTrue(stripped.contains("a=rtpmap:96 VP8/90000"))
    }

    @Test
    fun enforceVp8Only_leavesSdpWithVp8OnlyUnchanged() {
        val sdpWithVp8Only = sdp(
            "v=0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=rtpmap:96 VP8/90000",
        )
        assertEquals(sdpWithVp8Only, CallSdp.enforceVp8Only(sdpWithVp8Only))
        assertEquals("", CallSdp.enforceVp8Only(""))
    }
}
