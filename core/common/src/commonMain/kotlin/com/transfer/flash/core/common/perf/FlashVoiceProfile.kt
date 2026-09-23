package com.transfer.flash.core.common.perf

/**
 * Opus packetization and bitrate envelope for one [FlashPerformanceMode].
 *
 * ## The failure this exists to fix (ERROR-033)
 *
 * A voice-only call measured at ~25 kbit/s of payload was still laggy on a 2.4 GHz-only rugged
 * handset. That number is the reason the tiering has a voice profile at all: 25 kbit/s cannot
 * congest anything, so the bottleneck was never bits. It was **packets**.
 *
 * The stack shipped with 10 ms Opus packets (`ptime=10`), chosen to cut packetization delay for
 * capable phones, which is 100 packets per second in each direction. Two costs scale with that
 * rate and not with payload size:
 *
 * - **Per-packet headers.** RTP (12) + UDP (8) + IPv4 (20) + the SRTP auth tag (10) is ~50 bytes
 *   carried on every packet. At 100 pps that is ~40 kbit/s of header — more than the 25 kbit/s
 *   of speech it wraps. At 60 ms packets the same speech costs ~7 kbit/s of header.
 * - **Airtime.** 802.11 charges a largely fixed price per frame: preamble, PLCP, MAC header,
 *   DIFS, random backoff, SIFS and an ACK, on a half-duplex medium shared with every other
 *   associated client and, on a mesh, with the backhaul. On legacy 2.4 GHz rates that is
 *   hundreds of microseconds per frame. 200 frames/second of bidirectional voice therefore
 *   spends a double-digit percentage of the channel on overhead before any other traffic exists,
 *   and contention delay — not bandwidth — is what the user experiences as lag and jitter.
 *
 * A third cost is local: every packet is an interrupt, a crypto operation and a scheduler
 * wake-up. Cutting the rate 6x is a direct CPU saving on a device that has none to spare.
 *
 * ## The trade being made
 *
 * Longer packets cost mouth-to-ear delay directly — 60 ms packets add ~50 ms over 10 ms ones,
 * and a lost packet takes 60 ms of speech with it instead of 10 ms. That is the right trade only
 * where the alternative is worse: on a contended half-duplex link, queueing and retransmission
 * delay already exceed the packetization delay being saved, so the long packets are *lower*
 * latency in practice. On a link with headroom they are not, which is why [HIGH] keeps 10 ms.
 *
 * In-band FEC compensates for the larger loss granularity and is on at every tier.
 *
 * @param ptimeMs Opus frame duration. Must be one of Opus's own frame sizes — 10, 20, 40 or
 *   60 ms — because a value the codec cannot honour is silently rounded by the encoder.
 * @param maxBitrateBps sender ceiling. A ceiling, not a target: Opus decides its own rate below
 *   this, and the cap only stops a generous bandwidth estimate handing voice bits it cannot use.
 * @param useDtx discontinuous transmission — during silence Opus drops to roughly one packet
 *   every 400 ms instead of a continuous stream. Worth real airtime on a contended link; costs
 *   an audible comfort-noise transition, which is why it is off at [HIGH].
 * @param useInbandFec in-band forward error correction. On at every tier: it is cheap, and it is
 *   what makes the longer packet sizes survivable.
 */
public data class FlashVoiceProfile(
    public val ptimeMs: Int,
    public val maxBitrateBps: Int,
    public val useDtx: Boolean,
    public val useInbandFec: Boolean = true,
) {
    /**
     * Packets per second this profile implies, one direction. Integer division truncates
     * (60 ms → 16), which is fine for the logging and comparison this is used for.
     */
    public val packetsPerSecond: Int get() = if (ptimeMs <= 0) 0 else MS_PER_SECOND / ptimeMs

    /**
     * Approximate per-packet header cost in kbit/s, one direction — the number that made 25
     * kbit/s of speech behave like 65. RTP + UDP + IPv4 + SRTP tag, rounded to 50 bytes.
     */
    public val headerOverheadKbps: Int get() = packetsPerSecond * PACKET_OVERHEAD_BYTES * 8 / 1000

    public companion object {
        private const val MS_PER_SECOND = 1_000

        /** RTP 12 + UDP 8 + IPv4 20 + SRTP auth tag 10. */
        private const val PACKET_OVERHEAD_BYTES = 50

        /**
         * 60 ms packets, 16 kbit/s ceiling, DTX on. ~16 pps instead of 100.
         *
         * 60 ms is Opus's longest single frame, so this is the cheapest the codec can be without
         * stuffing multiple frames per packet. 16 kbit/s is still comfortably intelligible
         * wideband speech — Opus is transparent for voice from about 12 kbit/s up.
         */
        public val LOW: FlashVoiceProfile = FlashVoiceProfile(
            ptimeMs = 60,
            maxBitrateBps = 20_000,
            useDtx = true,
        )

        /**
         * 20 ms packets, 24 kbit/s ceiling, DTX on. 20 ms is WebRTC's own default: halves the
         * packet rate against [HIGH] at a packetization cost nobody can hear.
         */
        public val MEDIUM: FlashVoiceProfile = FlashVoiceProfile(
            ptimeMs = 20,
            maxBitrateBps = 24_000,
            useDtx = true,
        )

        /**
         * 20 ms packets, 32 kbit/s ceiling, DTX on. 20 ms is the WebRTC global standard
         * (WhatsApp, Meet, Discord) which halves packet rate to 50 pps, and DTX collapses
         * silence during listening periods to ~2.5 pps, eliminating Wi-Fi channel contention
         * and NetEQ jitter buffer bloat in 1:1 and multi-peer group calls.
         */
        public val HIGH: FlashVoiceProfile = FlashVoiceProfile(
            ptimeMs = 20,
            maxBitrateBps = 32_000,
            useDtx = true,
        )
    }
}
