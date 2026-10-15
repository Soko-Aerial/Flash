@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.announcements

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.hubprotocol.rest.AnnouncementFeedDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementSearchResponseDto
import com.transfer.flash.core.hubprotocol.rest.PostAnnouncementRequestDto
import com.transfer.flash.core.hubprotocol.rest.UpdateAnnouncementRequestDto

/**
 * Fake implementation of [HubAnnouncementsApi] for unit tests.
 */
@FlashInternalApi
public class FakeHubAnnouncementsApi : HubAnnouncementsApi {

    public val items: MutableMap<String, AnnouncementItemDto> = mutableMapOf()
    public var currentEpoch: String = "test-epoch"
    public var nextSeq: Long = 1L
    public var simulateOffline: Boolean = false
    public var simulateRateLimitSeconds: Int? = null
    public var simulateConflict: AnnouncementItemDto? = null
    public var simulateNotAllowed: Boolean = false
    public var simulateUnsupported: Boolean = false
    public var simulatePinLimit: Boolean = false

    override suspend fun fetchFeed(hubUrl: String, since: Long, limit: Int): FlashResult<AnnouncementFeedDto> {
        if (simulateOffline) return FlashResult.Failure(HubAnnouncementError.Offline.toFlashError())
        if (simulateUnsupported) return FlashResult.Failure(HubAnnouncementError.NotSupportedByHub.toFlashError())

        val feedItems = items.values
            .filter { it.seq > since }
            .sortedBy { it.seq }
            .take(limit)

        return FlashResult.Success(
            AnnouncementFeedDto(
                epoch = currentEpoch,
                seq = items.values.maxOfOrNull { it.seq } ?: 0L,
                items = feedItems,
                // Same as the server (AnnouncementStore.getChangesFeed): a full page names where the next one starts.
                nextCursor = if (feedItems.size == limit) feedItems.last().seq.toString() else null,
            ),
        )
    }

    override suspend fun search(
        hubUrl: String,
        query: String,
        departmentId: String?,
        limit: Int,
        before: Long?,
    ): FlashResult<AnnouncementSearchResponseDto> {
        if (simulateOffline) return FlashResult.Failure(HubAnnouncementError.Offline.toFlashError())
        if (simulateUnsupported) return FlashResult.Failure(HubAnnouncementError.NotSupportedByHub.toFlashError())

        val q = query.trim().lowercase()
        val results = items.values.filter {
            !it.deleted &&
                (it.title.lowercase().contains(q) || it.body.lowercase().contains(q)) &&
                (departmentId == null || it.departmentId == departmentId)
        }.take(limit)

        return FlashResult.Success(
            AnnouncementSearchResponseDto(
                items = results,
                total = results.size,
            ),
        )
    }

    override suspend fun getById(hubUrl: String, id: String): FlashResult<AnnouncementItemDto?> {
        if (simulateOffline) return FlashResult.Failure(HubAnnouncementError.Offline.toFlashError())
        val item = items[id]
        return if (item != null && !item.deleted) FlashResult.Success(item) else FlashResult.Success(null)
    }

    override suspend fun post(hubUrl: String, request: PostAnnouncementRequestDto): FlashResult<AnnouncementItemDto> {
        if (simulateOffline) return FlashResult.Failure(HubAnnouncementError.Offline.toFlashError())
        if (simulateNotAllowed) return FlashResult.Failure(HubAnnouncementError.NotAllowed.toFlashError())
        if (simulatePinLimit) return FlashResult.Failure(HubAnnouncementError.PinLimit.toFlashError())
        simulateRateLimitSeconds?.let { return FlashResult.Failure(HubAnnouncementError.RateLimited(it).toFlashError()) }

        val id = "ann-${nextSeq}"
        val seq = nextSeq++
        val item = AnnouncementItemDto(
            id = id,
            scope = request.scope,
            departmentId = request.departmentId,
            title = request.title,
            body = request.body,
            pinned = request.pinned,
            expiresAt = request.expiresAtMs,
            seq = seq,
            createdAt = 1000L * seq,
            updatedAt = 1000L * seq,
        )
        items[id] = item
        return FlashResult.Success(item)
    }

    override suspend fun update(
        hubUrl: String,
        id: String,
        request: UpdateAnnouncementRequestDto,
    ): FlashResult<AnnouncementItemDto> {
        if (simulateOffline) return FlashResult.Failure(HubAnnouncementError.Offline.toFlashError())
        if (simulateNotAllowed) return FlashResult.Failure(HubAnnouncementError.NotAllowed.toFlashError())
        simulateConflict?.let { return FlashResult.Failure(HubAnnouncementError.Conflict(it).toFlashError()) }

        val existing = items[id] ?: return FlashResult.Failure(HubAnnouncementError.Removed.toFlashError())
        if (request.expectedSeq > 0L && existing.seq != request.expectedSeq) {
            return FlashResult.Failure(HubAnnouncementError.Conflict(existing).toFlashError())
        }

        val seq = nextSeq++
        val updated = existing.copy(
            title = request.title.ifBlank { existing.title },
            body = request.body.ifBlank { existing.body },
            pinned = request.pinned ?: existing.pinned,
            expiresAt = request.expiresAtMs ?: existing.expiresAt,
            edited = true,
            seq = seq,
            updatedAt = 1000L * seq,
        )
        items[id] = updated
        return FlashResult.Success(updated)
    }

    override suspend fun delete(hubUrl: String, id: String): FlashResult<Unit> {
        if (simulateOffline) return FlashResult.Failure(HubAnnouncementError.Offline.toFlashError())
        if (simulateNotAllowed) return FlashResult.Failure(HubAnnouncementError.NotAllowed.toFlashError())

        val existing = items[id] ?: return FlashResult.Failure(HubAnnouncementError.Removed.toFlashError())
        val seq = nextSeq++
        items[id] = existing.copy(deleted = true, seq = seq, title = "", body = "")
        return FlashResult.Success(Unit)
    }

    override suspend fun setPinned(hubUrl: String, id: String, pinned: Boolean): FlashResult<Unit> {
        if (simulateOffline) return FlashResult.Failure(HubAnnouncementError.Offline.toFlashError())
        if (simulatePinLimit && pinned) return FlashResult.Failure(HubAnnouncementError.PinLimit.toFlashError())

        val existing = items[id] ?: return FlashResult.Failure(HubAnnouncementError.Removed.toFlashError())
        val seq = nextSeq++
        items[id] = existing.copy(pinned = pinned, seq = seq)
        return FlashResult.Success(Unit)
    }
}
