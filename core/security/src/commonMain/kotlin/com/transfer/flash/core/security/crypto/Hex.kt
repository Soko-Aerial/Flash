package com.transfer.flash.core.security.crypto

/**
 * Hex formatting for `commonMain` (Phase 07).
 *
 * Replaces `"%02X".format(byte)` / `"%02x".format(byte)`, which are `kotlin.text.String.format`
 * — JVM-only and therefore unavailable under D1 = Option B (CONVENTIONS.md R6).
 *
 * Behaviour is identical to the `Formatter` calls these replace: `java.util.Formatter` documents
 * that a negative `Byte` argument to `%X` is treated as the value plus 2^8, i.e. unsigned — which
 * is exactly `toInt() and 0xFF`. `FlashFingerprintTest` pins a fixed SHA-256 vector whose first
 * byte is `0x82` (negative as a signed `Byte`), so the high-bit case is covered by an existing
 * assertion rather than by inspection.
 *
 * `kotlin.text.HexFormat` is deliberately not used: it is still `@ExperimentalStdlibApi` in
 * Kotlin 2.2.10 and would push an opt-in onto every call site for no gain over four lines.
 */

private const val HEX_LOWER = "0123456789abcdef"
private const val HEX_UPPER = "0123456789ABCDEF"

/** Two lowercase hex digits for this byte, zero-padded. Equivalent to `"%02x".format(this)`. */
internal fun Byte.toHexLower(): String {
    val v = toInt() and 0xFF
    return "${HEX_LOWER[v shr 4]}${HEX_LOWER[v and 0x0F]}"
}

/** Two uppercase hex digits for this byte, zero-padded. Equivalent to `"%02X".format(this)`. */
internal fun Byte.toHexUpper(): String {
    val v = toInt() and 0xFF
    return "${HEX_UPPER[v shr 4]}${HEX_UPPER[v and 0x0F]}"
}

/** Lowercase hex string over every byte, no separator. */
internal fun ByteArray.toHexLower(): String = buildString(size * 2) {
    for (b in this@toHexLower) {
        val v = b.toInt() and 0xFF
        append(HEX_LOWER[v shr 4])
        append(HEX_LOWER[v and 0x0F])
    }
}
