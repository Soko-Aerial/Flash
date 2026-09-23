package com.transfer.flash.core.calling

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.perf.FlashVideoProfile
import com.transfer.flash.core.common.perf.FlashVoiceProfile

/**
 * SDP rewriting for latency and bitrate (C7, ADR-025; made tier-aware by ERROR-033).
 *
 * Two knobs that are only reachable through the session description on this stack — neither
 * webrtc-kmp's `RtcConfiguration` nor its `RtpParameters` wrapper exposes them:
 *
 * 1. **Opus packetization** (`a=ptime` + `minptime`). WebRTC defaults to 20 ms packets. *Shorter*
 *    packets cut packetization delay; *longer* ones cut the packet rate, and on a contended
 *    half-duplex 2.4 GHz link the packet rate is what costs latency — see [FlashVoiceProfile] for
 *    the arithmetic. Which direction is the win depends on the device and the radio, which is why
 *    this is a tier knob now and not a constant.
 * 2. **Encoder bitrate window** (`x-google-{start,min,max}-bitrate`). A cold VP8 encoder starts
 *    near 300 kbps and lets bandwidth estimation walk it up, so the opening second of a call is a
 *    smear; the ceiling matters because libwebrtc's internal codec table otherwise caps the stream
 *    wherever it pleases.
 *
 * ## Direction matters, and that is the whole tiering design
 *
 * A sender takes its packetization and bitrate from the description it **receives**. So the local
 * and the remote rewrite do different jobs and cannot be the same function:
 *
 * - [tuneLocal] runs on a description this device generated and **forces** our tier's numbers —
 *   the values libwebrtc put there are its own defaults, not a statement from anybody. The result
 *   travels on the wire, so this is also how we *declare* our tier to the peer.
 * - [tuneRemote] runs on a description the peer sent and combines the two declarations into a
 *   **conservative envelope**: `ptime`/`minptime` take the larger value, bitrate ceilings take the
 *   smaller, DTX is on if either end asked for it.
 *
 * The envelope is what makes a mixed-tier call work. A HIGH-tier Pixel that merely forced its own
 * numbers onto a LOW-tier BelFone's offer would rewrite `ptime:60` straight back to `ptime:10` and
 * defeat the entire request — the Pixel's sender would go on emitting 100 packets/second into the
 * radio that cannot take them. With the envelope both devices independently compute the *same*
 * effective parameters no matter which of them offered, which is a stronger and more useful
 * invariant than the old "wire content must not depend on a local toggle".
 *
 * Everything here is pure string work over a text protocol: fully unit-testable on the JVM, which
 * matters because the alternative is testing bitrate policy on a phone. Every rewrite is
 * idempotent — a forced value re-forced is unchanged, and min/max of a value with itself is that
 * value — because both entry points can legitimately see the same body twice.
 */
internal object CallSdp {

    /** How one parameter combines when both endpoints have declared a value for it. */
    private enum class Envelope {
        /** Larger wins: longer Opus frames, i.e. fewer packets. Also "either end wants it" (1/0). */
        MAX,

        /** Smaller wins: the more constrained bitrate ceiling. */
        MIN,
    }

    private class Param(val key: String, val value: String, val envelope: Envelope)

    /**
     * Rewrites a description **this device generated**, forcing [mode]'s numbers. The result is
     * what goes on the wire, so this doubles as our tier declaration to the peer.
     */
    fun tuneLocal(sdp: String, mode: FlashPerformanceMode): String =
        rewrite(sdp, mode, force = true)

    /**
     * Rewrites a description **received from the peer** into the conservative envelope of both
     * endpoints' declarations. This is the description our own sender reads its packetization and
     * bitrate from, so this — not [tuneLocal] — is what actually throttles this device.
     */
    fun tuneRemote(sdp: String, mode: FlashPerformanceMode): String =
        rewrite(sdp, mode, force = false)

