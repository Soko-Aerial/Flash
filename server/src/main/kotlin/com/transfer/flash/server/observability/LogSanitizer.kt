package com.transfer.flash.server.observability

/**
 * Makes a client-controlled string safe to put in a log line (REL-13: log injection). A device id, a friendly name or
 * a frame type may contain a newline or a control character that would forge a second log line; this replaces every
 * such character and caps the length.
 */
object LogSanitizer {
    private const val DEFAULT_MAX = 64

    fun clean(value: String?, max: Int = DEFAULT_MAX): String {
        if (value == null) return "-"
        val builder = StringBuilder(minOf(value.length, max) + 1)
        var i = 0
        while (i < value.length && builder.length < max) {
            val c = value[i]
            val unsafe = Character.isISOControl(c) || c == ' ' || c == ' '
            builder.append(if (unsafe) '?' else c)
            i++
        }
        if (i < value.length) builder.append('~')
        return builder.toString()
    }
}
