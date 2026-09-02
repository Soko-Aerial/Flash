package com.transfer.flash.core.calling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CallSdp] rewriting tests (C7, ADR-025).
 *
 * SDP is the only place this stack exposes Opus packetization and the encoder's start
 * bitrate, so those two latency knobs are set by string surgery on a text protocol. The
 * failure modes are all silent — a malformed fmtp line does not throw, it just makes the
 * peer ignore the codec — and the alternative to unit tests is measuring bitrate policy on
 * a phone. Hence: exact expected lines, and an explicit guard on every payload type that
 * must NOT be touched (`rtx`, `red`, `ulpfec`, PCMU).
 */
class CallSdpTest {

    private fun sdp(vararg lines: String): String = lines.joinToString("\r\n")

    /** The lines of one m-section, `m=` line included. */
    private fun section(body: String, kind: String): List<String> {
        val lines = body.split("\r\n", "\n")
        val start = lines.indexOfFirst { it.startsWith("m=$kind ") }
        if (start < 0) return emptyList()
        return listOf(lines[start]) + lines.drop(start + 1).takeWhile { !it.startsWith("m=") }
    }

    private val videoParams =
        "x-google-start-bitrate=${CallSdp.VIDEO_START_BITRATE_KBPS};" +
            "x-google-min-bitrate=${CallSdp.VIDEO_MIN_BITRATE_KBPS};" +
            "x-google-max-bitrate=${CallSdp.VIDEO_MAX_BITRATE_KBPS}"

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
    // Audio: 10 ms packetization
    // ------------------------------------------------------------------

    /**
     * `a=ptime:20` is what libwebrtc generates, and the *sender* reads packetization from
     * the description it receives — so a stale 20 here is 10 ms of one-way delay that no
     * per-call API can take back. Exactly one ptime line may survive.
     */
    @Test
    fun audio_forcesTenMillisecondPtime() {
        val audio = section(CallSdp.tune(offer), "audio")

        assertEquals(
            listOf("a=ptime:${CallSdp.OPUS_PTIME_MS}"),
            audio.filter { it.startsWith("a=ptime:") },
        )
        assertTrue("maxptime is not ours to change: $audio", "a=maxptime:120" in audio)
    }

    /** An audio section with no ptime at all gets one, after the last attribute line. */
    @Test
    fun audio_insertsPtimeWhenAbsent() {
        val body = sdp(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "c=IN IP4 0.0.0.0",
            "a=mid:0",
            "a=rtpmap:111 opus/48000/2",
        )

        val audio = section(CallSdp.tune(body), "audio")

        assertEquals("a=ptime:${CallSdp.OPUS_PTIME_MS}", audio.last())
    }

    /** Merging must not drop parameters the peer negotiated; only ours override. */
    @Test
    fun audio_mergesOpusFmtpInPlace() {
        val audio = section(CallSdp.tune(offer), "audio")

        assertEquals(
            "a=fmtp:111 minptime=${CallSdp.OPUS_PTIME_MS};useinbandfec=1;usedtx=0",
            audio.single { it.startsWith("a=fmtp:111 ") },
        )
    }

    /** No fmtp line for Opus means the params have to be created next to the rtpmap. */
    @Test
    fun audio_createsOpusFmtpWhenAbsent() {
        val body = sdp(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=rtcp-fb:111 transport-cc",
        )

        val audio = section(CallSdp.tune(body), "audio")
        val rtpmapAt = audio.indexOfFirst { it.startsWith("a=rtpmap:111 ") }

        assertEquals(
            "a=fmtp:111 minptime=${CallSdp.OPUS_PTIME_MS};useinbandfec=1;usedtx=0",
            audio[rtpmapAt + 1],
        )
    }

    /** `red` and PCMU are not Opus: their payload types must come out untouched. */
    @Test
    fun audio_leavesNonOpusPayloadTypesAlone() {
        val audio = section(CallSdp.tune(offer), "audio")

        assertTrue("red's fmtp is a payload list, not params: $audio", "a=fmtp:63 111/111" in audio)
        assertTrue("PCMU needs no fmtp: $audio", audio.none { it.startsWith("a=fmtp:0 ") })
    }

    /** Bitrate hints in an audio section would be nonsense — and are silently ignored. */
    @Test
    fun audio_carriesNoVideoBitrateHints() {
        val audio = section(CallSdp.tune(offer), "audio")

        assertTrue(audio.none { it.contains("x-google-") })
    }

    // ------------------------------------------------------------------
    // Video: encoder start bitrate
    // ------------------------------------------------------------------

