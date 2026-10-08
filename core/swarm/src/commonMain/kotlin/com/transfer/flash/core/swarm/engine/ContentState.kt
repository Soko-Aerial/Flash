package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmWaitReason

/**
 * Internal state for a content item managed by [SwarmEngine].
 */
internal class ContentState(
    val groupId: String,
    val root: ContentRoot,
    val messageId: String,
    val role: SwarmRole,
    val originId: String,
    val originKey: String,
    val fileName: String,
    val mime: String,
    val totalSize: Long,
    val pieceSize: Int,
    var manifest: SwarmManifest?,
    val localTransferId: String,
    val sourceUri: String?,
    val partialKey: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    initialState: SwarmLifecycleState = SwarmLifecycleState.OFFERED,
) {
    val totalPieces: Int = PieceMath.pieceCount(totalSize, pieceSize)
    val bitfield: Bitfield = Bitfield(totalPieces)
    val persistedBits: Bitfield = Bitfield(totalPieces)

    var bytesDone: Long = 0L
    var state: SwarmLifecycleState = initialState
    var waitReason: SwarmWaitReason? = null
    var failReason: String? = null

    var manifestAssembler: ManifestCodec.Reassembler? = null
    var manifestPeerInFlight: String? = null
    var manifestRequestSentAtMs: Long = 0L

    var originSourceLost: Boolean = false

    /** The origin's own signed LOST statement (ERROR-109), kept so a member that connects later hears it without a new signature. */
    var signedSourceLost: SwarmFrame.SourceStatus? = null

    /** `atMs` of the newest SourceStatus applied from the origin; an older one that arrives late is ignored. */
    var sourceStatusAtMs: Long = 0L
    var storageUnavailable: Boolean = false
    var verifyFailures: Int = 0

    var finalPath: String? = null
    var identitySize: Long? = null
    var identityModifiedMs: Long? = null

    val deliveredTo = LinkedHashSet<String>()

    /** Last time the origin republished its row only to refresh the per-member view (rates age without an event). */
    var lastRecipientPublishMs: Long = 0L

    // pieceIndex -> set of peerIds currently requested from
    val inFlightByPiece = LinkedHashMap<Int, LinkedHashSet<String>>()

    // (peerId, pieceIndex) -> requestSentTimestampMs
    val inFlightRequests = LinkedHashMap<Pair<String, Int>, Long>()

    // Peers currently occupying active serve slots for this content
    val activeRequesters = LinkedHashSet<String>()

    // peerId -> outstanding serve bytes
    val outstandingServeBytes = LinkedHashMap<String, Long>()

    // pieceIndex -> count of active read/serve operations in flight
    val pendingServesByPiece = LinkedHashMap<Int, Int>()

    // Pieces persisted and waiting to be sent in HAVE frames (INV-4)
    val pendingHavePieces = LinkedHashSet<Int>()

    // peerId -> set of pieces served by origin to that peer
    val originServedPiecesByPeer = LinkedHashMap<String, LinkedHashSet<Int>>()

    // (peerId, pieceIndex) -> count of ELSEWHERE rejections sent
    val elsewhereRejections = LinkedHashMap<Pair<String, Int>, Int>()

    init {
        if (role == SwarmRole.ORIGIN) {
            for (i in 0 until totalPieces) {
                bitfield.set(i, true)
                persistedBits.set(i, true)
            }
            bytesDone = totalSize
        }
    }

    val remainingBytes: Long get() = maxOf(0L, totalSize - bytesDone)
    val piecesDone: Int get() = bitfield.count()
    val isComplete: Boolean get() = bitfield.isComplete()

    fun pieceLength(index: Int): Int = PieceMath.pieceLength(index, totalSize, pieceSize)

    fun toRecord(nowMs: Long): SwarmContentRecord = SwarmContentRecord(
        root = root,
        groupId = groupId,
        messageId = messageId,
        role = role,
        originId = originId,
        originKey = originKey,
        fileName = fileName,
        mime = mime,
        totalSize = totalSize,
        pieceSize = pieceSize,
        manifestBytes = manifest?.let { ManifestCodec.encodeCanonical(it) },
        bits = persistedBits.toByteArray(),
        bytesDone = bytesDone,
        state = state,
        waitReason = waitReason,
        failReason = failReason,
        localTransferId = localTransferId,
        sourceUri = sourceUri,
        sourcePersistent = sourceUri != null,
        partialKey = partialKey,
        finalPath = finalPath,
        identitySize = identitySize ?: totalSize,
        identityModifiedMs = identityModifiedMs ?: createdAtMs,
        deliveredTo = deliveredTo.toSet(),
        createdAtMs = createdAtMs,
        lastProgressAtMs = nowMs,
        expiresAtMs = expiresAtMs,
    )
}
