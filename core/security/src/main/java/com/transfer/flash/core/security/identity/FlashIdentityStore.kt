package com.transfer.flash.core.security.identity

import com.transfer.flash.core.common.result.FlashResult

/**
 * Storage contract for managing the local device's persistent identity and display name.
 */
public interface FlashIdentityStore {
    /** Returns the persistent local identity, generating one on first access if absent. */
    public fun getIdentity(): FlashIdentity

    /** Updates the user-visible friendly display name. */
    public fun updateFriendlyName(name: String): FlashResult<Unit>
}
