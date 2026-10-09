package com.transfer.flash.core.network.radio

/**
 * Wire constants and header codec for the Flash radio frame (Profile M), carried in the information field of an AX.25 UI
 * frame with PID 0xF0. Byte layout, rationale and golden vectors: `docs/network/RADIO-WIRE-FORMAT.md`.
 *
 * ```
 * off size field
 *  0    1  MAGIC    0xF1
 *  1    1  VT       version (high nibble, 1) | kind (low nibble)
 *  2    1  FLAGS    bit0 SEGMENTED, bits1-2 AUTH (0 = pairwise AEAD, 1 = ECDSA signed clear text), bits3-7 zero
 *  3    1  TTL      remaining hops; NOT authenticated (a relay edits it without a key), clamped by every receiver
 *  4    4  TAG      rotating sender tag (AEAD) or first 4 bytes of SHA-256(sender public key) (SIGNED)
 *  8    4  COUNTER  uint32 big-endian, per sender and recipient (AEAD) or per sender (SIGNED)
 * 12    n  BODY     AEAD: ciphertext || 16-byte tag.  SIGNED: plaintext || 64-byte raw ECDSA r||s
 * ```
 */
public object RadioWire {
    /** First byte of every Flash radio frame. */
    public const val MAGIC: Int = 0xF1

    /** Protocol version carried in the high nibble of byte 1. */
    public const val VERSION: Int = 1

    /** Header size in bytes (bytes 0..11). */
    public const val HEADER_SIZE: Int = 12

    /** AES-GCM tag length. */
    public const val TAG_SIZE: Int = 16

    /** Raw ECDSA signature length. */
    public const val SIGNATURE_SIZE: Int = 64

    /** Bytes of the segment sub-header inside the protected body of a segmented frame: msgId(2) idx(1) total(1). */
    public const val SEGMENT_HEADER_SIZE: Int = 4

    /** Default budget for the AX.25 information field (radio plan section 6.0 item 1). */
    public const val DEFAULT_INFO_BUDGET: Int = 220

    /** FLAGS bit 0. */
    public const val FLAG_SEGMENTED: Int = 0x01

    /** FLAGS bits 1-2 mask. */
    public const val FLAG_AUTH_MASK: Int = 0x06

    /** AUTH value 0 shifted into place: pairwise AEAD. */
    public const val AUTH_AEAD: Int = 0

    /** AUTH value 1 shifted into place: signed clear text (broadcast, Profile A only). */
    public const val AUTH_SIGNED: Int = 1 shl 1

    /** Flags that must be zero in version 1. */
    public const val FLAGS_RESERVED_MASK: Int = 0xF8

    /** Maximum body bytes that fit one unsegmented AEAD frame for [infoBudget]. */
    public fun maxBodyUnsegmented(infoBudget: Int = DEFAULT_INFO_BUDGET): Int = infoBudget - HEADER_SIZE - TAG_SIZE

    /** Maximum body bytes per segment of a segmented AEAD frame for [infoBudget]. */
    public fun maxBodyPerSegment(infoBudget: Int = DEFAULT_INFO_BUDGET): Int =
        infoBudget - HEADER_SIZE - TAG_SIZE - SEGMENT_HEADER_SIZE
}

/** The message type, low nibble of byte 1. Values are wire-stable. */
public enum class RadioKind(public val code: Int) {
    /** A chat text (UTF-8 body). */
    TEXT(1),

    /** Delivery acknowledgement: body is the u32 big-endian counter of the first frame of the acknowledged message. */
    ACK(2),

    /** Diagnostic ping used by the BT-00 tool: arbitrary body, answered with [ACK]. */
    PING(3),

    /** Reserved: a short voice clip. Not implemented. */
    VOICE_CLIP(4),

    /** Reserved: a position beacon. Profile A only; the codec carries it but Profile M policy must not send it. */
    BEACON(5);

    /** Lookup. */
    public companion object {
        /** The kind for a wire [code], or null for an unknown one (a receiver drops those). */
        public fun fromCode(code: Int): RadioKind? = entries.firstOrNull { it.code == code }
    }
}

