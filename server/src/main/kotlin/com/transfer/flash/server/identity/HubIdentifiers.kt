package com.transfer.flash.server.identity

import com.transfer.flash.core.hubprotocol.certs.HubAuth

/** Identifier rules the hub enforces on everything a client or an administrator names (SEC-07, SEC-15). */
object HubIdentifiers {
    /** Letters, digits and `. _ : @ -`, starting with a letter or digit, at most 128 characters. */
    private val DEVICE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:@\\-]{0,127}$")

    /** Department and group ids: same alphabet as device ids (no `/`, they appear in URLs and in cookies). */
    private val ENTITY_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:@\\-]{0,127}$")

    fun isValidDeviceId(id: String): Boolean = HubAuth.isValidDeviceId(id) && DEVICE_ID.matches(id)

    /** Department / group identifier. */
    fun isValidEntityId(id: String): Boolean = ENTITY_ID.matches(id)

    /** A human-readable name: 1..128 characters, no control characters. */
    fun isValidName(name: String): Boolean =
        name.isNotBlank() && name.length <= 128 && name.none { it.code < 0x20 || it.code == 0x7F }

    /** Strips control characters and trims to [max] for a free-text label that is stored but never trusted. */
    fun sanitizeLabel(text: String?, max: Int = 128): String =
        (text ?: "").filter { it.code >= 0x20 && it.code != 0x7F }.trim().take(max)
}
