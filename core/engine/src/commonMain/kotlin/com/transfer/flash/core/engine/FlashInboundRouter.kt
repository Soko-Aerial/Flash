@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.messaging.RealFlashChatRepository
import com.transfer.flash.core.messaging.protocol.ChatTextFrameCodec
import com.transfer.flash.core.messaging.protocol.DirectChatFamily
import com.transfer.flash.core.messaging.protocol.DirectMessageActionCodec
import com.transfer.flash.core.messaging.protocol.GroupFrameCodec
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.messaging.protocol.PttAudioFrame
import com.transfer.flash.core.messaging.protocol.PttFrameCodec
import com.transfer.flash.core.messaging.protocol.PttSessionCodec
import com.transfer.flash.core.ptt.FlashPtt
import com.transfer.flash.core.security.crypto.E2eFrameCodec
import com.transfer.flash.core.security.crypto.SecureBinaryFrameCodec
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.RejectReason
import com.transfer.flash.core.transfer.chunked.WholeFileCheck
import com.transfer.flash.core.transfer.chunked.WholeFileVerifier
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import kotlin.coroutines.cancellation.CancellationException

/**
 * The slice of the chat repository the inbound router feeds. [RealFlashChatRepository] is adapted
 * to it ([asInboundSink]); tests supply a recording fake so routing decisions (what reached chat
 * and what did not) can be asserted without a database.
 */
public interface InboundChatSink {
    /** A decoded direct-chat frame (text, receipt, reaction, typing, delete-for-everyone). */
    public suspend fun onInboundWireFrame(frame: MessageWireFrame, transportPeerId: String?)

    /** A decoded group wire frame from [peerDeviceId]. */
    public suspend fun onInboundGroupWireFrame(peerDeviceId: String, frame: GroupWireFrame)
}

/** Adapts the real chat repository to the router's [InboundChatSink] seam. */
public fun RealFlashChatRepository.asInboundSink(): InboundChatSink {
    val repository = this
    return object : InboundChatSink {
        override suspend fun onInboundWireFrame(frame: MessageWireFrame, transportPeerId: String?) =
            repository.onInboundWireFrame(frame, transportPeerId = transportPeerId)

        override suspend fun onInboundGroupWireFrame(peerDeviceId: String, frame: GroupWireFrame) =
            repository.onInboundGroupWireFrame(peerDeviceId, frame)
    }
}

/**
 * Shared inbound wire frame routing and session dispatch for Flash hosts (ADR-010 / C7.0).
 * Unifies the text and binary dispatch cascades across Android and Desktop JVM platforms.
 *
 * Failure containment: neither entry point lets an exception escape (a throwing repository, codec
 * or host hook is logged at WARN and the frame is treated as handled), and every
 * [ReceiveEvent] of a frame is processed independently, so one failing event cannot starve the
 * events after it. [CancellationException] is always rethrown.
 *
 * Transfer ownership: a transfer id belongs to the peer that offered it. Chunk, FILE_START and
 * `FLASH_XFER` control frames for an id owned by a different peer are dropped, and a FILE_START
 * whose id would not survive [FlashPathSanitizer.sanitize] unchanged is refused, because the id
 * names the receive directory (two ids must never alias one directory).
 */
public object FlashInboundRouter {

    public const val CALL_PREFIX: String = "FLASH_CALL"
    public const val PAIR_PREFIX: String = "FLASH_PAIR"
    public const val XFER_PREFIX: String = "FLASH_XFER"

    /** Log tag of every router line. Matches the host tag (`FlashEngine`) so existing logcat filters keep working. */
    public const val TAG: String = "FlashEngine"

    /** Ownership map size above which entries without a live pipeline session are pruned. */
    private const val OWNER_PRUNE_THRESHOLD: Int = 64

    public fun isCallFrameText(text: String): Boolean =
        FlashTextFraming.parseFields(text, CALL_PREFIX) != null

    public fun isPairingFrameText(text: String): Boolean =
        FlashTextFraming.parseFields(text, PAIR_PREFIX) != null

    public fun isTransferControlText(text: String): Boolean =
        FlashTextFraming.parseFields(text, XFER_PREFIX) != null

    public fun isPttAudio(data: ByteArray): Boolean =
        PttAudioFrame.isPttAudio(data)