/** The parsed 12-byte header. */
public class RadioHeader(
    public val kind: RadioKind,
    public val flags: Int,
    public val ttl: Int,
    tag: ByteArray,
    public val counter: Long,
) {
    /** The 4-byte sender tag, a private copy. */
    public val tag: ByteArray = tag.copyOf()

    init {
        require(tag.size == 4) { "tag must be 4 bytes" }
        require(counter in 0..0xFFFF_FFFFL) { "counter must fit uint32" }
        require(ttl in 0..255) { "ttl must fit a byte" }
    }

    /** True when FLAGS bit 0 is set. */
    public val segmented: Boolean get() = flags and RadioWire.FLAG_SEGMENTED != 0

    /** True when AUTH selects signed clear text. */
    public val signed: Boolean get() = flags and RadioWire.FLAG_AUTH_MASK == RadioWire.AUTH_SIGNED

    /** Serialises to the 12 header bytes. */
    public fun encode(): ByteArray {
        val h = ByteArray(RadioWire.HEADER_SIZE)
        h[0] = RadioWire.MAGIC.toByte()
        h[1] = ((RadioWire.VERSION shl 4) or kind.code).toByte()
        h[2] = flags.toByte()
        h[3] = ttl.toByte()
        tag.copyInto(h, 4)
        h[8] = (counter ushr 24).toByte()
        h[9] = (counter ushr 16).toByte()
        h[10] = (counter ushr 8).toByte()
        h[11] = counter.toByte()
        return h
    }

    /** The bytes the AEAD or signature authenticates: the header without TTL, plus a domain label. */
    public fun authenticatedBytes(): ByteArray {
        val h = encode()
        return AAD_LABEL + byteArrayOf(h[0], h[1], h[2]) + h.copyOfRange(4, 12)
    }

    /** Why a header was refused. */
    public enum class Problem { TOO_SHORT, BAD_MAGIC, BAD_VERSION, UNKNOWN_KIND, RESERVED_FLAGS, UNSUPPORTED_AUTH }

    /** Parse result. */
    public sealed interface Parsed {
        /** Header parsed; [bodyOffset] is where the body starts. */
        public class Ok(public val header: RadioHeader, public val bodyOffset: Int) : Parsed

        /** Header refused. */
        public class Bad(public val problem: Problem) : Parsed
    }

    /** Parsing. */
    public companion object {
        private val AAD_LABEL: ByteArray = "flash-radio-v1".encodeToByteArray()

        /** Parses the first 12 bytes of [info]. Never throws. */
        public fun parse(info: ByteArray): Parsed {
            if (info.size < RadioWire.HEADER_SIZE) return Parsed.Bad(Problem.TOO_SHORT)
            if (info[0].toInt() and 0xFF != RadioWire.MAGIC) return Parsed.Bad(Problem.BAD_MAGIC)
            val vt = info[1].toInt() and 0xFF
            if (vt ushr 4 != RadioWire.VERSION) return Parsed.Bad(Problem.BAD_VERSION)
            val kind = RadioKind.fromCode(vt and 0x0F) ?: return Parsed.Bad(Problem.UNKNOWN_KIND)
            val flags = info[2].toInt() and 0xFF
            if (flags and RadioWire.FLAGS_RESERVED_MASK != 0) return Parsed.Bad(Problem.RESERVED_FLAGS)
            val auth = flags and RadioWire.FLAG_AUTH_MASK
            if (auth != RadioWire.AUTH_AEAD && auth != RadioWire.AUTH_SIGNED) return Parsed.Bad(Problem.UNSUPPORTED_AUTH)
            val counter = ((info[8].toLong() and 0xFF) shl 24) or ((info[9].toLong() and 0xFF) shl 16) or
                ((info[10].toLong() and 0xFF) shl 8) or (info[11].toLong() and 0xFF)
            return Parsed.Ok(
                RadioHeader(kind, flags, info[3].toInt() and 0xFF, info.copyOfRange(4, 8), counter),
                RadioWire.HEADER_SIZE,
            )
        }

        /** Returns a copy of [info] with the TTL byte replaced (what a relay does); null if [info] is shorter than a header. */
        public fun withTtl(info: ByteArray, ttl: Int): ByteArray? {
            if (info.size < RadioWire.HEADER_SIZE) return null
            val copy = info.copyOf()
            copy[3] = ttl.coerceIn(0, 255).toByte()
            return copy
        }
    }
}
