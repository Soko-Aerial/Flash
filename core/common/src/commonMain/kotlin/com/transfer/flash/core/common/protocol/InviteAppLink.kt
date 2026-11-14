package com.transfer.flash.core.common.protocol

/**
 * An HTTPS wrapper for an existing Flash invite. The credential lives in the fragment, so the
 * link host can serve a static install page without receiving the invite or its Hub address.
 */
public object InviteAppLink {
    public const val GROUP_PATH: String = "/invite/group"
    public const val ENROLL_PATH: String = "/invite/enroll"
    public const val MAX_LINK_CHARS: Int = 4096

    private const val GROUP_SCHEME = "flash://g/1/"
    private const val ENROLL_SCHEME = "flash://enroll?"
    private val webLink = Regex("""https://([A-Za-z0-9.-]+(?::[0-9]{1,5})?)(/invite/(?:group|enroll))#([A-Za-z0-9_-]+)""")
    private val hostPattern = Regex("""[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+""")

    /** Wraps a legacy invite for a configured HTTPS host, leaving the legacy format available. */
    public fun wrap(host: String, invite: String): String? {
        if (!validHost(host) || !isInvite(invite)) return null
        val path = if (invite.startsWith(GROUP_SCHEME)) GROUP_PATH else ENROLL_PATH
        val payload = Base64Url.encode(invite.encodeToByteArray())
        return "https://$host$path#$payload".takeIf { it.length <= MAX_LINK_CHARS }
    }

    /**
     * Unwraps an HTTPS invite. OS activation supplies [expectedHost] to enforce the configured
     * origin. Explicit Paste invite may use any host because no request is sent to that host.
     * The original group or enrollment parser still validates the resulting invite.
     */
    public fun unwrap(link: String, expectedHost: String? = null): String? {
        if (link.length > MAX_LINK_CHARS) return null
        val match = webLink.matchEntire(link) ?: return null
        val (host, path, payload) = match.destructured
        if (expectedHost != null && !host.equals(expectedHost, ignoreCase = true)) return null
        if (!validHost(host.lowercase())) return null
        val bytes = Base64Url.decodeOrNull(payload) ?: return null
        val invite = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            return null
        }
        if (!isInvite(invite)) return null
        return invite.takeIf { (path == GROUP_PATH) == it.startsWith(GROUP_SCHEME) }
    }

    private fun validHost(host: String): Boolean {
        val name = host.substringBefore(':')
        val port = host.substringAfter(':', "").toIntOrNull()
        return name.length <= 253 && hostPattern.matches(name) && (':' !in host || (port != null && port in 1..65535))
    }

    private fun isInvite(invite: String): Boolean =
        invite.length in 1..MAX_LINK_CHARS && invite.none { it.isWhitespace() || it.isISOControl() || it == '#' } &&
            (invite.startsWith(GROUP_SCHEME) || invite.startsWith(ENROLL_SCHEME))
}
