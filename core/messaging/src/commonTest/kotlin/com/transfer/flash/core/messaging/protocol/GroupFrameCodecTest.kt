package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun groupMediaFrameRoundTripsSignatureInV2() {
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
            signature = "sig-abc-123",
        )
        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun deleteForEveryoneRoundTripsAsDistinctGroupAction() {
        val frame = GroupWireFrame.DeleteForEveryone(
            groupId = "g-1",
            messageId = "m-1",
            from = "peer-a",
        )
        val encoded = GroupFrameCodec.encode(frame)

        assertTrue(encoded.startsWith("FLASH_GACT action=delete "))
        assertEquals(frame, GroupFrameCodec.decode(encoded))
        assertNull(GroupFrameCodec.decode("FLASH_GACT action=future groupId=g msgId=m from=a"))
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

    @Test
    fun gmem_helloRoundTrips() {
        val nonce = ByteArray(16) { (it + 1).toByte() }
        val hello = GroupWireFrame.GsHello(
            groupId = "g2-crew",
            from = "device-a",
            epoch = 42L,
            nonce = nonce,
        )
        val encoded = GroupFrameCodec.encode(hello)
        assertTrue(encoded.startsWith("FLASH_GMEM op=hello"))
        assertEquals(hello, GroupFrameCodec.decode(encoded))
    }

    @Test
    fun gmem_challengeRoundTrips() {
        val nonce = ByteArray(16) { (it + 10).toByte() }
        val mac = ByteArray(32) { (it + 20).toByte() }
        val challenge = GroupWireFrame.GsChallenge(
            groupId = "g2-crew",
            from = "device-b",
            epoch = 1L,
            nonce = nonce,
            mac = mac,
        )
        val encoded = GroupFrameCodec.encode(challenge)
        assertTrue(encoded.startsWith("FLASH_GMEM op=challenge"))
        assertEquals(challenge, GroupFrameCodec.decode(encoded))
    }

    @Test
    fun gmem_proofRoundTrips() {
        val mac = ByteArray(32) { (it + 30).toByte() }
        val proof = GroupWireFrame.GsProof(
            groupId = "g2-crew",
            from = "device-a",
            epoch = 2L,
            mac = mac,
        )
        val encoded = GroupFrameCodec.encode(proof)
        assertTrue(encoded.startsWith("FLASH_GMEM op=proof"))
        assertEquals(proof, GroupFrameCodec.decode(encoded))
    }

    @Test
    fun gmem_resultRoundTripsAllReasons() {
        listOf("ok" to true, "failed" to false, "stale" to false).forEach { (reason, ok) ->
            val result = GroupWireFrame.GsResult(
                groupId = "g2-crew",
                from = "device-b",
                ok = ok,
                reason = reason,
            )
            val encoded = GroupFrameCodec.encode(result)
            assertTrue(encoded.startsWith("FLASH_GMEM op=result"))
            assertEquals(result, GroupFrameCodec.decode(encoded))
        }
    }

    @Test
    fun gmem_hostileInputsRejectedSafely() {
        // Unknown op
        assertNull(GroupFrameCodec.decode("FLASH_GMEM op=unknown groupId=g2-crew from=dev1"))
        // Legacy group id (must be g2-)
        assertNull(GroupFrameCodec.decode("FLASH_GMEM op=hello groupId=legacy-crew from=dev1 epoch=1 nonce=AQIDBAUGBwgJCgsMDQ4PEA=="))
        // Nonce wrong size (not 16 bytes)
        assertNull(GroupFrameCodec.decode("FLASH_GMEM op=hello groupId=g2-crew from=dev1 epoch=1 nonce=AQID"))
        // MAC wrong size (not 32 bytes)
        assertNull(GroupFrameCodec.decode("FLASH_GMEM op=proof groupId=g2-crew from=dev1 epoch=1 mac=AQID"))
        // Malformed base64
        assertNull(GroupFrameCodec.decode("FLASH_GMEM op=proof groupId=g2-crew from=dev1 epoch=1 mac=NOT_VALID_BASE64!"))
        // Invalid epoch <= 0
        assertNull(GroupFrameCodec.decode("FLASH_GMEM op=hello groupId=g2-crew from=dev1 epoch=0 nonce=AQIDBAUGBwgJCgsMDQ4PEA=="))
        // Unknown reason
        assertNull(GroupFrameCodec.decode("FLASH_GMEM op=result groupId=g2-crew from=dev1 ok=true reason=bogus"))
    }
}
