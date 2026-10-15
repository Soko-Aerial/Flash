package com.transfer.flash.core.messaging.model

import com.transfer.flash.core.enterprise.OrgLease

/** Fixed sample content for previews, [com.transfer.flash.core.messaging.SampleFlashChatRepository] and tests of screens. Never shown in a release. */
public object HubSampleData {
    public val state: HubUiState = HubUiState(
        enrolled = true,
        loaded = true,
        orgName = "Acme Corp",
        myDeviceId = "dev-self",
        myDepartmentId = "dept-eng",
        lease = OrgLease.Valid(untilMs = 1_900_000_000_000L),
        syncStatus = HubSyncStatus.OK,
        lastSyncedAtMs = 1_800_000_000_000L,
        peopleCount = 5,
    )

    public val people: List<HubPersonUi> = listOf(
        HubPersonUi("dev-amara", "Amara Okafor", "dept-eng", "Engineering", HubPersonRole.HEAD, true, HubReachability.LAN, paired = true, orgVerified = true, isSelf = false),
        HubPersonUi("dev-ben", "Ben Hartley", "dept-fin", "Finance", HubPersonRole.PERSON, false, HubReachability.OFFLINE, paired = false, orgVerified = false, isSelf = false),
        HubPersonUi("dev-chen", "Chen Wei", "dept-eng", "Engineering", HubPersonRole.PERSON, null, HubReachability.UNKNOWN, paired = false, orgVerified = false, isSelf = false),
        HubPersonUi("dev-dana", "Dana Kruger", "dept-hr", "People", HubPersonRole.ADMIN, true, HubReachability.HUB, paired = false, orgVerified = false, isSelf = false),
        HubPersonUi("dev-self", "You", "dept-eng", "Engineering", HubPersonRole.PERSON, null, HubReachability.UNKNOWN, paired = false, orgVerified = false, isSelf = true),
    )

    public val departments: List<HubDepartmentUi> = listOf(
        HubDepartmentUi("dept-eng", "Engineering", 12, 2, true, listOf("dev-amara"), listOf("Amara Okafor"), false),
        HubDepartmentUi("dept-fin", "Finance", 6, 1, false, emptyList(), emptyList(), false),
    )

    public val groups: List<HubGroupUi> = listOf(
        HubGroupUi("g-eng", "Engineering all-hands", "Engineering", 12, 4, false, HubGroupMembership.JOINED, null),
        HubGroupUi("g-fin", "Finance close", "Finance", 6, null, true, HubGroupMembership.NOT_JOINED, null),
    )
}
