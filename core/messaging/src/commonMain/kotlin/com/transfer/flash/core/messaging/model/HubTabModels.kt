package com.transfer.flash.core.messaging.model

import com.transfer.flash.core.enterprise.OrgLease

/**
 * UI read models of the Hub tab (UI-058, Plan A4 H6). Everything here is immutable and already sorted / filtered by the
 * data layer: the screens never sort or filter a directory in composition and never see a hub client or a Room type.
 */

/** How a colleague appears in the list: a plain person, the head of a department, or an organisation administrator. */
public enum class HubPersonRole { PERSON, HEAD, ADMIN }

/**
 * How reachable a colleague is right now. [UNKNOWN] is the honest answer when the app has no live session and no hub
 * presence information: it is NOT "offline" (the colleague may be online elsewhere).
 */
public enum class HubReachability { LAN, HUB, OFFLINE, UNKNOWN }

/** Where the directory snapshot stands, for the status strip. */
public enum class HubSyncStatus {
    /** No successful directory sync has ever finished on this device. */
    NEVER,

    /** The last attempt succeeded (or an older success is still the latest outcome). */
    OK,

    /** The last attempt failed because the device or the hub was not reachable; cached content is still shown. */
    FAILED_OFFLINE,

    /** The last attempt failed for another reason (refused, hub error, storage). */
    FAILED_OTHER,

    /** The hub does not offer the directory sync (an older server). */
    UNSUPPORTED,
}

/** State of the whole tab: enrolment, organisation, trust lease, last sync. Cheap; derived, never stored. */
@Immutable
public data class HubUiState(
    /** False for an unenrolled device and after unenrol: every other field is then neutral. */
    public val enrolled: Boolean = false,
    /** True once the first directory read finished; false means "loading first time" (skeleton rows). */
    public val loaded: Boolean = false,
    public val orgName: String = "",
    public val myDeviceId: String = "",
    /** The enrolled department id of this device, or null. */
    public val myDepartmentId: String? = null,
    public val myRole: HubPersonRole = HubPersonRole.PERSON,
    /** Plan B trust lease; the screens feed it to `FlashHubStatusMath.orgTrustState`. */
    public val lease: OrgLease = OrgLease.None,
    public val identityMismatch: Boolean = false,
    public val clockWrong: Boolean = false,
    public val syncStatus: HubSyncStatus = HubSyncStatus.NEVER,
    /** Epoch ms of the last successful directory sync, or null when there never was one. */
    public val lastSyncedAtMs: Long? = null,
    /** True while a refresh started by [refreshHub] is running. */
    public val isRefreshing: Boolean = false,
    /** Colleagues in the snapshot that this device may see. */
    public val peopleCount: Int = 0,
    /** The last group fetch failed and no earlier result is cached: the Groups segment then says so instead of "no groups". */
    public val groupsLoadFailed: Boolean = false,
    /** The hub answered that it has no announcements feature (older server): the News segment says so. */
    public val newsUnsupported: Boolean = false,
)

/** One colleague. [online] is null when presence is withheld or unknown (the UI then shows NO dot, not a grey one). */
@Immutable
public data class HubPersonUi(
    public val deviceId: String,
    public val displayName: String,
    public val departmentId: String,
    /** Human department name, or an empty string when the snapshot has no such department. */
    public val departmentName: String,
    public val role: HubPersonRole,
    public val online: Boolean?,
    public val reachability: HubReachability,
    public val paired: Boolean,
    /** True when the snapshot statement matches the live session's key (Plan B binding). */
    public val orgVerified: Boolean,
    public val isSelf: Boolean,
)

/** One department the caller may see. */
@Immutable
public data class HubDepartmentUi(
    public val id: String,
    public val name: String,
    public val memberCount: Int,
    /** Listed groups of this department known from the last group refresh. */
    public val groupCount: Int,
    /** True for the caller's own department. */
    public val isMine: Boolean,
    public val headIds: List<String>,
    /** Display names of the heads that are in the snapshot, in [headIds] order. */
    public val headNames: List<String>,
    public val hidden: Boolean,
)

public enum class HubGroupMembership { JOINED, PENDING, NOT_JOINED }

/** One organisation group the caller may see (the content of the old "Browse department groups" sheet). */
@Immutable
public data class HubGroupUi(
    public val groupId: String,
    public val name: String,
    public val departmentName: String,
    public val memberCount: Int,
    public val onlineCount: Int?,
    public val requiresApproval: Boolean,
    public val membership: HubGroupMembership,
    /** Admins only: join requests waiting. */
    public val pendingRequests: Int?,
)
