package com.transfer.flash.core.network.kiss

/**
 * AX.25 link-layer addressing and Unnumbered Information (UI) frames, the part a KISS TNC hands to the host
 * (no flags, no FCS: the TNC adds and checks them).
 *
 * Reference: AX.25 Link Access Protocol for Amateur Packet Radio, v2.2 (TAPR/ARRL, 1998), section 3 (frame structure,
 * address field encoding, control field) and the PID table in section 3.4 (0xF0 = no layer 3 protocol; **0xCC = ARPA
 * Internet Protocol**, which is why Flash must never use it, see the radio plan correction E3).
 *
 * Address field, 7 bytes per address: six callsign characters, each ASCII shifted left one bit and space padded, then the
 * SSID byte `C R R SSID(4) E`: bit 7 command/response (destination and source) or has-been-repeated (digipeaters), bits 6 and
 * 5 reserved and set to 1, bits 4..1 the SSID, bit 0 the end-of-address-field marker (1 on the last address only).
 */
public class Ax25Address(callsign: String, public val ssid: Int = 0, public val flagBit: Boolean = false) {
    /** Upper-case callsign without padding, 1..6 characters from A-Z and 0-9. */
    public val callsign: String = callsign.uppercase()

    init {
        require(this.callsign.length in 1..6) { "callsign must be 1..6 characters: '$callsign'" }
        require(this.callsign.all { it in 'A'..'Z' || it in '0'..'9' }) { "callsign must be A-Z/0-9: '$callsign'" }
        require(ssid in 0..15) { "ssid must be 0..15, was $ssid" }
    }

    /** `CALL` for SSID 0, else `CALL-n` (the customary text form). */
    override fun toString(): String = if (ssid == 0) callsign else "$callsign-$ssid"

    /** Same callsign and SSID; [flagBit] (C / H bit) is not part of identity. */
    override fun equals(other: Any?): Boolean = other is Ax25Address && other.callsign == callsign && other.ssid == ssid

    override fun hashCode(): Int = callsign.hashCode() * 31 + ssid

    /** Copy with a different C / H bit. */
    public fun withFlag(flag: Boolean): Ax25Address = Ax25Address(callsign, ssid, flag)