    /**
     * Routes one inbound UTF-8 text frame from [peerDeviceId].
     *
     * Cascade order:
     * 1. Calling signaling (`FLASH_CALL` prefix)
     * 2. Presence exchange (`FLASH_PRES` prefix)
     * 3. Connection mode controller (`FLASH_LINK` prefix)
     * 4. Push-to-talk voice/ping control
     * 5. Pairing handshake (`FLASH_PAIR` prefix); a host with no [pairingHandler] leaves it unconsumed
     * 6. E2E encrypted frame (`FLASH_SEC`), with downgrade attack rejection
     * 7. Direct chat action (e.g. DeleteForEveryone)
     * 8. Group wire frame
     * 9. Direct chat message / receipt / typing / reaction
     * 10. Transfer control (`FLASH_XFER`), only from the peer that owns the transfer id
     *
     * Returns true if the frame was consumed or definitively handled; false otherwise. Never throws
     * (except [CancellationException]).
     *
     * @param incomingOwners transferId to owning peer id, shared with [routeInboundBinary]; a control
     * frame for an id owned by another peer is ignored. Ids without an entry fall back to the peer
     * recorded on the repository row.
     */
    public suspend fun routeInboundText(
        peerDeviceId: String,
        text: String,
        callHandler: (suspend (peerDeviceId: String, text: String) -> Boolean)? = null,
        presenceHandler: ((peerDeviceId: String, text: String) -> Boolean)? = null,
        modeHandler: ((peerDeviceId: String, text: String) -> Boolean)? = null,
        pttProvider: (() -> FlashPtt?)? = null,
        pairingHandler: ((peerDeviceId: String, text: String) -> Unit)? = null,
        sessionKeyLookup: ((peerDeviceId: String) -> ByteArray?)? = null,
        chatRepository: RealFlashChatRepository? = null,
        transferRepository: RealFlashTransferRepository? = null,
        customHandler: (suspend (peerDeviceId: String, text: String) -> Boolean)? = null,
        chatSink: InboundChatSink? = chatRepository?.asInboundSink(),
        incomingOwners: Map<String, String>? = null,
    ): Boolean = try {
        routeText(
            peerDeviceId, text, callHandler, presenceHandler, modeHandler, pttProvider, pairingHandler,
            sessionKeyLookup, chatSink, transferRepository, customHandler, incomingOwners,
        )
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        FlashLog.w(TAG, "Inbound text frame handler failed peer=$peerDeviceId: ${t.message}", t)
        true
    }

    private suspend fun routeText(
        peerDeviceId: String,
        text: String,
        callHandler: (suspend (peerDeviceId: String, text: String) -> Boolean)?,
        presenceHandler: ((peerDeviceId: String, text: String) -> Boolean)?,
        modeHandler: ((peerDeviceId: String, text: String) -> Boolean)?,
        pttProvider: (() -> FlashPtt?)?,
        pairingHandler: ((peerDeviceId: String, text: String) -> Unit)?,
        sessionKeyLookup: ((peerDeviceId: String) -> ByteArray?)?,
        chatSink: InboundChatSink?,
        transferRepository: RealFlashTransferRepository?,
        customHandler: (suspend (peerDeviceId: String, text: String) -> Boolean)?,
        incomingOwners: Map<String, String>?,
    ): Boolean {
        // 1. Calling signaling (ADR-025): most latency-sensitive frame class
        if (isCallFrameText(text)) {
            val consumed = callHandler?.invoke(peerDeviceId, text) == true
            if (!consumed) {
                FlashLog.w(
                    TAG,
                    "Call frame dropped (no calling engine attached, or nothing to route it to) peer=$peerDeviceId",
                )
            }
            return true
        }

        // 2. Presence sharing (PC4)
        if (presenceHandler?.invoke(peerDeviceId, text) == true) return true

        // 3. Link control (PC5)
        if (modeHandler?.invoke(peerDeviceId, text) == true) return true

        // 4. PTT (ADR-032): ping + voice session control
        val ptt = pttProvider?.invoke()
        if (ptt != null) {
            if (ptt.onInboundText(peerDeviceId, text)) return true
        } else if (PttFrameCodec.decode(text) != null || PttSessionCodec.decode(text) != null) {
            FlashLog.w(TAG, "PTT frame dropped (no PTT engine attached) peer=$peerDeviceId")
            return true
        }

        // 5. Pairing traffic (Phase 26-3). A host that pairs elsewhere (the Android app's discovery
        // holder) passes no handler; the frame then falls through silently, as it always did there.
        if (isPairingFrameText(text)) {
            if (pairingHandler != null) {
                pairingHandler(peerDeviceId, text)
                return true
            }
            return customHandler?.invoke(peerDeviceId, text) == true
        }

        // 6. E2E decryption
        val sessionKey = sessionKeyLookup?.invoke(peerDeviceId)
        val plainText = if (E2eFrameCodec.isSecuredFrame(text)) {
            if (sessionKey != null) {
                E2eFrameCodec.decryptWireFrame(text, sessionKey) ?: run {
                    FlashLog.w(TAG, "Failed to decrypt FLASH_SEC frame from $peerDeviceId")
                    return true
                }
            } else {
                FlashLog.w(TAG, "Received FLASH_SEC from $peerDeviceId with no stored session key; dropping")
                return true
            }
        } else {
            // Audit S1b: downgrade protection for keyed direct-chat peers
            if (DirectChatFamily.matches(text) && sessionKey != null) {
                FlashLog.w(TAG, "Dropped plaintext direct-chat frame from keyed peer $peerDeviceId (downgrade)")
                return true
            }
            text
        }

        // 7. Direct message action
        DirectMessageActionCodec.decode(plainText)?.let { frame ->
            chatSink?.onInboundWireFrame(frame, transportPeerId = peerDeviceId)
            return true
        }

        // 8. Group wire frame
        GroupFrameCodec.decode(plainText)?.let { frame ->
            FlashLog.i(TAG, "Inbound group frame ${frame::class.simpleName} from id=$peerDeviceId")
            chatSink?.onInboundGroupWireFrame(peerDeviceId, frame)
            return true
        }

        // 9. Direct chat text families. transportPeerId is passed for ALL families: the repository's
        // fail-closed spoof guards only fire when it is non-null.
        when (val decoded = ChatTextFrameCodec.decode(plainText, System.currentTimeMillis(), peerDeviceId)) {
            is ChatTextFrameCodec.DecodeResult.Frame -> {
                chatSink?.onInboundWireFrame(decoded.frame, transportPeerId = peerDeviceId)
                return true
            }
            ChatTextFrameCodec.DecodeResult.RecognizedButInvalid -> return true
            null -> Unit
        }

        // 10. Transfer control frames
        FlashTextFraming.parseFields(text, XFER_PREFIX)?.let { fields ->
            val action = fields["action"]
            val transferId = fields["transferId"]
            if (action != null && transferId != null) {
                val owner = ownerOf(transferId, incomingOwners, transferRepository)
                if (owner != null && owner != peerDeviceId) {
                    FlashLog.w(
                        TAG,
                        "Ignored transfer control action=$action transferId=$transferId from peer=$peerDeviceId: " +
                            "the transfer belongs to another peer",
                    )
                    return true
                }
                transferRepository?.onRemoteTransferControl(transferId, action)
                return true
            }
        }

        // 11. Custom fallback
        if (customHandler?.invoke(peerDeviceId, text) == true) return true

        return false
    }