    /** Every real video codec in the section is seeded, whether or not it had an fmtp line. */
    @Test
    fun video_seedsBitrateOnEveryCodec() {
        val video = section(CallSdp.tune(offer), "video")

        assertEquals("a=fmtp:96 $videoParams", video.single { it.startsWith("a=fmtp:96 ") })
        assertEquals(
            "a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;" +
                "profile-level-id=42e01f;$videoParams",
            video.single { it.startsWith("a=fmtp:98 ") },
        )
    }

    /**
     * Retransmission and FEC payload types carry structural parameters (`apt=96`), not codec
     * ones. Writing a bitrate hint into `a=fmtp:97` is how you lose retransmissions.
     */
    @Test
    fun video_leavesRtxRedAndUlpfecAlone() {
        val video = section(CallSdp.tune(offer), "video")

        assertEquals("a=fmtp:97 apt=96", video.single { it.startsWith("a=fmtp:97 ") })
        assertTrue(video.none { it.startsWith("a=fmtp:99 ") })
        assertTrue(video.none { it.startsWith("a=fmtp:100 ") })
    }

    /** ptime is an audio attribute; a video section must not acquire one. */
    @Test
    fun video_hasNoPtime() {
        val video = section(CallSdp.tune(offer), "video")

        assertTrue(video.none { it.startsWith("a=ptime:") })
    }

    /**
     * The literal ceiling, pinned (D8). Every other assertion in this class interpolates the
     * constants, so all of them would have passed just as happily at the old 8 Mbit/s — and
     * 8 Mbit/s of video on a phone hotspot is what made voice unintelligible. The number is the
     * fix, so the number is what the test names.
     */
    @Test
    fun video_capsBitrateForAPhoneHotspot() {
        val video = section(CallSdp.tune(offer), "video")
        val params = video.single { it.startsWith("a=fmtp:96 ") }.substringAfter(' ')

        assertTrue("ceiling must be 2500: $params", "x-google-max-bitrate=2500" in params)
        assertTrue("start must be 1200: $params", "x-google-start-bitrate=1200" in params)
        assertTrue("floor must be 600: $params", "x-google-min-bitrate=600" in params)
    }

    /**
     * Seeding the estimate above the cap would ask congestion control to walk *down* from an
     * illegal rate on the very first frame; seeding below the floor would make the seed a no-op.
     */
    @Test
    fun video_seedsBetweenTheFloorAndTheCeiling() {
        assertTrue(CallSdp.VIDEO_MIN_BITRATE_KBPS <= CallSdp.VIDEO_START_BITRATE_KBPS)
        assertTrue(CallSdp.VIDEO_START_BITRATE_KBPS < CallSdp.VIDEO_MAX_BITRATE_KBPS)
    }

    // ------------------------------------------------------------------
    // Shape preservation
    // ------------------------------------------------------------------

    /**
     * [CallSdp.tune] runs over descriptions this device generated as well as ones it received,
     * so it can legitimately see the same body twice. A second pass that appended a duplicate
     * `usedtx=0` or a second ptime would be a parse error on the peer.
     */
    @Test
    fun tune_isIdempotent() {
        val once = CallSdp.tune(offer)

        assertEquals(once, CallSdp.tune(once))
    }

    /** CRLF is what libwebrtc emits, and mixing terminators inside one body breaks parsers. */
    @Test
    fun tune_preservesCrlf() {
        val tuned = CallSdp.tune(offer)

        assertTrue(tuned.contains("\r\n"))
        assertTrue("no bare LF may survive", tuned.split("\r\n").none { it.contains("\n") })
    }

    /** An LF-only body stays LF-only — the terminator is detected, not imposed. */
    @Test
    fun tune_preservesBareLf() {
        val body = listOf(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
        ).joinToString("\n")

        val tuned = CallSdp.tune(body)

        assertFalse(tuned.contains("\r"))
        assertTrue(tuned.contains("a=ptime:${CallSdp.OPUS_PTIME_MS}"))
    }

    /** A body with nothing to tune comes back byte-identical rather than reformatted. */
    @Test
    fun tune_passesThroughWhatItDoesNotRecognise() {
        val sessionOnly = sdp("v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0")

        assertEquals(sessionOnly, CallSdp.tune(sessionOnly))
        assertEquals("", CallSdp.tune(""))
    }

    /** A data-only m-section (the transfer path's SCTP negotiation) is not audio or video. */
    @Test
    fun tune_ignoresApplicationSections() {
        val body = sdp(
            "v=0",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
            "a=mid:2",
            "a=sctp-port:5000",
        )

        assertEquals(body, CallSdp.tune(body))
    }
}
