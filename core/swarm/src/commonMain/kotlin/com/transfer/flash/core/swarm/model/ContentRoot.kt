package com.transfer.flash.core.swarm.model

import kotlin.jvm.JvmInline

/**
 * SHA-256 root of a swarm manifest, represented as 64 lowercase hex characters.
 */
@JvmInline
public value class ContentRoot(public val hex: String) {
    init {
        require(hex.length == 64) { "ContentRoot hex must be 64 characters, got: ${hex.length}" }
        for (i in 0 until 64) {
            val c = hex[i]
            require(c in '0'..'9' || c in 'a'..'f') { "ContentRoot hex must be lowercase hex, invalid char '$c' at $i" }
        }
    }

    public fun toByteArray(): ByteArray {
        val bytes = ByteArray(32)
        for (i in 0 until 32) {
            val h = hex[i * 2]
            val l = hex[i * 2 + 1]
            val high = if (h in '0'..'9') h - '0' else h - 'a' + 10
            val low = if (l in '0'..'9') l - '0' else l - 'a' + 10
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    public companion object {
        private const val HEX_DIGITS: String = "0123456789abcdef"

        public fun fromBytes(bytes: ByteArray): ContentRoot {
            require(bytes.size == 32) { "ContentRoot requires exactly 32 bytes, got ${bytes.size}" }
            val sb = StringBuilder(64)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_DIGITS[v ushr 4])
                sb.append(HEX_DIGITS[v and 0x0F])
            }
            return ContentRoot(sb.toString())
        }
    }
}
