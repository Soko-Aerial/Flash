@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.engine.MagicFrameRouter
import com.transfer.flash.core.engine.group.GroupFileSender
import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.messaging.model.FlashMemberRole
import com.transfer.flash.core.network.ws.formatHelloFeatures
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves zero-change behavior when swarm is not attached (§7A SW-8 Task 13).
 *
 * Verifies:
 * 1. HELLO frame advertises no caps (formatHelloFeatures returns null when localFeatures is empty).
 * 2. Inbound FSW1 frames are dropped when swarm is not attached (unregistered in MagicFrameRouter).
 * 3. GroupFileSender performs the legacy announce + direct sendFile push when swarm is not attached.
 */
class ZeroChangeProofTest {

    @Test
    fun `without attachSwarm HELLO advertises no caps`() {
        val features = emptySet<String>()
        val capsHeader = formatHelloFeatures(features)
        assertNull(capsHeader, "HELLO caps must be omitted when no features/swarm are attached")
    }

    @Test
    fun `without attachSwarm FSW1 binary frames are dropped by MagicFrameRouter`() {
        val router = MagicFrameRouter()
        // Synthesize an FSW1 frame (magic = "FSW1")
        val fsw1Frame = byteArrayOf(
            'F'.code.toByte(),
            'S'.code.toByte(),
            'W'.code.toByte(),
            '1'.code.toByte(),
            0x01, 0x00, 0x00, 0x00, // type / dummy payload
        )

        var replyCalled = false
        val consumed = router.dispatch("peer-123", fsw1Frame) {
            replyCalled = true
            true
        }

        assertTrue(consumed, "FSW1 frame must be consumed/dropped by MagicFrameRouter so it does not reach legacy pipeline")
        assertFalse(replyCalled, "Reply must not be invoked for unhandled FSW1 frames")
    }

    @Test
    fun `without attachSwarm group file sender executes legacy announce and direct push`() = runTest {
        val announceCalls = mutableListOf<String>()
        val sendFileCalls = mutableListOf<String>()
        var attachmentRecorded = false

        val members = listOf(
            FlashGroupMemberUi(id = "self", name = "Me", initials = "M", role = FlashMemberRole.Owner),
            FlashGroupMemberUi(id = "peer-1", name = "Alice", initials = "A", role = FlashMemberRole.Member),
            FlashGroupMemberUi(id = "peer-2", name = "Bob", initials = "B", role = FlashMemberRole.Member),
        )

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
                // Without swarm attached, swarm arguments must all be null
                assertNull(root, "root must be null without swarm")
                assertNull(pieceSize, "pieceSize must be null without swarm")
                assertNull(swarm, "swarm flag must be null without swarm")
                assertNull(rootSig, "rootSig must be null without swarm")
                announceCalls.add(recipientId)
                true
            },
            sendFile = { device, uri, name, size, xferId, wireId ->
                sendFileCalls.add(device.id.value)
            },
            sendGroupAttachment = { gId, mId, tId, name, mime, size, path, dur, amps ->
                attachmentRecorded = true
            },
            idFactory = { "id-${announceCalls.size + 1}" },
            isV2Group = null, // not a v2 group with swarm
            peerFeatures = null,
            prepareSwarmOrigin = null, // no swarm origin preparation
        )

        sender.send(
            groupId = "group-1",
            uri = "file:///tmp/sample.bin",
            displayName = "sample.bin",
            sizeBytes = 1024L * 1024L,
            mimeType = "application/octet-stream",
        )

        assertEquals(listOf("peer-1", "peer-2"), announceCalls, "Must announce to all other members")
        assertEquals(listOf("peer-1", "peer-2"), sendFileCalls, "Must push file to all announced members via legacy sendFile")
        assertTrue(attachmentRecorded, "Must record sender attachment bubble")
    }
}