    /** Address constants and parsing. */
    public companion object {
        /** Parses `CALL` or `CALL-n`; returns null on anything else. */
        public fun parseOrNull(text: String): Ax25Address? {
            val t = text.trim().uppercase()
            val dash = t.indexOf('-')
            val call = if (dash < 0) t else t.substring(0, dash)
            val ssid = if (dash < 0) 0 else t.substring(dash + 1).toIntOrNull() ?: return null
            return try {
                Ax25Address(call, ssid)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/** Which side of a command/response exchange this frame claims to be (AX.25 2.2 section 6.1.2). */
public enum class Ax25Role {
    /** Destination C bit = 1, source C bit = 0. What APRS and every UI beacon uses. */
    COMMAND,

    /** Destination C bit = 0, source C bit = 1. */
    RESPONSE,

    /** Both C bits 0: pre-v2 stations. */
    LEGACY,
}

/** Why [Ax25FrameCodec.decode] refused a frame. */
public enum class Ax25DecodeError { TOO_SHORT, ADDRESS_FIELD_UNTERMINATED, TOO_MANY_DIGIPEATERS, MISSING_CONTROL, BAD_CALLSIGN }

/**
 * A decoded AX.25 frame. For UI frames [control] is 0x03 (or 0x13 with the poll/final bit) and [pid] is set.
 * [info] is the information field (Flash puts its radio frame here).
 */
public class Ax25Frame(
    public val destination: Ax25Address,
    public val source: Ax25Address,
    public val digipeaters: List<Ax25Address>,
    public val control: Int,
    public val pid: Int?,
    info: ByteArray,
    public val role: Ax25Role = Ax25Role.COMMAND,
) {
    /** The information field, a private copy. */
    public val info: ByteArray = info.copyOf()

    /** True when the control field is an Unnumbered Information frame (ignoring the P/F bit). */
    public val isUi: Boolean get() = (control and 0xEF) == Ax25FrameCodec.CONTROL_UI

    override fun toString(): String =
        "Ax25Frame($source>$destination${if (digipeaters.isEmpty()) "" else "," + digipeaters.joinToString(",")} " +
            "ctl=0x${control.toString(16)} pid=${pid?.let { "0x" + it.toString(16) }} info=${info.size}B)"
}

/** Result of [Ax25FrameCodec.decode]. */
public sealed interface Ax25DecodeResult {
    /** A well-formed frame. */
    public class Ok(public val frame: Ax25Frame) : Ax25DecodeResult

    /** A refused frame and why. */
    public class Error(public val reason: Ax25DecodeError) : Ax25DecodeResult
}

/** Encoder and decoder for AX.25 UI frames and tolerant parsing of any other frame header. */
public object Ax25FrameCodec {
    /** Control field of a UI frame without the poll/final bit. */
    public const val CONTROL_UI: Int = 0x03

    /** PID: no layer 3 protocol. The only PID Flash uses. */
    public const val PID_NO_LAYER3: Int = 0xF0

    /** PID: ARPA Internet Protocol. Flash MUST NOT use it (a TNC or digipeater may treat the payload as IP). */
    public const val PID_ARPA_IP: Int = 0xCC

    /** PID: ARPA Address Resolution Protocol. */
    public const val PID_ARPA_ARP: Int = 0xCD

    /** AX.25 allows at most 8 digipeaters. */
    public const val MAX_DIGIPEATERS: Int = 8

    /**
     * Builds a UI frame. [pid] defaults to 0xF0 and `0xCC`/`0xCD` are refused.
     */
    public fun encodeUi(
        destination: Ax25Address,
        source: Ax25Address,
        info: ByteArray,
        digipeaters: List<Ax25Address> = emptyList(),
        pid: Int = PID_NO_LAYER3,
        role: Ax25Role = Ax25Role.COMMAND,
        pollFinal: Boolean = false,
    ): ByteArray {
        require(pid in 0..255) { "pid out of range" }
        require(pid != PID_ARPA_IP && pid != PID_ARPA_ARP) { "PID 0x${pid.toString(16)} means IP/ARP; Flash uses 0xF0" }
        require(digipeaters.size <= MAX_DIGIPEATERS) { "at most $MAX_DIGIPEATERS digipeaters" }
        val destC = role == Ax25Role.COMMAND
        val srcC = role == Ax25Role.RESPONSE
        val out = ByteArray((2 + digipeaters.size) * 7 + 2 + info.size)
        var i = 0
        i = putAddress(out, i, destination, destC, last = false)
        i = putAddress(out, i, source, srcC, last = digipeaters.isEmpty())
        for ((n, d) in digipeaters.withIndex()) i = putAddress(out, i, d, d.flagBit, last = n == digipeaters.size - 1)
        out[i++] = (CONTROL_UI or if (pollFinal) 0x10 else 0).toByte()
        out[i++] = pid.toByte()
        info.copyInto(out, i)
        return out
    }

    /** Parses the bytes of a KISS data frame. Never throws. */
    public fun decode(bytes: ByteArray): Ax25DecodeResult {
        if (bytes.size < 15) return Ax25DecodeResult.Error(Ax25DecodeError.TOO_SHORT)
        // Find the end-of-address marker: bit 0 of every 7th byte starting at index 6.
        var end = -1
        var idx = 6
        var count = 0
        while (idx < bytes.size) {
            count++
            if (bytes[idx].toInt() and 0x01 == 1) {
                end = idx
                break
            }
            if (count > 2 + MAX_DIGIPEATERS) return Ax25DecodeResult.Error(Ax25DecodeError.TOO_MANY_DIGIPEATERS)
            idx += 7
        }
        if (end < 0) return Ax25DecodeResult.Error(Ax25DecodeError.ADDRESS_FIELD_UNTERMINATED)
        if (count < 2) return Ax25DecodeResult.Error(Ax25DecodeError.TOO_SHORT)
        val addrLen = count * 7
        if (bytes.size < addrLen + 1) return Ax25DecodeResult.Error(Ax25DecodeError.MISSING_CONTROL)
        val addrs = ArrayList<Ax25Address>(count)
        for (n in 0 until count) {
            addrs += getAddress(bytes, n * 7) ?: return Ax25DecodeResult.Error(Ax25DecodeError.BAD_CALLSIGN)
        }
        val control = bytes[addrLen].toInt() and 0xFF
        val hasPid = (control and 0x01) == 0 || (control and 0xEF) == CONTROL_UI // I frames and UI carry a PID
        val pid: Int?
        val infoStart: Int
        if (hasPid) {
            if (bytes.size < addrLen + 2) return Ax25DecodeResult.Error(Ax25DecodeError.MISSING_CONTROL)
            pid = bytes[addrLen + 1].toInt() and 0xFF
            infoStart = addrLen + 2
        } else {
            pid = null
            infoStart = addrLen + 1
        }
        val destC = addrs[0].flagBit
        val srcC = addrs[1].flagBit
        val role = when {
            destC && !srcC -> Ax25Role.COMMAND
            !destC && srcC -> Ax25Role.RESPONSE
            else -> Ax25Role.LEGACY
        }
        return Ax25DecodeResult.Ok(
            Ax25Frame(addrs[0], addrs[1], addrs.drop(2), control, pid, bytes.copyOfRange(infoStart, bytes.size), role),
        )
    }

    private fun putAddress(out: ByteArray, at: Int, a: Ax25Address, flag: Boolean, last: Boolean): Int {
        for (n in 0 until 6) {
            val ch = if (n < a.callsign.length) a.callsign[n].code else ' '.code
            out[at + n] = (ch shl 1).toByte()
        }
        var ssidByte = 0x60 or (a.ssid shl 1)
        if (flag) ssidByte = ssidByte or 0x80
        if (last) ssidByte = ssidByte or 0x01
        out[at + 6] = ssidByte.toByte()
        return at + 7
    }

    private fun getAddress(b: ByteArray, at: Int): Ax25Address? {
        val sb = StringBuilder(6)
        for (n in 0 until 6) {
            val ch = ((b[at + n].toInt() and 0xFF) ushr 1)
            if (ch == ' '.code) continue
            sb.append(ch.toChar())
        }
        val ssidByte = b[at + 6].toInt() and 0xFF
        return try {
            Ax25Address(sb.toString(), (ssidByte ushr 1) and 0x0F, flagBit = ssidByte and 0x80 != 0)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
