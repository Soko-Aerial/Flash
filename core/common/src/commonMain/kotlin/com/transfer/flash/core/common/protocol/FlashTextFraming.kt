package com.transfer.flash.core.common.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Protocol serialization and text escaping utilities for Flash line-based TCP and WebSocket framing.
 *
 * Escapes delimiters:
 * - '%' -> '%25'
 * - ' ' (space) -> '%20'
 * - '=' -> '%3D'
 */
@FlashInternalApi
public object FlashTextFraming {

    public fun escape(value: String): String {
        return value
            .replace("%", "%25")
            .replace(" ", "%20")
            .replace("=", "%3D")
    }

    public fun unescape(value: String): String {
        return value
            .replace("%3D", "=")
            .replace("%20", " ")
            .replace("%25", "%")
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
        val parts = text.trim().split(' ')
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
