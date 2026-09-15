package com.transfer.flash.core.common.protocol

/**
 * Minimal pure-Kotlin Base64 codec (RFC 4648, standard alphabet, with padding).
 *
 * Used to carry SDP bodies inside Flash text-framed wire messages without corruption.
 *
 * ### Why this exists
 *
 * - `java.util.Base64` requires API 26+, but `core/common` targets `minSdk = 24`.
 * - `android.util.Base64` is Android-only and breaks the pure-JVM unit tests that
 *   exercise [com.transfer.flash.core.calling.protocol.CallFrameCodec].
 *
 * The codec is intentionally small: encode + decode of arbitrary byte arrays and
 * UTF-8 strings, strict validation, constant-time-ish lookup via a 256-entry table.
 * It is NOT a general-purpose base64 implementation; it exists solely so wire
 * transports can safely embed arbitrary binary/whitespace-heavy payloads.
 *
 * Phase 06 made "pure Kotlin" literally true so the file could live in `commonMain`:
 * `decode` accumulated into a `java.io.ByteArrayOutputStream` (the output size is exactly
 * `dataLength * 3 / 4`, so a preallocated [ByteArray] plus a write index is equivalent), and
 * `decodeUtf8` used the JVM-only `toString(Charsets.UTF_8)` instead of `decodeToString()`.
 * Both are behaviour-identical, including U+FFFD substitution for malformed UTF-8.
 */
public object Base64 {
    private const val PAD = '='

    private val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Maps byte value -> base64 char index, -1 for invalid. */
    private val DECODE_TABLE = IntArray(256) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    /**
     * Encodes [data] into a base64 string (standard alphabet, `=` padding).
     */
    public fun encode(data: ByteArray): String {
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0

            out.append(ALPHABET[(b0 ushr 2) and 0x3F])
            out.append(ALPHABET[((b0 shl 4) or (b1 ushr 4)) and 0x3F])
            out.append(if (i + 1 < data.size) ALPHABET[((b1 shl 2) or (b2 ushr 6)) and 0x3F] else PAD)
            out.append(if (i + 2 < data.size) ALPHABET[b2 and 0x3F] else PAD)
            i += 3
        }
        return out.toString()
    }

    /**
     * Encodes [value] (UTF-8) into a base64 string.
     */
    public fun encodeUtf8(value: String): String = encode(value.encodeToByteArray())

    /**
     * Decodes a base64 string back into its raw bytes.
     *
     * @throws IllegalArgumentException if [encoded] contains characters outside the
     *   standard alphabet or has invalid padding.
     */
    public fun decode(encoded: String): ByteArray {
        val input = encoded.trim()
        if (input.isEmpty()) return ByteArray(0)

        // Validate padding: at most two '=' at the very end; total length must be
                // a multiple of 4 (standard base64).
                val padIndex = input.indexOf(PAD)
                if (padIndex != -1) {
            val pads = input.substring(padIndex)
                    if (pads.any { it != PAD }) {
                        throw IllegalArgumentException("Invalid base64: '=' must only appear at the end")
            }
                    if (pads.length > 2) {
                        throw IllegalArgumentException("Invalid base64: too many padding characters")
            }
                }
                if (input.length % 4 != 0) {
                    throw IllegalArgumentException("Invalid base64: total length must be a multiple of 4")
        }

        var dataLength = input.length
        while (dataLength > 0 && input[dataLength - 1] == PAD) dataLength--

        if (dataLength % 4 == 1) {
            throw IllegalArgumentException("Invalid base64: length mod 4 == 1")
        }

        val out = ByteArray((dataLength * 3) / 4)
        var outIndex = 0
        var i = 0
        while (i < dataLength) {
            val c0 = decodeChar(input[i])
            val c1 = decodeChar(input[i + 1])
            val c2 = if (i + 2 < dataLength) decodeChar(input[i + 2]) else 0
            val c3 = if (i + 3 < dataLength) decodeChar(input[i + 3]) else 0

            val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            out[outIndex++] = ((triple ushr 16) and 0xFF).toByte()
            if (i + 2 < dataLength) out[outIndex++] = ((triple ushr 8) and 0xFF).toByte()
            if (i + 3 < dataLength) out[outIndex++] = (triple and 0xFF).toByte()
            i += 4
        }
        return out
    }

    /**
     * Decodes a base64 string into a UTF-8 string.
     *
     * @throws IllegalArgumentException on malformed input.
     */
    public fun decodeUtf8(encoded: String): String = decode(encoded).decodeToString()

    // SENTINEL: Bounds check c.code to prevent IndexOutOfBoundsException on non-ASCII input (>0xFF)
    private fun decodeChar(c: Char): Int {
        val v = if (c.code in 0..255) DECODE_TABLE[c.code] else -1
        if (v == -1) {
            throw IllegalArgumentException("Invalid base64 character: '$c' (0x${c.code.toString(16)})")
        }
        return v
    }
}
