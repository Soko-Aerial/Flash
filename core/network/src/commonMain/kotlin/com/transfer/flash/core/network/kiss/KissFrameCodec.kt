package com.transfer.flash.core.network.kiss

/**
 * KISS TNC framing (Phil Karn, "The KISS TNC: A simple Host-to-TNC communications protocol", 1987;
 * reproduced in the Wikipedia article "KISS (TNC)" and in the Direwolf user guide, section "KISS").
 *
 * Sans-IO and allocation-light: [KissFrameCodec] turns one command+payload into wire bytes, [KissStreamDecoder] turns an
 * arbitrary sequence of received byte chunks into frames. Neither throws on malformed input; a damaged frame is dropped
 * and counted ([KissDecoderStats]). Wire layout and the golden vectors that pin it are in
 * `docs/network/RADIO-WIRE-FORMAT.md` section 1.
 *
 * ```
 * FEND  CMD  data...  FEND
 * C0    0x   escaped  C0        escaping inside CMD+data: C0 -> DB DC, DB -> DB DD
 * CMD = (port << 4) | command   port 0..15, command 0..15 (0xFF alone = "return to normal")
 * ```
 */
public object Kiss {
    /** Frame end / delimiter. */
    public const val FEND: Int = 0xC0

    /** Frame escape. */
    public const val FESC: Int = 0xDB

    /** Transposed FEND (follows FESC). */
    public const val TFEND: Int = 0xDC

    /** Transposed FESC (follows FESC). */
    public const val TFESC: Int = 0xDD

    /** Command 0: data frame (an AX.25 frame without FCS or flags follows). */
    public const val CMD_DATA: Int = 0x00

    /** Command 1: TXDELAY, keyup delay in 10 ms units. */
    public const val CMD_TXDELAY: Int = 0x01

    /** Command 2: persistence P, 0..255, probability (P+1)/256 of transmitting in a slot. */
    public const val CMD_PERSISTENCE: Int = 0x02

    /** Command 3: slot time in 10 ms units. */
    public const val CMD_SLOT_TIME: Int = 0x03

    /** Command 4: TXtail in 10 ms units (obsolete in most TNCs, still defined). */
    public const val CMD_TX_TAIL: Int = 0x04

    /** Command 5: full duplex flag. */
    public const val CMD_FULL_DUPLEX: Int = 0x05

    /** Command 6: SetHardware, TNC specific. */
    public const val CMD_SET_HARDWARE: Int = 0x06

    /** Command 15 (with port 15 = byte 0xFF): leave KISS mode. */
    public const val CMD_RETURN: Int = 0x0F
}

/**
 * One decoded KISS frame. [port] is the high nibble and [command] the low nibble of the command byte. [data] excludes the
 * command byte. Note: [equals] and [hashCode] compare array contents.
 */
public class KissFrame(public val port: Int, public val command: Int, data: ByteArray) {
    /** Payload, a private copy. */
    public val data: ByteArray = data.copyOf()

    init {
        require(port in 0..15) { "KISS port must be 0..15, was $port" }
        require(command in 0..15) { "KISS command must be 0..15, was $command" }
    }

    /** True for command 0 (an AX.25 frame in [data]). */
    public val isData: Boolean get() = command == Kiss.CMD_DATA

    /** The command byte as sent: `(port << 4) | command`. */
    public val commandByte: Int get() = (port shl 4) or command

    override fun equals(other: Any?): Boolean =
        other is KissFrame && other.port == port && other.command == command && other.data.contentEquals(data)

    override fun hashCode(): Int = (port * 31 + command) * 31 + data.contentHashCode()

    override fun toString(): String = "KissFrame(port=$port, command=$command, data=${data.size}B)"
}

/** Encoder for KISS frames. */
public object KissFrameCodec {

    /** Wire bytes `FEND CMD data FEND` for [frame], escaping the command byte and the data. */
    public fun encode(frame: KissFrame): ByteArray = encode(frame.port, frame.command, frame.data)

    /** Wire bytes for a [command] on [port] carrying [data]. */
    public fun encode(port: Int, command: Int, data: ByteArray): ByteArray {
        require(port in 0..15) { "KISS port must be 0..15, was $port" }
        require(command in 0..15) { "KISS command must be 0..15, was $command" }
        var extra = 0
        for (b in data) if (isSpecial(b.toInt() and 0xFF)) extra++
        val cmd = (port shl 4) or command
        val cmdSpecial = isSpecial(cmd)
        val out = ByteArray(2 + 1 + (if (cmdSpecial) 1 else 0) + data.size + extra)
        var i = 0
        out[i++] = Kiss.FEND.toByte()
        i = put(out, i, cmd)
        for (b in data) i = put(out, i, b.toInt() and 0xFF)
        out[i++] = Kiss.FEND.toByte()
        check(i == out.size)
        return out
    }

    /** A data frame (command 0) on [port]. */
    public fun encodeData(ax25: ByteArray, port: Int = 0): ByteArray = encode(port, Kiss.CMD_DATA, ax25)

    /** A parameter command such as [Kiss.CMD_TXDELAY] with a one-byte [value] (0..255). */
    public fun encodeParameter(command: Int, value: Int, port: Int = 0): ByteArray {
        require(value in 0..255) { "KISS parameter must be 0..255, was $value" }
        return encode(port, command, byteArrayOf(value.toByte()))
    }

