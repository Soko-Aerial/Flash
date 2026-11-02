package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.SourceState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.common.perf.FlashThermalStatus
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceReadStatus
import com.transfer.flash.core.swarm.model.SwarmFailReason
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmTombstone

/**
 * Sans-IO Swarm State Machine (§5.2, SW-4).
 * Pure state machine: no I/O, no system clock, no coroutines.
 * Deterministic: randomness derived strictly from [seed] (INV-11).
 */
public class SwarmEngine(
    public val config: SwarmConfig,
    public val localDeviceId: String,
    seed: Long,
) {
    private val random = SeededRandom(seed)
    public val strikeBook: StrikeBook = StrikeBook()
    private val requestWindow = RequestWindow(budgetBytes = config.profile.inFlightByteBudget)
    private val piecePicker = PiecePicker(random)
    private val sourceSelector = SourceSelector(random)
    private val servePolicy = ServePolicy()

    private val contents = LinkedHashMap<Pair<String, ContentRoot>, ContentState>()
    private val peers = LinkedHashMap<String, PeerConnectionState>()
    private val tombstones = LinkedHashMap<Pair<String, String>, SwarmTombstone>()

    /** Contents whose wait reason and row a HAVE touched while the request window was full (OPT-O08-07). */
    private val deferredWaitReasons = HashSet<Pair<String, ContentRoot>>()

    private var networkUp: Boolean = true
    private var systemSuspended: Boolean = false
    private var callActive: Boolean = false
    private var localServingEnabled: Boolean = config.servingEnabled
    private var freeStorageBytes: Long = Long.MAX_VALUE

    /** Thermal step-down state (OPT-O08-06); the profile and budget the engine is actually running with. */
    private var thermalStatus: FlashThermalStatus = FlashThermalStatus.NONE
    private var effectiveProfile: SwarmProfile = config.profile

    private val manifestHandler = ManifestHandler(strikeBook)
    private val serveHandler = ServeHandler(servePolicy, requestWindow, config)
    private val transferScheduler = TransferScheduler(piecePicker, sourceSelector, requestWindow, strikeBook)
    private val contentLifecycleHandler = ContentLifecycleHandler(localDeviceId, config, strikeBook)
    private val peerHandler = PeerHandler(peers, contents, tombstones) { tb, cmds -> applyTombstone(tb, cmds) }

    /** Time of the newest event; lets [snapshot] age a rate without a clock of its own. */
    private var lastEventMs: Long = 0L

    public fun handle(event: SwarmEvent): List<SwarmCommand> {
        if (event.nowMs > lastEventMs) lastEventMs = event.nowMs
        val commands = ArrayList<SwarmCommand>()
        when (event) {
            is SwarmEvent.Announced -> {
                contentLifecycleHandler.handleAnnounced(event, contents, tombstones, commands) { content ->
                    publishRow(content, commands)
                    if (content.state == SwarmLifecycleState.ACTIVE) {
                        updateWaitReason(content)
                        publishRow(content, commands)
                        if (content.role == SwarmRole.RECEIVER) {
                            manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands)
                            transferScheduler.scheduleRequests(
                                content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                        for ((peerId, peer) in peers) {
                            if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(content.groupId, peerId)) {
                                peerHandler.sendSummary(peerId, content.groupId, localServingEnabled, systemSuspended, commands, event.nowMs)
                            }
                        }
                    } else if (content.state == SwarmLifecycleState.OFFERED && content.role == SwarmRole.RECEIVER) {
                        // ERROR-119: an offer waits for a person, and everything that does not need the person's consent
                        // is done in that time so Accept starts the first piece at once: fetch the manifest (hashes
                        // only, no file data) and tell the group what this device holds (nothing), which makes the
                        // origin answer with its piece map. Without this the first request waited three round trips
                        // after Accept, and the sender saw nobody until then.
                        manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands, event.nowMs)
                        for ((peerId, peer) in peers) {
                            if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(content.groupId, peerId)) {
                                peerHandler.sendSummary(peerId, content.groupId, localServingEnabled, systemSuspended, commands, event.nowMs)
                            }
                        }
                    }
                }
            }
            is SwarmEvent.TombstonesRestored -> {
                for (tb in event.tombstones) tombstones.putIfAbsent(tb.groupId to tb.messageId, tb)
            }
            is SwarmEvent.Restored -> {
                contentLifecycleHandler.handleRestored(event, contents, tombstones, commands) { content ->
                    publishRow(content, commands)
                    if (content.state == SwarmLifecycleState.ACTIVE) {
                        updateWaitReason(content)
                        publishRow(content, commands)
                        if (content.role == SwarmRole.RECEIVER) {
                            manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands, event.nowMs)
                            transferScheduler.scheduleRequests(
                                content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                    }
                    if (content.state != SwarmLifecycleState.CANCELLED) {
                        for ((peerId, peer) in peers) {
                            if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(content.groupId, peerId)) {
                                peerHandler.sendSummary(peerId, content.groupId, localServingEnabled, systemSuspended, commands, event.nowMs)
                            }
                        }
                    }
                }
            }
            is SwarmEvent.Accepted -> {
                contents[event.groupId to event.root]?.let { content ->
                    contentLifecycleHandler.handleAccepted(event, content, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        manifestHandler.maybeFetchManifest(it, peers, ::isPeerAllowed, commands)
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                        for ((peerId, peer) in peers) {
                            if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(it.groupId, peerId)) {
                                peerHandler.sendSummary(peerId, it.groupId, localServingEnabled, systemSuspended, commands, event.nowMs)
                            }
                        }
                    }
                }
            }
            is SwarmEvent.ManifestPartArrived -> {
                contents[event.part.groupId to event.part.root]?.let { content ->
                    manifestHandler.handleManifestPart(event, content, commands) {
                        if (it.state == SwarmLifecycleState.ACTIVE) updateWaitReason(it)
                        publishRow(it, commands)
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
            is SwarmEvent.ManifestComplete -> {
                contents[event.groupId to event.root]?.let { content ->
                    manifestHandler.handleManifestComplete(
                        e = event,
                        content = content,
                        commands = commands,
                        onManifestReady = {
                            if (it.state == SwarmLifecycleState.ACTIVE) updateWaitReason(it)
                            publishRow(it, commands)
                            transferScheduler.scheduleRequests(
                                it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        },
                        retryFetch = { manifestHandler.maybeFetchManifest(it, peers, ::isPeerAllowed, commands) }
                    )
                }
            }
            is SwarmEvent.PieceArrived -> {
                contents[event.groupId to event.root]?.let { content ->
                    val peerState = peers[event.peerId]?.contentStates?.get(content.root)
                    transferScheduler.handlePieceArrived(event, content, peerState, commands) {
                        // Driver writes piece
                    }
                }
            }
            is SwarmEvent.PieceStored -> {
                contents[event.groupId to event.root]?.let { content ->
                    transferScheduler.handlePieceStored(event, content, peers, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        if (!it.isComplete) {
                            transferScheduler.scheduleRequests(
                                it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                    }
                }
            }
            is SwarmEvent.PieceStoreFailed -> {
                contents[event.groupId to event.root]?.let { content ->
                    transferScheduler.handlePieceStoreFailed(event, content, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                    }
                }
            }
            is SwarmEvent.PieceRead -> {
                contents[event.groupId to event.root]?.let { content ->
                    val isTombstoned = (content.groupId to content.messageId) in tombstones ||
                        isTombstoned(content.groupId, content.root) ||
                        content.state == SwarmLifecycleState.CANCELLED
                    val isAllowed = isPeerAllowed(content.groupId, event.peerId)
                    serveHandler.handlePieceRead(event, content, isTombstoned, isAllowed, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                    }
                }
            }
            is SwarmEvent.FinalizeResult -> {
                contents[event.groupId to event.root]?.let { content ->
                    transferScheduler.handleFinalizeResult(
                        event, content, peers, ::isPeerAllowed, commands
                    ) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        if (it.state == SwarmLifecycleState.ACTIVE) {
                            transferScheduler.scheduleRequests(
                                it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                    }
                }
            }
            is SwarmEvent.SourceStatusSigned -> {
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    // ERROR-109: only while the source is still lost; a late signature must not announce a loss that ended.
                    if (content.role == SwarmRole.ORIGIN && content.originSourceLost) {
                        content.signedSourceLost = event.frame
                        for ((peerId, peer) in peers) {
                            if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(content.groupId, peerId)) {
                                commands.add(SwarmCommand.Send(peerId = peerId, frame = event.frame))
                            }
                        }
                    }
                }
            }
            is SwarmEvent.SourceStatusArrived -> {
                if (!event.signatureValid) {
                    strikeBook.recordStrike(event.peerId, event.frame.root)
                    return commands
                }
                val target = contents[event.frame.groupId to event.frame.root]
                // ERROR-109: only the content's own origin can say its source is lost or back (a valid signature from any
                // other member is still not the origin), and an older statement than the one applied is a replay.
                if (target != null &&
                    (target.role != SwarmRole.RECEIVER ||
                        event.frame.originId != target.originId ||
                        event.frame.atMs < target.sourceStatusAtMs)
                ) {
                    return commands
                }
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    content.sourceStatusAtMs = event.frame.atMs
                    content.originSourceLost = event.frame.status == SourceState.LOST
                    updateWaitReason(content)
                    publishRow(content, commands)
                    transferScheduler.scheduleRequests(
                        content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                    )
                }
            }
            is SwarmEvent.PeerUp -> {
                peerHandler.handlePeerUp(event, localServingEnabled, systemSuspended, commands) {
                    recheckAllContents(event.nowMs, commands)
                }
            }
            is SwarmEvent.PeerDown -> {
                peerHandler.handlePeerDown(event, ::cancelPeerInFlight) {
                    recheckAllContents(event.nowMs, commands)
                }
            }
            is SwarmEvent.SummaryArrived -> {
                // ERROR-110: INV-3, a device outside the group exchanges nothing with us, tombstones included.
                if (!isPeerAllowed(event.frame.groupId, event.peerId)) return commands
                peerHandler.handleSummaryArrived(event, localServingEnabled, systemSuspended, commands) { gid ->
                    for (content in contents.values.filter { it.groupId == gid }) {
                        if (content.state == SwarmLifecycleState.ACTIVE) {
                            updateWaitReason(content)
                            publishRow(content, commands)
                            manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands)
                            transferScheduler.scheduleRequests(
                                content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                    }
                }
            }
            is SwarmEvent.HaveArrived -> {
                if (!isPeerAllowed(event.frame.groupId, event.peerId)) return commands
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    peerHandler.handleHaveArrived(event, content) {
                        // OPT-O08-07: in a 20-node mesh every stored piece reaches every member as HAVE, so this runs
                        // tens of times a second. Reclassifying the wait reason and republishing the row on each one
                        // rebuilt the scheduler's answer for no new request: while the request window is full, the
                        // next work can only happen when a piece arrives or the tick reschedules. The bitfield update
                        // above is never skipped, so nothing is lost — the answer is deferred, not dropped.
                        if (it.state != SwarmLifecycleState.ACTIVE ||
                            requestWindow.canRequestBytes(it.pieceSize.toLong())
                        ) {
                            updateWaitReason(it)
                            publishRow(it, commands)
                            transferScheduler.scheduleRequests(
                                it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        } else {
                            deferredWaitReasons.add(it.groupId to it.root)
                        }
                    }
                }
            }
            is SwarmEvent.HaveAllArrived -> {
                if (!isPeerAllowed(event.frame.groupId, event.peerId)) return commands
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    peerHandler.handleHaveAllArrived(event, content) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
            is SwarmEvent.RequestArrived -> {
                val content = contents[event.frame.groupId to event.frame.root]
                val isTombstoned = (event.frame.groupId to (content?.messageId ?: "")) in tombstones ||
                    isTombstoned(event.frame.groupId, event.frame.root) ||
                    content?.state == SwarmLifecycleState.CANCELLED
                serveHandler.handleRequestArrived(
                    e = event,
                    content = content,
                    isTombstoned = isTombstoned,
                    localServingEnabled = localServingEnabled,
                    systemSuspended = systemSuspended,
                    callActive = callActive,
                    countPeerCopies = { idx -> countPeerCopies(content, idx, requesterPeerId = event.peerId) },
                    countPendingServes = { idx -> countPendingServes(content, idx) },
                    commands = commands,
                    profile = effectiveProfile,
                    thermalServingBlocked = thermalServingBlocked,
                )
            }
            is SwarmEvent.UnrequestArrived -> {
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    serveHandler.handleUnrequestArrived(event, content)
                }
            }
            is SwarmEvent.RejectArrived -> {
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    val peerState = peers[event.peerId]?.contentStates?.get(content.root)
                    serveHandler.handleRejectArrived(event, content, peerState)
                    transferScheduler.scheduleRequests(
                        content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                    )
                }
            }
            is SwarmEvent.CancelArrived -> {
                contentLifecycleHandler.handleCancelArrived(
                    event, peers, ::isPeerAllowed, { tb -> applyTombstone(tb, commands) }, commands
                )
            }
            is SwarmEvent.CancelAckArrived -> { /* Log or no-op */ }
            is SwarmEvent.LocalCancel -> {
                contents[event.groupId to event.root]?.let { content ->
                    contentLifecycleHandler.handleLocalCancel(event, content, ::cancelInFlight, commands) {
                        publishRow(it, commands)
                    }
                }
            }
            is SwarmEvent.LocalPause -> {
                contents[event.groupId to event.root]?.let { content ->
                    contentLifecycleHandler.handleLocalPause(event, content, ::cancelInFlight, commands) {
                        publishRow(it, commands)
                    }
                }
            }
            is SwarmEvent.LocalResume -> {
                contents[event.groupId to event.root]?.let { content ->
                    contentLifecycleHandler.handleLocalResume(event, content, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        manifestHandler.maybeFetchManifest(it, peers, ::isPeerAllowed, commands)
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
            is SwarmEvent.TombstoneSigned -> {
                applyTombstone(event.tombstone, commands)
                for ((peerId, peer) in peers) {
                    if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(event.tombstone.groupId, peerId)) {
                        commands.add(
                            SwarmCommand.Send(
                                peerId = peerId,
                                frame = SwarmFrame.Cancel(
                                    groupId = event.tombstone.groupId,
                                    root = event.tombstone.root,
                                    originId = event.tombstone.originId,
                                    messageId = event.tombstone.messageId,
                                    reason = event.tombstone.reason,
                                    cancelledAtMs = event.tombstone.cancelledAtMs,
                                    signature = event.tombstone.signature,
                                ),
                            )
                        )
                    }
                }
            }
            is SwarmEvent.MembershipChanged -> {
                peerHandler.handleMembershipChanged(
                    event, localServingEnabled, systemSuspended, ::cancelPeerInFlight, ::cancelInFlight, commands
                ) {
                    updateWaitReason(it)
                    publishRow(it, commands)
                    if (it.state == SwarmLifecycleState.ACTIVE) {
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
            is SwarmEvent.NetworkUp -> {
                networkUp = true
                recheckAllContents(event.nowMs, commands)
            }
            is SwarmEvent.NetworkDown -> {
                networkUp = false
                for (content in contents.values) {
                    if (content.state == SwarmLifecycleState.ACTIVE) {
                        cancelInFlight(content)
                        updateWaitReason(content)
                        publishRow(content, commands)
                    }
                }
            }
            is SwarmEvent.SpaceChanged -> {
                freeStorageBytes = event.freeBytes
                recheckAllContents(event.nowMs, commands)
            }
            is SwarmEvent.SystemSuspend -> {
                systemSuspended = true
                for (content in contents.values) {
                    if (content.state == SwarmLifecycleState.ACTIVE) {
                        cancelInFlight(content)
                        updateWaitReason(content)
                        publishRow(content, commands)
                    }
                }
            }
            is SwarmEvent.SystemResume -> {
                systemSuspended = false
                recheckAllContents(event.nowMs, commands)
            }
            is SwarmEvent.ServingEnabled -> {
                localServingEnabled = event.enabled
            }
            is SwarmEvent.CallActive -> {
                callActive = event.active
                requestWindow.setCallActive(event.active)
            }
            is SwarmEvent.ThermalChanged -> applyThermalStatus(event.status)
            is SwarmEvent.Tick -> {
                transferScheduler.checkTimeouts(contents.values, peers, event.nowMs)
                transferScheduler.flushPendingHaves(contents.values, peers, ::isPeerAllowed, event.nowMs, commands)
                pruneTombstones(event.nowMs)

                for (content in contents.values) {
                    if (content.state != SwarmLifecycleState.ACTIVE) {
                        deferredWaitReasons.remove(content.groupId to content.root)
                    }
                    if (content.state != SwarmLifecycleState.COMPLETE &&
                        content.state != SwarmLifecycleState.CANCELLED &&
                        content.state != SwarmLifecycleState.FAILED &&
                        event.nowMs >= content.expiresAtMs
                    ) {
                        content.state = SwarmLifecycleState.FAILED
                        content.failReason = SwarmFailReason.EXPIRED.reasonCode
                        cancelInFlight(content)
                        commands.add(SwarmCommand.DeletePartial(content.groupId, content.root))
                        publishRow(content, commands)
                        commands.add(SwarmCommand.PersistRecord(content.toRecord(event.nowMs)))
                    } else if (content.state == SwarmLifecycleState.OFFERED &&
                        content.role == SwarmRole.RECEIVER && content.manifest == null
                    ) {
                        // The offer's manifest prefetch (see Announced) must survive a lost reply like the active one does.
                        if (content.manifestPeerInFlight != null && event.nowMs - content.manifestRequestSentAtMs > 5_000L) {
                            content.manifestPeerInFlight = null
                            content.manifestAssembler = null
                        }
                        manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands, event.nowMs)
                    } else if (content.state == SwarmLifecycleState.ACTIVE) {
                        updateWaitReason(content)
                        if (deferredWaitReasons.remove(content.groupId to content.root)) {
                            // OPT-O08-07: a HAVE that arrived while the request window was full is answered here.
                            publishRow(content, commands)
                        }
                        if (content.role == SwarmRole.ORIGIN &&
                            event.nowMs - content.lastRecipientPublishMs >= RECIPIENT_REPUBLISH_MS &&
                            peers.values.any { it.isConnected && it.contentStates[content.root]?.bitfield?.isComplete() != true }
                        ) {
                            // The sender's per-member rates move between events and decay when a member stalls.
                            content.lastRecipientPublishMs = event.nowMs
                            publishRow(content, commands)
                        }
                        if (content.role == SwarmRole.RECEIVER && content.manifest == null) {
                            if (content.manifestPeerInFlight != null && event.nowMs - content.manifestRequestSentAtMs > 5_000L) {
                                content.manifestPeerInFlight = null
                                content.manifestAssembler = null
                            }
                            manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands, event.nowMs)
                        }
                        transferScheduler.scheduleRequests(
                            content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
        }
        return commands
    }

    public fun snapshot(): SwarmSnapshot {
        val map = LinkedHashMap<Pair<String, ContentRoot>, ContentSnapshot>()
        for ((key, c) in contents) {
            map[key] = contentSnapshotOf(c)
        }
        return SwarmSnapshot(map)
    }

    /**
     * The snapshot of one content item. A row publish needs only its own content; [snapshot] rebuilt every content's
     * holder statistics for each row, which is the same O(pieces × peers) work the wait classifier used to throw away
     * (OPT-O25-08).
     */
    internal fun contentSnapshot(groupId: String, root: ContentRoot): ContentSnapshot? =
        contents[groupId to root]?.let { contentSnapshotOf(it) }

    private fun contentSnapshotOf(c: ContentState): ContentSnapshot {
        val (onlineHolders, distCopies) = computeHolderStats(c)
        return ContentSnapshot(
            groupId = c.groupId,
            root = c.root,
            state = c.state,
            waitReason = c.waitReason,
            bytesDone = c.bytesDone,
            totalBytes = c.totalSize,
            piecesDone = c.piecesDone,
            totalPieces = c.totalPieces,
            holdersOnline = onlineHolders,
            distributedCopies = distCopies,
            canGoOffline = c.role == SwarmRole.ORIGIN && distCopies >= 1,
            deliveredTo = c.deliveredTo.toSet(),
            pieceBlocks = computePieceBlocks(c),
            recipients = computeRecipients(c),
        )
    }

    public fun debugDump(): String {
        val sb = StringBuilder()
        for ((key, c) in contents) {
            sb.appendLine("Content ${c.groupId}:${c.root.hex}: state=${c.state}, wait=${c.waitReason}, inFlight=${c.inFlightRequests.entries.map { "${it.key.first.take(6)}:${it.key.second}@${it.value}" }}, bits=${c.bitfield.count()}/${c.totalPieces}")
        }
        for ((peerId, p) in peers) {
            sb.appendLine("Peer ${peerId.take(6)}: isConnected=${p.isConnected}, sw1=${p.hasSw1Feature}")
            for ((root, pcs) in p.contentStates) {
                sb.appendLine("  pcs ${root.hex.take(8)}: bitsCount=${pcs.bitfield.count()}, servingEnabled=${pcs.servingEnabled}, backoff=${pcs.backoffUntilMs}, win=${requestWindow.getWindow(peerId, root)}, inFlightBytes=${requestWindow.currentInFlightBytes}")
            }
        }
        return sb.toString()
    }

    /**
     * True while at least one content is offered or transferring: the only states whose tick can produce work.
     * With no such content the driver ticks slowly and wakes early on an announcement or an inbound frame (OPT-O08-05).
     */
    internal fun hasActiveWork(): Boolean {
        for (content in contents.values) {
            if (content.state == SwarmLifecycleState.ACTIVE || content.state == SwarmLifecycleState.OFFERED) return true
        }
        return false
    }

    /** The in-flight request budget the engine is currently running with, for the thermal step-down test. */
    internal val inFlightBudgetBytes: Long get() = requestWindow.currentBudgetBytes

    /** The serve profile in force after any thermal step-down (OPT-O08-06). */
    internal val activeServeProfile: SwarmProfile get() = effectiveProfile

    /**
     * OPT-O08-06: follow the host's thermal governor. `MODERATE` steps the serve profile down one notch, `SEVERE` and
     * above drop to [SwarmProfile.LOW] **and** stop serving; recovery restores the configured profile and the user's
     * own serving choice, which this never overwrites. The governor applies its own dwell/hysteresis, so a flapping
     * sensor does not flap this.
     */
    private fun applyThermalStatus(status: FlashThermalStatus) {
        if (status == thermalStatus) return
        thermalStatus = status
        val next = when (status) {
            FlashThermalStatus.NONE, FlashThermalStatus.LIGHT -> config.profile
            FlashThermalStatus.MODERATE -> when (config.profile) {
                SwarmProfile.HIGH -> SwarmProfile.MEDIUM
                SwarmProfile.MEDIUM, SwarmProfile.LOW -> SwarmProfile.LOW
            }
            FlashThermalStatus.SEVERE, FlashThermalStatus.CRITICAL, FlashThermalStatus.EMERGENCY -> SwarmProfile.LOW
        }
        if (next != effectiveProfile) {
            effectiveProfile = next
            requestWindow.setBudgetBytes(next.inFlightByteBudget)
        }
    }

    /** Serving is blocked by heat only at SEVERE and above; the profile step-down covers MODERATE. */
    private val thermalServingBlocked: Boolean
        get() = thermalStatus >= FlashThermalStatus.SEVERE

    private fun cancelInFlight(content: ContentState) {
        for ((key, _) in content.inFlightRequests) {
            val pLen = content.pieceLength(key.second).toLong()
            requestWindow.removeInFlightBytes(pLen)
        }
        content.inFlightRequests.clear()
        content.inFlightByPiece.clear()
    }

    private fun cancelPeerInFlight(content: ContentState, peerId: String) {
        val toRemove = ArrayList<Int>()
        for ((key, _) in content.inFlightRequests) {
            if (key.first == peerId) toRemove.add(key.second)
        }
        for (p in toRemove) {
            content.inFlightRequests.remove(peerId to p)
            content.inFlightByPiece[p]?.remove(peerId)
            val pLen = content.pieceLength(p).toLong()
            requestWindow.removeInFlightBytes(pLen)
        }
    }

    private fun isPeerAllowed(groupId: String, peerId: String): Boolean {
        return peers[peerId]?.allowedGroups?.get(groupId) ?: true
    }

    private fun updateWaitReason(content: ContentState) {
        // OPT-O25-08: this used to compute the full holder statistics (O(pieces × peers)) and throw the result away,
        // then walk every piece twice more looking for a holder of a missing piece. Both questions are now answered
        // a 64-bit word at a time.
        val isOriginConn = peers[content.originId]?.isConnected == true

        var hasOnlineHoldersForMissing = false
        var hasOfflineHolders = false
        for (peer in peers.values) {
            if (peer.peerId == localDeviceId) continue
            if (!isPeerAllowed(content.groupId, peer.peerId)) continue
            val pcs = peer.contentStates[content.root] ?: continue
            if (pcs.bitfield.isEmpty()) continue
            if (!pcs.bitfield.hasBitOutside(content.bitfield)) continue
            if (peer.isConnected) {
                hasOnlineHoldersForMissing = true
                if (hasOfflineHolders) break
            } else {
                hasOfflineHolders = true
                if (hasOnlineHoldersForMissing) break
            }
        }

        content.waitReason = WaitClassifier.classify(
            WaitClassificationContext(
                systemSuspended = systemSuspended,
                networkUp = networkUp,
                freeBytes = freeStorageBytes,
                remainingBytes = content.remainingBytes,
                storageUnavailable = content.storageUnavailable,
                isOrigin = content.role == SwarmRole.ORIGIN,
                isOriginConnected = isOriginConn,
                isOriginSourceLost = content.originSourceLost,
                hasConnectedHoldersForMissing = hasOnlineHoldersForMissing,
                hasDisconnectedHoldersForMissing = hasOfflineHolders,
            )
        )
    }

    private fun publishRow(content: ContentState, commands: MutableList<SwarmCommand>) {
        commands.add(
            SwarmCommand.PublishRow(
                groupId = content.groupId,
                root = content.root,
                state = content.state,
                waitReason = content.waitReason,
                bytesDone = content.bytesDone,
                totalSize = content.totalSize,
                failReason = content.failReason,
                cancelReason = if (content.state == SwarmLifecycleState.CANCELLED) {
                    tombstones[content.groupId to content.messageId]?.reason
                } else null,
            )
        )
    }

    private fun applyTombstone(tombstone: SwarmTombstone, commands: MutableList<SwarmCommand>) {
        val key = tombstone.groupId to tombstone.messageId
        if (key in tombstones) return
        tombstones[key] = tombstone
        commands.add(SwarmCommand.PersistTombstone(tombstone))

        val content = contents[tombstone.groupId to tombstone.root] ?: return
        if (content.messageId == tombstone.messageId) {
            content.activeRequesters.clear()
            content.outstandingServeBytes.clear()
            if (content.state == SwarmLifecycleState.COMPLETE) {
                // Keep file, stop serving
            } else {
                content.state = SwarmLifecycleState.CANCELLED
                cancelInFlight(content)
                commands.add(SwarmCommand.DeletePartial(content.groupId, content.root))
                commands.add(SwarmCommand.PersistRecord(content.toRecord(tombstone.cancelledAtMs)))
                publishRow(content, commands)
            }
        }
    }

    private fun isTombstoned(groupId: String, root: ContentRoot): Boolean {
        return tombstones.values.any { it.groupId == groupId && it.root == root }
    }

    /**
     * Drops tombstones that are past their retention (OPT-O08-08, OPT-O02-05). The map is loaded from storage at start
     * and was never pruned while the process ran, so every Summary carried the whole history — 64 of them make a Summary
     * too large to encode at all. The window matches the persisted row's (37 days from the cancellation); the database
     * rows are purged by the host's startup sweep, which this does not replace.
     */
    private fun pruneTombstones(nowMs: Long) {
        if (tombstones.isEmpty()) return
        val iterator = tombstones.entries.iterator()
        while (iterator.hasNext()) {
            val tb = iterator.next().value
            if (tb.cancelledAtMs > 0L && nowMs - tb.cancelledAtMs > TOMBSTONE_RETENTION_MS) iterator.remove()
        }
    }

    private fun recheckAllContents(nowMs: Long, commands: MutableList<SwarmCommand>) {
        for (content in contents.values) {
            if (content.state == SwarmLifecycleState.ACTIVE) {
                updateWaitReason(content)
                publishRow(content, commands)
                transferScheduler.scheduleRequests(
                    content, peers, ::isPeerAllowed, nowMs, systemSuspended, networkUp, commands
                )
            }
        }
    }

    /** Real piece state for the UI map: this device's bitfield, requests in flight, connected members' bitfields. */
    private fun computePieceBlocks(content: ContentState): List<Int> {
        val peerBitfields = peers.values
            .filter { it.isConnected && it.peerId != localDeviceId && isPeerAllowed(content.groupId, it.peerId) }
            .mapNotNull { it.contentStates[content.root]?.bitfield }
        return PieceBlocks.compute(
            totalPieces = content.totalPieces,
            local = content.bitfield,
            inFlight = content.inFlightByPiece.keys,
            peerBitfields = peerBitfields,
        )
    }

    /**
     * Origin only. Lists every group member this device has seen that is connected, plus every member known to have the
     * whole file even if it has since gone offline. A connected member that has announced nothing yet is listed with
     * 0 bytes: it has not accepted (or has just accepted) and the sender should see that it is there.
     */
    private fun computeRecipients(content: ContentState): List<RecipientSnapshot> {
        if (content.role != SwarmRole.ORIGIN) return emptyList()
        val result = ArrayList<RecipientSnapshot>()
        for (p in peers.values) {
            if (p.peerId == localDeviceId || !isPeerAllowed(content.groupId, p.peerId)) continue
            val pcs = p.contentStates[content.root]
            val hasAll = p.peerId in content.deliveredTo || (pcs != null && pcs.bitfield.isComplete())
            if (!p.isConnected && !hasAll) continue
            val growing = pcs != null && !hasAll && p.isConnected && lastEventMs - pcs.lastGrowthAtMs <= RECIPIENT_RATE_STALE_MS
            result.add(
                RecipientSnapshot(
                    peerId = p.peerId,
                    bytesHeld = if (hasAll) content.totalSize else pcs?.heldBytes ?: 0L,
                    totalBytes = content.totalSize,
                    hasAll = hasAll,
                    online = p.isConnected,
                    rateBytesPerSec = if (growing) pcs.holdRateBps.toLong() else 0L,
                )
            )
        }
        return result.sortedBy { it.peerId }
    }

    private fun computeHolderStats(content: ContentState): Pair<Int, Int> {
        val candidatePeers = peers.values.filter {
            it.isConnected && it.peerId != localDeviceId && isPeerAllowed(content.groupId, it.peerId)
        }
        var onlineHolders = 0
        var union: LongArray? = null
        val words = (content.totalPieces + 63) ushr 6
        for (p in candidatePeers) {
            val pcs = p.contentStates[content.root]
            if (pcs != null && !pcs.bitfield.isEmpty()) {
                onlineHolders++
                if (words > 0) {
                    val bits = union ?: LongArray(words).also { union = it }
                    for (w in 0 until words) bits[w] = bits[w] or pcs.bitfield.rawWord(w)
                }
            }
        }

        // `distributedCopies` is the smallest number of connected holders of any piece. Zero as soon as one piece has
        // no holder — which is the normal answer while a transfer is still running, and now costs one word-wise pass.
        var minCopies = 0
        if (candidatePeers.isNotEmpty() && words > 0) {
            val bits = union ?: LongArray(words)
            var complete = true
            for (w in 0 until words) {
                val mask = if (w == words - 1) {
                    val tail = content.totalPieces and 63
                    if (tail == 0) -1L else (1L shl tail) - 1L
                } else {
                    -1L
                }
                if (bits[w] and mask != mask) {
                    complete = false
                    break
                }
            }
            if (complete) minCopies = exactMinCopies(content, candidatePeers, words)
        }
        return onlineHolders to minCopies
    }

    /**
     * The smallest per-piece holder count when every piece is held by at least one candidate. Bit-sliced over 64-bit
     * words, so the cost is `peers × words × levels` instead of `pieces × peers` (OPT-O25-08). Only reached when the
     * word-wise union already showed that no piece is missing from the swarm.
     */
    private fun exactMinCopies(
        content: ContentState,
        candidatePeers: List<PeerConnectionState>,
        words: Int,
    ): Int {
        val levels = 32 - candidatePeers.size.coerceAtLeast(1).countLeadingZeroBits()
        val planes = Array(levels) { LongArray(words) }
        for (p in candidatePeers) {
            val bf = p.contentStates[content.root]?.bitfield ?: continue
            for (w in 0 until words) {
                var carry = bf.rawWord(w)
                if (carry == 0L) continue
                for (l in 0 until levels) {
                    val next = planes[l][w] xor carry
                    carry = planes[l][w] and carry
                    planes[l][w] = next
                }
            }
        }
        var min = Int.MAX_VALUE
        for (w in 0 until words) {
            // Every position needs its count, so walk the pieces of this word.
            for (b in 0 until 64) {
                val piece = (w shl 6) + b
                if (piece >= content.totalPieces) break
                var count = 0
                for (l in 0 until levels) {
                    count = count or (((planes[l][w] ushr b) and 1L).toInt() shl l)
                }
                if (count < min) min = count
            }
        }
        return if (min == Int.MAX_VALUE) 0 else min
    }

    private fun countPeerCopies(content: ContentState?, index: Int, requesterPeerId: String? = null): Int {
        if (content == null) return 0
        var count = 0
        for ((peerId, peer) in peers) {
            if (peerId != localDeviceId && (requesterPeerId == null || peerId != requesterPeerId) && peer.isConnected && peer.hasSw1Feature) {
                if (!isPeerAllowed(content.groupId, peerId)) continue
                if (strikeBook.isBanned(peerId, content.root)) continue
                val pcs = peer.contentStates[content.root]
                if (pcs != null && !pcs.servingEnabled) continue

                val fromHave = pcs != null && pcs.bitfield.get(index)
                val fromOrigin = content.role == SwarmRole.ORIGIN && content.originServedPiecesByPeer[peerId]?.contains(index) == true
                if (fromHave || fromOrigin) {
                    count++
                }
            }
        }
        return count
    }

    private fun countPendingServes(content: ContentState?, index: Int): Int {
        return content?.pendingServesByPiece?.get(index) ?: 0
    }
}

private const val RECIPIENT_RATE_STALE_MS = 4_000L
private const val RECIPIENT_REPUBLISH_MS = 1_000L

/** How long a tombstone is kept in memory — the same 37-day window `RoomSwarmStateStore` persists (O08-08). */
private const val TOMBSTONE_RETENTION_MS = 37L * 24 * 60 * 60 * 1000L
