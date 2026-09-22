@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.id.UuidIdGenerator
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.chunked.ChunkSource
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.FileMeta
import com.transfer.flash.core.transfer.chunked.ResumeBitVector
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.concurrent.PlatformLock
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.transfer.multistream.MultiStreamDispatcher
import com.transfer.flash.core.transfer.multistream.MultiStreamResult
import com.transfer.flash.core.transfer.multistream.RollingRateMeter
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import com.transfer.flash.core.transfer.store.TransferStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// [FileSourceOpener] — the URI → stream seam this repository is constructed with — moved to
// `commonMain/FileSourceOpener.kt` in Phase 13B-2 and its `open()` now returns `okio.Source`.
// Same package, so no import is needed here and no consumer's `fileSourceOpener = { … }` lambda
// changes shape; the two out-of-module ones each gained a `.source()` bridge call.

/**
 * Concrete implementation of [FlashTransferRepository] (C5.2).
 * Orchestrates multi-stream chunked file transfers over [MultiStreamDispatcher],
 * persists transfer progress and resume states through the optional [TransferStore] port
 * (null = run without persistence; only resume-across-restart is disabled), and exposes reactive UI state.
 */
public class RealFlashTransferRepository(
    private val chunker: Chunker = Chunker(),
    private val streamChannelFactory: StreamChannelFactory,
    private val fileSourceOpener: FileSourceOpener,
    private val store: TransferStore? = null,
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultStreams: Int = 2,
    /**
     * When true (production wiring), every OUTBOUND send parks after emitting FILE_START and does
     * NOT stream chunks until the receiver explicitly accepts (a RESUME control frame). This is the
     * sender half of the inbound offer/accept gate (#5): a compliant sender sends nothing to drop,
     * so no chunk is ever lost to the pre-accept window (the dispatcher does not retransmit merely
     * un-ACKed chunks). Defaults off so unit tests keep their immediate-streaming contract.
     */
    private val requireReceiverAcceptance: Boolean = false,
    /** Predicate indicating whether a given peer device has an established encrypted channel. */
    private val isPeerEncrypted: (peerDeviceId: String) -> Boolean = { false },
    private val performanceMode: () -> FlashPerformanceMode = { FlashPerformanceMode.HIGH },
) : FlashTransferRepository {

    private val _activeTransfers = MutableStateFlow<List<FlashTransfer>>(emptyList())
    override val activeTransfers: StateFlow<List<FlashTransfer>> = _activeTransfers.asStateFlow()

    /**
     * Receive-side intake control. Emitted when an INBOUND transfer is paused/resumed/cancelled.
     * The transport host stops draining the session's inbound channel while any inbound
     * transfer is paused — TCP backpressure then throttles the sender (no wire protocol change).
     */
    private val _incomingControl = MutableSharedFlow<IncomingControl>(extraBufferCapacity = 16)
    public val incomingControl: MutableSharedFlow<IncomingControl> = _incomingControl

    public data class IncomingControl(val transferId: String, val action: String)

    /**
     * Wire-level control frames to deliver to the counterpart peer (`FLASH_XFER`, ADR-018):
     * pause/resume/cancel so BOTH sides reflect state and stop/start deterministically instead
     * of relying on TCP backpressure alone (which cannot reach dedicated data channels).
     */
    private val _outgoingControl = MutableSharedFlow<OutgoingControl>(extraBufferCapacity = 16)
    public val outgoingControl: MutableSharedFlow<OutgoingControl> = _outgoingControl

    public data class OutgoingControl(val transferId: String, val peerDeviceId: String?, val action: String)

    public companion object {
        public const val ACTION_PAUSE: String = "pause"
        public const val ACTION_RESUME: String = "resume"
        public const val ACTION_CANCEL: String = "cancel"

        /**
         * Local-only intake actions for the inbound offer gate (#5). These never go on the wire as
         * an XFER `action` — accept maps to a RESUME sent to the peer, decline to a CANCEL. They
         * travel on [incomingControl] so the host can resolve/drop the deferred pipeline sink.
         */
        public const val ACTION_ACCEPT: String = "accept"
        public const val ACTION_DECLINE: String = "decline"
    }

    /**
     * Guards the three per-transfer registry maps below ([runningJobs], [runningDispatchers],
     * [pauseIntents]), which were `ConcurrentHashMap` / `ConcurrentHashMap.newKeySet()` before
     * Phase 13B-3d. `java.util.concurrent` has no `commonMain` equivalent, so the concurrent
     * collections become plain ones behind a [PlatformLock].
     *
     * Two rules keep it deadlock-free, and both are load-bearing:
     *  * **No suspension inside a critical section.** `PlatformLock.withLock` cannot be `inline`
     *    (it is an `expect class` member), so the compiler enforces this for us.
     *  * **No call-out inside a critical section.** `MultiStreamDispatcher.setPaused`,
     *    `Job.cancel` and `MultiStreamDispatcher.onInboundFrame` all take locks of their own and
     *    the last can reach a host callback, so this lock is released before every one of them —
     *    read or remove under the lock, then act on the returned value.
     *
     * [receiverDone] deliberately gets its own lock, not this one: the two sets of state are never
     * touched together, and the inbound-chunk path must not queue behind the send-side registry.
     */
    private val registryLock = PlatformLock()
    private val runningJobs = mutableMapOf<String, Job>()
    private val runningDispatchers = mutableMapOf<String, MultiStreamDispatcher>()

    /**
     * Transfer ids whose transmission should be paused, recorded independently of whether a
     * dispatcher exists yet. Guarded by [registryLock].
     *
     * `sendFile` returns as soon as the send coroutine is launched, but the dispatcher only lands
     * in [runningDispatchers] after the resume-chunk DAO query and dispatcher construction have
     * run. A pause issued inside that window used to be a silent no-op — the state flipped to
     * Paused and `executeSend` immediately overwrote it with Transferring, so the sending device
     * "could not pause" at all. The intent survives that race and is applied the moment the
     * dispatcher is registered.
     */
    private val pauseIntents = mutableSetOf<String>()

    /**
     * Voice-call quiet hint. While active, live and newly registered dispatchers reduce only their
     * progress-watcher cadence; ACK ingestion and chunk transmission remain unchanged.
     */
    @Volatile
    public var voiceCallActive: Boolean = false
        set(value) {
            field = value
            val dispatchers = registryLock.withLock { runningDispatchers.values.toList() }
            dispatchers.forEach { it.quietWatcherHint = value }
        }

    /** Emits a wire control intent, logging drops instead of losing them silently. */
    private fun emitOutgoing(transferId: String, peerDeviceId: String?, action: String) {
        if (!_outgoingControl.tryEmit(OutgoingControl(transferId, peerDeviceId, action))) {
            runCatching {
                FlashLog.w(
                    "TRANSFER",
                    "Dropped outgoing control action=$action transferId=$transferId (no collector / buffer full)",
                )
            }
        }
    }

    /** Emits a local intake-gate intent, logging drops instead of losing them silently. */
    private fun emitIncoming(transferId: String, action: String) {
        if (!_incomingControl.tryEmit(IncomingControl(transferId, action))) {
            runCatching {
                FlashLog.w(
                    "TRANSFER",
                    "Dropped incoming control action=$action transferId=$transferId (no collector / buffer full)",
                )
            }
        }
    }

    override suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
    ): FlashResult<FlashTransferId> =
        sendFile(targetDevice, fileUri, displayName, fileSize, wireFileId = null)

    override suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        wireFileId: String?,
    ): FlashResult<FlashTransferId> =
        sendFileWithIdentity(
            targetDevice = targetDevice,
            fileUri = fileUri,
            displayName = displayName,
            fileSize = fileSize,
            transferIdString = UuidIdGenerator.newId(),
            fileId = wireFileId ?: UuidIdGenerator.newId(),
        )

    override suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        transferId: String,
        wireFileId: String,
    ): FlashResult<FlashTransferId> {
        if (transferId.isBlank() || wireFileId.isBlank()) {
            return FlashResult.Failure(FlashError.Unknown("Transfer identities must not be blank"))
        }
        return sendFileWithIdentity(
            targetDevice = targetDevice,
            fileUri = fileUri,
            displayName = displayName,
            fileSize = fileSize,
            transferIdString = transferId,
            fileId = wireFileId,
        )
    }

    private suspend fun sendFileWithIdentity(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        transferIdString: String,
        fileId: String,
    ): FlashResult<FlashTransferId> {
        val transferId = FlashTransferId(transferIdString)

    val initialTransfer = FlashTransfer(
        id = transferId,
        peerName = targetDevice.friendlyName,
        fileName = displayName,
        direction = FlashTransferDirection.Sending,
        bytesDone = 0L,
        bytesTotal = fileSize,
        state = FlashTransferState.Queued,
        sourceUri = fileUri,
        wireFileId = fileId,
        peerDeviceId = targetDevice.id.value,
        errorMessage = if (requireReceiverAcceptance) "waiting for receiver to accept" else null,
        isEncrypted = isPeerEncrypted(targetDevice.id.value),
    )

    // #5 sender half: park before streaming until the receiver accepts (arrives as RESUME). We
    // reuse the pause-intent race machinery — seeding it BEFORE the send job launches guarantees
    // the dispatcher starts paused the instant it registers, so only FILE_START (the offer) goes
    // out. A RESUME that beats dispatcher registration clears the intent and the sender streams
    // immediately; either ordering is safe.
    if (requireReceiverAcceptance) {
        registryLock.withLock { pauseIntents.add(transferIdString) }
    }

    _activeTransfers.update { it + initialTransfer }
    store?.insertTransfer(
        transferId = transferIdString,
        totalBytes = fileSize,
        status = FlashTransferState.Queued.name,
    )

        val job = repositoryScope.launch(workerDispatcher) {
            executeSend(
                transferId = transferIdString,
                fileId = fileId,
                fileUri = fileUri,
                displayName = displayName,
                fileSize = fileSize,
                peerName = targetDevice.friendlyName,
                peerDeviceId = targetDevice.id.value,
            )
        }
        registryLock.withLock { runningJobs[transferIdString] = job }

        return FlashResult.Success(transferId)
    }

    private suspend fun executeSend(
        transferId: String,
        fileId: String,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        peerName: String,
        peerDeviceId: String? = null,
    ) {
        val meta = FileMeta(
            transferId = transferId,
            fileId = fileId,
            fileName = displayName,
            totalBytes = fileSize,
        )

        val doneIndexes = store?.doneChunks(transferId) ?: emptyList()
        val source = ChunkSource { fileSourceOpener.open(fileUri) }

        val transferProfile = performanceMode().transfer
        val resolvedStreams = if (defaultStreams != 2) defaultStreams else transferProfile.streamCount
        val dispatcher = MultiStreamDispatcher(
            chunker = chunker,
            meta = meta,
            source = source,
            factory = streamChannelFactory,
            streamCount = resolvedStreams,
            requestedChunkSize = transferProfile.chunkSizeBytes,
            feedBufferFrames = transferProfile.feedBufferFrames,
            sharedBufferFrames = transferProfile.sharedBufferFrames,
            doneIndexes = doneIndexes,
            workerDispatcher = workerDispatcher,
            peerDeviceId = peerDeviceId,
        )
        registryLock.withLock { runningDispatchers[transferId] = dispatcher }
        // A call that went active while this dispatcher was being built still quiets it.
        dispatcher.quietWatcherHint = voiceCallActive

        // Honour a pause requested before this dispatcher existed (see [pauseIntents]).
        applyPendingPauseOrStart(transferId, dispatcher)

        // Persist only newly confirmed chunks. The watcher emits every 10 ms (or 250 ms while a
        // voice call is active), so whole-set snapshots and per-tick row writes are needlessly
        // quadratic and expensive on slow storage.
        val persistedChunks = ResumeBitVector(dispatcher.totalChunks).also { it.reconcile(doneIndexes) }
        var lastConfirmedCount = -1
        var persistedBytesDone = -1L
        val progressJob = repositoryScope.launch(workerDispatcher) {
            dispatcher.progress.collect { progress ->
                updateTransferState(transferId) {
                    it.copy(
                        bytesDone = progress.bytesDone,
                        // Rate is -1.0 until the rolling window holds two samples; a negative
                        // speed must never reach the UI.
                        speedBytesPerSec = progress.instantBytesPerSec.coerceAtLeast(0.0).toLong(),
                        etaSeconds = if (progress.etaMs >= 0) progress.etaMs / 1000 else -1L,
                    )
                }

                val confirmedCount = dispatcher.confirmedCountSnapshot()
                if (confirmedCount != lastConfirmedCount) {
                    val fresh = dispatcher.confirmedIndexesNotIn(persistedChunks)
                    if (fresh.isNotEmpty()) {
                        store?.markChunksDone(transferId, fresh)
                        fresh.forEach { persistedChunks.markReceived(it) }
                    }
                    lastConfirmedCount = confirmedCount
                    if (progress.bytesDone != persistedBytesDone) {
                        store?.setBytesDone(transferId, progress.bytesDone)
                        persistedBytesDone = progress.bytesDone
                    }
                }
            }
        }

        try {
            when (val result = dispatcher.send()) {
                is MultiStreamResult.Completed -> {
                    progressJob.cancel()
                    updateTransferState(transferId) {
                        it.copy(
                            bytesDone = fileSize,
                            state = FlashTransferState.Completed,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                        )
                    }
                    store?.setStatus(transferId, FlashTransferState.Completed.name)
                    store?.setBytesDone(transferId, fileSize)
                }

                is MultiStreamResult.Failed -> {
                    progressJob.cancel()
                    // Persist unconfirmed chunks if needed
                    val unconfirmed = result.unconfirmedIndexes
                    updateTransferState(transferId) {
                        it.copy(
                            state = FlashTransferState.Failed,
                            errorMessage = result.reason,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                        )
                    }
                    store?.setStatus(transferId, FlashTransferState.Failed.name)
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Cooperative pause/cancel: the caller (pauseTransfer/cancelTransfer) already set
            // the authoritative terminal state. Marking Failed here would overwrite it
            // (media-downloader DownloadManager.handleCancellation pattern). Re-throw so
            // structured concurrency sees normal cancellation.
            progressJob.cancel()
            throw ce
        } catch (t: Throwable) {
            progressJob.cancel()
            updateTransferState(transferId) {
                it.copy(
                    state = FlashTransferState.Failed,
                    errorMessage = t.message ?: "Transfer aborted unexpectedly",
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L,
                )
            }
            store?.setStatus(transferId, FlashTransferState.Failed.name)
        } finally {
            // Retire only OUR registrations: a resume that relaunched this transferId may already
            // have registered a replacement dispatcher/job under the same key, and blindly
            // removing here would orphan the live transfer (pause/resume/cancel would stop
            // reaching it).
            //
            // The identity check was `ConcurrentHashMap.remove(key, value)` — `ConcurrentMap`'s
            // two-arg compare-and-remove — before 13B-3d. Under [registryLock] the check and all
            // three removals are one critical section, so the retirement is now atomic as a
            // *trio* rather than atomic per map. That is strictly stronger than what it replaces
            // (no observer can catch a half-retired transfer); it is a consequence of the
            // conversion, not a fix this sub-step went looking for.
            registryLock.withLock {
                if (runningDispatchers[transferId] === dispatcher) {
                    runningDispatchers.remove(transferId)
                    runningJobs.remove(transferId)
                    pauseIntents.remove(transferId)
                }
            }
        }
    }

    /**
     * Moves a freshly registered send into either Transferring or Paused, depending on whether a
     * pause was requested while the dispatcher was still being built (see [pauseIntents]).
     *
     * The intent is re-checked AFTER the Transferring write: [pauseTransfer] records its intent
     * before it looks the dispatcher up, so between the two orderings one side always observes the
     * other and the pause can no longer be lost.
     */
    private suspend fun applyPendingPauseOrStart(
        transferId: String,
        dispatcher: MultiStreamDispatcher,
    ) {
        suspend fun enterPaused() {
            dispatcher.setPaused(true)
            updateTransferState(transferId) {
                it.copy(state = FlashTransferState.Paused, speedBytesPerSec = 0L, etaSeconds = -1L)
            }
            store?.setStatus(transferId, FlashTransferState.Paused.name)
        }

        // Each probe takes [registryLock] on its own rather than wrapping the block: `enterPaused`
        // suspends, and a non-inline `withLock` cannot contain a suspension point.
        if (registryLock.withLock { transferId in pauseIntents }) {
            enterPaused()
            return
        }
        updateTransferState(transferId) { it.copy(state = FlashTransferState.Transferring) }
        store?.setStatus(transferId, FlashTransferState.Transferring.name)
        if (registryLock.withLock { transferId in pauseIntents }) enterPaused()
    }

    override suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit> {
        val transfer = _activeTransfers.value.find { it.id == transferId }
            ?: return FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Transfer not found: ${transferId.value}"))
        val jobPresent = registryLock.withLock { transferId.value in runningJobs }
        runCatching {
            FlashLog.i(
                "TRANSFER",
                "pauseTransfer id=${transferId.value} direction=${transfer.direction} state=${transfer.state} jobPresent=$jobPresent",
            )
        }

        if (transfer.direction == FlashTransferDirection.Receiving) {
            // Inbound: gate local intake AND tell the sender to stop transmitting
            // (application-level; TCP backpressure cannot reach dedicated data channels).
            updateTransferState(transferId.value) {
                it.copy(state = FlashTransferState.Paused, speedBytesPerSec = 0L, etaSeconds = -1L)
            }
            emitIncoming(transferId.value, ACTION_PAUSE)
            emitOutgoing(transferId.value, transfer.peerDeviceId, ACTION_PAUSE)
            return FlashResult.Success(Unit)
        }

        // Outbound: COOPERATIVE pause — flip the dispatcher flag (instant, reversible).
        // Cancelling the job here was unreliable (blocking socket writes swallow cancellation)
        // and made resume relaunch a second dispatcher while the first kept running.
        //
        // The intent is recorded FIRST and unconditionally: when the dispatcher is still being
        // constructed there is nothing to flip yet, and executeSend applies the intent as soon as
        // it registers (previously this branch pause was silently overwritten by Transferring).
        //
        // Recording the intent and reading the dispatcher in ONE critical section is what makes
        // "first" mean first: `executeSend`'s registration can no longer slip between them.
        // `setPaused` itself is a call-out and runs after the lock is released.
        val paused = registryLock.withLock {
            pauseIntents.add(transferId.value)
            runningDispatchers[transferId.value]
        }
        paused?.setPaused(true)
        updateTransferState(transferId.value) {
            it.copy(state = FlashTransferState.Paused, speedBytesPerSec = 0L, etaSeconds = -1L)
        }
        store?.setStatus(transferId.value, FlashTransferState.Paused.name)
        // Always tell the peer, dispatcher or not: it stops ACK/intake churn on its side and keeps
        // both UIs in lockstep.
        emitOutgoing(transferId.value, transfer.peerDeviceId, ACTION_PAUSE)
        return FlashResult.Success(Unit)
    }

    override suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit> {
        val transfer = _activeTransfers.value.find { it.id == transferId }
            ?: return FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Transfer not found: ${transferId.value}"))

        // Terminal transfers have nothing to resume.
        if (transfer.state == FlashTransferState.Completed) {
            return FlashResult.Success(Unit)
        }

        val dispatcher = registryLock.withLock { runningDispatchers[transferId.value] }
        // isActive, not mere presence: executeSend's finally only retires its OWN dispatcher/job
        // pair, so a send that died before registering a dispatcher leaves a completed Job behind.
        // Treating that as a live sender turns Retry into setPaused(false) on nothing — a no-op.
        val liveSender = transfer.direction == FlashTransferDirection.Sending &&
            dispatcher != null &&
            registryLock.withLock { runningJobs[transferId.value] }?.isActive == true
        // `isPaused` is a plain @Volatile read on the dispatcher, but it is still a call-out and
        // stays outside [registryLock]; only the intent probe is guarded.
        val wirePaused = liveSender &&
            (dispatcher!!.isPaused || registryLock.withLock { transferId.value in pauseIntents })

        // Outbound with no live worker — a Failed, Cancelled, or stalled transfer being retried, or a resume
        // issued after executeSend already retired its registrations. Relaunch a fresh send job!
        if (transfer.direction == FlashTransferDirection.Sending && !liveSender) {
            relaunchSend(transfer, notifyPeer = true)
            return FlashResult.Success(Unit)
        }

        // A live dispatcher that is actually paused MUST be resumable regardless of the tracked
        // state: bailing out on a state mismatch left the wire paused with no way back.
        if (!wirePaused &&
            transfer.state != FlashTransferState.Paused &&
            transfer.state != FlashTransferState.Failed &&
            transfer.state != FlashTransferState.Cancelled
        ) {
            return FlashResult.Success(Unit)
        }

        // Clear the pending-pause intent first so a dispatcher registering concurrently (or a
        // relaunch below) does not start paused again.
        registryLock.withLock { pauseIntents.remove(transferId.value) }

        if (transfer.direction == FlashTransferDirection.Receiving) {
            // Resume draining the inbound channel; buffered chunks flow, ACKs resume,
            // and the wire control frame un-pauses the sender's dispatcher.
            updateTransferState(transferId.value) {
                it.copy(state = FlashTransferState.Transferring, errorMessage = null)
            }
            if (transfer.state == FlashTransferState.Offered) {
                emitIncoming(transferId.value, ACTION_ACCEPT)
            } else {
                emitIncoming(transferId.value, ACTION_RESUME)
            }
            emitOutgoing(transferId.value, transfer.peerDeviceId, ACTION_RESUME)
            return FlashResult.Success(Unit)
        }

        // Outbound: live dispatcher paused cooperatively → just unpause (no relaunch).
        if (liveSender) {
            dispatcher!!.setPaused(false)
            updateTransferState(transferId.value) {
                it.copy(state = FlashTransferState.Transferring, errorMessage = null)
            }
            store?.setStatus(transferId.value, FlashTransferState.Transferring.name)
            emitOutgoing(transferId.value, transfer.peerDeviceId, ACTION_RESUME)
            return FlashResult.Success(Unit)
        }

        // Outbound fallback with no live worker
        relaunchSend(transfer, notifyPeer = true)
        return FlashResult.Success(Unit)
    }

    /**
     * Restarts a send whose worker is gone: a Failed transfer being retried (from either side) or a
     * resume that arrives after `executeSend` already retired its dispatcher and job.
     *
     * The receiver keys its session on `(transferId, fileId)` and treats an *identical* re-offer as
     * a resume restart that keeps accumulated progress (ReceivePipeline.handleFileStart), so the
     * wire identity and the file facts have to be reproduced exactly — [FlashTransfer.wireFileId]
     * and [FlashTransfer.sourceUri], never a fresh UUID or the display name. Anything the receiver
     * already persisted is skipped via its resume vector, so a retry costs only what is missing.
     *
     * @param notifyPeer false when the peer is the one that asked for the resume: echoing its own
     *   RESUME straight back is pointless churn.
     */
    private fun relaunchSend(transfer: FlashTransfer, notifyPeer: Boolean) {
        val transferId = transfer.id.value
        // Clear the pending-pause intent first so the fresh dispatcher does not register paused.
        registryLock.withLock { pauseIntents.remove(transferId) }
        // If requireReceiverAcceptance is on and this transfer never moved any bytes (unaccepted offer),
        // re-arm the acceptance gate so FILE_START is sent as an offer and chunks are parked.
        // If the peer is the one that asked for the resume (notifyPeer == false), the receiver has already
        // explicitly requested/accepted the transfer, so we do not re-park.
        if (requireReceiverAcceptance && transfer.bytesDone == 0L && notifyPeer) {
            registryLock.withLock { pauseIntents.add(transferId) }
        }
        updateTransferState(transferId) {
            it.copy(
                state = FlashTransferState.Queued,
                errorMessage = if (requireReceiverAcceptance && transfer.bytesDone == 0L) "waiting for receiver to accept" else null,
            )
        }
        // Tell the peer before the relaunch: a receiver that paused its own intake must re-open
        // the gate, otherwise the fresh dispatcher blocks on backpressure with nothing draining.
        if (notifyPeer) {
            emitOutgoing(transferId, transfer.peerDeviceId, ACTION_RESUME)
        }
        val job = repositoryScope.launch(workerDispatcher) {
            store?.setStatus(transferId, FlashTransferState.Queued.name)
            executeSend(
                transferId = transferId,
                // Stable wire identity: the receiver's session is keyed on (transferId, fileId);
                // a fresh fileId here would be rejected as SESSION_CONFLICT.
                fileId = transfer.wireFileId ?: UuidIdGenerator.newId(),
                // Resume MUST re-read the original source, not the display name.
                fileUri = transfer.sourceUri ?: transfer.fileName,
                displayName = transfer.fileName,
                fileSize = transfer.bytesTotal,
                peerName = transfer.peerName,
                peerDeviceId = transfer.peerDeviceId,
            )
        }
        registryLock.withLock { runningJobs[transferId] = job }
    }

    override suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit> {
        val transfer = _activeTransfers.value.find { it.id == transferId }
        receiverRateMetersLock.withLock { receiverRateMeters.remove(transferId.value) }
        val job = registryLock.withLock { runningJobs.remove(transferId.value) }
        // Drop the pause intent BEFORE unpausing: a dispatcher registering concurrently must not
        // re-enter the paused state and swallow the cancellation.
        registryLock.withLock { pauseIntents.remove(transferId.value) }
        val dispatcher = registryLock.withLock { runningDispatchers.remove(transferId.value) }
        dispatcher?.setPaused(false) // unpause so cancellation lands at the next suspension point
        job?.cancel()

        updateTransferState(transferId.value) {
            it.copy(state = FlashTransferState.Cancelled, speedBytesPerSec = 0L, etaSeconds = 0L)
        }
        store?.setStatus(transferId.value, FlashTransferState.Cancelled.name)

        // Tell the counterpart so both sides tear down deterministically (ADR-018).
        if (transfer != null) {
            emitOutgoing(transferId.value, transfer.peerDeviceId, ACTION_CANCEL)
            if (transfer.direction == FlashTransferDirection.Receiving) {
                emitIncoming(transferId.value, ACTION_CANCEL)
            }
        }
        return FlashResult.Success(Unit)
    }

    /**
     * Applies a `FLASH_XFER` control frame received from the counterpart peer (ADR-018).
     * Keeps BOTH sides' state and transmission behavior in lockstep.
     */
    public fun onRemoteTransferControl(transferId: String, action: String) {
        runCatching { FlashLog.i("TRANSFER", "remote control action=$action transferId=$transferId") }
        val transfer = _activeTransfers.value.find { it.id.value == transferId } ?: return
        when (action) {
            ACTION_PAUSE -> when (transfer.direction) {
                FlashTransferDirection.Sending -> {
                    // Recorded as an intent too: the peer can pause us before our dispatcher is
                    // registered (it sees FILE_START from the first opened channel).
                    val paused = registryLock.withLock {
                        pauseIntents.add(transferId)
                        runningDispatchers[transferId]
                    }
                    paused?.setPaused(true)
                    updateTransferState(transferId) {
                        it.copy(
                            state = FlashTransferState.Paused,
                            speedBytesPerSec = 0L,
                            etaSeconds = -1L,
                            errorMessage = "paused by receiver",
                        )
                    }
                }
                FlashTransferDirection.Receiving -> {
                    // No intake gating here: the sender has already stopped, and the gate is
                    // session-wide — closing it would also stall ACKs for unrelated transfers
                    // sharing this socket. Resume DOES ungate (idempotent, see below).
                    updateTransferState(transferId) {
                        it.copy(
                            state = FlashTransferState.Paused,
                            speedBytesPerSec = 0L,
                            etaSeconds = -1L,
                            errorMessage = "paused by sender",
                        )
                    }
                }
            }
            ACTION_RESUME -> when (transfer.direction) {
                FlashTransferDirection.Sending -> {
                    registryLock.withLock { pauseIntents.remove(transferId) }
                    // `isActive`, not mere presence: a send that died before registering a
                    // dispatcher leaves a completed Job behind (executeSend's finally only retires
                    // its own registration pair), and a stale entry must not block the relaunch.
                    if (registryLock.withLock { runningJobs[transferId] }?.isActive == true) {
                        // Live worker — streaming, or parked on the #5 offer gate waiting for this
                        // very RESUME (the receiver's accept). Unpausing is all that is needed, and
                        // the null-safe call covers the accept arriving before the dispatcher has
                        // registered: the intent drop above is what un-parks it in that window.
                        registryLock.withLock { runningDispatchers[transferId] }?.setPaused(false)
                        updateTransferState(transferId) {
                            it.copy(state = FlashTransferState.Transferring, errorMessage = null)
                        }
                    } else {
                        // No worker left: the receiver is retrying a send that already died (its
                        // Retry button emits RESUME). `runningDispatchers[id]?.setPaused(false)`
                        // was a no-op here while the state flip still claimed Transferring, so
                        // BOTH UIs sat at "Transferring" with nothing on the wire — the "retry
                        // does nothing" report. A dead send can only come back as a fresh job.
                        relaunchSend(transfer, notifyPeer = false)
                    }
                }
                FlashTransferDirection.Receiving -> {
                    // MUST re-open the intake gate: without this a receiver that paused locally
                    // stayed gated forever while its UI claimed Transferring, and the resumed
                    // sender blocked on backpressure with zero progress.
                    emitIncoming(transferId, ACTION_RESUME)
                    updateTransferState(transferId) {
                        it.copy(state = FlashTransferState.Transferring, errorMessage = null)
                    }
                }
            }
            ACTION_CANCEL -> when (transfer.direction) {
                FlashTransferDirection.Sending -> {
                    registryLock.withLock { pauseIntents.remove(transferId) }
                    // Unpause first (as local cancelTransfer does): a paused worker parks in a
                    // poll loop, and leaving the flag set risks re-parking before teardown.
                    registryLock.withLock { runningDispatchers.remove(transferId) }?.setPaused(false)
                    registryLock.withLock { runningJobs.remove(transferId) }?.cancel()
                    updateTransferState(transferId) {
                        it.copy(
                            state = FlashTransferState.Cancelled,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                            errorMessage = "cancelled by receiver",
                        )
                    }
                }
                FlashTransferDirection.Receiving -> {
                    // Host tears down sink + pipeline session on this event.
                    emitIncoming(transferId, ACTION_CANCEL)
                    updateTransferState(transferId) {
                        it.copy(
                            state = FlashTransferState.Cancelled,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                            errorMessage = "cancelled by sender",
                        )
                    }
                }
            }
        }
    }

    override fun onInboundFrame(bytes: ByteArray): Boolean {
        // Snapshot under the lock, fan out after releasing it. `MultiStreamDispatcher.onInboundFrame`
        // takes the dispatcher's own locks and can reach a host callback, so holding [registryLock]
        // across the loop would nest this lock underneath every dispatcher's — the one ordering
        // this class never establishes anywhere else. A one-shot copy is also the closest match to
        // the weakly-consistent `ConcurrentHashMap.values` iteration it replaces.
        val dispatchers = registryLock.withLock { runningDispatchers.values.toList() }
        var handled = false
        dispatchers.forEach { dispatcher ->
            if (dispatcher.onInboundFrame(0, bytes)) {
                handled = true
            }
        }
        return handled
    }

    // ---- receive-side tracking (inbound transfers surface in activeTransfers) ------------------

    /**
     * Compact receiver bookkeeping: one [ResumeBitVector] per transfer rather than one boxed
     * `Int`/hash entry per chunk. Vectors grow to the highest observed index because total chunk
     * counts are not present in persisted chunk rows.
     */
    private val receiverDoneLock = PlatformLock()
    private val receiverDone = mutableMapOf<String, ResumeBitVector>()
    private val receiverRateMetersLock = PlatformLock()
    private val receiverRateMeters = mutableMapOf<String, RollingRateMeter>()

    /** Warms [receiverDone] from persisted chunk rows. Call once during transport startup. */
    public suspend fun preloadReceiverProgress() {
        val rows = store?.allDoneChunks() ?: return
        receiverDoneLock.withLock {
            for (row in rows) {
                if (row.chunkIndex < 0) continue
                val existing = receiverDone[row.transferId]
                val requiredChunks = row.chunkIndex + 1
                val bits = if (existing == null || existing.totalChunks < requiredChunks) {
                    ResumeBitVector(requiredChunks).also { grown ->
                        if (existing != null) grown.reconcile(existing.doneIndexes())
                        receiverDone[row.transferId] = grown
                    }
                } else {
                    existing
                }
                bits.markReceived(row.chunkIndex)
            }
        }
    }

    /** Synchronous resume seed for the receive pipeline; empty when nothing was persisted. */
    public fun receiverDoneIndexes(transferId: String): List<Int> =
        receiverDoneLock.withLock { receiverDone[transferId]?.doneIndexes() ?: emptyList() }

    /**
     * Records receiver-confirmed chunks (#20): updates memory immediately and persists only indexes
     * not already known. Negative wire-derived indexes are ignored.
     */
    public fun onIncomingChunkConfirmed(transferId: String, indexes: List<Int>) {
        if (indexes.isEmpty()) return
        val fresh = receiverDoneLock.withLock {
            val valid = indexes.filter { it >= 0 }
            if (valid.isEmpty()) return@withLock emptyList()
            val requiredChunks = valid.maxOrNull()!! + 1
            val existing = receiverDone[transferId]
            val bits = if (existing == null || existing.totalChunks < requiredChunks) {
                ResumeBitVector(requiredChunks).also { grown ->
                    if (existing != null) grown.reconcile(existing.doneIndexes())
                    receiverDone[transferId] = grown
                }
            } else {
                existing
            }
            valid.filter { bits.markReceived(it) }
        }
        if (fresh.isEmpty()) return
        val activeStore = store ?: return
        repositoryScope.launch(workerDispatcher) {
            activeStore.markChunksDone(transferId, fresh)
        }
    }

    override fun onIncomingOffered(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String?,
    ) {
        _activeTransfers.update { list ->
            if (list.any { it.id.value == transferId }) {
                list
            } else {
                list + FlashTransfer(
                    id = FlashTransferId(transferId),
                    peerName = peerName,
                    fileName = fileName,
                    direction = FlashTransferDirection.Receiving,
                    bytesDone = 0L,
                    bytesTotal = totalBytes,
                    state = FlashTransferState.Offered,
                    wireFileId = fileId,
                    peerDeviceId = peerDeviceId,
                    isEncrypted = peerDeviceId?.let { isPeerEncrypted(it) } ?: false,
                )
            }
        }
    }

    override suspend fun acceptIncoming(transferId: FlashTransferId): FlashResult<Unit> {
        val id = transferId.value
        val transfer = _activeTransfers.value.find { it.id.value == id }
            ?: return FlashResult.Failure(FlashError.TransferFailed(id, "unknown transfer"))
        if (transfer.state != FlashTransferState.Offered) {
            return FlashResult.Failure(FlashError.TransferFailed(id, "not an open offer"))
        }
        updateTransferState(id) {
            it.copy(state = FlashTransferState.Transferring, errorMessage = null)
        }
        // Host resolves the deferred destination sink for this id, THEN sends the sender a RESUME —
        // that ordering is load-bearing: a chunk arriving before the sink is resolved would be
        // dropped and never retransmitted. We therefore emit ONLY the local ACCEPT here and let the
        // host sequence acceptSession()→RESUME on this single event.
        emitIncoming(id, ACTION_ACCEPT)
        return FlashResult.Success(Unit)
    }

    override suspend fun declineIncoming(transferId: FlashTransferId): FlashResult<Unit> {
        val id = transferId.value
        receiverRateMetersLock.withLock { receiverRateMeters.remove(id) }
        val transfer = _activeTransfers.value.find { it.id.value == id }
            ?: return FlashResult.Failure(FlashError.TransferFailed(id, "unknown transfer"))
        if (transfer.state != FlashTransferState.Offered) {
            return FlashResult.Failure(FlashError.TransferFailed(id, "not an open offer"))
        }
        updateTransferState(id) {
            it.copy(
                state = FlashTransferState.Cancelled,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
                errorMessage = "declined",
            )
        }
        // Host drops the (never-materialized) pipeline session; sender abandons the parked send.
        emitIncoming(id, ACTION_DECLINE)
        emitOutgoing(id, transfer.peerDeviceId, ACTION_CANCEL)
        return FlashResult.Success(Unit)
    }

    override fun onIncomingStarted(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String?,
        localPath: String?,
    ) {
        receiverRateMetersLock.withLock {
            val meter = receiverRateMeters.getOrPut(transferId) {
                RollingRateMeter(SystemTimeSource::nowMs)
            }
            meter.reset()
            val existing = _activeTransfers.value.find { it.id.value == transferId }
            meter.record(existing?.bytesDone ?: 0L)
        }
        _activeTransfers.update { list ->
            if (list.any { it.id.value == transferId }) {
                // Row already present (an accepted OFFER, or a retry re-opening a session for a
                // transfer that failed mid-flight): keep its identity but fill in the now-resolved
                // destination path and ensure it is Transferring.
                //
                // Cancelled is the one state never downgraded — a decline that raced the sink
                // resolution must stay declined. Failed IS revived on purpose: this callback only
                // ever runs when a session just opened for writes, so the sender is streaming
                // again, and leaving the row Failed made the retry invisible (onIncomingProgress
                // only advances a Transferring row, so a resume showed zero progress until it
                // completed).
                list.map { existing ->
                    if (existing.id.value != transferId) {
                        existing
                    } else if (existing.state == FlashTransferState.Cancelled) {
                        existing
                    } else {
                        existing.copy(
                            state = FlashTransferState.Transferring,
                            errorMessage = null,
                            localPath = localPath ?: existing.localPath,
                        )
                    }
                }
            } else {
                list + FlashTransfer(
                    id = FlashTransferId(transferId),
                    peerName = peerName,
                    fileName = fileName,
                    direction = FlashTransferDirection.Receiving,
                    bytesDone = 0L,
                    bytesTotal = totalBytes,
                    state = FlashTransferState.Transferring,
                    wireFileId = fileId,
                    peerDeviceId = peerDeviceId,
                    localPath = localPath,
                    isEncrypted = peerDeviceId?.let { isPeerEncrypted(it) } ?: false,
                )
            }
        }
    }

    override fun onIncomingProgress(transferId: String, bytesDone: Long) {
        val (rate, eta) = receiverRateMetersLock.withLock {
            val meter = receiverRateMeters.getOrPut(transferId) {
                RollingRateMeter(SystemTimeSource::nowMs)
            }
            meter.record(bytesDone)
            val now = SystemTimeSource.nowMs()
            val instantRate = meter.instantBytesPerSec(now)
            val computedSpeed = instantRate.coerceAtLeast(0.0).toLong()
            val transfer = _activeTransfers.value.find { it.id.value == transferId }
            val computedEta = if (computedSpeed > 0L && transfer != null && transfer.bytesTotal > bytesDone) {
                ((transfer.bytesTotal - bytesDone) / computedSpeed)
            } else {
                -1L
            }
            computedSpeed to computedEta
        }
        updateTransferState(transferId) { transfer ->
            if (transfer.direction == FlashTransferDirection.Receiving &&
                transfer.state == FlashTransferState.Transferring
            ) {
                transfer.copy(
                    bytesDone = maxOf(transfer.bytesDone, bytesDone),
                    speedBytesPerSec = rate,
                    etaSeconds = eta,
                )
            } else {
                transfer
            }
        }
    }

    override fun onIncomingCompleted(transferId: String, verified: Boolean, localPath: String?) {
        receiverRateMetersLock.withLock { receiverRateMeters.remove(transferId) }
        updateTransferState(transferId) { transfer ->
            transfer.copy(
                bytesDone = transfer.bytesTotal,
                state = FlashTransferState.Completed,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
                errorMessage = if (verified) null else "completed without whole-file verification",
                localPath = localPath ?: transfer.localPath,
            )
        }
    }

    override fun onIncomingFailed(transferId: String, reason: String) {
        receiverRateMetersLock.withLock { receiverRateMeters.remove(transferId) }
        updateTransferState(transferId) { transfer ->
            // Never clobber a terminal state: a declined/cancelled offer or an already-completed
            // transfer must not be relabelled Failed by a late teardown callback.
            if (transfer.state == FlashTransferState.Cancelled ||
                transfer.state == FlashTransferState.Completed
            ) {
                transfer
            } else {
                transfer.copy(
                    state = FlashTransferState.Failed,
                    errorMessage = reason,
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L,
                )
            }
        }
    }

    private fun updateTransferState(transferId: String, transform: (FlashTransfer) -> FlashTransfer) {
        _activeTransfers.update { list ->
            list.map { if (it.id.value == transferId) transform(it) else it }
        }
    }
}