    /**
     * Enforces VP8 as the exclusive video codec in the SDP description.
     *
     * WebRTC on Windows/Desktop (`webrtc-java` 0.17.0) statically bundles the VP8 encoder/decoder
     * (LibvpxVp8Decoder), but advertises AV1, VP9, and H264 in receiver capabilities without bundling
     * their required native dynamic libraries (e.g. Cisco openh264.dll or dav1d.dll). When negotiating
     * with an Android phone offering AV1, VP9, or H264, WebRTC on Desktop falls back to
     * `NullVideoDecoder` ("Can't initialize NullVideoDecoder. The NullVideoDecoder doesn't support decoding."),
     * rendering incoming phone video completely black.
     *
     * Filtering the video section to VP8 (and its RTX retransmission payload type) guarantees that
     * both Desktop and Android negotiate VP8, which succeeds across all supported platforms.
     */
    fun enforceVp8Only(sdp: String): String {
        if (sdp.isBlank()) return sdp
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(eol)

        // Find VP8 payload types: a=rtpmap:<pt> VP8/...
        val vp8Pts = lines.filter { it.startsWith(RTPMAP_PREFIX) }
            .mapNotNull { line ->
                val body = line.removePrefix(RTPMAP_PREFIX)
                val pt = body.substringBefore(' ', "")
                val codec = body.substringAfter(' ', "").substringBefore('/').uppercase()
                if (codec == "VP8" && pt.isNotEmpty() && pt.all(Char::isDigit)) pt else null
            }.toSet()

        if (vp8Pts.isEmpty()) return sdp

        // Find RTX payload types associated with VP8: a=fmtp:<pt> apt=<vp8Pt>
        val rtxPts = lines.filter { it.startsWith(FMTP_PREFIX) }
            .mapNotNull { line ->
                val body = line.removePrefix(FMTP_PREFIX)
                val pt = body.substringBefore(' ', "")
                val params = body.substringAfter(' ', "")
                val apt = params.split(';')
                    .firstOrNull { it.trim().startsWith("apt=") }
                    ?.substringAfter("apt=")?.trim()
                if (apt in vp8Pts && pt.isNotEmpty() && pt.all(Char::isDigit)) pt else null
            }.toSet()

        // Allowed video payload types: strictly VP8 (dropping RTX prevents mismatched SSRC/FID demux errors)
        val allowedPts = vp8Pts

        val out = ArrayList<String>(lines.size)
        var inVideo = false
        var dropPts = emptySet<String>()

        for (line in lines) {
            if (line.startsWith("m=")) {
                inVideo = line.startsWith("m=video ")
            }
            if (inVideo && line.startsWith("m=video ")) {
                val parts = line.split(' ')
                val header = parts.take(3)
                val allPts = parts.drop(3)
                val retainedPts = allPts.filter { it in allowedPts }
                dropPts = (allPts.toSet() - allowedPts)
                out += (header + (if (retainedPts.isNotEmpty()) retainedPts else allPts)).joinToString(" ")
                continue
            }
            if (inVideo) {
                // Drop FID (RTX) ssrc groups in video when RTX is not used
                if (line.startsWith("a=ssrc-group:FID ")) {
                    continue
                }
                // RFC 7741 specifies no fmtp parameters for VP8. In modern libwebrtc,
                // VideoDecoderFactoryTemplate strictly checks supported_format.parameters == format.parameters.
                // Since VP8 supported format has empty parameters {}, any fmtp line (e.g. x-google-* bitrate params)
                // causes decoder lookup to fail and fall back to NullVideoDecoder.
                if (line.startsWith(FMTP_PREFIX)) {
                    continue
                }
                val pt = if (line.startsWith(RTPMAP_PREFIX)) {
                    line.removePrefix(RTPMAP_PREFIX).substringBefore(' ')
                } else if (line.startsWith("a=rtcp-fb:")) {
                    line.removePrefix("a=rtcp-fb:").substringBefore(' ')
                } else null

                if (pt != null && pt in dropPts) {
                    continue
                }
            }
            out += line
        }
        return out.joinToString(eol)
    }

    /** Compatibility alias for [enforceVp8Only]. */
    fun stripH264(sdp: String): String = enforceVp8Only(sdp)

