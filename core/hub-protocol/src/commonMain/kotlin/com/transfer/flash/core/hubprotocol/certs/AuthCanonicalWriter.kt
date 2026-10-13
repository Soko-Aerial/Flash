package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Deterministic canonical byte serializer for enterprise certificates, delegations, revocation lists and the
 * handshake proofs. The first field is always the domain `tag`. Every field is written with a 4-byte big-endian
 * length prefix followed by its payload, so concatenating fields can never be re-read as a different field split
 * (SEC-14: no bare string concatenation of nonces and identifiers).
 *
 * Sets are written sorted (strings by natural order, longs ascending) preceded by their element count.
 */
@FlashInternalApi
public class AuthCanonicalWriter(tag: String) {
    private val parts = mutableListOf<ByteArray>()
    private var total = 0

    init {
        text(tag)
    }

    public fun text(value: String): AuthCanonicalWriter = bytes(value.encodeToByteArray())

    public fun bytes(value: ByteArray): AuthCanonicalWriter {
        val len = value.size
        add(
            byteArrayOf(
                (len ushr 24).toByte(),
                (len ushr 16).toByte(),
                (len ushr 8).toByte(),
                len.toByte(),
            )
        )
        add(value)
        return this
    }

    public fun long(value: Long): AuthCanonicalWriter = bytes(
        ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }
    )

    public fun int(value: Int): AuthCanonicalWriter = bytes(
        ByteArray(4) { i -> (value ushr (24 - 8 * i)).toByte() }
    )

    public fun bool(value: Boolean): AuthCanonicalWriter = bytes(byteArrayOf(if (value) 1 else 0))

    public fun stringSet(values: Set<String>): AuthCanonicalWriter {
        val sorted = values.sorted()
        int(sorted.size)
        for (v in sorted) {
            text(v)
        }
        return this
    }

    public fun longSet(values: Set<Long>): AuthCanonicalWriter {
        val sorted = values.sorted()
        int(sorted.size)
        for (v in sorted) {
            long(v)
        }
        return this
    }

    public fun build(): ByteArray {
        val out = ByteArray(total)
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun add(part: ByteArray) {
        parts.add(part)
        total += part.size
    }
}

/** Lower-case hex of [this], used by golden-vector tests and diagnostics. */
@FlashInternalApi
public fun ByteArray.toHubHex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return sb.toString()
}
