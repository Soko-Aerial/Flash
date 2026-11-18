package com.transfer.flash.server.directory

import com.transfer.flash.server.auth.SecretBox
import java.util.Base64

/** Short-lived links to the ordinary Hub group join workflow. */
class HubGroupJoinLinks(
    private val secrets: SecretBox,
    private val clock: () -> Long,
) {
    fun issue(group: GroupCharter): String {
        check(secrets.isActive)
        val data = "${group.id}\n${group.version}\n${clock() + TTL_MS}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secrets.sealBytes(data.encodeToByteArray(), AAD))
    }

    fun resolve(token: String, directory: OrganizationDirectory): GroupCharter? {
        if (!secrets.isActive || token.length !in 1..MAX_TOKEN_LENGTH || !TOKEN_PATTERN.matches(token)) return null
        val fields = runCatching {
            val sealed = Base64.getUrlDecoder().decode(token)
            secrets.openBytes(sealed, AAD).decodeToString(throwOnInvalidSequence = true).split('\n')
        }.getOrNull() ?: return null
        if (fields.size != 3) return null
        val version = fields[1].toLongOrNull() ?: return null
        val expiresAt = fields[2].toLongOrNull() ?: return null
        val now = clock()
        if (expiresAt <= now || expiresAt - now > TTL_MS) return null
        return directory.getGroup(fields[0])?.takeIf { it.version == version }
    }

    private companion object {
        const val AAD = "flash-hub:group-join-link:v1"
        const val TTL_MS = 24 * 60 * 60 * 1000L
        const val MAX_TOKEN_LENGTH = 2048
        val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}
