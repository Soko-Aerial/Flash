package com.transfer.flash.core.swarm.sim

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.engine.SwarmCommand
import com.transfer.flash.core.swarm.engine.SwarmConfig
import com.transfer.flash.core.swarm.engine.SwarmEngine
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceReadStatus
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.transfer.chunked.Sha256

/**
 * Simulated host node orchestrating a SwarmEngine with mock storage, persistence, and network (SW-5).
 */
class SimNode(
    val id: String,
    val clock: SimClock,
    val queue: SimEventQueue,
    val network: SimNetwork,
    val storage: SimStorage,
    val store: SimStore,
    val config: SwarmConfig = SwarmConfig(),
    val isOrigin: Boolean = false,
    val isLegacy: Boolean = false,
    seed: Long = 42L,
) {
    var engine: SwarmEngine = SwarmEngine(config, id, seed)

    var currentManifest: SwarmManifest? = null
    var canonicalManifestBytes: ByteArray? = null
    var manifestFragments: List<ByteArray>? = null

    var defaultPeerAllowed: Boolean = true
    val disallowedPeers: MutableSet<String> = LinkedHashSet()

    var uploadedBytes: Long = 0L
    var downloadedBytes: Long = 0L
    var originUploadBytes: Long = 0L
    var framesSentWhileWaiting: Int = 0
    var corruptWritesCount: Int = 0

    var completed: Boolean = false
    var failed: Boolean = false
    var cancelled: Boolean = false
    var latestRow: SwarmCommand.PublishRow? = null

    val sentFrames = mutableListOf<Pair<String, SwarmFrame>>()
    val receivedFrames = mutableListOf<Pair<String, SwarmFrame>>()

    init {
        network.registerNode(this)
    }

    fun isPeerAllowed(groupId: String, peerId: String): Boolean {
        return defaultPeerAllowed && peerId !in disallowedPeers
    }

    fun setContentManifest(manifest: SwarmManifest, canonicalBytes: ByteArray) {
        currentManifest = manifest
        canonicalManifestBytes = canonicalBytes
        manifestFragments = ManifestCodec.fragment(canonicalBytes)
    }

    fun onPeerUp(peerId: String, features: Set<String>) {
        val cmds = engine.handle(SwarmEvent.PeerUp(peerId, features, clock.nowMs))
        processCommands(cmds)
    }

    fun onPeerDown(peerId: String) {
        val cmds = engine.handle(SwarmEvent.PeerDown(peerId, clock.nowMs))
        processCommands(cmds)
    }

    fun tick() {
        val cmds = engine.handle(SwarmEvent.Tick(clock.nowMs))
        processCommands(cmds)
    }

    fun receiveFrame(senderId: String, frame: SwarmFrame) {
        receivedFrames.add(senderId to frame)
        when (frame) {
            is SwarmFrame.Summary -> {
                val cmds = engine.handle(SwarmEvent.SummaryArrived(senderId, frame, clock.nowMs))
                processCommands(cmds)
            }
            is SwarmFrame.ManifestGet -> {
                val frags = manifestFragments
                if (frags != null && frame.fragmentIndex in frags.indices) {
                    val part = SwarmFrame.ManifestPart(
                        groupId = frame.groupId,
                        root = frame.root,
                        fragmentIndex = frame.fragmentIndex,
                        fragmentCount = frags.size,
                        bytes = frags[frame.fragmentIndex],
                    )
                    sendDirectFrame(senderId, part)
                }
            }
            is SwarmFrame.ManifestPart -> {
                val cmds = engine.handle(SwarmEvent.ManifestPartArrived(senderId, frame, clock.nowMs))
                processCommands(cmds)
            }
            is SwarmFrame.Have -> {
                val cmds = engine.handle(SwarmEvent.HaveArrived(senderId, frame, clock.nowMs))
                processCommands(cmds)
            }
            is SwarmFrame.HaveAll -> {
                val cmds = engine.handle(SwarmEvent.HaveAllArrived(senderId, frame, clock.nowMs))
                processCommands(cmds)
            }
            is SwarmFrame.Request -> {
                val allowed = isPeerAllowed(frame.groupId, senderId)
                val cmds = engine.handle(SwarmEvent.RequestArrived(senderId, frame, allowed, clock.nowMs))
                processCommands(cmds)
            }
            is SwarmFrame.Unrequest -> {
                val cmds = engine.handle(SwarmEvent.UnrequestArrived(senderId, frame, clock.nowMs))
                processCommands(cmds)
            }
            is SwarmFrame.Piece -> {
                val expectedHash = currentManifest?.pieceHashes?.getOrNull(frame.index)
                val actualHash = Sha256.digest(frame.bytes)
                val verified = expectedHash != null && actualHash.contentEquals(expectedHash)
                downloadedBytes += frame.bytes.size

                val cmds = engine.handle(
                    SwarmEvent.PieceArrived(
                        peerId = senderId,
                        groupId = frame.groupId,
                        root = frame.root,
                        index = frame.index,
                        bytes = frame.bytes,
                        verified = verified,
                        nowMs = clock.nowMs,
                    )
                )
                processCommands(cmds)
            }
            is SwarmFrame.Reject -> {
                val cmds = engine.handle(SwarmEvent.RejectArrived(senderId, frame, clock.nowMs))
                processCommands(cmds)
            }
            is SwarmFrame.Cancel -> {
                // In simulation, verify against declared originId in frame
                val sigValid = frame.originId == "origin" || frame.originId == id
                val cmds = engine.handle(SwarmEvent.CancelArrived(senderId, frame, sigValid, clock.nowMs))
                processCommands(cmds)
            }
            is SwarmFrame.CancelAck -> {
                val cmds = engine.handle(SwarmEvent.CancelAckArrived(senderId, frame, clock.nowMs))
                processCommands(cmds)
            }
            is SwarmFrame.SourceStatus -> {
                val cmds = engine.handle(SwarmEvent.SourceStatusArrived(senderId, frame, signatureValid = true, nowMs = clock.nowMs))
                processCommands(cmds)
            }
        }
    }

    private fun sendDirectFrame(toPeerId: String, frame: SwarmFrame) {
        val bytes = when (frame) {
            is SwarmFrame.Piece -> frame.bytes.size.toLong()
            is SwarmFrame.ManifestPart -> frame.bytes.size.toLong()
            else -> 64L
        }
        sentFrames.add(toPeerId to frame)
        network.send(id, toPeerId, bytes) {
            val target = getPeerNode(toPeerId)
            target?.receiveFrame(id, frame)
        }
    }

    fun processCommands(commands: List<SwarmCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is SwarmCommand.Send -> {
                    sentFrames.add(cmd.peerId to cmd.frame)
                    if (cmd.frame is SwarmFrame.Request && latestRow?.waitReason != null) {
                        framesSentWhileWaiting++
                    }
                    val bytes = when (cmd.frame) {
                        is SwarmFrame.Piece -> cmd.frame.bytes.size.toLong()
                        is SwarmFrame.ManifestPart -> cmd.frame.bytes.size.toLong()
                        else -> 64L
                    }
                    if (cmd.frame is SwarmFrame.Piece) {
                        uploadedBytes += bytes
                        if (isOrigin) originUploadBytes += bytes
                    }
                    network.send(id, cmd.peerId, bytes) {
                        val target = getPeerNode(cmd.peerId)
                        target?.receiveFrame(id, cmd.frame)
                    }
                }
                is SwarmCommand.ReadPiece -> {
                    val bytes = storage.readPiece(cmd.index)
                    val status = if (bytes != null) PieceReadStatus.OK else PieceReadStatus.GONE
                    queue.schedule(0L) {
                        val nextCmds = engine.handle(
                            SwarmEvent.PieceRead(
                                peerId = cmd.forPeerId,
                                groupId = cmd.groupId,
                                root = cmd.root,
                                index = cmd.index,
                                bytes = bytes,
                                status = status,
                                nowMs = clock.nowMs,
                            )
                        )
                        processCommands(nextCmds)
                    }
                }
                is SwarmCommand.WritePiece -> {
                    val expectedHash = currentManifest?.pieceHashes?.getOrNull(cmd.index)
                    if (expectedHash != null && !Sha256.digest(cmd.bytes).contentEquals(expectedHash)) {
                        corruptWritesCount++
                    }
                    storage.writePiece(cmd.index, cmd.bytes)
                    queue.schedule(0L) {
                        val nextCmds = engine.handle(
                            SwarmEvent.PieceStored(
                                groupId = cmd.groupId,
                                root = cmd.root,
                                index = cmd.index,
                                nowMs = clock.nowMs,
                            )
                        )
                        processCommands(nextCmds)
                    }
                }
                is SwarmCommand.SyncAndPersistBits -> {
                    storage.sync(cmd.bits)
                    store.updateBits(cmd.groupId, cmd.root, cmd.bits, cmd.bytesDone)
                }
                is SwarmCommand.Finalize -> {
                    val ok = currentManifest?.let { storage.verifyWholeFile(it.fileSha256) } ?: true
                    queue.schedule(0L) {
                        val nextCmds = engine.handle(
                            SwarmEvent.FinalizeResult(
                                groupId = cmd.groupId,
                                root = cmd.root,
                                ok = ok,
                                nowMs = clock.nowMs,
                            )
                        )
                        processCommands(nextCmds)
                    }
                }
                is SwarmCommand.DeletePartial -> {
                    storage.deletePartial()
                }
                is SwarmCommand.PersistRecord -> {
                    store.putRecord(cmd.record)
                }
                is SwarmCommand.SignTombstone -> {
                    val tombstone = SwarmTombstone(
                        groupId = cmd.groupId,
                        root = cmd.root,
                        originId = cmd.originId,
                        messageId = cmd.messageId,
                        reason = cmd.reason,
                        cancelledAtMs = cmd.cancelledAtMs,
                        signature = ByteArray(64) { 1 },
                    )
                    queue.schedule(0L) {
                        val nextCmds = engine.handle(
                            SwarmEvent.TombstoneSigned(tombstone, clock.nowMs)
                        )
                        processCommands(nextCmds)
                    }
                }
                is SwarmCommand.SignSourceStatus -> {
                    val frame = SwarmFrame.SourceStatus(
                        groupId = cmd.groupId,
                        root = cmd.root,
                        originId = cmd.originId,
                        messageId = cmd.messageId,
                        status = cmd.status,
                        reason = cmd.reason,
                        atMs = cmd.atMs,
                        signature = ByteArray(64) { 1 },
                    )
                    queue.schedule(0L) {
                        processCommands(engine.handle(SwarmEvent.SourceStatusSigned(frame, clock.nowMs)))
                    }
                }
                is SwarmCommand.PersistTombstone -> {
                    store.putTombstone(cmd.tombstone)
                }
                is SwarmCommand.PublishRow -> {
                    latestRow = cmd
                    if (cmd.state == SwarmLifecycleState.COMPLETE) completed = true
                    if (cmd.state == SwarmLifecycleState.FAILED) failed = true
                    if (cmd.state == SwarmLifecycleState.CANCELLED) cancelled = true
                }
                is SwarmCommand.RequestSession -> {
                    // Host session request - simulated direct link handles it
                }
                is SwarmCommand.Log -> {
                    // Structured engine log
                }
            }
        }
    }

    private fun getPeerNode(peerId: String): SimNode? = network.getNode(peerId)
}
