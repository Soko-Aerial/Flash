@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.announcements

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.hubprotocol.rest.AnnouncementFeedDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementSearchResponseDto
import com.transfer.flash.core.hubprotocol.rest.PostAnnouncementRequestDto
import com.transfer.flash.core.hubprotocol.rest.UpdateAnnouncementRequestDto

/**
 * Typed errors for organization announcements (Plan A3 §4.3 and §9).
 * Enables UI callers to display exact, user-facing sentences.
 * Plaintext on the hub (ADR-138): encryption is NEVER claimed.
 */
@FlashInternalApi
public sealed interface HubAnnouncementError {
    /** org_ann_not_allowed: "You can only post announcements for your own department." */
    public data object NotAllowed : HubAnnouncementError

    /** org_ann_too_long: "This announcement is too long. Shorten it to %d characters." */
    public data class TooLong(public val maxChars: Int) : HubAnnouncementError

    /** org_ann_rate: "You are posting too fast. Try again in %d seconds." */
    public data class RateLimited(public val retryAfterSec: Int) : HubAnnouncementError

    /** org_ann_conflict: "Someone changed this announcement while you were editing. Review their version." */
    public data class Conflict(public val current: AnnouncementItemDto?) : HubAnnouncementError

    /** org_ann_offline: "You are offline. Your draft is saved. Connect to the company network and try again." */
    public data object Offline : HubAnnouncementError

    /** org_ann_unsupported: "This company server does not support announcements yet." */
    public data object NotSupportedByHub : HubAnnouncementError

    /** org_ann_removed: "This announcement was removed." */
    public data object Removed : HubAnnouncementError

    /** org_ann_pin_limit: "You can pin up to 3 announcements here. Unpin one first." */
    public data object PinLimit : HubAnnouncementError

    /** Device or certificate is revoked on the hub. */
    public data object Revoked : HubAnnouncementError

    /** Server error. */
    public data class Server(public val message: String) : HubAnnouncementError

    public fun toFlashError(): FlashError = when (this) {
        Offline -> FlashError.NetworkUnavailable("You are offline. Your draft is saved. Connect to the company network and try again.")
        is TooLong -> FlashError.Domain("TOO_LONG", "This announcement is too long. Shorten it to $maxChars characters.", maxChars)
        is RateLimited -> FlashError.Domain("RATE_LIMITED", "You are posting too fast. Try again in $retryAfterSec seconds.", retryAfterSec)
        is Conflict -> FlashError.Domain("CONFLICT", "Someone changed this announcement while you were editing. Review their version.", current)
        NotAllowed -> FlashError.Domain("NOT_ALLOWED", "You can only post announcements for your own department.")
        NotSupportedByHub -> FlashError.Domain("UNSUPPORTED", "This company server does not support announcements yet.")
        Removed -> FlashError.Domain("REMOVED", "This announcement was removed.")
        PinLimit -> FlashError.Domain("PIN_LIMIT", "You can pin up to 3 announcements here. Unpin one first.")
        Revoked -> FlashError.Domain("REVOKED", "The hub refused this device's sign-in.")
        is Server -> FlashError.Domain("SERVER", message)
    }

    public companion object {
        public fun fromFlashError(error: FlashError): HubAnnouncementError = when (error) {
            is FlashError.NetworkUnavailable -> Offline
            is FlashError.Domain -> when (error.code) {
                "TOO_LONG" -> TooLong(error.details as? Int ?: 4000)
                "RATE_LIMITED" -> RateLimited(error.details as? Int ?: 5)
                "CONFLICT" -> Conflict(error.details as? AnnouncementItemDto)
                "NOT_ALLOWED" -> NotAllowed
                "UNSUPPORTED" -> NotSupportedByHub
                "REMOVED" -> Removed
                "PIN_LIMIT" -> PinLimit
                "REVOKED" -> Revoked
                else -> Server(error.message)
            }
            else -> Server(error.toString())
        }
    }
}

/**
 * Client interface for interacting with Flash Hub Announcements REST endpoints (Plan A3 §4.1).
 */
@FlashInternalApi
public interface HubAnnouncementsApi {

    /**
     * Fetches announcement changes feed (`GET /api/v1/announcements?since=<since>&limit=<limit>`).
     */
    public suspend fun fetchFeed(
        hubUrl: String,
        since: Long,
        limit: Int = 100,
    ): FlashResult<AnnouncementFeedDto>

    /**
     * Searches announcements (`GET /api/v1/announcements?q=<query>&departmentId=<departmentId>&limit=<limit>&before=<before>`).
     */
    public suspend fun search(
        hubUrl: String,
        query: String,
        departmentId: String? = null,
        limit: Int = 50,
        before: Long? = null,
    ): FlashResult<AnnouncementSearchResponseDto>

    /**
     * Gets a single announcement by ID (`GET /api/v1/announcements/{id}`).
     */
    public suspend fun getById(
        hubUrl: String,
        id: String,
    ): FlashResult<AnnouncementItemDto?>

    /**
     * Posts a new announcement (`POST /api/v1/announcements`).
     */
    public suspend fun post(
        hubUrl: String,
        request: PostAnnouncementRequestDto,
    ): FlashResult<AnnouncementItemDto>

    /**
     * Updates an announcement (`PUT /api/v1/announcements/{id}`).
     */
    public suspend fun update(
        hubUrl: String,
        id: String,
        request: UpdateAnnouncementRequestDto,
    ): FlashResult<AnnouncementItemDto>

    /**
     * Deletes an announcement (`DELETE /api/v1/announcements/{id}`).
     */
    public suspend fun delete(
        hubUrl: String,
        id: String,
    ): FlashResult<Unit>

    /**
     * Pins or unpins an announcement (`POST /api/v1/announcements/{id}/pin` or `unpin`).
     */
    public suspend fun setPinned(
        hubUrl: String,
        id: String,
        pinned: Boolean,
    ): FlashResult<Unit>
}
