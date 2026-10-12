package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Parsed enrollment invitation from a `flash://enroll` URI.
 */
@FlashInternalApi
public data class EnrollmentInvite(
    public val serverUrl: String,
    public val token: String,
    public val rootFingerprint: String,
) {
    public companion object {
        public fun parseOrNull(uriString: String): EnrollmentInvite? {
            val trimmed = uriString.trim()
            if (!trimmed.startsWith("flash://enroll?") && !trimmed.startsWith("flash://enroll/?")) {
                // Also support http(s) links like http://host:8443/enroll?token=...
                if (!trimmed.contains("token=")) return null
            }

            val query = if (trimmed.contains("?")) trimmed.substringAfter("?") else trimmed
            val params = query.split("&").associate { param ->
                val eq = param.indexOf('=')
                if (eq > 0) {
                    param.substring(0, eq) to param.substring(eq + 1)
                } else {
                    param to ""
                }
            }

            val rawServer = decodePercent(params["server"] ?: params["host"] ?: params["hub"] ?: return null)
            val token = decodePercent(params["token"] ?: return null)
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
    }
}

/**
 * Organization directory group entry returned by the hub.
 */
@FlashInternalApi
public data class EnterpriseDirectoryGroup(
    public val groupId: String,
    public val name: String,
    public val department: String,
    public val visibility: String,
    public val description: String = "",
    public val requiresApproval: Boolean = false,
    public val memberCount: Int = 0,
)

/**
 * Organization member entry returned by the hub.
 */
@FlashInternalApi
public data class EnterpriseDirectoryMember(
    public val deviceId: String,
    public val friendlyName: String,
    public val department: String,
    public val role: String = "member",
    public val online: Boolean = false,
)

/**
 * Department entry returned by the hub.
 */
@FlashInternalApi
public data class EnterpriseDepartment(
    public val departmentId: String,
    public val name: String,
    public val description: String = "",
    public val memberCount: Int = 0,
    public val groupCount: Int = 0,
)