    private fun opusParams(voice: FlashVoiceProfile): List<Param> = listOf(
        // The shortest frame we are willing to RECEIVE. Same value as our own ptime: a peer sending
        // us shorter frames than we send it gains nothing and costs us the packet rate anyway.
        Param("minptime", voice.ptimeMs.toString(), Envelope.MAX),
        // Cheap loss concealment, and what makes the longer frame sizes survivable. Pinned so a
        // peer cannot negotiate it away.
        Param("useinbandfec", if (voice.useInbandFec) "1" else "0", Envelope.MAX),
        // DTX collapses silence to roughly one packet per 400 ms. MAX, so a constrained peer turns
        // it on for both directions — the airtime it saves is on the link, which both ends share.
        Param("usedtx", if (voice.useDtx) "1" else "0", Envelope.MAX),
        // Average receive bitrate ceiling in bps (RFC 7587). MIN ensures conservative envelope.
        Param("maxaveragebitrate", voice.maxBitrateBps.toString(), Envelope.MIN),
    )

    private fun videoParams(video: FlashVideoProfile): List<Param> = listOf(
        Param("x-google-start-bitrate", video.startBitrateKbps.toString(), Envelope.MIN),
        Param("x-google-min-bitrate", video.minBitrateKbps.toString(), Envelope.MIN),
        Param("x-google-max-bitrate", video.maxBitrateKbps.toString(), Envelope.MIN),
    )

    /**
     * Returns [sdp] with its audio and video sections retuned, or [sdp] unchanged when there is
     * nothing to do. Never throws: a body this does not recognise is passed through.
     */
    private fun rewrite(sdp: String, mode: FlashPerformanceMode, force: Boolean): String {
        if (sdp.isBlank()) return sdp
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(eol)
        val out = ArrayList<String>(lines.size + 8)
        var section = ArrayList<String>()
        var kind = ""

        fun flushSection() {
            out += when (kind) {
                "audio" -> tuneAudio(section, mode.voice, force)
                "video" -> tuneVideo(section, mode.video, force)
                else -> section
            }
            section = ArrayList()
        }

        for (line in lines) {
            if (line.startsWith("m=")) {
                flushSection()
                kind = line.removePrefix("m=").substringBefore(' ')
            }
            section += line
        }
        flushSection()
        return out.joinToString(eol)
    }

    private fun tuneAudio(
        lines: List<String>,
        voice: FlashVoiceProfile,
        force: Boolean,
    ): List<String> =
        withPtime(mergeFmtp(lines, OPUS_CODECS, opusParams(voice), force), voice.ptimeMs, force)

    private fun tuneVideo(
        lines: List<String>,
        video: FlashVideoProfile,
        force: Boolean,
    ): List<String> = mergeFmtp(lines, VIDEO_CODECS, videoParams(video), force)

    /**
     * The effective value of one parameter. [force] ignores [theirs] entirely; otherwise a peer
     * declaration folds into the envelope. A null [theirs] — the peer said nothing — always yields
     * [ours], because there is no second declaration to be conservative about.
     */
    private fun combine(theirs: Int?, ours: Int, envelope: Envelope, force: Boolean): Int {
        if (force || theirs == null) return ours
        return when (envelope) {
            Envelope.MAX -> maxOf(theirs, ours)
            Envelope.MIN -> minOf(theirs, ours)
        }
    }

    /**
     * [combine] over the string form an fmtp parameter list carries. A declaration this cannot read
     * as a number is treated as no declaration: it is not a constraint we could honour, so our own
     * value stands rather than being dropped.
     */
    private fun combined(theirs: String?, param: Param, force: Boolean): String {
        val ours = param.value.toIntOrNull() ?: return param.value
        return combine(theirs?.toIntOrNull(), ours, param.envelope, force).toString()
    }

    /**
     * Merges [params] into the `a=fmtp:` line of every payload type in this media section
     * whose `a=rtpmap:` names one of [codecs], creating the fmtp line when it is absent.
     * Payload types for `rtx`, `red` and `ulpfec` are left alone by construction — they
     * never match a codec name.
     */
    private fun mergeFmtp(
        lines: List<String>,
        codecs: Set<String>,
        params: List<Param>,
        force: Boolean,
    ): List<String> {
        val targets = lines.mapNotNullTo(LinkedHashSet()) { rtpmapPayloadType(it, codecs) }
        if (targets.isEmpty()) return lines
        val alreadyHaveFmtp = lines.mapNotNullTo(HashSet()) { fmtpPayloadType(it) }
        val out = ArrayList<String>(lines.size + targets.size)
        for (line in lines) {
            val fmtpPt = fmtpPayloadType(line)
            if (fmtpPt != null && fmtpPt in targets) {
                out += "a=fmtp:$fmtpPt " + mergeParams(line.substringAfter(' ', ""), params, force)
                continue
            }
            out += line
            val rtpmapPt = rtpmapPayloadType(line, codecs)
            if (rtpmapPt != null && rtpmapPt !in alreadyHaveFmtp) {
                out += "a=fmtp:$rtpmapPt " + mergeParams("", params, force)
            }
        }
        return out
    }

