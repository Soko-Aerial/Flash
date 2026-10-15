@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.messaging.model

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.text.foldForSearch
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.persistence.db.entity.HubAnnouncementEntity

/**
 * Organization announcement for display in UI (Plan A3 §4.3).
 * Plaintext on the hub (ADR-138): encryption is NEVER claimed.
 */
@Immutable
public data class HubAnnouncementUi(
    public val id: String,
    public val scope: String, // "org" | "department"
    public val departmentId: String? = null,
    public val title: String,
    public val body: String,
    public val authorName: String,
    public val authorRole: String,
    public val createdAtMs: Long,
    public val updatedAtMs: Long,
    public val edited: Boolean,
    public val pinned: Boolean,
    public val expiresAtMs: Long? = null,
    public val seq: Long,
    public val isRead: Boolean,
)

/**
 * Draft announcement prepared by an authorized poster (Plan A3 §4.3).
 */
public data class HubAnnouncementDraft(
    public val scope: String, // "org" | "department"
    public val departmentId: String? = null,
    public val title: String,
    public val body: String,
    public val pinned: Boolean = false,
    public val expiresAtMs: Long? = null,
)

/**
 * Dynamic posting capabilities of the current device in the enrolled organization (Plan A3 §4.3).
 */
public sealed interface HubPostingRights {
    /** Regular employee or unenrolled device: cannot post. */
    public data object None : HubPostingRights

    /** Organization administrator: can post company-wide or to any department. */
    public data class OrgWide(public val allDepartments: List<String> = emptyList()) : HubPostingRights

    /** Department head: can post only to own assigned department(s). */
    public data class Department(public val departmentIds: List<String>) : HubPostingRights
}

public fun HubAnnouncementEntity.toUi(): HubAnnouncementUi = HubAnnouncementUi(
    id = id,
    scope = scope,
    departmentId = departmentId,
    title = title,
    body = body,
    authorName = authorName,
    authorRole = authorRole,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    edited = edited,
    pinned = pinned,
    expiresAtMs = expiresAtMs,
    seq = seq,
    isRead = readAtMs != null,
)

public fun AnnouncementItemDto.toEntity(existingReadAtMs: Long? = null): HubAnnouncementEntity = HubAnnouncementEntity(
    id = id,
    scope = scope,
    departmentId = departmentId,
    title = title,
    body = body,
    authorName = authorName,
    authorRole = authorRole,
    createdAtMs = createdAt,
    updatedAtMs = updatedAt,
    edited = edited,
    pinned = pinned,
    expiresAtMs = expiresAt,
    seq = seq,
    readAtMs = existingReadAtMs,
    searchFolded = foldForSearch("$title $body $authorName"),
)

public fun AnnouncementItemDto.toUi(isRead: Boolean = false): HubAnnouncementUi = HubAnnouncementUi(
    id = id,
    scope = scope,
    departmentId = departmentId,
    title = title,
    body = body,
    authorName = authorName,
    authorRole = authorRole,
    createdAtMs = createdAt,
    updatedAtMs = updatedAt,
    edited = edited,
    pinned = pinned,
    expiresAtMs = expiresAt,
    seq = seq,
    isRead = isRead,
)