    /**
     * Routes one inbound binary frame from [peerDeviceId].
     *
     * Cascade order:
     * 1. PTT voice audio (`PTT1` magic)
     * 2. Secure binary frame decryption
     * 3. Magic frame router dispatch (e.g. Swarm)
     * 4. Sender-side ACK/COMPLETE frame dispatch
     * 5. Ownership and path-safety gate for FILE_START / CHUNK frames
     * 6. Receiver-side chunk pipeline dispatch (`ReceivePipeline.onFrame`) and its events
     *
     * Returns true if the frame was handled; false otherwise. Never throws (except
     * [CancellationException]).
     *
     * Host hooks, all optional:
     * - [onSessionStarted]: first thing for every new receive session, before the already-completed and
     *   retry branches (hosts register per-peer tracking here).
     * - [onTransferUntracked]: the router is done with a transfer id (already completed, or the Completed
     *   event was handled, successfully or not); hosts drop per-peer tracking here.
     * - [onResumableRetry]: the sender re-offered a transfer this device already accepted. Without a hook
     *   the router admits (disk-space gate), resolves the sink, seeds progress and releases the sender.
     * - [onOfferReceived]: a genuinely new offer.
     *
     * @param incomingOwners transferId to owning peer id; filled by the router when a session starts and
     * shared with [routeInboundText]. Use a concurrent map.
     */
    public fun routeInboundBinary(
        peerDeviceId: String?,
        data: ByteArray,
        reply: (ByteArray) -> Boolean,
        peerLabel: String = "peer",
        pttProvider: (() -> FlashPtt?)? = null,
        sessionKeyLookup: ((peerDeviceId: String) -> ByteArray?)? = null,
        magicRouter: MagicFrameRouter? = null,
        transferRepository: RealFlashTransferRepository? = null,
        receivePipeline: ReceivePipeline? = null,
        incomingMeta: MutableMap<String, ChunkFrame.FileStart>? = null,
        receivedPaths: MutableMap<String, String>? = null,
        openHandles: MutableMap<String, RandomAccessSinkHandle>? = null,
        fileExistsAndSizeMatches: ((path: String, expectedBytes: Long) -> Boolean)? = null,
        sendXferResume: ((peerDeviceId: String, transferId: String) -> Unit)? = null,
        sendXferCancel: ((peerDeviceId: String, transferId: String) -> Unit)? = null,
        onOfferReceived: ((frame: ChunkFrame.FileStart, peerDeviceId: String?, secureReply: (ByteArray) -> Boolean) -> Unit)? = null,
        onProgressUpdated: ((transferId: String, bytesDone: Long) -> Unit)? = null,
        onRejected: ((event: ReceiveEvent.Rejected, peerDeviceId: String?) -> Unit)? = null,
        onCompleted: ((event: ReceiveEvent.Completed, peerDeviceId: String?) -> Unit)? = null,
        onSessionStarted: ((frame: ChunkFrame.FileStart, peerDeviceId: String?) -> Unit)? = null,
        onTransferUntracked: ((transferId: String) -> Unit)? = null,
        onResumableRetry: ((frame: ChunkFrame.FileStart, peerDeviceId: String?, secureReply: (ByteArray) -> Boolean) -> Unit)? = null,
        incomingOwners: MutableMap<String, String>? = null,
    ): Boolean = try {
        routeBinary(
            peerDeviceId, data, reply, peerLabel, pttProvider, sessionKeyLookup, magicRouter, transferRepository,
            receivePipeline, incomingMeta, receivedPaths, openHandles, fileExistsAndSizeMatches, sendXferResume,
            sendXferCancel, onOfferReceived, onProgressUpdated, onRejected, onCompleted, onSessionStarted,
            onTransferUntracked, onResumableRetry, incomingOwners,
        )
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        FlashLog.w(TAG, "Inbound binary frame handler failed peer=$peerDeviceId: ${t.message}", t)
        true
    }

