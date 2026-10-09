package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.concurrent.SyncMap
import com.transfer.flash.core.common.concurrent.SyncSet
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.swarm.api.FlashSwarm
import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.swarm.api.FlashSwarmStatus
import com.transfer.flash.core.swarm.codec.DecodeResult
import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.codec.SwarmFrameCodec
import com.transfer.flash.core.swarm.codec.SwarmStatement
import com.transfer.flash.core.swarm.engine.SwarmCommand
import com.transfer.flash.core.swarm.engine.SwarmEngine
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.swarm.model.PieceReadStatus
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmStateStore
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.swarm.model.SwarmWaitReason
import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferRecipient
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.model.FlashTransferWaitReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Single-coroutine actor driver orchestrating [SwarmEngine] with platform I/O ports (§5.2, SW-8).
 */
@OptIn(FlashInternalApi::class)
public class SwarmDriver(
    public val config: FlashSwarmConfig,
    public val localDeviceId: String,
    public val transport: SwarmTransport,
    public val groupContext: SwarmGroupContext,
    public val storage: PieceStorage,
    public val stateStore: SwarmStateStore,
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeSource: FlashTimeSource = SystemTimeSource,
    seed: Long = 42L,
) : FlashSwarm {

    private val engine: SwarmEngine = SwarmEngine(config.toEngineConfig(), localDeviceId, seed)
    private val eventChannel: Channel<SwarmEvent> = Channel(Channel.UNLIMITED)
    private val persistChannel: Channel<SwarmCommand> = Channel(Channel.UNLIMITED)

    private val _rows = MutableStateFlow<List<FlashTransfer>>(emptyList())
    override val rows: StateFlow<List<FlashTransfer>> = _rows.asStateFlow()

    // Touched by the actor and by worker-dispatcher coroutines, so every shared map is lock-guarded.
    private val statusFlows = SyncMap<String, MutableStateFlow<FlashSwarmStatus?>>()

    /** groupId -> the group's name, resolved when the group is first seen (the row builder is not suspending). */
    private val groupTitles = SyncMap<String, String>()

    private val contentRecords = SyncMap<Pair<String, ContentRoot>, SwarmContentRecord>()
    private val transferIdToKey = SyncMap<String, Pair<String, ContentRoot>>()
    private val keyToTransferId = SyncMap<Pair<String, ContentRoot>, String>()

    private val cachedManifests = SyncMap<ContentRoot, SwarmManifest>()
    private val manifestFragments = SyncMap<ContentRoot, List<ByteArray>>()

    /** Groups this device holds swarm content for; the gate is asked about each before a peer is told anything. */
    private val knownGroups = SyncSet<String>()

    /** One ordered outbound queue per peer: frames to a peer must leave in the order the engine produced them. */
    private val sendQueues = SyncMap<String, Channel<SwarmFrame>>()

    /** Completes once persisted state has been fed to the engine; inbound frames and announcements wait for it. */
    private val restored = CompletableDeferred<Unit>()
    private var lastMalformedLogMs: Long = 0L

    private fun now(): Long = timeSource.nowMs()

    public companion object {
        private const val MEMBERSHIP_RECHECK_MS: Long = 2_000L

        public fun partialKeyFor(root: ContentRoot, groupId: String): String {
            val groupHash = Sha256.digest(groupId.encodeToByteArray()).toHexString().take(8)
            return "${root.hex}-$groupHash"
        }

        private fun ByteArray.toHexString(): String =
            joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    init {
        // 1. Start single-coroutine actor consumer
        scope.launch {
            for (event in eventChannel) {
                try {
                    val commands = engine.handle(event)
                    for (cmd in commands) {
                        executeCommand(cmd)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // One bad event must not stop the whole swarm.
                    FlashLog.e("SWARM", "engine event failed event=${event::class.simpleName}: ${t.message}", t)
                }
            }
        }

        // Persistence queue worker: ensures file sync and database writes are strictly FIFO
        scope.launch(workerDispatcher) {
            for (cmd in persistChannel) {
                // One failed write (disk full, a locked database) must not end this worker: every later write would
                // be queued for ever and the cancellation would take the whole binding down with it.
                try {
                    when (cmd) {
                        is SwarmCommand.SyncAndPersistBits -> {
                            val key = partialKeyFor(cmd.root, cmd.groupId)
                            storage.openPartial(key, cmd.bytesDone)?.use { it.sync() }
                            stateStore.setBits(cmd.root, cmd.groupId, cmd.bits, cmd.bytesDone)
                        }
                        is SwarmCommand.PersistRecord -> {
                            stateStore.upsert(cmd.record)
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    val what = when (cmd) {
                        is SwarmCommand.SyncAndPersistBits -> "bits root=${cmd.root.hex.take(8)}"
                        is SwarmCommand.PersistRecord -> "record root=${cmd.record.root.hex.take(8)}"
                        else -> cmd::class.simpleName
                    }
                    FlashLog.e("SWARM", "persist failed ($what): ${t::class.simpleName}: ${t.message}", t)
                }
            }
        }

        // 2. Periodic 250ms tick
        scope.launch {
            while (isActive) {
                delay(250)
                eventChannel.trySend(SwarmEvent.Tick(now()))
            }
        }

        // 3. Listen to transport connected peers
        scope.launch {
            var previousPeers = emptyMap<String, Set<String>>()
            transport.connectedPeers.collect { currentPeers ->
                val added = currentPeers.keys - previousPeers.keys
                val removed = previousPeers.keys - currentPeers.keys
                restored.await()
                for (peerId in added) {
                    val features = currentPeers[peerId].orEmpty()
                    if ("sw1" in features) {
                        val denied = knownGroups.toSet().filter { !groupContext.isPeerAllowed(it, peerId) }.toSet()
                        FlashLog.i("SWARM", "peer up peer=$peerId deniedGroups=${denied.size}")
                        eventChannel.send(SwarmEvent.PeerUp(peerId, features, now(), denied))
                    }
                }
                for (peerId in removed) {
                    sendQueues.remove(peerId)?.close()
                    eventChannel.send(SwarmEvent.PeerDown(peerId, now()))
                }
                previousPeers = currentPeers
            }
        }

        // 4. Listen to group context membership changes
        scope.launch {
            groupContext.membershipChanges.collect { groupId ->
                restored.await()
                emitMembership(groupId, recheckInactive = true)
            }
        }

        // 5. Rebuild the engine from persisted state (R4): tombstones first, then every content item.
        scope.launch(workerDispatcher) {
            try {
                runCatching { runRetentionCleanup() }
                    .onFailure { FlashLog.w("SWARM", "retention cleanup failed: ${it.message}") }
                val tombstones = stateStore.tombstones()
                if (tombstones.isNotEmpty()) {
                    eventChannel.send(SwarmEvent.TombstonesRestored(tombstones, now()))
                }
                val records = stateStore.loadAll()
                val groups = records.map { it.groupId }.distinct()
                for (g in groups) {
                    knownGroups.add(g)
                    rememberGroupTitle(g)
                    emitMembership(g, recheckInactive = false)
                }
                for (record in records) {
                    val normalized = rememberRecord(record)
                    eventChannel.send(SwarmEvent.Restored(normalized, now()))
                }
                // A roster that reads "not a member" must persist before restored rows are failed.
                for (g in groups) emitMembership(g, recheckInactive = true)
                FlashLog.i("SWARM", "restore done contents=${records.size} groups=${groups.size} tombstones=${tombstones.size}")
                val freeBytes = storage.freeBytesFor("")
                eventChannel.send(SwarmEvent.SpaceChanged(freeBytes, now()))
                eventChannel.send(SwarmEvent.SystemResume(now()))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                FlashLog.e("SWARM", "restore failed: ${t.message}", t)
            } finally {
                restored.complete(Unit)
            }
        }
    }

    /** Stores [record] in the driver's lookups with the storage key the files really use, and returns it. */
    private fun rememberRecord(record: SwarmContentRecord): SwarmContentRecord {
        val normalized = record.copy(partialKey = partialKeyFor(record.root, record.groupId))
        contentRecords[normalized.groupId to normalized.root] = normalized
        transferIdToKey[normalized.localTransferId] = normalized.groupId to normalized.root
        keyToTransferId[normalized.groupId to normalized.root] = normalized.localTransferId
        normalized.manifestBytes?.let { raw ->
            ManifestCodec.decode(raw, normalized.root)?.let { manifest ->
                cachedManifests[normalized.root] = manifest
                manifestFragments[normalized.root] = ManifestCodec.fragment(raw)
            }
        }
        return normalized
    }

    /**
     * Tells the engine who may exchange [groupId] content with this device now.
     * [recheckInactive]: a roster that is mid-update can read "not a member" for a moment, and that verdict
     * deletes partial downloads, so it must hold for a short while before it is believed.
     */
    private suspend fun emitMembership(groupId: String, recheckInactive: Boolean) {
        var selfActive = groupContext.isLocalActiveMember(groupId)
        if (!selfActive && recheckInactive) {
            delay(MEMBERSHIP_RECHECK_MS)
            selfActive = groupContext.isLocalActiveMember(groupId)
        }
        val activePeers = transport.connectedPeers.value.keys.filter {
            groupContext.isPeerAllowed(groupId, it)
        }.toSet()
        eventChannel.send(SwarmEvent.MembershipChanged(groupId, activePeers, selfActive, now()))
    }

    private fun enqueueSend(peerId: String, frame: SwarmFrame) {
        val queue = sendQueues.getOrPut(peerId) {
            val channel = Channel<SwarmFrame>(Channel.UNLIMITED)
            scope.launch(workerDispatcher) {
                for (f in channel) {
                    try {
                        transport.send(peerId, SwarmFrameCodec.encode(f))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        FlashLog.w("SWARM", "send failed peer=$peerId frame=${f::class.simpleName}: ${t.message}")
                    }
                }
            }
            channel
        }
        queue.trySend(frame)
    }

    /**
     * Ingests a raw inbound FSW1 binary frame from the host's magic router (§5.2, SW-8).
     */
    public suspend fun onInboundFrame(peerId: String, frameBytes: ByteArray) {
        restored.await()
        when (val decoded = SwarmFrameCodec.decode(frameBytes)) {
            is DecodeResult.Decoded -> {
                handleFrame(peerId, decoded.frame)
            }
            is DecodeResult.Malformed -> logDropped("malformed", peerId, frameBytes.size)
            is DecodeResult.Unknown -> logDropped("unknown", peerId, frameBytes.size)
        }
    }

    /** Rate-limited so a peer cannot flood the log with junk frames. */
    private fun logDropped(kind: String, peerId: String, size: Int) {
        val t = now()
        if (t - lastMalformedLogMs >= 1_000L) {
            lastMalformedLogMs = t
            FlashLog.w("SWARM", "dropped $kind frame peer=$peerId bytes=$size")
        }
    }

    private suspend fun handleFrame(peerId: String, frame: SwarmFrame) {
        when (frame) {
            is SwarmFrame.Summary -> {
                // ERROR-110: the roster is read live here, so a member removed a moment ago is not heard before the engine is told.
                if (!groupContext.isPeerAllowed(frame.groupId, peerId)) {
                    logDropped("non-member summary", peerId, 0)
                    return
                }
                val validTombstones = frame.tombstones.filter { tb ->
                    val authorKey = groupContext.authorKey(tb.groupId, tb.originId)
                    val stmt = SwarmStatement.cancel(
                        groupId = tb.groupId,
                        root = tb.root,
                        originId = tb.originId,
                        messageId = tb.messageId,
                        reason = tb.reason.name,
                        cancelledAtMs = tb.cancelledAtMs,
                    )
                    authorKey != null && groupContext.verifyStatement(authorKey, stmt, tb.signature)
                }
                eventChannel.send(SwarmEvent.SummaryArrived(peerId, frame.copy(tombstones = validTombstones), now()))
            }
            is SwarmFrame.ManifestGet -> {
                // A manifest is only for members of the group that holds this content.
                val known = contentRecords[frame.groupId to frame.root] != null
                val frags = if (known && groupContext.isPeerAllowed(frame.groupId, peerId)) manifestFragments[frame.root] else null
                if (frags != null && frame.fragmentIndex in frags.indices) {
                    val part = SwarmFrame.ManifestPart(
                        groupId = frame.groupId,
                        root = frame.root,
                        fragmentIndex = frame.fragmentIndex,
                        fragmentCount = frags.size,
                        bytes = frags[frame.fragmentIndex],
                    )
                    enqueueSend(peerId, part)
                }
            }
            is SwarmFrame.ManifestPart -> eventChannel.send(SwarmEvent.ManifestPartArrived(peerId, frame, now()))
            is SwarmFrame.Have -> {
                if (groupContext.isPeerAllowed(frame.groupId, peerId)) {
                    eventChannel.send(SwarmEvent.HaveArrived(peerId, frame, now()))
                } else {
                    logDropped("non-member have", peerId, 0)
                }
            }
            is SwarmFrame.HaveAll -> {
                if (groupContext.isPeerAllowed(frame.groupId, peerId)) {
                    eventChannel.send(SwarmEvent.HaveAllArrived(peerId, frame, now()))
                } else {
                    logDropped("non-member have-all", peerId, 0)
                }
            }
            is SwarmFrame.Request -> {
                val allowed = groupContext.isPeerAllowed(frame.groupId, peerId)
                // Serving is a separate switch (signed group setting + this device's preference), not part of membership.
                val serveAllowed = allowed && groupContext.isServeAllowed(frame.groupId, peerId)
                eventChannel.send(SwarmEvent.RequestArrived(peerId, frame, allowed, now(), serveAllowed))
            }
            is SwarmFrame.Unrequest -> eventChannel.send(SwarmEvent.UnrequestArrived(peerId, frame, now()))
            is SwarmFrame.Piece -> {
                val expectedHash = cachedManifests[frame.root]?.pieceHashes?.getOrNull(frame.index)
                val verified = expectedHash != null && Sha256.digest(frame.bytes).contentEquals(expectedHash)
                eventChannel.send(
                    SwarmEvent.PieceArrived(
                        peerId = peerId,
                        groupId = frame.groupId,
                        root = frame.root,
                        index = frame.index,
                        bytes = frame.bytes,
                        verified = verified,
                        nowMs = now(),
                    )
                )
            }
            is SwarmFrame.Reject -> eventChannel.send(SwarmEvent.RejectArrived(peerId, frame, now()))
            is SwarmFrame.Cancel -> {
                val authorKey = groupContext.authorKey(frame.groupId, frame.originId)
                val stmt = SwarmStatement.cancel(
                    groupId = frame.groupId,
                    root = frame.root,
                    originId = frame.originId,
                    messageId = frame.messageId,
                    reason = frame.reason.name,
                    cancelledAtMs = frame.cancelledAtMs,
                )
                val valid = authorKey != null && groupContext.verifyStatement(authorKey, stmt, frame.signature)
                eventChannel.send(SwarmEvent.CancelArrived(peerId, frame, valid, now()))
            }
            is SwarmFrame.CancelAck -> eventChannel.send(SwarmEvent.CancelAckArrived(peerId, frame, now()))
            is SwarmFrame.SourceStatus -> {
                val authorKey = groupContext.authorKey(frame.groupId, frame.originId)
                val stmt = SwarmStatement.source(
                    groupId = frame.groupId,
                    root = frame.root,
                    originId = frame.originId,
                    messageId = frame.messageId,
                    status = frame.status.name,
                    reason = frame.reason.name,
                    atMs = frame.atMs,
                )
                val valid = authorKey != null && groupContext.verifyStatement(authorKey, stmt, frame.signature)
                eventChannel.send(SwarmEvent.SourceStatusArrived(peerId, frame, valid, now()))
            }
        }
    }

    private fun executeCommand(cmd: SwarmCommand) {
        when (cmd) {
            is SwarmCommand.Send -> enqueueSend(cmd.peerId, cmd.frame)
            is SwarmCommand.RequestSession -> {
                transport.requestSession(cmd.peerId)
            }
            is SwarmCommand.ReadPiece -> {
                scope.launch(workerDispatcher) {
                    val record = contentRecords[cmd.groupId to cmd.root]
                    val bytes = readPieceBytes(record, cmd.groupId, cmd.root, cmd.index)
                    val expectedHash = cachedManifests[cmd.root]?.pieceHashes?.getOrNull(cmd.index)
                    val valid = bytes != null && (expectedHash == null || Sha256.digest(bytes).contentEquals(expectedHash))
                    val status = if (valid) PieceReadStatus.OK else PieceReadStatus.GONE
                    eventChannel.send(
                        SwarmEvent.PieceRead(
                            peerId = cmd.forPeerId,
                            groupId = cmd.groupId,
                            root = cmd.root,
                            index = cmd.index,
                            bytes = if (valid) bytes else null,
                            status = status,
                            nowMs = now(),
                        )
                    )
                }
            }
            is SwarmCommand.WritePiece -> {
                scope.launch(workerDispatcher) {
                    val record = contentRecords[cmd.groupId to cmd.root]
                    val totalSize = record?.totalSize ?: 0L
                    val pieceSize = record?.pieceSize ?: PieceMath.MIN_PIECE_SIZE
                    val offset = PieceMath.pieceOffset(cmd.index, pieceSize)
                    val key = partialKeyFor(cmd.root, cmd.groupId)

                    val handle = storage.openPartial(key, totalSize)
                    if (handle != null) {
                        try {
                            handle.writeAt(offset, cmd.bytes, cmd.bytes.size)
                            eventChannel.send(SwarmEvent.PieceStored(cmd.groupId, cmd.root, cmd.index, now()))
                        } catch (e: Exception) {
                            eventChannel.send(SwarmEvent.PieceStoreFailed(cmd.groupId, cmd.root, cmd.index, e.message ?: "Write failed", now()))
                        } finally {
                            handle.close()
                        }
                    } else {
                        eventChannel.send(SwarmEvent.PieceStoreFailed(cmd.groupId, cmd.root, cmd.index, "Cannot open partial storage", now()))
                    }
                }
            }
            is SwarmCommand.SyncAndPersistBits -> {
                persistChannel.trySend(cmd)
            }
            is SwarmCommand.Finalize -> {
                scope.launch(workerDispatcher) {
                    val record = contentRecords[cmd.groupId to cmd.root]
                    val manifest = cachedManifests[cmd.root]
                    val key = partialKeyFor(cmd.root, cmd.groupId)
                    if (record != null && manifest != null) {
                        val res = storage.finalize(key, record.fileName, record.mime, manifest.fileSha256)
                        FlashLog.i("SWARM", "finalize root=${cmd.root.hex.take(8)} ok=${res.ok} badPieces=${res.badPieces.size}")
                        if (res.ok && res.finalPath != null) {
                            val updated = record.copy(
                                finalPath = res.finalPath,
                                identitySize = res.identity?.sizeBytes ?: record.totalSize,
                                identityModifiedMs = res.identity?.lastModifiedMs ?: record.createdAtMs,
                            )
                            contentRecords[cmd.groupId to cmd.root] = updated
                        }
                        eventChannel.send(
                            SwarmEvent.FinalizeResult(
                                groupId = cmd.groupId,
                                root = cmd.root,
                                ok = res.ok,
                                badPieces = res.badPieces,
                                finalPath = res.finalPath,
                                identitySize = res.identity?.sizeBytes,
                                identityModifiedMs = res.identity?.lastModifiedMs,
                                nowMs = now(),
                            )
                        )
                    } else {
                        eventChannel.send(SwarmEvent.FinalizeResult(cmd.groupId, cmd.root, ok = false, nowMs = now()))
                    }
                }
            }
            is SwarmCommand.DeletePartial -> {
                scope.launch(workerDispatcher) {
                    storage.deletePartial(partialKeyFor(cmd.root, cmd.groupId))
                }
            }
            is SwarmCommand.PersistRecord -> {
                val record = rememberRecord(cmd.record)
                persistChannel.trySend(SwarmCommand.PersistRecord(record))
            }
            is SwarmCommand.SignTombstone -> {
                scope.launch(workerDispatcher) {
                    val stmt = SwarmStatement.cancel(
                        groupId = cmd.groupId,
                        root = cmd.root,
                        originId = cmd.originId,
                        messageId = cmd.messageId,
                        reason = cmd.reason.name,
                        cancelledAtMs = cmd.cancelledAtMs,
                    )
                    val sig = groupContext.signStatement(cmd.groupId, stmt)
                    if (sig != null) {
                        val tombstone = SwarmTombstone(
                            groupId = cmd.groupId,
                            root = cmd.root,
                            originId = cmd.originId,
                            messageId = cmd.messageId,
                            reason = cmd.reason,
                            cancelledAtMs = cmd.cancelledAtMs,
                            signature = sig,
                        )
                        eventChannel.send(SwarmEvent.TombstoneSigned(tombstone, now()))
                    }
                }
            }
            is SwarmCommand.SignSourceStatus -> {
                scope.launch(workerDispatcher) {
                    val stmt = SwarmStatement.source(
                        groupId = cmd.groupId,
                        root = cmd.root,
                        originId = cmd.originId,
                        messageId = cmd.messageId,
                        status = cmd.status.name,
                        reason = cmd.reason.name,
                        atMs = cmd.atMs,
                    )
                    val sig = groupContext.signStatement(cmd.groupId, stmt)
                    if (sig != null) {
                        val frame = SwarmFrame.SourceStatus(
                            groupId = cmd.groupId,
                            root = cmd.root,
                            originId = cmd.originId,
                            messageId = cmd.messageId,
                            status = cmd.status,
                            reason = cmd.reason,
                            atMs = cmd.atMs,
                            signature = sig,
                        )
                        eventChannel.send(SwarmEvent.SourceStatusSigned(frame, now()))
                    } else {
                        FlashLog.w("SWARM", "source status not signed root=${cmd.root.hex.take(8)} (no signing key for the group)")
                    }
                }
            }
            is SwarmCommand.PersistTombstone -> {
                scope.launch(workerDispatcher) {
                    stateStore.putTombstone(cmd.tombstone)
                }
            }
            is SwarmCommand.PublishRow -> {
                updateTransferRow(cmd)
            }
            is SwarmCommand.Log -> FlashLog.i("SWARM", cmd.line.removePrefix("SWARM: "))
        }
    }

    private suspend fun readPieceBytes(
        record: SwarmContentRecord?,
        groupId: String,
        root: ContentRoot,
        index: Int,
    ): ByteArray? {
        val pieceSize = record?.pieceSize ?: PieceMath.MIN_PIECE_SIZE
        val totalSize = record?.totalSize ?: (pieceSize.toLong() * (index + 1))
        val offset = PieceMath.pieceOffset(index, pieceSize)
        val len = PieceMath.pieceLength(index, totalSize, pieceSize)

        // 1. Try origin sourceUri if origin
        if (record?.role == SwarmRole.ORIGIN && record.sourceUri != null) {
            storage.openSource(record.sourceUri)?.use { handle ->
                val buf = ByteArray(len)
                val read = handle.readAt(offset, buf, len)
                if (read == len) return buf
            }
        }

        // 2. Try finalPath if receiver completed
        val finalPath = record?.finalPath
        if (finalPath != null) {
            storage.openSource(finalPath)?.use { handle ->
                val buf = ByteArray(len)
                val read = handle.readAt(offset, buf, len)
                if (read == len) return buf
            }
        }

        // 3. Try partial storage
        val key = partialKeyFor(root, groupId)
        storage.openPartial(key, record?.totalSize ?: 0L)?.use { handle ->
            val buf = ByteArray(len)
            val read = handle.readAt(offset, buf, len)
            if (read == len) return buf
        }

        return null
    }

    private fun updateTransferRow(cmd: SwarmCommand.PublishRow) {
        val transferId = keyToTransferId[cmd.groupId to cmd.root] ?: cmd.root.hex
        val record = contentRecords[cmd.groupId to cmd.root]
        val direction = if (record?.role == SwarmRole.ORIGIN) FlashTransferDirection.Sending else FlashTransferDirection.Receiving

        val transferState = when (cmd.state) {
            SwarmLifecycleState.OFFERED -> FlashTransferState.Offered
            SwarmLifecycleState.ACTIVE -> if (cmd.waitReason != null) FlashTransferState.Queued else FlashTransferState.Transferring
            SwarmLifecycleState.PAUSED_BY_USER -> FlashTransferState.Paused
            SwarmLifecycleState.VERIFYING -> FlashTransferState.Verifying
            SwarmLifecycleState.COMPLETE -> FlashTransferState.Completed
            SwarmLifecycleState.FAILED -> FlashTransferState.Failed
            SwarmLifecycleState.CANCELLED -> FlashTransferState.Cancelled
        }

        val mappedWait = when (cmd.waitReason) {
            SwarmWaitReason.WAITING_FOR_SENDER -> FlashTransferWaitReason.WaitingForSender
            SwarmWaitReason.WAITING_FOR_HOLDERS -> FlashTransferWaitReason.WaitingForHolders
            SwarmWaitReason.WAITING_FOR_NETWORK -> FlashTransferWaitReason.WaitingForNetwork
            SwarmWaitReason.WAITING_FOR_SPACE -> FlashTransferWaitReason.WaitingForSpace
            SwarmWaitReason.WAITING_FOR_STORAGE -> FlashTransferWaitReason.WaitingForStorage
            SwarmWaitReason.WAITING_FOR_SYSTEM -> FlashTransferWaitReason.WaitingForSystem
            SwarmWaitReason.WAITING_FOR_SESSION -> FlashTransferWaitReason.WaitingForSession
            null -> null
        }

        val errorMessage = if (cmd.state == SwarmLifecycleState.FAILED) {
            TransferFailureText.friendly(cmd.failReason, transferId)
        } else null

        val snapshot = engine.snapshot().contents[cmd.groupId to cmd.root]
        val canGoOffline = snapshot?.canGoOffline ?: false
        val holdersOnline = snapshot?.holdersOnline ?: 0

        val speedBps = rowSpeed(transferId, direction, transferState, cmd.bytesDone, snapshot?.recipients.orEmpty())
        val etaSeconds = if (direction == FlashTransferDirection.Receiving && speedBps > 0L && cmd.totalSize > cmd.bytesDone) {
            (cmd.totalSize - cmd.bytesDone) / speedBps
        } else 0L

        val transfer = FlashTransfer(
            id = FlashTransferId(transferId),
            speedBytesPerSec = speedBps,
            etaSeconds = etaSeconds,
            // ERROR-111: a person reads the group's name, not its id.
            peerName = groupTitles[cmd.groupId] ?: "Group",
            fileName = record?.fileName ?: "file.bin",
            direction = direction,
            bytesDone = cmd.bytesDone,
            bytesTotal = cmd.totalSize,
            state = transferState,
            waitReason = mappedWait,
            errorMessage = errorMessage,
            canGoOffline = canGoOffline,
            holdersOnline = holdersOnline,
            pieceBlocks = snapshot?.pieceBlocks.orEmpty(),
            // ERROR-119: a row without a path made every received group file answer "File not available yet" when
            // opened. The finished file's path is the receiver's; the origin's own is the file it is sending from.
            localPath = when {
                record == null -> null
                record.role == SwarmRole.ORIGIN -> record.sourceUri
                transferState == FlashTransferState.Completed -> record.finalPath
                else -> null
            },
            sourceUri = if (record?.role == SwarmRole.ORIGIN) record.sourceUri else null,
            recipients = snapshot?.recipients.orEmpty().map {
                FlashTransferRecipient(
                    peerId = it.peerId,
                    bytesHeld = it.bytesHeld,
                    bytesTotal = it.totalBytes,
                    hasAll = it.hasAll,
                    online = it.online,
                    rateBytesPerSec = it.rateBytesPerSec,
                )
            },
        )

        rowProbes.onRow(transferId, direction, transferState, cmd.bytesDone, cmd.totalSize, transfer.recipients, now())

        val currentList = _rows.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id.value == transferId }
        if (existingIndex >= 0) {
            currentList[existingIndex] = transfer
        } else {
            currentList.add(transfer)
        }
        _rows.value = currentList

        // Update status flow
        val flow = statusFlows.getOrPut(transferId) { MutableStateFlow(null) }
        if (snapshot != null) {
            flow.value = FlashSwarmStatus(
                root = cmd.root,
                groupId = cmd.groupId,
                holdersOnline = snapshot.holdersOnline,
                distributedCopies = snapshot.distributedCopies,
                canGoOffline = snapshot.canGoOffline,
                waitReason = snapshot.waitReason,
                piecesDone = snapshot.piecesDone,
                totalPieces = snapshot.totalPieces,
                bytesDone = snapshot.bytesDone,
                totalBytes = snapshot.totalBytes,
                isComplete = snapshot.state == SwarmLifecycleState.COMPLETE,
                pieceBlocks = snapshot.pieceBlocks,
                recipients = snapshot.recipients,
            )
        }
    }

    private val rowProbes = SwarmRowProbes { name, fields -> FlashProbe.emit(name, *fields.toTypedArray()) }

    /** Per-row throughput samples: transferId -> (time, bytes, smoothed bytes/s). Only touched from the driver's actor. */
    private val speedSamples = HashMap<String, Triple<Long, Long, Double>>()

    /**
     * What the row shows as its speed. A receiver: growth of its own bytes, smoothed. A sender: the sum of its members'
     * current rates (what is being delivered right now, wherever the pieces come from). Zero whenever it is not moving.
     */
    private fun rowSpeed(
        transferId: String,
        direction: FlashTransferDirection,
        state: FlashTransferState,
        bytesDone: Long,
        recipients: List<com.transfer.flash.core.swarm.engine.RecipientSnapshot>,
    ): Long {
        if (direction == FlashTransferDirection.Sending) return recipients.sumOf { it.rateBytesPerSec }
        if (state != FlashTransferState.Transferring) {
            speedSamples.remove(transferId)
            return 0L
        }
        val nowMs = now()
        val previous = speedSamples[transferId]
        if (previous == null) {
            speedSamples[transferId] = Triple(nowMs, bytesDone, 0.0)
            return 0L
        }
        val (atMs, bytes, rate) = previous
        val elapsed = nowMs - atMs
        if (elapsed < SPEED_SAMPLE_MIN_MS) return rate.toLong()
        val instant = (bytesDone - bytes).coerceAtLeast(0L) * 1000.0 / elapsed
        val smoothed = if (rate == 0.0) instant else (0.6 * rate) + (0.4 * instant)
        speedSamples[transferId] = Triple(nowMs, bytesDone, smoothed)
        return smoothed.toLong()
    }

    private fun findKey(transferId: String): Pair<String, ContentRoot>? {
        return transferIdToKey[transferId]
            ?: contentRecords.valuesSnapshot().firstOrNull { it.localTransferId == transferId || it.messageId == transferId }?.let { it.groupId to it.root }
    }

    override fun status(transferId: String): StateFlow<FlashSwarmStatus?> {
        return statusFlows.getOrPut(transferId) { MutableStateFlow(null) }.asStateFlow()
    }

    override suspend fun accept(transferId: String) {
        val key = findKey(transferId) ?: return
        eventChannel.send(SwarmEvent.Accepted(key.first, key.second, now()))
    }

    override suspend fun decline(transferId: String) {
        cancelLocal(transferId)
    }

    override suspend fun pause(transferId: String) {
        val key = findKey(transferId) ?: return
        eventChannel.send(SwarmEvent.LocalPause(key.first, key.second, now()))
    }

    override suspend fun resume(transferId: String) {
        val key = findKey(transferId) ?: return
        eventChannel.send(SwarmEvent.LocalResume(key.first, key.second, now()))
    }

    override suspend fun cancelLocal(transferId: String) {
        val key = findKey(transferId) ?: return
        eventChannel.send(SwarmEvent.LocalCancel(key.first, key.second, asOrigin = false, nowMs = now()))
    }

    override suspend fun cancelAsOrigin(
        transferId: String,
        reason: SwarmTombstoneReason,
    ) {
        val key = findKey(transferId) ?: return
        eventChannel.send(SwarmEvent.LocalCancel(key.first, key.second, asOrigin = true, reason = reason, nowMs = now()))
    }

    override suspend fun pauseForSystem(transferId: String, reason: String) {
        eventChannel.send(SwarmEvent.SystemSuspend(now()))
    }

    override fun reevaluate() {
        scope.launch(workerDispatcher) {
            val freeBytes = storage.freeBytesFor("")
            eventChannel.send(SwarmEvent.SpaceChanged(freeBytes, now()))
            eventChannel.send(SwarmEvent.SystemResume(now()))
        }
    }

    override suspend fun runRetentionCleanup() {
        val nowMs = timeSource.nowMs()
        stateStore.purgeExpired(nowMs)
        val activeRecords = stateStore.loadAll()
        // The files are named by partialKeyFor(); the key stored on a record is not guaranteed to match it.
        val activeKeys = activeRecords.map { partialKeyFor(it.root, it.groupId) }.toSet()
        storage.purgeOrphanedPartials(activeKeys)
        eventChannel.send(SwarmEvent.Tick(nowMs))
    }

    private suspend fun rememberGroupTitle(groupId: String) {
        val title = runCatching { groupContext.groupTitle(groupId) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        groupTitles[groupId] = title
    }

    /**
     * Announces a new content piece as origin or receiver (§5.2, SW-8).
     */
    public suspend fun announceContent(event: SwarmEvent.Announced) {
        restored.await()
        transferIdToKey[event.messageId] = event.groupId to event.root
        keyToTransferId[event.groupId to event.root] = event.messageId
        // A group's peers must be classified before anything about it is announced to them.
        if (knownGroups.add(event.groupId)) emitMembership(event.groupId, recheckInactive = false)
        rememberGroupTitle(event.groupId)
        FlashLog.i("SWARM", "announce root=${event.root.hex.take(8)} group=${event.groupId} origin=${event.isOrigin} size=${event.totalSize}")
        eventChannel.send(event)
    }

    public fun onNetworkUp() {
        eventChannel.trySend(SwarmEvent.NetworkUp(now()))
    }

    public fun onNetworkDown() {
        eventChannel.trySend(SwarmEvent.NetworkDown(now()))
    }

    public fun onServingEnabled(enabled: Boolean) {
        eventChannel.trySend(SwarmEvent.ServingEnabled(enabled, now()))
    }

    public fun onCallActive(active: Boolean) {
        eventChannel.trySend(SwarmEvent.CallActive(active, now()))
    }

    public fun onSpaceChanged(freeBytes: Long) {
        eventChannel.trySend(SwarmEvent.SpaceChanged(freeBytes, now()))
    }

    public fun debugDump(): String = engine.debugDump()
}

private const val SPEED_SAMPLE_MIN_MS = 700L
