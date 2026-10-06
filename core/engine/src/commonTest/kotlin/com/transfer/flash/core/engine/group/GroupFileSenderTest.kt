package com.transfer.flash.core.engine.group

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.messaging.model.FlashMemberRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupFileSenderTest {

    private data class AnnounceCall(
        val groupId: String,
        val recipientDeviceId: String,
        val messageId: String,
        val transferId: String,
        val wireFileId: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    private data class SendFileCall(
        val targetDeviceId: String,
        val uri: String,
        val displayName: String,
        val sizeBytes: Long,
        val transferId: String,
        val wireFileId: String,
    )

    private data class SendGroupAttachmentCall(
        val groupId: String,
        val messageId: String,
        val transferId: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val localPath: String,
        val voiceDurationMs: Long,
        val voiceAmplitudes: List<Int>,
    )

    @Test
    fun `group file sender announces to all non-self members and sends to announced members`() = runTest {
        val events = mutableListOf<String>()
        val announceCalls = mutableListOf<AnnounceCall>()
        val sendFileCalls = mutableListOf<SendFileCall>()
        val sendGroupAttachmentCalls = mutableListOf<SendGroupAttachmentCall>()

        val members = listOf(
            FlashGroupMemberUi(id = "self", name = "Me", initials = "M", role = FlashMemberRole.Owner),
            FlashGroupMemberUi(id = "peer-1", name = "Alice", initials = "A", role = FlashMemberRole.Member),
            FlashGroupMemberUi(id = "peer-2", name = "Bob", initials = "B", role = FlashMemberRole.Member),
            FlashGroupMemberUi(id = "peer-3", name = "Charlie", initials = "C", role = FlashMemberRole.Member),
        )

        var idCounter = 100
        val idFactory = { "id-${idCounter++}" }

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
            announce = { groupId, recipientId, msgId, xferId, wireId, name, mime, size ->
                events.add("announce-$recipientId")
                announceCalls.add(
                    AnnounceCall(groupId, recipientId, msgId, xferId, wireId, name, mime, size)
                )
                // Peer 2 fails announcement (e.g. untrusted or failed)
                recipientId != "peer-2"
            },
            sendFile = { device, uri, name, size, xferId, wireId ->
                events.add("send-${device.id.value}")
                sendFileCalls.add(
                    SendFileCall(device.id.value, uri, name, size, xferId, wireId)
                )
            },
            sendGroupAttachment = { gId, mId, tId, name, mime, size, path, dur, amps ->
                events.add("attachment")
                sendGroupAttachmentCalls.add(
                    SendGroupAttachmentCall(gId, mId, tId, name, mime, size, path, dur, amps)
                )
            },
            idFactory = idFactory,
        )

        sender.send(
            groupId = "group-123",
            uri = "content://file/1",
            displayName = "photo.jpg",
            sizeBytes = 2048L,
            mimeType = "image/jpeg",
        )

        // 1. Assert beginGroupAttachment was called 3 times (self excluded)
        assertEquals(3, announceCalls.size)
        assertEquals(listOf("peer-1", "peer-2", "peer-3"), announceCalls.map { it.recipientDeviceId })

        // 2. All announce calls share the same messageId and wireFileId
        val sharedMessageId = announceCalls.first().messageId
        val sharedWireFileId = announceCalls.first().wireFileId
        assertTrue(announceCalls.all { it.messageId == sharedMessageId })
        assertTrue(announceCalls.all { it.wireFileId == sharedWireFileId })

        // 3. Three distinct recipient transferIds
        val recipientTransferIds = announceCalls.map { it.transferId }
        assertEquals(3, recipientTransferIds.toSet().size)

        // 4. sendFile called only for peers whose announce returned true (peer-1 and peer-3)
        assertEquals(2, sendFileCalls.size)
        assertEquals(listOf("peer-1", "peer-3"), sendFileCalls.map { it.targetDeviceId })
        assertEquals(announceCalls[0].transferId, sendFileCalls[0].transferId)
        assertEquals(sharedWireFileId, sendFileCalls[0].wireFileId)
        assertEquals(announceCalls[2].transferId, sendFileCalls[1].transferId)
        assertEquals(sharedWireFileId, sendFileCalls[1].wireFileId)

        // 5. Order is announce-then-send for each member, then sendGroupAttachment
        assertEquals(
            listOf(
                "announce-peer-1", "send-peer-1",
                "announce-peer-2",
                "announce-peer-3", "send-peer-3",
                "attachment",
            ),
            events,
        )

        // 6. sendGroupAttachment is called once, with transferId = messageId
        assertEquals(1, sendGroupAttachmentCalls.size)
        val groupAttachment = sendGroupAttachmentCalls.single()
        assertEquals("group-123", groupAttachment.groupId)
        assertEquals(sharedMessageId, groupAttachment.messageId)
        assertEquals(sharedMessageId, groupAttachment.transferId)
        assertEquals("photo.jpg", groupAttachment.fileName)
        assertEquals("image/jpeg", groupAttachment.mimeType)
        assertEquals(2048L, groupAttachment.sizeBytes)
        assertEquals("content://file/1", groupAttachment.localPath)
    }
}
