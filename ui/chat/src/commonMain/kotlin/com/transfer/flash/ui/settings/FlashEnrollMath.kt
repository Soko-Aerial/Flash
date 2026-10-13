package com.transfer.flash.ui.settings

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite

/**
 * Pure parsing and validation math for enterprise enrollment links.
 */
public object FlashEnrollMath {

    /**
     * Parses a `flash://enroll` or `http(s)://.../enroll` invite URI string with the one shared parser in
     * `:core:hub-protocol` (CLI-17), so the clients and the server agree. The shared [EnrollmentInvite] is returned
     * as is: the former copy of its three fields in this module is gone.
     */
    @FlashInternalApi
    public fun parseInvite(raw: String): EnrollmentInvite? = EnrollmentInvite.parseOrNull(raw)

    /**
     * Checks if [raw] is a valid enterprise enrollment invitation link.
     */
    @OptIn(FlashInternalApi::class)
    public fun isEnrollInvite(raw: String): Boolean = parseInvite(raw) != null
}
