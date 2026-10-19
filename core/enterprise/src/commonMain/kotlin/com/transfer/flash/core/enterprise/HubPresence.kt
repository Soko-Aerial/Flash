@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.CancellationException

/**
 * **Hub presence** — who of this device's colleagues currently holds a live session on the organisation hub.
 *
 * The hub already knows this: its own relay-connection pool answers `isOnline`, and the directory serves it as
 * `PersonDto.online` on the org-wide people listing (`GET /api/v1/directory/people`, `docs/protocol.md`). Nothing in a
 * client ever asked for it, so a colleague reached **over the hub** used to show Offline in the chat header while the
 * messages arrived normally (`logs/handoff.md`: "hub presence not surfaced").
 *
 * This is the read side of that listing: one call per poll, [MAX_PAGES] pages at most, and the ids the hub reports
 * online. It is deliberately *not* persisted and *not* cached across polls — presence is ephemeral, and the hub is the
 * only authority for it. A failed round returns null, which means "we learned nothing this time", never "nobody is
 * online": the caller keeps its previous answer instead of blinking every colleague offline.
 *
 * Only devices the caller may already see are in the listing (`DirectoryVisibility.canListMember`): a department-less
 * colleague or one in a non-hidden department is listed; an outsider never learns that a hidden department's member is
 * online (`presenceVisible`).
 */
@FlashInternalApi
public suspend fun hubOnlineDeviceIds(
    api: DirectoryPrivacyApi,
    hubUrl: String,
    maxPages: Int = HubPresenceDefaults.MAX_PAGES,
    pageSize: Int = HubPresenceDefaults.PAGE_SIZE,
): Set<String>? {
    if (hubUrl.isBlank()) return null
    val online = HashSet<String>()
    var cursor: String? = null
    var page = 0
    while (page < maxPages.coerceAtLeast(1)) {
        val result = try {
            api.fetchPeople(hubUrl, cursor, pageSize.coerceIn(1, HubPresenceDefaults.MAX_PAGE_SIZE))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Transport failure: no conclusion about anybody's presence this round.
            return null
        }
        val body = when (result) {
            is FlashResult.Success -> result.value
            is FlashResult.Failure -> return null
        }
        // A hub that predates Plan A2 has no people listing: hub presence stays unavailable rather than empty.
        if (body.hubTooOld) return null
        body.people.forEach { person -> if (person.online) online += person.deviceId }
        val next = body.nextCursor
        if (next.isNullOrBlank()) return online
        cursor = next
        page++
    }
    // More people than we were willing to page through: report what we saw and let the next poll continue.
    return online
}

/** Numbers the presence poll shares between the hub client and its hosts. */
@FlashInternalApi
public object HubPresenceDefaults {
    /**
     * How often the hub is asked who is online. The listing has its own budget (burst 30, then 1 per 2 s per device —
     * `HubRateLimits.directoryPeople`), so this must stay far above two seconds.
     */
    public const val POLL_MS: Long = 15_000L

    /** The largest page the hub serves on the people listing. */
    public const val MAX_PAGE_SIZE: Int = 500

    public const val PAGE_SIZE: Int = MAX_PAGE_SIZE

    /**
     * Pages read per poll. 4 x 500 = the first 2,000 people of the organisation; past that the poll reports the ids it
     * saw. Raise it only with a look at the hub's per-device budget.
     */
    public const val MAX_PAGES: Int = 4
}
