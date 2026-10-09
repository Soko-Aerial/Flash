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
    fun syncRequestWithoutTheAdr100KeysIsByteIdenticalToTheOldWireShape() {
        val legacy = GroupWireFrame.SyncRequest(
            groupId = "g-1", syncId = "s-1", from = "peer-a",
            sinceSentAt = 1234L, sinceMessageId = "m-9",
            tier = GroupSyncTier.LOW, maxPerSecond = 5, maxTotal = 100,
        )
        val encoded = GroupFrameCodec.encode(legacy)
        assertFalse(encoded.contains("windowMs"))
        assertFalse(encoded.contains("files="))
        assertFalse(encoded.contains("cont="))
        val decoded = GroupFrameCodec.decode(encoded) as GroupWireFrame.SyncRequest
        assertNull(decoded.windowMs)
        assertNull(decoded.includeFiles)
        assertFalse(decoded.continuation)
    }

    @Test
    fun syncRequestRoundTripsTheWindowFilesAndContinuation() {
        val frame = GroupWireFrame.SyncRequest(
            groupId = "g-1", syncId = "s-1", from = "peer-a",
            sinceSentAt = 1234L, sinceMessageId = "m-9",
            tier = GroupSyncTier.HIGH, maxPerSecond = 20, maxTotal = 500,
            windowMs = 30L * 24L * 60L * 60L * 1000L, includeFiles = false, continuation = true,
        )
        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
        val all = frame.copy(windowMs = Long.MAX_VALUE)
        assertEquals(all, GroupFrameCodec.decode(GroupFrameCodec.encode(all)))
    }

    @Test
    fun anOldBuildStyleRequestWithUnknownKeysStillDecodes_andANegativeWindowIsIgnored() {
        val frame = GroupWireFrame.SyncRequest(
            groupId = "g-1", syncId = "s-1", from = "peer-a", sinceSentAt = 1L, sinceMessageId = "m",
            tier = GroupSyncTier.LOW, maxPerSecond = 5, maxTotal = 100, windowMs = 5L,
        )
        val negative = GroupFrameCodec.encode(frame).replace("windowMs=5", "windowMs=-5")
        assertNull((GroupFrameCodec.decode(negative) as GroupWireFrame.SyncRequest).windowMs)
    }

    @Test
    fun syncPageRoundTripsAndRefusesNegativeCounts() {
        val frame = GroupWireFrame.SyncPage(
            groupId = "g-1", syncId = "s-1", from = "peer-a", count = 100, remaining = 1850, more = true,
            lastSentAt = 1_700_000_000_000L, lastMessageId = "m-100", keyEpoch = 0L,
        )
        val encoded = GroupFrameCodec.encode(frame)
        assertEquals(frame, GroupFrameCodec.decode(encoded))
        assertNull(GroupFrameCodec.decode(encoded.replace("count=100", "count=-1")))
        assertNull(GroupFrameCodec.decode(encoded.replace("remaining=1850", "remaining=-3")))
        assertNull(GroupFrameCodec.decode(encoded.replace("more=true", "more=maybe")))
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

    private fun swarmPush(offer: GroupWireFrame.SwarmOffer?) = GroupWireFrame.SyncPush(
        groupId = "g2-crew", syncId = "s-1", from = "peer-b",
        message = GroupWireFrame.Message(
            groupId = "g2-crew", messageId = "m-1", from = "peer-a", senderName = "Peer A", sentAt = 42L, text = "",
            signature = "sig-abc", swarmOffer = offer,
        ),
    )

    @Test
    fun syncPushRoundTripsTheSwarmOfferOfAFileMessage() {
        val offer = GroupWireFrame.SwarmOffer("my file (1).bin", "application/octet-stream", 20_000_000L, "ab".repeat(32), 1_048_576, "rsig-xyz")
        val frame = swarmPush(offer)
        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun syncPushWithoutAnOfferStaysAsItWas() {
        val frame = swarmPush(null)
        val encoded = GroupFrameCodec.encode(frame)
        assertEquals(frame, GroupFrameCodec.decode(encoded))
        assertFalse(encoded.contains("aroot="), "a plain push carries no file keys: $encoded")
    }

    @Test
    fun anOfferWhoseRootIsNotSixtyFourLowercaseHexIsNotAnOffer() {
        for (badRoot in listOf("zz", "ab".repeat(31), "AB".repeat(32), "../../etc/passwd")) {
            val encoded = GroupFrameCodec.encode(
                swarmPush(GroupWireFrame.SwarmOffer("f.bin", "application/octet-stream", 10L, badRoot, 65_536, "rsig")),
            )
            val decoded = GroupFrameCodec.decode(encoded) as GroupWireFrame.SyncPush
            assertNull(decoded.message.swarmOffer, "root '$badRoot'")
            assertEquals("m-1", decoded.message.messageId)
        }
    }

    @Test
    fun aMediaFrameWithAMalformedSwarmRootCarriesNoRoot() {
        val media = GroupWireFrame.GroupMedia(
            groupId = "g2-crew", messageId = "m-9", transferId = "t-9", wireFileId = "w-9", from = "peer-a",
            senderName = "Peer A", fileName = "f.bin", mimeType = "application/octet-stream", sizeBytes = 10L, sentAt = 42L,
            signature = "sig", root = "zz", pieceSize = 65_536, swarm = 1, rootSig = "rsig",
        )
        val decoded = GroupFrameCodec.decode(GroupFrameCodec.encode(media)) as GroupWireFrame.GroupMedia
        assertNull(decoded.root)
        val ok = media.copy(root = "ab".repeat(32))
        assertEquals("ab".repeat(32), (GroupFrameCodec.decode(GroupFrameCodec.encode(ok)) as GroupWireFrame.GroupMedia).root)
    }

    @Test
    fun aHalfDescribedOfferIsNotAnOffer() {
        val encoded = GroupFrameCodec.encode(
            swarmPush(GroupWireFrame.SwarmOffer("f.bin", "application/octet-stream", 10L, "ab".repeat(32), 65_536, "rsig")),
        )
        val withoutSig = encoded.split(' ').filterNot { it.startsWith("arsig=") }.joinToString(" ")
        val decoded = GroupFrameCodec.decode(withoutSig) as GroupWireFrame.SyncPush
        assertNull(decoded.message.swarmOffer)
        assertEquals("m-1", decoded.message.messageId, "the message itself still arrives")
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
