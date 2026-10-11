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

            val server = params["server"] ?: params["host"] ?: params["hub"] ?: return null
            val token = params["token"] ?: return null
            val root = params["root"] ?: params["fingerprint"] ?: ""

            val normalizedServer = when {
                server.startsWith("http://") || server.startsWith("https://") -> server
                else -> "http://$server"
            }

            return EnrollmentInvite(
                serverUrl = normalizedServer.removeSuffix("/"),
                token = token,
                rootFingerprint = root.uppercase(),
            )
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
