package com.transfer.flash.core.engine.group

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.swarm.model.SwarmManifest

/**
 * Sends a file or voice attachment to all members of a group (SW-1, SW-8).
 *
 * This unifies the previously duplicated fan-out loops in MainActivity and DesktopShell.
 * The loop generates a single shared message ID and wire file ID for the group attachment,
 * announces to each member with a unique recipient transfer ID, transfers the file
 * to announced members (via swarm pull or legacy direct push), and records the single sender bubble.
 */
public class GroupFileSender(
    private val localDeviceId: () -> String,
    private val groupMembers: suspend (groupId: String) -> List<FlashGroupMemberUi>,
    private val deviceFor: (memberId: String, memberName: String) -> FlashDevice,
    private val announce: suspend (
        groupId: String,
        recipientDeviceId: String,
        messageId: String,
        transferId: String,
        wireFileId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        root: String?,
        pieceSize: Int?,
        swarm: Int?,
        rootSig: String?,
    ) -> Boolean,
    private val sendFile: suspend (
        targetDevice: FlashDevice,
        uri: String,
        displayName: String,
        sizeBytes: Long,
        transferId: String,
        wireFileId: String,
    ) -> Unit,
    private val sendGroupAttachment: suspend (
        groupId: String,
        messageId: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        localPath: String,
        voiceDurationMs: Long,
        voiceAmplitudes: List<Int>,
    ) -> Unit,
    private val idFactory: () -> String,
    private val isV2Group: (suspend (groupId: String) -> Boolean)? = null,
    private val peerFeatures: ((peerId: String) -> Set<String>)? = null,
    private val prepareSwarmOrigin: (suspend (
        groupId: String,
        messageId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        uri: String,
    ) -> Pair<SwarmManifest, String>?)? = null,
    /**
     * Keeps the swarm offer (root, piece size, origin signature) on the sender's row even when no member was announced
     * to as a swarm, so a member that connects later learns of the file through catch-up (ERROR-117).
     */
    private val recordSwarmOffer: ((messageId: String, root: String, pieceSize: Int, rootSig: String) -> Unit)? = null,
) {
    public constructor(
        localDeviceId: () -> String,
        groupMembers: suspend (groupId: String) -> List<FlashGroupMemberUi>,
        deviceFor: (memberId: String, memberName: String) -> FlashDevice,
        announce: suspend (
            groupId: String,
            recipientDeviceId: String,
            messageId: String,
            transferId: String,
            wireFileId: String,
            fileName: String,
            mimeType: String,
            sizeBytes: Long,
        ) -> Boolean,
        sendFile: suspend (
            targetDevice: FlashDevice,
            uri: String,
            displayName: String,
            sizeBytes: Long,
            transferId: String,
            wireFileId: String,
        ) -> Unit,
        sendGroupAttachment: suspend (
            groupId: String,
            messageId: String,
            transferId: String,
            fileName: String,
            mimeType: String,
            sizeBytes: Long,
            localPath: String,
            voiceDurationMs: Long,
            voiceAmplitudes: List<Int>,
        ) -> Unit,
        idFactory: () -> String,
    ) : this(
        localDeviceId = localDeviceId,
        groupMembers = groupMembers,
        deviceFor = deviceFor,
        announce = { g, r, m, t, w, f, mime, s, _, _, _, _ ->
            announce(g, r, m, t, w, f, mime, s)
        },
        sendFile = sendFile,
        sendGroupAttachment = sendGroupAttachment,
        idFactory = idFactory,
        isV2Group = null,
        peerFeatures = null,
        prepareSwarmOrigin = null,
    )

    public suspend fun send(
        groupId: String,
        uri: String,
        displayName: String,
        sizeBytes: Long,
        mimeType: String,
        voiceDurationMs: Long = 0L,
        voiceAmplitudes: List<Int> = emptyList(),
    ) {
        val sharedMessageId = idFactory()
        val sharedWireFileId = idFactory()
        val myId = localDeviceId()
        val members = groupMembers(groupId).filter { it.id != myId }

        val isSwarmable = voiceDurationMs == 0L &&
            PieceMath.choosePieceSize(sizeBytes) != null &&
            isV2Group?.invoke(groupId) == true &&
            prepareSwarmOrigin != null

        val swarmOrigin = if (isSwarmable) {
            prepareSwarmOrigin!!.invoke(groupId, sharedMessageId, displayName, mimeType, sizeBytes, uri)
        } else {
            null
        }

        for (member in members) {
            val recipientTransferId = idFactory()
            val features = peerFeatures?.invoke(member.id).orEmpty()
            val useSwarmForPeer = swarmOrigin != null && "sw1" in features

            val announced = if (useSwarmForPeer) {
                val (manifest, rootSig) = swarmOrigin!!
                announce(
                    groupId,
                    member.id,
                    sharedMessageId,
                    recipientTransferId,
                    sharedWireFileId,
                    displayName,
                    mimeType,
                    sizeBytes,
                    manifest.root.hex,
                    manifest.pieceSize,
                    1,
                    rootSig,
                )
            } else {
                announce(
                    groupId,
                    member.id,
                    sharedMessageId,
                    recipientTransferId,
                    sharedWireFileId,
                    displayName,
                    mimeType,
                    sizeBytes,
                    null,
                    null,
                    null,
                    null,
                )
            }
            if (!announced) continue

            // If peer is using swarm, they pull chunks themselves via FSW1 frames.
            // Only non-swarm peers receive the legacy direct push stream via sendFile.
            if (!useSwarmForPeer) {
                val targetDevice = deviceFor(member.id, member.name)
                sendFile(
                    targetDevice,
                    uri,
                    displayName,
                    sizeBytes,
                    recipientTransferId,
                    sharedWireFileId,
                )
            }
        }
        if (swarmOrigin != null) {
            val (manifest, rootSig) = swarmOrigin
            recordSwarmOffer?.invoke(sharedMessageId, manifest.root.hex, manifest.pieceSize, rootSig)
        }
        sendGroupAttachment(
            groupId,
            sharedMessageId,
            sharedMessageId,
            displayName,
            mimeType,
            sizeBytes,
            uri,
            voiceDurationMs,
            voiceAmplitudes,
        )
    }
}