    /**
     * Writes exactly one `a=ptime:` into an audio section — [ptimeMs] when [force], otherwise the
     * envelope of [ptimeMs] and what the section already asks for. Duplicates are dropped, and the
     * line lands after the section's last attribute so the `m= i= c= b= a=` ordering RFC 4566
     * requires is preserved.
     *
     * A section carrying several `a=ptime:` lines is read at its largest value, so the result does
     * not depend on their order. If `a=maxptime:` is present, the negotiated `ptime` is clamped so
     * it never exceeds the receiver's packet duration ceiling (RFC 4566 / RFC 7587).
     */
    private fun withPtime(lines: List<String>, ptimeMs: Int, force: Boolean): List<String> {
        val theirs = lines
            .filter { it.startsWith(PTIME_PREFIX) }
            .mapNotNull { it.removePrefix(PTIME_PREFIX).trim().toIntOrNull() }
            .maxOrNull()
        val maxPtime = lines
            .filter { it.startsWith(MAX_PTIME_PREFIX) }
            .mapNotNull { it.removePrefix(MAX_PTIME_PREFIX).trim().toIntOrNull() }
            .minOrNull()
        val combined = combine(theirs, ptimeMs, Envelope.MAX, force)
        val clamped = if (maxPtime != null && maxPtime > 0) combined.coerceAtMost(maxPtime) else combined
        val wanted = PTIME_PREFIX + clamped
        val out = ArrayList<String>(lines.size + 1)
        var replaced = false
        for (line in lines) {
            if (line.startsWith(PTIME_PREFIX)) {
                if (!replaced) {
                    out += wanted
                    replaced = true
                }
                continue
            }
            out += line
        }
        if (replaced) return out
        val insertAt = out.indexOfLast { it.startsWith("a=") } + 1
        if (insertAt <= 0) return out
        out.add(insertAt, wanted)
        return out
    }

    /**
     * Merges [params] into a `;`-separated fmtp parameter list. Tokens the peer sent that we have
     * no opinion about are preserved in place, and so is their order — only the values of our own
     * keys move, and a key we own that is absent is appended.
     */
    private fun mergeParams(existing: String, params: List<Param>, force: Boolean): String {
        val merged = LinkedHashMap<String, String?>()
        existing.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { token ->
                if (token.contains('=')) {
                    merged[token.substringBefore('=')] = token.substringAfter('=')
                } else {
                    merged[token] = null
                }
            }
        params.forEach { param ->
            merged[param.key] = combined(theirs = merged[param.key], param = param, force = force)
        }
        return merged.entries.joinToString(";") { (key, value) ->
            if (value == null) key else "$key=$value"
        }
    }

    /** Payload type of an `a=rtpmap:` line naming one of [codecs], else null. */
    private fun rtpmapPayloadType(line: String, codecs: Set<String>): String? {
        if (!line.startsWith(RTPMAP_PREFIX)) return null
        val body = line.removePrefix(RTPMAP_PREFIX)
        val pt = body.substringBefore(' ', "")
        if (pt.isEmpty() || !pt.all(Char::isDigit)) return null
        val name = body.substringAfter(' ', "").substringBefore('/').uppercase()
        return if (name in codecs) pt else null
    }

    /** Payload type of an `a=fmtp:` line, else null. */
    private fun fmtpPayloadType(line: String): String? {
        if (!line.startsWith(FMTP_PREFIX)) return null
        val pt = line.removePrefix(FMTP_PREFIX).substringBefore(' ', "")
        return pt.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private val OPUS_CODECS = setOf("OPUS")

    private val VIDEO_CODECS = setOf("VP8", "VP9", "H264", "H265", "AV1", "AV1X")

    private const val RTPMAP_PREFIX = "a=rtpmap:"
    private const val FMTP_PREFIX = "a=fmtp:"
    private const val PTIME_PREFIX = "a=ptime:"
    private const val MAX_PTIME_PREFIX = "a=maxptime:"
}
