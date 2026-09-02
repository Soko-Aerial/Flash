package com.transfer.flash.core.calling

/**
 * SDP rewriting for latency and bitrate (C7, ADR-025).
 *
 * Two knobs that are only reachable through the session description on this stack — neither
 * webrtc-kmp's `RtcConfiguration` nor its `RtpParameters` wrapper exposes them:
 *
 * 1. **Opus packetization.** WebRTC defaults to 20 ms audio packets, so every talk spurt
 *    pays 20 ms of packetization delay before a single byte leaves the device. `a=ptime:10`
 *    plus `minptime=10` in the Opus fmtp halves that. libwebrtc's SDP parser folds a
 *    media-level `a=ptime` into the codec parameters of that m-section, and the *sender*
 *    reads its packetization from the description it **receives** — which is why [tune] is
 *    applied symmetrically to local and remote descriptions rather than just outbound ones.
 *
 * 2. **Encoder start bitrate.** A cold VP8 encoder begins near 300 kbps and lets bandwidth
 *    estimation walk it up, which is why call start shows four `initEncode` calls in ~350 ms
 *    and the first second of a 1080p call looks like a smear. `x-google-start-bitrate` seeds
 *    the estimate instead. It is seeded *below* the ceiling, not at it: on a phone hotspot the
 *    opening burst is shared with the voice stream it is supposed to leave room for (D8).
 *
 * Everything here is pure string work over a text protocol: fully unit-testable on the JVM,
 * which matters because the alternative is testing bitrate policy on a phone. All rewrites
 * are idempotent — [tune] runs over descriptions this device generated as well as ones it
 * received, and may see the same body twice.
 */
internal object CallSdp {

    /** Opus packet duration, ms. 20 is WebRTC's default; Opus supports 10. */
    const val OPUS_PTIME_MS: Int = 10

    /**
     * Encoder start bitrate, kbit/s — the initial bandwidth estimate, not a cap.
     *
     * High enough that the first second of a call is already sharp, low enough that the opening
     * burst does not itself congest the link it is trying to measure. It used to be 2.5 Mbit/s,
     * which on a phone hotspot meant the very first video frames competed with the audio stream
     * before congestion control had a single RTCP report to work from.
     */
    const val VIDEO_START_BITRATE_KBPS: Int = 1_200

    /** Floor the encoder is allowed to drop to before it sheds resolution instead. */
    const val VIDEO_MIN_BITRATE_KBPS: Int = 600

    /**
     * Ceiling for video, kbit/s. Congestion control stays free to use less.
     *
     * Sized for the network Flash actually runs on, not for the camera (D8). The old 8 Mbit/s
     * was chosen to let 1080p30 run unconstrained on a LAN — but the real deployment is a phone
     * hotspot: half-duplex, one radio, shared with every other associated client. 8 Mbit/s of
     * video on that link is precisely what starved a 32 kbit/s voice stream and made calls
     * unintelligible while the picture stayed pretty. 2.5 Mbit/s still carries a sharp 720p and
     * a serviceable 1080p talking head, and leaves headroom that voice can actually reach.
     *
     * The ceiling is unconditional — it is a network-shape fix, not a preference. The
     * "Prioritise voice quality" toggle governs the sender *priorities* and the adaptive
     * governor ([com.transfer.flash.core.calling.CallQualityGovernor]), not this number, because
     * [tune] is applied symmetrically to the local and remote descriptions and the wire content
     * must not depend on which device happens to have a toggle flipped.
     */
    const val VIDEO_MAX_BITRATE_KBPS: Int = 2_500

    private val OPUS_CODECS = setOf("OPUS")

    private val VIDEO_CODECS = setOf("VP8", "VP9", "H264", "H265", "AV1", "AV1X")

    private val OPUS_PARAMS = listOf(
        // The peer may send us 10 ms packets.
        "minptime" to OPUS_PTIME_MS.toString(),
        // Cheap loss concealment; on by default, pinned so a peer cannot negotiate it away.
        "useinbandfec" to "1",
        // DTX saves bandwidth we do not need and adds comfort-noise transitions.
        "usedtx" to "0",
    )

    private val VIDEO_PARAMS = listOf(
        "x-google-start-bitrate" to VIDEO_START_BITRATE_KBPS.toString(),
        "x-google-min-bitrate" to VIDEO_MIN_BITRATE_KBPS.toString(),
        "x-google-max-bitrate" to VIDEO_MAX_BITRATE_KBPS.toString(),
    )

    /**
     * Returns [sdp] with the audio and video sections retuned, or [sdp] unchanged if there
     * is nothing to do. Never throws: a body this does not recognise is passed through.
     */
    fun tune(sdp: String): String {
        if (sdp.isBlank()) return sdp
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(eol)
        val out = ArrayList<String>(lines.size + 8)
        var section = ArrayList<String>()
        var kind = ""

        fun flushSection() {
            out += when (kind) {
                "audio" -> tuneAudio(section)
                "video" -> tuneVideo(section)
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

    private fun tuneAudio(lines: List<String>): List<String> =
        withPtime(mergeFmtp(lines, OPUS_CODECS, OPUS_PARAMS))

    private fun tuneVideo(lines: List<String>): List<String> =
        mergeFmtp(lines, VIDEO_CODECS, VIDEO_PARAMS)

    /**
     * Merges [params] into the `a=fmtp:` line of every payload type in this media section
     * whose `a=rtpmap:` names one of [codecs], creating the fmtp line when it is absent.
     * Payload types for `rtx`, `red` and `ulpfec` are left alone by construction — they
     * never match a codec name.
     */
    private fun mergeFmtp(
        lines: List<String>,
        codecs: Set<String>,
        params: List<Pair<String, String>>,
    ): List<String> {
        val targets = lines.mapNotNullTo(LinkedHashSet()) { rtpmapPayloadType(it, codecs) }
        if (targets.isEmpty()) return lines
        val alreadyHaveFmtp = lines.mapNotNullTo(HashSet()) { fmtpPayloadType(it) }
        val out = ArrayList<String>(lines.size + targets.size)
        for (line in lines) {
            val fmtpPt = fmtpPayloadType(line)
            if (fmtpPt != null && fmtpPt in targets) {
                out += "a=fmtp:$fmtpPt " + mergeParams(line.substringAfter(' ', ""), params)
                continue
            }
            out += line
            val rtpmapPt = rtpmapPayloadType(line, codecs)
            if (rtpmapPt != null && rtpmapPt !in alreadyHaveFmtp) {
                out += "a=fmtp:$rtpmapPt " + mergeParams("", params)
            }
        }
        return out
    }

    /**
     * Forces `a=ptime:` in an audio section, replacing any existing value and dropping
     * duplicates. Inserted after the section's last attribute line so the `m= i= c= b= a=`
     * ordering RFC 4566 requires is preserved.
     */
    private fun withPtime(lines: List<String>): List<String> {
        val wanted = "a=ptime:$OPUS_PTIME_MS"
        val out = ArrayList<String>(lines.size + 1)
        var replaced = false
        for (line in lines) {
            if (line.startsWith("a=ptime:")) {
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

    /** Merges `key=value` [params] into a `;`-separated fmtp parameter list. */
    private fun mergeParams(existing: String, params: List<Pair<String, String>>): String {
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
        params.forEach { (key, value) -> merged[key] = value }
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

    private const val RTPMAP_PREFIX = "a=rtpmap:"
    private const val FMTP_PREFIX = "a=fmtp:"
}
