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

        val server = params["server"] ?: params["hub"] ?: params["host"] ?: return null
        val token = params["token"] ?: return null
        val root = params["root"] ?: params["fingerprint"] ?: ""

        val normalizedServer = when {
            server.startsWith("http://", ignoreCase = true) || server.startsWith("https://", ignoreCase = true) -> server
            else -> "http://$server"
        }.removeSuffix("/")

        return ParsedEnrollmentInvite(
            serverUrl = normalizedServer,
            token = token,
            rootFingerprint = root.uppercase(),
        )
    }

    /**
     * Checks if [raw] is a valid enterprise enrollment invitation link.
     */
    public fun isEnrollInvite(raw: String): Boolean = parseInvite(raw) != null
}
