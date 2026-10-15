@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The last verified revocation list of the hub, kept in memory so the synchronous `revocationsProvider` of the
 * directory sync and the roster verifier can use it (Plan B small gap). [fetch] must return a list ONLY when its
 * signature already verified against the organisation root (`HubSession.fetchRevocations` does). A failed fetch keeps
 * the previous list, and an older version never replaces a newer one, so a replayed stale list cannot un-revoke anyone.
 */
@FlashInternalApi
public class HubRevocationCache(private val fetch: suspend () -> RevocationList?) {
    private val lock = Mutex()

    @kotlin.concurrent.Volatile
    private var last: RevocationList? = null

    /** The cached list, or null before the first successful fetch. */
    public fun current(): RevocationList? = last

    /** Fetches and keeps the list when it is not older than the cached one. Never throws. */
    public suspend fun refresh() {
        lock.withLock {
            val fresh = runCatching { fetch() }.getOrNull() ?: return
            val old = last
            if (old == null || fresh.version >= old.version) last = fresh
        }
    }

    /** Forgets the list (unenrol or another organisation). */
    public fun clear() {
        last = null
    }
}
