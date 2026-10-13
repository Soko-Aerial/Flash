package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Parsed enrolment invitation: `flash://enroll?server=<url>&token=<single-use>&root=<fingerprint>` (or an
 * `http(s)://host/enroll?token=...` link). ONE parser for the clients and the server (CLI-17: `:ui:chat` used to carry
 * its own copy of this logic).
 *
 * [serverUrl] is normalised (scheme added if missing, trailing slash removed). A bare host defaults to `http://` for
 * backward compatibility with already issued links; check [usesTls] and warn or refuse before sending a token over
 * cleartext. [rootFingerprint] is upper-case hex SHA-256 of the root public key, empty when the link carries none.
 */
@FlashInternalApi
public data class EnrollmentInvite(
    public val serverUrl: String,
    public val token: String,
    public val rootFingerprint: String,
) {
    public val usesTls: Boolean get() = serverUrl.startsWith("https://", ignoreCase = true)

    /** Re-encodes this invite as a `flash://enroll` link with percent-encoded values. */
    public fun toLink(): String = buildLink(serverUrl, token, rootFingerprint)

    public companion object {
        /** Builds a `flash://enroll` link; the server URL and token are percent-encoded. */
        public fun buildLink(serverUrl: String, token: String, rootFingerprint: String): String =
            "flash://enroll?server=${encodePercent(serverUrl)}&token=${encodePercent(token)}&root=${encodePercent(rootFingerprint)}"

        /** Returns the parsed invite, or null when [uriString] is not an enrolment link. Never throws. */
        public fun parseOrNull(uriString: String): EnrollmentInvite? {
            val trimmed = uriString.trim()
            if (trimmed.isEmpty()) return null
            val isFlashLink = trimmed.startsWith("flash://enroll?") || trimmed.startsWith("flash://enroll/?")
            if (!isFlashLink && !trimmed.contains("token=")) return null

            val query = if (trimmed.contains("?")) trimmed.substringAfter("?").substringBefore("#") else trimmed
            val params = query.split("&").associate { param ->
                val eq = param.indexOf('=')
                if (eq > 0) {
                    param.substring(0, eq).trim() to param.substring(eq + 1).trim()
                } else {
                    param.trim() to ""
                }
            }

            val rawServer = decodePercent(params["server"] ?: params["host"] ?: params["hub"] ?: return null)
            val token = decodePercent(params["token"] ?: return null)
            if (rawServer.isBlank() || token.isBlank()) return null
            val root = decodePercent(params["root"] ?: params["fingerprint"] ?: "")

            val normalizedServer = when {
                rawServer.startsWith("http://", ignoreCase = true) || rawServer.startsWith("https://", ignoreCase = true) -> rawServer
                else -> "http://$rawServer"
            }

            return EnrollmentInvite(
                serverUrl = normalizedServer.removeSuffix("/"),
                token = token,
                rootFingerprint = root.uppercase(),
            )
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        private const val HEX = "0123456789ABCDEF"

        private fun encodePercent(input: String): String {
            val sb = StringBuilder()
            for (b in input.encodeToByteArray()) {
                val c = b.toInt() and 0xFF
                if (c < 0x80 && UNRESERVED.indexOf(c.toChar()) >= 0) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
                }
            }
            return sb.toString()
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
                    c == '%' && i + 2 < input.length + 0 && input.length - i >= 3 -> {
                        val byteVal = input.substring(i + 1, i + 3).toIntOrNull(16)
                        if (byteVal != null) {
                            bytes.add(byteVal.toByte())
                            i += 3
                        } else {
                            for (b in c.toString().encodeToByteArray()) bytes.add(b)
                            i++
                        }
                    }
                    else -> {
                        // Surrogate pairs must be encoded together, not one UTF-16 unit at a time.
                        val unit = if (c.isHighSurrogate() && i + 1 < input.length && input[i + 1].isLowSurrogate()) {
                            input.substring(i, i + 2)
                        } else {
                            c.toString()
                        }
                        for (b in unit.encodeToByteArray()) bytes.add(b)
                        i += unit.length
                    }
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