    private fun routeBinary(
        peerDeviceId: String?,
        data: ByteArray,
        reply: (ByteArray) -> Boolean,
        peerLabel: String,
        pttProvider: (() -> FlashPtt?)?,
        sessionKeyLookup: ((peerDeviceId: String) -> ByteArray?)?,
        magicRouter: MagicFrameRouter?,
        transferRepository: RealFlashTransferRepository?,
        receivePipeline: ReceivePipeline?,
        incomingMeta: MutableMap<String, ChunkFrame.FileStart>?,
        receivedPaths: MutableMap<String, String>?,
        openHandles: MutableMap<String, RandomAccessSinkHandle>?,
        fileExistsAndSizeMatches: ((path: String, expectedBytes: Long) -> Boolean)?,
        sendXferResume: ((peerDeviceId: String, transferId: String) -> Unit)?,
        sendXferCancel: ((peerDeviceId: String, transferId: String) -> Unit)?,
        onOfferReceived: ((frame: ChunkFrame.FileStart, peerDeviceId: String?, secureReply: (ByteArray) -> Boolean) -> Unit)?,
        onProgressUpdated: ((transferId: String, bytesDone: Long) -> Unit)?,
        onRejected: ((event: ReceiveEvent.Rejected, peerDeviceId: String?) -> Unit)?,
        onCompleted: ((event: ReceiveEvent.Completed, peerDeviceId: String?) -> Unit)?,
        onSessionStarted: ((frame: ChunkFrame.FileStart, peerDeviceId: String?) -> Unit)?,
        onTransferUntracked: ((transferId: String) -> Unit)?,
        onResumableRetry: ((frame: ChunkFrame.FileStart, peerDeviceId: String?, secureReply: (ByteArray) -> Boolean) -> Unit)?,
        incomingOwners: MutableMap<String, String>?,
    ): Boolean {
        // 1. PTT voice audio first (ADR-032): 4-byte magic disjoint from transfer pipeline
        if (PttAudioFrame.isPttAudio(data)) {
            pttProvider?.invoke()?.onInboundBinary(peerDeviceId, data)
            return true
        }

        val sessionKey = peerDeviceId?.let { sessionKeyLookup?.invoke(it) }
        val frameData = if (SecureBinaryFrameCodec.isSecureFrame(data)) {
            if (sessionKey == null) {
                FlashLog.w(TAG, "Received encrypted binary frame from $peerDeviceId but no session key exists")
                return true
            }
            val decrypted = SecureBinaryFrameCodec.decryptOrNull(data, sessionKey)
            if (decrypted == null) {
                FlashLog.w(TAG, "Failed to decrypt binary frame from $peerDeviceId (tampered or wrong key)")
                return true
            }
            decrypted
        } else {
            data
        }

        val secureReply: (ByteArray) -> Boolean = { replyBytes ->
            try {
                val toSend = if (sessionKey != null) {
                    SecureBinaryFrameCodec.encrypt(replyBytes, sessionKey)
                } else {
                    replyBytes
                }
                reply(toSend)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                FlashLog.w(TAG, "Failed to send reply frame to $peerDeviceId: ${t.message}", t)
                false
            }
        }

        // 3. Magic frame dispatch (e.g. Swarm)
        if (magicRouter?.dispatch(peerDeviceId, frameData, secureReply) == true) {
            return true
        }

        // 4. Sender-side ACK/COMPLETE frame
        val consumedBySender = try {
            transferRepository?.onInboundFrame(frameData) == true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            FlashLog.w(TAG, "Failed to route inbound frame to sender: ${t.message}")
            false
        }
        if (consumedBySender) return true

        // 5/6. Receiver pipeline
        val pipeline = receivePipeline ?: return false

        if (peerDeviceId != null && !admitReceiverFrame(
                frameData, peerDeviceId, transferRepository, incomingOwners, sendXferCancel,
            )
        ) {
            return true
        }

        val events = try {
            pipeline.onFrame(frameData)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            FlashLog.w(TAG, "Failed to process inbound binary frame: ${e.message}")
            return true
        }

        val host = ReceiveHost(
            peerDeviceId = peerDeviceId,
            peerLabel = peerLabel,
            secureReply = secureReply,
            pipeline = pipeline,
            transferRepository = transferRepository,
            incomingMeta = incomingMeta,
            receivedPaths = receivedPaths,
            openHandles = openHandles,
            fileExistsAndSizeMatches = fileExistsAndSizeMatches,
            sendXferResume = sendXferResume,
            sendXferCancel = sendXferCancel,
            onOfferReceived = onOfferReceived,
            onProgressUpdated = onProgressUpdated,
            onRejected = onRejected,
            onCompleted = onCompleted,
            onSessionStarted = onSessionStarted,
            onTransferUntracked = onTransferUntracked,
            onResumableRetry = onResumableRetry,
            incomingOwners = incomingOwners,
        )
        for (event in events) {
            // One failing event (a throwing repository or host hook) must not starve the rest.
            try {
                handleEvent(host, event)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                FlashLog.w(TAG, "Receive event ${event::class.simpleName} failed peer=$peerDeviceId: ${t.message}", t)
            }
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Receiver events
    // ---------------------------------------------------------------------------------------------

    private class ReceiveHost(
        val peerDeviceId: String?,
        val peerLabel: String,
        val secureReply: (ByteArray) -> Boolean,
        val pipeline: ReceivePipeline,
        val transferRepository: RealFlashTransferRepository?,
        val incomingMeta: MutableMap<String, ChunkFrame.FileStart>?,
        val receivedPaths: MutableMap<String, String>?,
        val openHandles: MutableMap<String, RandomAccessSinkHandle>?,
        val fileExistsAndSizeMatches: ((path: String, expectedBytes: Long) -> Boolean)?,
        val sendXferResume: ((peerDeviceId: String, transferId: String) -> Unit)?,
        val sendXferCancel: ((peerDeviceId: String, transferId: String) -> Unit)?,
        val onOfferReceived: ((frame: ChunkFrame.FileStart, peerDeviceId: String?, secureReply: (ByteArray) -> Boolean) -> Unit)?,
        val onProgressUpdated: ((transferId: String, bytesDone: Long) -> Unit)?,
        val onRejected: ((event: ReceiveEvent.Rejected, peerDeviceId: String?) -> Unit)?,
        val onCompleted: ((event: ReceiveEvent.Completed, peerDeviceId: String?) -> Unit)?,
        val onSessionStarted: ((frame: ChunkFrame.FileStart, peerDeviceId: String?) -> Unit)?,
        val onTransferUntracked: ((transferId: String) -> Unit)?,
        val onResumableRetry: ((frame: ChunkFrame.FileStart, peerDeviceId: String?, secureReply: (ByteArray) -> Boolean) -> Unit)?,
        val incomingOwners: MutableMap<String, String>?,
    )

    private fun handleEvent(host: ReceiveHost, event: ReceiveEvent) {
        when (event) {
            is ReceiveEvent.SessionStarted -> handleSessionStarted(host, event.frame)
            is ReceiveEvent.AckBatchReady -> {
                val transferId = event.frame.transferId
                host.transferRepository?.onIncomingChunkConfirmed(transferId, event.frame.indexes)
                reportProgress(host, transferId)
                host.secureReply(ChunkFrame.serialize(event.frame))
            }
            is ReceiveEvent.Completed -> handleCompleted(host, event)
            is ReceiveEvent.Rejected -> {
                // R-06: a storage write failed. This is handled here, before and independently of any host hook, so every
                // host fails the row, drops the session and tells the sender instead of only logging a rejection.
                if (event.reason == RejectReason.WRITE_FAILED) handleWriteFailed(host, event)
                if (host.onRejected != null) {
                    host.onRejected.invoke(event, host.peerDeviceId)
                } else {
                    val tid = event.transferId
                    if (event.reason != RejectReason.UNEXPECTED_DIRECTION && event.reason != RejectReason.AWAITING_ACCEPTANCE) {
                        FlashLog.w(TAG, "Receiver rejected frame: reason=${event.reason} transferId=$tid index=${event.index}")
                    }
                    if (event.reason == RejectReason.UNKNOWN_TRANSFER && tid != null && host.peerDeviceId != null) {
                        host.sendXferCancel?.invoke(host.peerDeviceId, tid)
                    }
                }
            }
        }
    }

    /**
     * The pipeline could not write a chunk (full disk, removed card, revoked folder grant) and has already dropped the
     * session. Fails the transfer row with a sentence a person can act on, closes the sink, forgets the offer metadata
     * and cancels the sender, whose chunks would otherwise be answered "unknown transfer" one by one.
     */
    private fun handleWriteFailed(host: ReceiveHost, event: ReceiveEvent.Rejected) {
        val transferId = event.transferId ?: return
        host.pipeline.cancelSession(transferId)
        host.openHandles?.remove(transferId)?.let { handle ->
            try {
                handle.close()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                FlashLog.w(TAG, "Sink close after write failure failed transferId=$transferId: ${t.message}")
            }
        }
        host.incomingMeta?.remove(transferId)
        host.transferRepository?.onIncomingFailed(transferId, "write_failed")
        host.peerDeviceId?.let { host.sendXferCancel?.invoke(it, transferId) }
        host.incomingOwners?.remove(transferId)
        host.onTransferUntracked?.invoke(transferId)
        FlashLog.w(TAG, "Receive failed: storage write error transferId=$transferId index=${event.index}")
    }

    private fun handleSessionStarted(host: ReceiveHost, frame: ChunkFrame.FileStart) {
        val pipeline = host.pipeline
        val repo = host.transferRepository
        val peer = host.peerDeviceId
        val transferId = frame.transferId

        // Defense in depth: the pre-pipeline gate already refused a foreign or unsafe id, but a
        // session can still open for an id whose owner is only known now (e.g. a row without a peer).
        val owner = host.incomingOwners?.get(transferId)
        if (peer != null && owner != null && owner != peer) {
            FlashLog.w(TAG, "Offer for transferId=$transferId from peer=$peer ignored: owned by another peer")
            pipeline.cancelSession(transferId)
            return
        }

        host.incomingMeta?.put(transferId, frame)
        if (peer != null) {
            host.incomingOwners?.let { owners ->
                owners[transferId] = peer
                pruneOwners(owners, pipeline)
            }
        }
        // Host tracking first, BEFORE the branches below: a resumed or already-completed transfer is
        // as much "incoming from this peer" as a fresh one (a peer disconnect must be able to fail it).
        host.onSessionStarted?.invoke(frame, peer)

        val existing = repo?.activeTransfers?.value?.firstOrNull { it.id.value == transferId }
        val existingPath = host.receivedPaths?.get(transferId) ?: existing?.localPath
        // R-04 (sweep 2026-10-09): a file of the right LENGTH is not a finished transfer. A half-written pre-allocated file,
        // or an unrelated file at the same path, has the same length. Only a Completed row, or an on-disk file whose
        // SHA-256 equals the digest of this offer, may answer "already complete"; anything else goes through the normal
        // (resume) path, which writes over the file chunk by chunk and verifies it.
        val sizeMatches = existingPath != null && host.fileExistsAndSizeMatches?.invoke(existingPath, frame.totalBytes) == true
        val alreadyCompleted = (existing != null && existing.state == FlashTransferState.Completed) ||
            (sizeMatches && WholeFileVerifier.check(existingPath, frame.fileSha256Hex) == WholeFileCheck.MATCH)

        if (alreadyCompleted) {
            host.secureReply(ChunkFrame.serialize(ChunkFrame.Complete(transferId, frame.fileId, verified = true)))
            peer?.let { host.sendXferResume?.invoke(it, transferId) }
            // This branch ends the transfer's life on this side with no Completed event ever following,
            // so nothing else would untrack it. The leak that existed here: the id (incomingMeta and the
            // host's per-peer tracking) stayed registered forever, and the awaiting pipeline session kept
            // one of the limited session slots (a re-offer then produced no event at all).
            pipeline.declineSession(transferId)
            host.incomingMeta?.remove(transferId)
            host.incomingOwners?.remove(transferId)
            host.onTransferUntracked?.invoke(transferId)
            return
        }

        if (repo?.isResumableInboundRetry(transferId) == true) {
            val hook = host.onResumableRetry
            if (hook != null) {
                hook(frame, peer, host.secureReply)
            } else {
                resumeRetryDefault(host, frame)
            }
            return
        }

        if (host.onOfferReceived != null) {
            host.onOfferReceived.invoke(frame, peer, host.secureReply)
        } else {
            repo?.onIncomingOffered(
                transferId, frame.fileId, frame.fileName, frame.totalBytes, host.peerLabel, peer,
            )
        }
    }

    /**
     * Default handling of a re-offer for an already-accepted transfer, for hosts without a hook: the
     * same ordering as the normal accept (storage gate, sink, progress, THEN release the sender).
     * RESUME is only sent when the sink was actually resolved.
     */
    private fun resumeRetryDefault(host: ReceiveHost, frame: ChunkFrame.FileStart) {
        val repo = host.transferRepository ?: return
        val transferId = frame.transferId
        // ADR-069 / FA-2: refuse before the sink is resolved and the sender is released.
        if (!repo.admitIncoming(transferId)) return
        if (!host.pipeline.acceptSession(transferId)) {
            FlashLog.w(TAG, "Resumable retry transferId=$transferId: no awaiting session to accept; sender not released")
            return
        }
        repo.onIncomingStarted(
            transferId, frame.fileId, frame.fileName, frame.totalBytes,
            host.peerLabel, host.peerDeviceId, host.receivedPaths?.get(transferId),
        )
        reportProgress(host, transferId)
        host.peerDeviceId?.let { host.sendXferResume?.invoke(it, transferId) }
    }

    /** Recomputes verified bytes from the pipeline's done-set and publishes them. */
    private fun reportProgress(host: ReceiveHost, transferId: String) {
        if (host.incomingMeta?.get(transferId) == null) return
        // O(1): the pipeline maintains the verified byte count incrementally (was O(done chunks) per ack).
        val bytes = host.pipeline.doneBytes(transferId) ?: return
        host.transferRepository?.onIncomingProgress(transferId, bytes)
        host.onProgressUpdated?.invoke(transferId, bytes)
    }

    private fun handleCompleted(host: ReceiveHost, event: ReceiveEvent.Completed) {
        val transferId = event.frame.transferId
        val pipeline = host.pipeline
        try {
            // Flush and close the sink. Both are attempted (close even when flush throws) and a failure
            // of either is remembered: bytes that may not have reached storage must never be reported
            // as a verified file unless the assembled file itself proves otherwise.
            var ioFailure: Throwable? = null
            host.openHandles?.remove(transferId)?.let { handle ->
                try {
                    handle.flush()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    ioFailure = t
                    FlashLog.w(TAG, "Sink flush failed transferId=$transferId: ${t.message}", t)
                } finally {
                    try {
                        handle.close()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        if (ioFailure == null) ioFailure = t
                        FlashLog.w(TAG, "Sink close failed transferId=$transferId: ${t.message}", t)
                    }
                }
            }
            val path = host.receivedPaths?.remove(transferId)
            val expectedHex = host.incomingMeta?.remove(transferId)?.fileSha256Hex

            // Without a flush/close failure the chunk verdict stands; with one, only a whole-file digest
            // match of what is actually on disk may keep the transfer alive.
            val chunksVerified = if (ioFailure != null) {
                val onDisk = WholeFileVerifier.check(path, expectedHex)
                if (onDisk != WholeFileCheck.MATCH) {
                    FlashLog.w(
                        TAG,
                        "Transfer transferId=$transferId failed: sink I/O error and whole-file check=$onDisk",
                    )
                }
                onDisk == WholeFileCheck.MATCH && event.frame.verified
            } else {
                event.frame.verified
            }

            val wholeFile = host.transferRepository?.onIncomingFileAssembled(
                transferId, path, expectedHex, chunksVerified,
            )
            // The extra clause covers a host without a transfer repository: a sink I/O failure that the
            // assembled file does not disprove must still be answered as unverified.
            val failed = wholeFile == WholeFileCheck.MISMATCH || (ioFailure != null && !chunksVerified)
            val completeReply = if (failed) {
                pipeline.cancelSession(transferId)
                ChunkFrame.Complete(transferId, event.frame.fileId, verified = false)
            } else {
                event.frame
            }
            host.secureReply(ChunkFrame.serialize(completeReply))
            host.onCompleted?.invoke(event, host.peerDeviceId)
        } finally {
            // Untrack even if anything above threw (a throwing assemble used to leave the id tracked).
            host.incomingOwners?.remove(transferId)
            try {
                host.onTransferUntracked?.invoke(transferId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                FlashLog.w(TAG, "Untrack hook failed transferId=$transferId: ${t.message}", t)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Transfer identity: ownership and path safety
    // ---------------------------------------------------------------------------------------------

    private fun ownerOf(
        transferId: String,
        incomingOwners: Map<String, String>?,
        transferRepository: RealFlashTransferRepository?,
    ): String? = incomingOwners?.get(transferId)
        ?: transferRepository?.activeTransfers?.value?.firstOrNull { it.id.value == transferId }?.peerDeviceId

    private fun pruneOwners(owners: MutableMap<String, String>, pipeline: ReceivePipeline) {
        if (owners.size <= OWNER_PRUNE_THRESHOLD) return
        val live = pipeline.activeTransferIds()
        owners.keys.retainAll(live)
    }

    /** Transfer id of a CHUNK or FILE_START frame, read straight from the header without parsing the payload. */
    private class PeekedFrame(val isFileStart: Boolean, val transferId: String)

    private fun peekFrame(bytes: ByteArray): PeekedFrame? {
        if (bytes.size < ChunkFrame.HEADER_SIZE + 2) return null
        val magic = ChunkFrame.MAGIC
        for (i in magic.indices) if (bytes[i] != magic[i]) return null
        if ((bytes[4].toInt() and 0xFF) != ChunkFrame.VERSION) return null
        val type = bytes[5]
        val isStart = type == ChunkFrame.FrameType.FILE_START.code
        if (!isStart && type != ChunkFrame.FrameType.CHUNK.code) return null
        val length = (bytes[ChunkFrame.HEADER_SIZE].toInt() and 0xFF) or
            ((bytes[ChunkFrame.HEADER_SIZE + 1].toInt() and 0xFF) shl 8)
        val start = ChunkFrame.HEADER_SIZE + 2
        if (length == 0 || start + length > bytes.size) return null
        return PeekedFrame(isStart, bytes.decodeToString(start, start + length))
    }

    /**
     * Gate for receiver frames, run before the pipeline sees them. Returns false (frame dropped) when
     * the frame names a transfer owned by another peer, or a FILE_START whose id is unsafe as a
     * directory name or would alias another receive directory on a case-insensitive filesystem.
     */
    private fun admitReceiverFrame(
        frameData: ByteArray,
        peerDeviceId: String,
        transferRepository: RealFlashTransferRepository?,
        incomingOwners: MutableMap<String, String>?,
        sendXferCancel: ((peerDeviceId: String, transferId: String) -> Unit)?,
    ): Boolean {
        val peeked = peekFrame(frameData) ?: return true
        val transferId = peeked.transferId

        if (peeked.isFileStart) {
            if (FlashPathSanitizer.sanitize(transferId) != transferId) {
                FlashLog.w(TAG, "Offer refused from peer=$peerDeviceId: transferId is not a safe directory name")
                sendXferCancel?.invoke(peerDeviceId, transferId)
                return false
            }
            val aliased = incomingOwners?.keys?.any { it != transferId && it.equals(transferId, ignoreCase = true) } == true ||
                transferRepository?.activeTransfers?.value?.any {
                    it.direction == FlashTransferDirection.Receiving &&
                        it.id.value != transferId &&
                        it.id.value.equals(transferId, ignoreCase = true)
                } == true
            if (aliased) {
                FlashLog.w(TAG, "Offer refused from peer=$peerDeviceId: transferId differs from another transfer only by case")
                sendXferCancel?.invoke(peerDeviceId, transferId)
                return false
            }
        }

        // CHUNK frames are hot: the O(1) owner map only; rows are consulted for the (rare) FILE_START.
        val owner = incomingOwners?.get(transferId)
            ?: if (peeked.isFileStart) ownerOf(transferId, null, transferRepository) else null
        if (owner != null && owner != peerDeviceId) {
            FlashLog.w(
                TAG,
                "Dropped ${if (peeked.isFileStart) "offer" else "chunk"} for transferId=$transferId from peer=$peerDeviceId: " +
                    "the transfer belongs to another peer",
            )
            return false
        }
        return true
    }
}

/**
 * Shared peer session lifecycle event coordinator (ADR-010).
 */
public object FlashSessionCoordinator {

    /**
     * Executes the standard session-up lifecycle sequence when a peer connection is established.
     */
    public fun onSessionUp(
        peerDeviceId: String,
        chatRepository: RealFlashChatRepository? = null,
        onSignalingRestored: ((peerDeviceId: String) -> Unit)? = null,
        onPairingHello: ((peerDeviceId: String) -> Unit)? = null,
    ) {
        // 1. Pairing hello (lets peer derive shared comparison code immediately)
        onPairingHello?.invoke(peerDeviceId)

        // 2. Chat outbox flush & member group deliveries retryable
        chatRepository?.notifyPeerSessionUp(peerDeviceId)

        // 3. Holder-coordinated group sync request
        chatRepository?.sendGroupSyncRequests(peerDeviceId)

        // 4. Reconcile membership frames missed while offline
        chatRepository?.reconcileGroupMembership(peerDeviceId)

        // 5. Restore calling signaling channel
        onSignalingRestored?.invoke(peerDeviceId)
    }

    /**
     * Executes the standard session-down lifecycle sequence when a peer connection drops.
     */
    public fun onSessionDown(
        peerDeviceId: String,
        onSignalingLost: ((peerDeviceId: String) -> Unit)? = null,
    ) {
        // Open call signaling recovery window (Wi-Fi roam tolerance, ERROR-033)
        onSignalingLost?.invoke(peerDeviceId)
    }
}
