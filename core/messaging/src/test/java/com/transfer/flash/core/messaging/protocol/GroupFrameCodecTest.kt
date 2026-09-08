package com.transfer.flash.core.messaging.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupFrameCodecTest {
    @Test
    fun create_roundTripsEscapedNameAndIndexedMembers() {
        val frame = GroupWireFrame.Create(
            groupId = "group-1",
            from = "creator",
            operationId = "op-1",
            membershipVersion = 4L,
            name = "Friends = 100%",
            memberIds = listOf("creator", "alex", "sam"),
        )

        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun unknownActionIsIgnoredForForwardCompatibility() {
        assertNull(GroupFrameCodec.decode("FLASH_GROUP action=future groupId=g from=a opId=o version=1"))
        assertNull(GroupFrameCodec.decode("FLASH_GSYNC op=future groupId=g syncId=s from=a keyEpoch=0"))
    }

    @Test
    fun stateFrameRoundTripsFullRosterWithPerMemberVersions() {
        val frame = GroupWireFrame.State(
            groupId = "g-1",
            from = "peer-a",
            operationId = "op-state",
            membershipVersion = 99L,
            name = "Design = Team",
            creatorId = "peer-a",
            members = listOf(
                GroupWireFrame.RosterEntry("peer-a", "Peer A", "owner", 10L, 10L, "op-create", true),
                GroupWireFrame.RosterEntry("peer-b", "Peer B", "member", 10L, 50L, "op-leave", false),
                GroupWireFrame.RosterEntry("peer-c", "Peer C", "member", 10L, 60L, "op-readd", true),
            ),
        )

        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun stateFrameRejectsOversizedRoster() {
        val encoded = GroupFrameCodec.encode(
            GroupWireFrame.State(
                groupId = "g-1", from = "peer-a", operationId = "op", membershipVersion = 1L,
                name = "Big", creatorId = "peer-a",
                members = (1..GroupPolicy.MAX_MEMBERS + 1).map { index ->
                    GroupWireFrame.RosterEntry("p$index", "P$index", "member", 0L, 0L, "op", true)
                },
            ),
        )
        assertNull(GroupFrameCodec.decode(encoded))
    }

    @Test
    fun syncClaimRoundTripsWithTierAndIds() {
        val frame = GroupWireFrame.SyncClaim(
            groupId = "g-1", syncId = "s-1", from = "peer-a",
            messageIds = listOf("m-1", "m-2", "m-3"), tier = GroupSyncTier.LOW,
        )
        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun syncRequestRoundTripsCursorAndBudgets() {
        val frame = GroupWireFrame.SyncRequest(
            groupId = "g-1", syncId = "s-1", from = "peer-a",
            sinceSentAt = 1234L, sinceMessageId = "m-9",
            tier = GroupSyncTier.LOW, maxPerSecond = 5, maxTotal = 100,
        )
        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun groupMediaFrameRoundTripsAllIdentityFields() {
        val frame = GroupWireFrame.GroupMedia(
            groupId = "g-1",
            messageId = "msg-1",
            transferId = "t-1",
            wireFileId = "wire-1",
            from = "peer-a",
            senderName = "Peer A",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 123456L,
            sentAt = 42L,
        )
        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun groupMessageRoundTripsReplyAndEpoch() {
        val frame = GroupWireFrame.Message(
            groupId = "g",
            messageId = "m",
            from = "a",
            senderName = "Alex",
            sentAt = 42L,
            text = "hello team",
            replyToId = "old",
            replyToPreview = "earlier text",
        )

        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun membershipVersionAllowsNewerReaddButRejectsStaleAdd() {
        val leave = GroupMembershipVersion(version = 8L, operationId = "leave")
        assertFalse(membershipUpdateWins(GroupMembershipVersion(7L, "add"), leave))
        assertTrue(membershipUpdateWins(GroupMembershipVersion(9L, "add-again"), leave))
    }

    @Test
    fun cursorUsesMessageIdWhenTimestampsMatch() {
        assertTrue(GroupSyncCursor(10L, "b") > GroupSyncCursor(10L, "a"))
        assertEquals(0, GroupSyncCursor(10L, "a").compareTo(GroupSyncCursor(10L, "a")))
    }

    @Test
    fun policyRequiresLocalMemberAndSixDeviceMaximum() {
        assertTrue(GroupPolicy.validMemberIds(listOf("self", "a", "b", "c", "d", "e"), "self"))
        assertFalse(GroupPolicy.validMemberIds(listOf("self", "a", "b", "c", "d", "e", "f"), "self"))
        assertFalse(GroupPolicy.validMemberIds(listOf("a", "b"), "self"))
    }
}
