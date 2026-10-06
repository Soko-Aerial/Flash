package com.transfer.flash.core.engine.group

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.messaging.model.FlashMemberRole
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.swarm.model.SwarmManifest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupFileSenderSwarmTest {

    private val testManifest = com.transfer.flash.core.swarm.model.ManifestBuilder(PieceMath.MIN_PIECE_SIZE).apply {
        addBlock(ByteArray(1024 * 1024) { 1 })
    }.build()
    private val testRootSig = "dummy-root-signature-base64"

    @Test
    fun `hybrid swarm and legacy push for mixed feature group`() = runTest {
        val announcedPeers = mutableMapOf<String, Int?>()
        val sendFileCalls = mutableListOf<String>()
        var attachmentRecorded = false

        val members = listOf(
            FlashGroupMemberUi(id = "self", name = "Me", initials = "M", role = FlashMemberRole.Owner),
            FlashGroupMemberUi(id = "peer-swarm", name = "Alice", initials = "A", role = FlashMemberRole.Member),
            FlashGroupMemberUi(id = "peer-legacy", name = "Bob", initials = "B", role = FlashMemberRole.Member),
        )

        var idGen = 1
        val sender = GroupFileSender(
            localDeviceId = { "self" },
            groupMembers = { members },
            deviceFor = { memberId, name ->
                FlashDevice(
                    id = FlashDeviceId(memberId),
                    friendlyName = name,
                    transportType = FlashTransportType.LAN,
                )
            },
            announce = { groupId, recipientId, msgId, xferId, wireId, name, mime, size, root, pieceSize, swarm, rootSig ->
                announcedPeers[recipientId] = swarm
                if (recipientId == "peer-swarm") {
                    assertEquals(1, swarm)
                    assertNotNull(root)
                    assertNotNull(pieceSize)
                    assertEquals(testRootSig, rootSig)
                } else {
                    assertNull(swarm)
                    assertNull(root)
                    assertNull(pieceSize)
                    assertNull(rootSig)
                }
                true
            },
            sendFile = { device, uri, name, size, xferId, wireId ->
                sendFileCalls.add(device.id.value)
            },
            sendGroupAttachment = { gId, mId, tId, name, mime, size, path, dur, amps ->
                attachmentRecorded = true
            },
            idFactory = { "id-${idGen++}" },
            isV2Group = { true },
            peerFeatures = { peerId ->
                if (peerId == "peer-swarm") setOf("sw1") else emptySet()
            },
            prepareSwarmOrigin = { gId, mId, name, mime, size, uri ->
                testManifest to testRootSig
            },
        )

        sender.send(
            groupId = "g2-group-123",
            uri = "file:///tmp/large.iso",
            displayName = "large.iso",
            sizeBytes = testManifest.totalSize,
            mimeType = "application/octet-stream",
        )

        assertEquals(mapOf("peer-swarm" to 1, "peer-legacy" to null), announcedPeers)
        // peer-swarm must NOT receive direct sendFile push
        // peer-legacy MUST receive direct sendFile push
        assertEquals(listOf("peer-legacy"), sendFileCalls)
        assertTrue(attachmentRecorded)
    }

    @Test
    fun `legacy group falls back to legacy push even with sw1 peers`() = runTest {
        val announcedPeers = mutableMapOf<String, Int?>()
        val sendFileCalls = mutableListOf<String>()

        val members = listOf(
            FlashGroupMemberUi(id = "self", name = "Me", initials = "M", role = FlashMemberRole.Owner),
            FlashGroupMemberUi(id = "peer-swarm", name = "Alice", initials = "A", role = FlashMemberRole.Member),
        )

        val sender = GroupFileSender(
            localDeviceId = { "self" },
            groupMembers = { members },
            deviceFor = { memberId, name ->
                FlashDevice(id = FlashDeviceId(memberId), friendlyName = name, transportType = FlashTransportType.LAN)
            },
            announce = { groupId, recipientId, msgId, xferId, wireId, name, mime, size, root, pieceSize, swarm, rootSig ->
                announcedPeers[recipientId] = swarm
                assertNull(swarm)
                true
            },
            sendFile = { device, uri, name, size, xferId, wireId ->
                sendFileCalls.add(device.id.value)
            },
            sendGroupAttachment = { _, _, _, _, _, _, _, _, _ -> },
            idFactory = { "id-1" },
            isV2Group = { false }, // Legacy group!
            peerFeatures = { setOf("sw1") },
            prepareSwarmOrigin = { _, _, _, _, _, _ -> testManifest to testRootSig },
        )

        sender.send(
            groupId = "g-legacy-1",
            uri = "file:///tmp/doc.pdf",
            displayName = "doc.pdf",
            sizeBytes = testManifest.totalSize,
            mimeType = "application/pdf",
        )

        assertEquals(mapOf<String, Int?>("peer-swarm" to null), announcedPeers)
        assertEquals(listOf("peer-swarm"), sendFileCalls)
    }

    @Test
    fun `voice note falls back to legacy push`() = runTest {
        val sendFileCalls = mutableListOf<String>()
        val sender = GroupFileSender(
            localDeviceId = { "self" },
            groupMembers = {
                listOf(
                    FlashGroupMemberUi(id = "self", name = "Me", initials = "M", role = FlashMemberRole.Owner),
                    FlashGroupMemberUi(id = "peer-1", name = "Alice", initials = "A", role = FlashMemberRole.Member),
                )
            },
            deviceFor = { memberId, name ->
                FlashDevice(id = FlashDeviceId(memberId), friendlyName = name, transportType = FlashTransportType.LAN)
            },
            announce = { _, _, _, _, _, _, _, _, _, _, swarm, _ ->
                assertNull(swarm)
                true
            },
            sendFile = { device, _, _, _, _, _ -> sendFileCalls.add(device.id.value) },
            sendGroupAttachment = { _, _, _, _, _, _, _, _, _ -> },
            idFactory = { "id-1" },
            isV2Group = { true },
            peerFeatures = { setOf("sw1") },
            prepareSwarmOrigin = { _, _, _, _, _, _ -> testManifest to testRootSig },
        )

        sender.send(
            groupId = "g2-1",
            uri = "file:///tmp/audio.m4a",
            displayName = "voice.m4a",
            sizeBytes = 32_000L,
            mimeType = "audio/m4a",
            voiceDurationMs = 5_000L, // voice note!
        )

        assertEquals(listOf("peer-1"), sendFileCalls)
    }
}
