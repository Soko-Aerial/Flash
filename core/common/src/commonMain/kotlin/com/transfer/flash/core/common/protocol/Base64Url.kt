package com.transfer.flash.core.common.protocol

/**
 * Pure-Kotlin Base64URL codec (RFC 4648 §5, URL and filename safe alphabet, no padding).
 *
 * Used for compact, URL-safe serialization of group invites (`flash://g/1/<base64url>`).
 *
 * Alphabet: `A-Z`, `a-z`, `0-9`, `-`, `_`.
 * Padding: unpadded (no `=` characters).
 */
public object Base64Url {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Maps byte value -> base64url char index, -1 for invalid. */
    private val DECODE_TABLE = IntArray(256) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    /**
     * Encodes [data] into an unpadded base64url string.
     */
    public fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val fullGroups = data.size / 3
        val remainder = data.size % 3
        val capacity = fullGroups * 4 + if (remainder == 1) 2 else if (remainder == 2) 3 else 0
        val out = StringBuilder(capacity)

        var i = 0
        while (i + 2 < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF

            out.append(ALPHABET[(b0 ushr 2) and 0x3F])
            out.append(ALPHABET[((b0 shl 4) or (b1 ushr 4)) and 0x3F])
            out.append(ALPHABET[((b1 shl 2) or (b2 ushr 6)) and 0x3F])
            out.append(ALPHABET[b2 and 0x3F])
            i += 3
        }

        if (remainder == 1) {
            val b0 = data[i].toInt() and 0xFF
            out.append(ALPHABET[(b0 ushr 2) and 0x3F])
            out.append(ALPHABET[(b0 shl 4) and 0x3F])
        } else if (remainder == 2) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            out.append(ALPHABET[(b0 ushr 2) and 0x3F])
            out.append(ALPHABET[((b0 shl 4) or (b1 ushr 4)) and 0x3F])
            out.append(ALPHABET[(b1 shl 2) and 0x3F])
        }

        return out.toString()
    }

    /**
     * Decodes an unpadded base64url string to bytes, or returns null if malformed.
     * Never throws.
     */
    public fun decodeOrNull(encoded: String): ByteArray? {
        val input = encoded.trim()
        if (input.isEmpty()) return ByteArray(0)

        val rem = input.length % 4
        if (rem == 1) return null // A single base64 char carries only 6 bits, not a full byte.

        val fullGroups = input.length / 4
        val extraBytes = if (rem == 2) 1 else if (rem == 3) 2 else 0
        val out = ByteArray(fullGroups * 3 + extraBytes)
        var outIndex = 0
        var i = 0

        while (i + 3 < input.length) {
            val c0 = decodeChar(input[i])
            val c1 = decodeChar(input[i + 1])
            val c2 = decodeChar(input[i + 2])
            val c3 = decodeChar(input[i + 3])
            if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) return null

            out[outIndex++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            out[outIndex++] = ((c1 shl 4) or (c2 ushr 2)).toByte()
            out[outIndex++] = ((c2 shl 6) or c3).toByte()
            i += 4
        }

        if (rem == 2) {
            val c0 = decodeChar(input[i])
            val c1 = decodeChar(input[i + 1])
            if (c0 < 0 || c1 < 0) return null
            out[outIndex++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
        } else if (rem == 3) {
            val c0 = decodeChar(input[i])
            val c1 = decodeChar(input[i + 1])
            val c2 = decodeChar(input[i + 2])
            if (c0 < 0 || c1 < 0 || c2 < 0) return null
            out[outIndex++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            out[outIndex++] = ((c1 shl 4) or (c2 ushr 2)).toByte()
        }

        return out
    }

    /**
     * Decodes an unpadded base64url string to bytes.
     *
     * @throws IllegalArgumentException if [encoded] contains invalid characters or has an invalid length.
     */
    public fun decode(encoded: String): ByteArray {
        return decodeOrNull(encoded)
            ?: throw IllegalArgumentException("Invalid base64url string")
    }

    private fun decodeChar(c: Char): Int {
        val code = c.code
        return if (code in 0..255) DECODE_TABLE[code] else -1
    }
}
