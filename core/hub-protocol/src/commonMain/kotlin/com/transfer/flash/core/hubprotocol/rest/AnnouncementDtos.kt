package com.transfer.flash.core.hubprotocol.rest

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlinx.serialization.Serializable

/**
 * Announcement DTOs for the Flash Hub Announcements API (Plan A3, ADR-138).
 *
 * Announcements are stored in plain text on the hub.
 */
@FlashInternalApi
@Serializable
public data class AnnouncementItemDto(
    public val id: String,
    public val scope: String, // "org" | "department"
    public val departmentId: String? = null,
    public val title: String = "",
    public val body: String = "",
    public val authorKind: String = "device", // "device" | "console"
    public val authorRef: String = "",
    public val authorName: String = "",
    public val authorRole: String = "admin", // "admin" | "dept_admin"
    public val createdAt: Long = 0L,
    public val updatedAt: Long = 0L,
    public val edited: Boolean = false,
    public val pinned: Boolean = false,
    public val pinnedAt: Long? = null,
    public val expiresAt: Long? = null,
    public val deleted: Boolean = false,
    public val seq: Long = 0L,
    public val kind: String = "text",
    public val flags: Int = 0,
)

@FlashInternalApi
@Serializable
public data class AnnouncementFeedDto(
    public val epoch: String,
    public val seq: Long,
    public val fullResync: Boolean = false,
    public val items: List<AnnouncementItemDto> = emptyList(),
    public val nextCursor: String? = null,
)

@FlashInternalApi
@Serializable
public data class AnnouncementSearchResponseDto(
    public val items: List<AnnouncementItemDto> = emptyList(),
    public val total: Int = 0,
    public val nextBefore: Long? = null,
)

@FlashInternalApi
@Serializable
public data class PostAnnouncementRequestDto(
    public val scope: String, // "org" | "department"
    public val departmentId: String? = null,
    public val title: String = "",
    public val body: String = "",
    public val pinned: Boolean = false,
    public val expiresAtMs: Long? = null,
    public val clientRequestId: String = "",
)

@FlashInternalApi
@Serializable
public data class UpdateAnnouncementRequestDto(
    public val title: String = "",
    public val body: String = "",
    public val expectedSeq: Long = 0L,
    public val pinned: Boolean? = null,
    public val expiresAtMs: Long? = null,
)

@FlashInternalApi
@Serializable
public data class AnnouncementPreviewRequestDto(
    public val scope: String,
    public val departmentId: String? = null,
    public val title: String,
    public val body: String,
)

@FlashInternalApi
@Serializable
public data class AnnouncementPreviewResponseDto(
    public val title: String,
    public val body: String,
    public val titleCodePoints: Int,
    public val bodyCodePoints: Int,
    public val links: List<String> = emptyList(),
    public val audienceScope: String,
    public val departmentName: String? = null,
    public val estimatedRecipients: Int = 0,
    public val honestyNotice: String,
)

@FlashInternalApi
@Serializable
public data class AnnouncementLimitsDto(
    public val maxTitleChars: Int = 120,
    public val maxBodyChars: Int = 4000,
    public val maxPinsPerScope: Int = 3,
)
