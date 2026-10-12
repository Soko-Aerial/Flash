package com.transfer.flash.ui.settings

/**
 * Parsed components of an enterprise enrollment invitation link.
 */
public data class ParsedEnrollmentInvite(
    public val serverUrl: String,
    public val token: String,
    public val rootFingerprint: String,
)

/**
 * Pure parsing and validation math for enterprise enrollment links.
 */
public object FlashEnrollMath {

    /**
     * Parses a `flash://enroll` or `http(s)://.../enroll` invite URI string into [ParsedEnrollmentInvite].
     */
    public fun parseInvite(raw: String): ParsedEnrollmentInvite? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val query = when {
            trimmed.contains("?") -> trimmed.substringAfter("?")
            trimmed.startsWith("flash://enroll") -> trimmed.removePrefix("flash://enroll").removePrefix("/").removePrefix("?")
            else -> trimmed
        }

        val params = query.split("&").associate { param ->
            val eq = param.indexOf('=')
            if (eq > 0) {
                param.substring(0, eq).trim() to param.substring(eq + 1).trim()
            } else {
                param.trim() to ""
            }
        }

        val rawServer = decodePercent(params["server"] ?: params["hub"] ?: params["host"] ?: return null)
        val token = decodePercent(params["token"] ?: return null)
        val root = decodePercent(params["root"] ?: params["fingerprint"] ?: "")

        val normalizedServer = when {
            rawServer.startsWith("http://", ignoreCase = true) || rawServer.startsWith("https://", ignoreCase = true) -> rawServer
            else -> "http://$rawServer"
        }.removeSuffix("/")

        return ParsedEnrollmentInvite(
            serverUrl = normalizedServer,
            token = token,
            rootFingerprint = root.uppercase(),
        )
    }

    private fun decodePercent(input: String): String {
        if (!input.contains('%') && !input.contains('+')) return input
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }
                c == '%' && i + 2 < input.length -> {
                    val hex = input.substring(i + 1, i + 3)
                    val byteVal = hex.toIntOrNull(16)
                    if (byteVal != null) {
                        bytes.add(byteVal.toByte())
                        i += 3
                    } else {
                        val enc = c.toString().encodeToByteArray()
                        for (b in enc) bytes.add(b)
                        i++
                    }
                }
                else -> {
                    val enc = c.toString().encodeToByteArray()
                    for (b in enc) bytes.add(b)
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    /**
     * Checks if [raw] is a valid enterprise enrollment invitation link.
     */
    public fun isEnrollInvite(raw: String): Boolean = parseInvite(raw) != null
}
