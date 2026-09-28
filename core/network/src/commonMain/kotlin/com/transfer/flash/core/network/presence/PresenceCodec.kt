@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.presence

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashTextFraming

/** Where a reported device can be dialed. Literal IP addresses only, never a host name. */
public data class PresenceEndpoint(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/** What the reporter knows about a device. [Gone] exists only in deltas, to withdraw an entry. */
public enum class PresenceReportState(internal val wire: String) {
    /** The reporter holds a live session with the device. */
    Connected("c"),

    /** The reporter's discovery sees the device, without a session. */
    Seen("s"),

    /** The reporter no longer knows the device (delta only). */
    Gone("x"),
}

/**
 * One line of a report.
 *
 * @property ageMs how long ago the reporter's information was fresh. Never a clock time, because
 *   phone clocks disagree; each receiver adds its own holding time.
 * @property hops 1 when the reporter observed the device itself, 2 when it relays a report.
 */
public data class PresenceEntry(
    val deviceId: String,
    val ageMs: Long,
    val state: PresenceReportState,
    val hops: Int,
    val endpoint: PresenceEndpoint? = null,
)

/** The four `FLASH_PRES` frames (PC4, ADR-046, `docs/protocol.md` "Presence sharing"). */
public sealed interface PresenceFrame {
    /**
     * Sent when a session opens, and again every refresh interval. [share] false marks a Ghost
     * device: nobody may report it. [salt] is this side's per-session salt for [Want] hashes; a
     * Ghost device sends none because it reports nothing.
     */
    public data class Hello(val share: Boolean, val salt: String?, val refreshMs: Long? = null) : PresenceFrame

    /** The asker's contacts, each as [PresenceCodec.matchHash] under the reporter's salt. */
    public data class Want(val hashes: Set<String>) : PresenceFrame

    /** [full] true is a digest that replaces everything this sender reported; false is a delta. */
    public data class Report(val full: Boolean, val entries: List<PresenceEntry>) : PresenceFrame
}

/**
 * Encodes and decodes `FLASH_PRES` text frames.
 *
 * Every value is validated here, so the rest of the presence code only sees well-formed input: ids
 * from a safe alphabet, ages within [MAX_AGE_ACCEPTED_MS], hops 1..[MAX_HOPS], and endpoints that are
 * literal, unicast, non-loopback addresses (a tip must never make us dial ourselves or a host name).
 * An entry that fails validation is dropped on its own; a frame whose type is unknown or whose
 * version is newer decodes to null and is ignored.
 */
public object PresenceCodec {
    public const val PREFIX: String = "FLASH_PRES"
    public const val VERSION: Int = 1

    /** Hard cap on hops (plan §3.2): a direct observation is 1, one relay makes it 2. */
    public const val MAX_HOPS: Int = 2
    public const val MAX_ENTRIES: Int = 128
    public const val MAX_WANT: Int = 512

    /** Upper bound on any age a frame may carry; each mode's max age is lower. */
    public const val MAX_AGE_ACCEPTED_MS: Long = 10 * 60_000L

    /** Salt length in bytes (hex on the wire). */
    public const val SALT_BYTES: Int = 16

    /** Truncated hash length in bytes (hex on the wire): 64 bits, ample for a few hundred ids. */
    public const val HASH_BYTES: Int = 8

    /**
     * Range of a hello's `r` (refresh interval, PC5). The top is the longest refresh any mode uses
     * (ECO), which [PresenceConfig.RELAY_ALLOWANCE_MS] depends on.
     */
    public const val MIN_REFRESH_MS: Long = 5_000L
    public const val MAX_REFRESH_MS: Long = 60_000L

    private const val MAX_ID_LENGTH = 80
    private val ID_CHARS = Regex("[A-Za-z0-9._:-]+")
    private val HEX = Regex("[0-9a-f]+")
    private val IPV6_CHARS = Regex("[0-9A-Fa-f:.]+")
    private val SCOPE_CHARS = Regex("[A-Za-z0-9_.-]+")

    /** True for any frame with the `FLASH_PRES` prefix, valid or not; routers consume all of them. */
    public fun isPresenceFrame(text: String): Boolean =
        text.startsWith(PREFIX) && (text.length == PREFIX.length || text[PREFIX.length] == ' ')

    public fun encode(frame: PresenceFrame): String {
        val fields = ArrayList<Pair<String, String>>(4)
        fields += "v" to VERSION.toString()
        when (frame) {
            is PresenceFrame.Hello -> {
                fields += "t" to "hello"
                fields += "share" to if (frame.share) "1" else "0"
                frame.salt?.let { fields += "salt" to it }
                frame.refreshMs?.let { fields += "r" to it.toString() }
            }
            is PresenceFrame.Want -> {
                fields += "t" to "want"
                fields += "h" to frame.hashes.sorted().joinToString(",")
            }
            is PresenceFrame.Report -> {
                fields += "t" to if (frame.full) "digest" else "delta"
                fields += "e" to frame.entries.joinToString(";") { encodeEntry(it) }
            }
        }
        return FlashTextFraming.encodeFields(PREFIX, fields)
    }

    public fun decode(text: String): PresenceFrame? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        val version = fields["v"]?.toIntOrNull() ?: return null
        if (version != VERSION) return null
        return when (fields["t"]) {
            "hello" -> {
                val share = when (fields["share"]) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                val salt = fields["salt"]?.takeIf { it.length == SALT_BYTES * 2 && HEX.matches(it) }
                // PC5: the sender's refresh interval, so a receiver in another mode holds its
                // reports long enough. Optional; out of range is treated as absent.
                val refresh = fields["r"]?.toLongOrNull()?.takeIf { it in MIN_REFRESH_MS..MAX_REFRESH_MS }
                PresenceFrame.Hello(share = share, salt = if (share) salt else null, refreshMs = refresh)
            }
            "want" -> {
                val raw = fields["h"].orEmpty()
                val hashes = if (raw.isEmpty()) emptyList() else raw.split(',')
                if (hashes.size > MAX_WANT) return null
                PresenceFrame.Want(
                    hashes.filterTo(HashSet()) { it.length == HASH_BYTES * 2 && HEX.matches(it) },
                )
            }
            "digest", "delta" -> {
                val raw = fields["e"].orEmpty()
                val parts = if (raw.isEmpty()) emptyList() else raw.split(';')
                if (parts.size > MAX_ENTRIES) return null
                val full = fields["t"] == "digest"
                val entries = parts.mapNotNull { decodeEntry(it) }
                    // A digest describes current knowledge; withdrawals belong in deltas only.
                    .filter { !full || it.state != PresenceReportState.Gone }
                PresenceFrame.Report(full = full, entries = entries)
            }
            else -> null
        }
    }

    /**
     * `H(salt, id)`: SHA-256 over a domain tag, the salt bytes and the id, truncated to
     * [HASH_BYTES]. [sha256] is injected because `core:network`'s common code has no crypto
     * dependency; every host passes `FlashFingerprint.fingerprint` (plain SHA-256).
     */
    public fun matchHash(sha256: (ByteArray) -> ByteArray, saltHex: String, deviceId: String): String {
        val input = DOMAIN.encodeToByteArray() + hexToBytes(saltHex) + deviceId.encodeToByteArray()
        return bytesToHex(sha256(input).copyOf(HASH_BYTES))
    }

    public fun isValidDeviceId(id: String): Boolean =
        id.length in 1..MAX_ID_LENGTH && ID_CHARS.matches(id)

    /** Literal unicast addresses only; rejects names, loopback, unspecified, multicast, broadcast. */
    public fun isDialableHost(host: String): Boolean {
        val v4 = host.split('.')
        if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) }) {
            val o = v4.map { it.toInt() }
            if (o.any { it > 255 }) return false
            val first = o[0]
            return first != 0 && first != 127 && first < 224 && !(o.all { it == 255 })
        }
        val address = host.substringBefore('%')
        val scope = host.substringAfter('%', "")
        if (!host.contains(':') || !IPV6_CHARS.matches(address)) return false
        if (host.contains('%') && !SCOPE_CHARS.matches(scope)) return false
        val lower = address.lowercase()
        return lower != "::" && lower != "::1" && !lower.startsWith("ff")
    }

    internal fun bytesToHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun encodeEntry(e: PresenceEntry): String =
        listOf(e.deviceId, e.ageMs.toString(), e.state.wire, e.hops.toString(), e.endpoint?.toString().orEmpty())
            .joinToString("|")

    private fun decodeEntry(raw: String): PresenceEntry? {
        val p = raw.split('|')
        if (p.size != 5) return null
        val id = p[0].takeIf(::isValidDeviceId) ?: return null
        val age = p[1].toLongOrNull()?.takeIf { it in 0..MAX_AGE_ACCEPTED_MS } ?: return null
        val state = PresenceReportState.entries.firstOrNull { it.wire == p[2] } ?: return null
        val hops = p[3].toIntOrNull()?.takeIf { it in 1..MAX_HOPS } ?: return null
        val endpoint = if (p[4].isEmpty()) null else decodeEndpoint(p[4]) ?: return null
        return PresenceEntry(id, age, state, hops, endpoint)
    }

    private fun decodeEndpoint(raw: String): PresenceEndpoint? {
        val colon = raw.lastIndexOf(':')
        if (colon <= 0) return null
        val host = raw.substring(0, colon)
        val port = raw.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        return if (isDialableHost(host)) PresenceEndpoint(host, port) else null
    }

    private const val DOMAIN = "flash-pres-v1|"
    private const val HEX_DIGITS = "0123456789abcdef"
}
