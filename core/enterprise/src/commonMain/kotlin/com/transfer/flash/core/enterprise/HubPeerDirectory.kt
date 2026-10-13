@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.time.SystemTimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the device may conclude about a hub-stamped sender id. */
@FlashInternalApi
public enum class HubPeerStanding {
    /** The authenticated directory lists this device. */
    MEMBER,

    /** The directory was read and does NOT list this device. */
    UNKNOWN,

    /** The directory could not be read (offline, signed out); no conclusion is possible yet. */
    UNAVAILABLE,
}

/**
 * A short-lived cache of the organisation's authenticated member directory (CLI-14, CLI-05).
 *
 * The hub stamps every delivered message with the sender's authenticated device id, but that alone does not make the
 * sender someone this device wants messages from: a hub-only 1:1 message is accepted only from a device the directory
 * lists (or one the user paired with, which the caller checks first). The same cache supplies the sender's certified
 * key and sealing key.
 *
 * A miss triggers one refresh (at most one per [minRefreshMs]), so a new colleague is recognised promptly without a
 * hub round trip per message; an unavailable directory yields [HubPeerStanding.UNAVAILABLE], never a guess.
 */
@FlashInternalApi
public class HubPeerDirectory(
    private val directory: HubDirectoryApi,
    private val hubUrl: () -> String?,
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val minRefreshMs: Long = DEFAULT_MIN_REFRESH_MS,
) {
    private val lock = Mutex()
    private var members: Map<String, EnterpriseDirectoryMember> = emptyMap()
    private var loadedAtMs: Long = Long.MIN_VALUE
    private var lastAttemptMs: Long = Long.MIN_VALUE

    /** The member entry for [deviceId], reading the directory when the cache is stale or does not know the id. */
    public suspend fun lookup(deviceId: String): EnterpriseDirectoryMember? = lock.withLock {
        val now = clock()
        val fresh = loadedAtMs != Long.MIN_VALUE && now - loadedAtMs < ttlMs
        members[deviceId]?.let { if (fresh) return@withLock it }
        refreshLocked(now)
        members[deviceId]
    }

    /** True when the directory lists [deviceId]; see [HubPeerStanding]. */
    public suspend fun standing(deviceId: String): HubPeerStanding {
        val found = lookup(deviceId)
        if (found != null) return HubPeerStanding.MEMBER
        return if (loadedAtMs == Long.MIN_VALUE) HubPeerStanding.UNAVAILABLE else HubPeerStanding.UNKNOWN
    }

    /** Drops the cache (after enrol, unenrol or a revocation) so the next lookup reads the directory again. */
    public suspend fun invalidate(): Unit = lock.withLock {
        members = emptyMap()
        loadedAtMs = Long.MIN_VALUE
        lastAttemptMs = Long.MIN_VALUE
    }

    private suspend fun refreshLocked(now: Long) {
        if (lastAttemptMs != Long.MIN_VALUE && now - lastAttemptMs < minRefreshMs) return
        lastAttemptMs = now
        val url = hubUrl() ?: return
        // A blank department asks for every member in the caller's scope.
        val result = directory.fetchDepartmentMembers(url, "")
        if (result is FlashResult.Success) {
            members = result.value.associateBy { it.deviceId }
            loadedAtMs = now
        }
    }

    public companion object {
        public const val DEFAULT_TTL_MS: Long = 10L * 60L * 1000L
        public const val DEFAULT_MIN_REFRESH_MS: Long = 30L * 1000L
    }
}
