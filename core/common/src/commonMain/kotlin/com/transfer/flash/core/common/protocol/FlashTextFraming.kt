package com.transfer.flash.core.common.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Protocol serialization and text escaping utilities for Flash line-based TCP and WebSocket framing.
 *
 * Escapes delimiters and line/control characters:
 * - '%' -> '%25'
 * - ' ' (space) -> '%20'
 * - '=' -> '%3D'
 * - C0 controls and DEL -> '%XX' (for example '\n' -> '%0A')
 *
 * A raw line break in a value would end the frame early, so [escape] must make every value safe for
 * one line. Control escapes are deliberately **not** decoded by [unescape]: a peer-supplied value is
 * never allowed to reintroduce a control character into a name, a reason or a log line. The literal
 * escape is shown instead, which is safe and visible.
 */
@FlashInternalApi
public object FlashTextFraming {

    private const val HEX = "0123456789ABCDEF"

    public fun escape(value: String): String {
        if (value.isEmpty()) return value
        val builder = StringBuilder(value.length)
        for (ch in value) {
            when {
                ch == '%' -> builder.append("%25")
                ch == ' ' -> builder.append("%20")
                ch == '=' -> builder.append("%3D")
                ch.code < 0x20 || ch.code == 0x7F -> {
                    builder.append('%')
                    builder.append(HEX[ch.code ushr 4])
                    builder.append(HEX[ch.code and 0x0F])
                }
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    public fun unescape(value: String): String {
        if ('%' !in value) return value
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length) {
                val high = hexDigit(value[index + 1])
                val low = hexDigit(value[index + 2])
                if (high >= 0 && low >= 0) {
                    val code = (high shl 4) or low
                    // Only printable escapes are decoded. A control escape stays literal so an
                    // untrusted name cannot smuggle a newline into a log line or the UI.
                    if (code >= 0x20 && code != 0x7F) {
                        builder.append(code.toChar())
                        index += 3
                        continue
                    }
                }
            }
            builder.append(ch)
            index++
        }
        return builder.toString()
    }

    private fun hexDigit(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'A'..'F' -> ch - 'A' + 10
        in 'a'..'f' -> ch - 'a' + 10
        else -> -1
    }

    public fun encodeFields(prefix: String, vararg fields: Pair<String, String>): String {
        return encodeFields(prefix, fields.toList())
    }

    public fun encodeFields(prefix: String, fields: List<Pair<String, String>>): String {
        val encodedPairs = fields.map { (key, value) ->
            "$key=${escape(value)}"
        }
        return (listOf(prefix) + encodedPairs).joinToString(separator = " ")
    }

    /**
     * Parses `PREFIX key=value ...`. A key that appears twice makes the whole frame unreadable (`null`): a map would
     * silently keep the last one, so a sender could hide a second value behind the one a reviewer or signer saw
     * (SEC-D04-04). No Flash encoder repeats a key.
     */
    public fun parseFields(text: String, expectedPrefix: String): Map<String, String>? {
        // trim() tolerates a trailing line ending; anything left is an embedded control and means the
        // frame is not a single well-formed line (for example a value that carried a raw newline).
        val trimmed = text.trim()
        if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) return null
        val parts = trimmed.split(' ')
        if (parts.firstOrNull() != expectedPrefix) return null

        val fields = LinkedHashMap<String, String>()
        for (part in parts.drop(1)) {
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            val key = part.substring(0, separator)
            if (fields.put(key, unescape(part.substring(separator + 1))) != null) return null
        }
        return fields
    }
}