    /** `C0 FF C0`: tell the TNC to leave KISS mode (not all TNCs honour it; a radio's menu setting is the reliable way). */
    public fun encodeReturnToNormal(): ByteArray = byteArrayOf(Kiss.FEND.toByte(), 0xFF.toByte(), Kiss.FEND.toByte())

    private fun isSpecial(v: Int): Boolean = v == Kiss.FEND || v == Kiss.FESC

    private fun put(out: ByteArray, at: Int, v: Int): Int {
        var i = at
        when (v) {
            Kiss.FEND -> {
                out[i++] = Kiss.FESC.toByte()
                out[i++] = Kiss.TFEND.toByte()
            }
            Kiss.FESC -> {
                out[i++] = Kiss.FESC.toByte()
                out[i++] = Kiss.TFESC.toByte()
            }
            else -> out[i++] = v.toByte()
        }
        return i
    }
}

/**
 * Counters of a [KissStreamDecoder]. [garbageBytes] are bytes seen outside any frame (before the first FEND),
 * [emptyFrames] are `FEND FEND` runs (normal, ignored), [badEscapes] frames dropped for `FESC` + anything but
 * `TFEND`/`TFESC` (or a `FEND` straight after `FESC`), [oversize] frames dropped for exceeding the size limit.
 */
public data class KissDecoderStats(
    val framesOk: Long = 0,
    val garbageBytes: Long = 0,
    val emptyFrames: Long = 0,
    val badEscapes: Long = 0,
    val oversize: Long = 0,
    val bytesIn: Long = 0,
)

/**
 * Streaming KISS decoder. Feed it whatever the serial port returns, in any chunking (one byte at a time is fine), and it
 * returns the frames completed by that chunk. Never throws.
 *
 * Policy (documented in `RADIO-WIRE-FORMAT.md`): the first `FEND` opens synchronisation; bytes before it are garbage. Back-to-back
 * `FEND`s are one delimiter. A frame is delivered only when its closing `FEND` arrives; a damaged frame (bad escape, longer
 * than [maxFrameBytes] after unescaping) is dropped whole at its closing `FEND` and the decoder resynchronises there.
 */
public class KissStreamDecoder(private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES) {
    private var buf = ByteArray(256)
    private var len = 0
    private var synced = false
    private var escaped = false
    private var poisonBadEscape = false
    private var poisonOversize = false
    private var stats = KissDecoderStats()

    init {
        require(maxFrameBytes >= 2) { "maxFrameBytes must be at least 2" }
    }

    /** Snapshot of the counters. */
    public val statistics: KissDecoderStats get() = stats

    /** Forget partial state (call after a reconnect so half a frame from the old link cannot join the new one). */
    public fun reset() {
        len = 0
        synced = false
        escaped = false
        poisonBadEscape = false
        poisonOversize = false
    }

    /** Feeds [data] (or the slice [offset] until [offset]+[length]) and returns the frames it completed, in order. */
    public fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<KissFrame> {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "bad slice" }
        var out: MutableList<KissFrame>? = null
        stats = stats.copy(bytesIn = stats.bytesIn + length)
        for (k in offset until offset + length) {
            val b = data[k].toInt() and 0xFF
            if (b == Kiss.FEND) {
                val f = closeFrame()
                if (f != null) {
                    if (out == null) out = ArrayList(2)
                    out.add(f)
                }
                synced = true
                len = 0
                escaped = false
                poisonBadEscape = false
                poisonOversize = false
                continue
            }
            if (!synced) {
                stats = stats.copy(garbageBytes = stats.garbageBytes + 1)
                continue
            }
            if (poisonBadEscape || poisonOversize) continue
            if (escaped) {
                escaped = false
                when (b) {
                    Kiss.TFEND -> append(Kiss.FEND)
                    Kiss.TFESC -> append(Kiss.FESC)
                    else -> poisonBadEscape = true
                }
            } else if (b == Kiss.FESC) {
                escaped = true
            } else {
                append(b)
            }
        }
        return out ?: emptyList()
    }

    private fun append(v: Int) {
        if (len >= maxFrameBytes) {
            poisonOversize = true
            return
        }
        if (len == buf.size) buf = buf.copyOf(minOf(buf.size * 2, maxFrameBytes.coerceAtLeast(buf.size + 1)))
        buf[len++] = v.toByte()
    }

    private fun closeFrame(): KissFrame? {
        if (!synced) return null
        if (escaped) poisonBadEscape = true // FESC directly followed by FEND
        if (poisonBadEscape) {
            stats = stats.copy(badEscapes = stats.badEscapes + 1)
            return null
        }
        if (poisonOversize) {
            stats = stats.copy(oversize = stats.oversize + 1)
            return null
        }
        if (len == 0) {
            stats = stats.copy(emptyFrames = stats.emptyFrames + 1)
            return null
        }
        val cmd = buf[0].toInt() and 0xFF
        val frame = KissFrame(port = cmd ushr 4, command = cmd and 0x0F, data = buf.copyOfRange(1, len))
        stats = stats.copy(framesOk = stats.framesOk + 1)
        return frame
    }

    /** Defaults. */
    public companion object {
        /** AX.25 info is at most 256 bytes plus up to 10 addresses (70 B) and control/PID; 1024 is generous. */
        public const val DEFAULT_MAX_FRAME_BYTES: Int = 1024
    }
}
