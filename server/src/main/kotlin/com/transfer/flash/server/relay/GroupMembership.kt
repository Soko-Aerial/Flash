package com.transfer.flash.server.relay

import com.transfer.flash.server.directory.OrganizationDirectory

/**
 * The relay's read-only question to the directory: who belongs to a group (SEC-02, SEC-03, REL-14). The relay uses it
 * to refuse group `FLASH_META` / group `FLASH_RELAY` from a non-member and to limit recipients to members.
 */
fun interface GroupMembershipView {
    /** The device ids that belong to [groupId], or null when no such group exists. */
    fun membersOf(groupId: String): Set<String>?

    companion object {
        /** No groups at all: every group send is refused as `ERR_NOT_MEMBER`. */
        val None: GroupMembershipView = GroupMembershipView { null }
    }
}

/** [GroupMembershipView] over [OrganizationDirectory] (member device ids of the group charter). */
class DirectoryGroupMembership(private val directory: OrganizationDirectory) : GroupMembershipView {
    override fun membersOf(groupId: String): Set<String>? = directory.getGroup(groupId)?.memberDeviceIds?.toSet()
}
