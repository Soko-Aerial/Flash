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
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.chunked.WholeFileCheck
import com.transfer.flash.core.transfer.chunked.WholeFileVerifier
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.Path.Companion.toPath

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
    /**
     * Parallel streams per send. Null (the default, and what every production host passes) lets the
     * performance profile decide. An explicit value always wins, including 2: the default used to be the
     * number 2, so a caller that asked for exactly 2 was read as "not set" and got the profile's count.
     */
    private val defaultStreams: Int? = null,
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
    /**
     * Free bytes where received files are written, or null when that is unknown (the default). The host supplies it
     * (`File.usableSpace` of the receive directory); [admitIncoming] refuses an offer that cannot fit (ADR-069, FA-2).
     * Null never blocks a transfer: an unknown amount is not a reason to refuse.
     */
    private val freeSpaceBytes: () -> Long? = { null },
    /**
     * How long a retried receive may sit at "Downloading" without a single new byte before it goes back to Failed with
     * [TransferFailureText.SENDER_DID_NOT_RESUME] (C-08). A receiver cannot restart a sender that is gone, so without a
     * bound the row would never say so.
     */
    private val retryReceiveWaitMs: Long = RETRY_RECEIVE_WAIT_MS,
) : FlashTransferRepository, AutoCloseable {

    override fun close() {
        repositoryScope.cancel()
    }

    private val _internalTransfers = MutableStateFlow<List<FlashTransfer>>(emptyList())

    /** The rows each external owner publishes, by source (ADR-216): the swarm and the hub file binding both attach. */
    private val _externalRows = MutableStateFlow<Map<String, List<FlashTransfer>>>(emptyMap())
    private val _activeTransfers = MutableStateFlow<List<FlashTransfer>>(emptyList())
    override val activeTransfers: StateFlow<List<FlashTransfer>> = _activeTransfers.asStateFlow()

    private class ExternalSource(val control: ExternalTransferControl, val collector: Job)

    private val externalLock = PlatformLock()

    /** Replaced whole under [externalLock], read without it. */
    @Volatile
    private var externalSources: Map<String, ExternalSource> = emptyMap()

    /** The external owner of [transferId], or null when the 1:1 pipeline owns it. */
    private fun externalControlFor(transferId: String): ExternalTransferControl? =
        externalSources.values.firstOrNull { it.control.owns(transferId) }?.control

    /**
     * Asked first by [sendFile] without identities, the overload every host's 1:1 attachment send uses (ADR-216). The hub
     * file binding takes a send there when no direct path exists; null, or an interceptor answering null, leaves the send
     * to the pipeline below exactly as before.
     */
    @Volatile
    public var sendInterceptor: TransferSendInterceptor? = null

    /**
     * Ids of completed rows the user cleared. External (swarm) rows are re-published whole on every emission, so
     * a cleared one has to be filtered out of the merge, not just deleted once.
     */
    private val dismissedFinishedIds = MutableStateFlow<Set<String>>(emptySet())

    private fun updateMergedTransfers() {
        val internal = _internalTransfers.value
        val external = _externalRows.value.values.flatten()
        val merged = if (external.isEmpty()) {
            internal
        } else {
            val externalIds = external.mapTo(HashSet(external.size)) { it.id.value }
            internal.filterNot { it.id.value in externalIds } + external
        }
        val dismissed = dismissedFinishedIds.value
        _activeTransfers.value = if (dismissed.isEmpty()) {
            merged
        } else {
            // Only a still-finished row stays hidden: a Failed row that is retried (or any row that left its finished state)
            // shows again, and its id is forgotten below so a later failure is not hidden by an old dismissal.
            merged.filterNot { it.state.isFinished() && it.id.value in dismissed }
        }
        if (dismissed.isNotEmpty()) {
            val revived = merged.filter { !it.state.isFinished() && it.id.value in dismissed }.mapTo(HashSet()) { it.id.value }
            if (revived.isNotEmpty()) dismissedFinishedIds.update { it - revived }
        }
    }

    override fun clearFinishedHistory() {
        val finished = _activeTransfers.value.filter { it.state == FlashTransferState.Completed }
        if (finished.isEmpty()) return
        dismissedFinishedIds.update { it + finished.map { row -> row.id.value } }
        persistDismissed(finished.map { row -> row.id.value })
        updateMergedTransfers()
    }

    override fun dismissTransfer(transferId: FlashTransferId) {
        val row = _activeTransfers.value.firstOrNull { it.id == transferId } ?: return
        if (!row.state.isFinished()) return
        dismissedFinishedIds.update { it + transferId.value }
        persistDismissed(listOf(transferId.value))
        updateMergedTransfers()
    }

    override fun clearFailedHistory() {
        val rows = _activeTransfers.value.filter { it.state == FlashTransferState.Failed || it.state == FlashTransferState.Cancelled }
        if (rows.isEmpty()) return
        dismissedFinishedIds.update { it + rows.map { row -> row.id.value } }
        persistDismissed(rows.map { row -> row.id.value })
        updateMergedTransfers()
    }

    private fun FlashTransferState.isFinished(): Boolean =
        this == FlashTransferState.Completed || this == FlashTransferState.Failed || this == FlashTransferState.Cancelled

    private fun updateInternalTransfers(transform: (List<FlashTransfer>) -> List<FlashTransfer>) {
        _internalTransfers.update(transform)
        updateMergedTransfers()
    }

    /**
     * Attaches external transfer rows (managed by FlashSwarm, SW-8, or the hub file binding, ADR-216) into
     * [activeTransfers]. Each [source] keeps its own rows and control: attaching one never hides another's rows, and
     * attaching the same source again replaces only that source (its old collector is cancelled).
     */
    public fun attachExternalRows(
        rows: StateFlow<List<FlashTransfer>>,
        control: ExternalTransferControl,
        source: String = DEFAULT_EXTERNAL_SOURCE,
    ) {
        val collector = repositoryScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            rows.collect { extList ->
                // A replaced collector that was mid-emission when it was cancelled must not overwrite its successor;
                // the check and the write share the lock that replaces the source.
                val current = externalLock.withLock {
                    val mine = externalSources[source]?.collector === self
                    if (mine) _externalRows.update { it + (source to extList) }
                    mine
                }
                if (current) updateMergedTransfers()
            }
        }
        val previous = externalLock.withLock {
            val old = externalSources[source]
            externalSources = externalSources + (source to ExternalSource(control, collector))
            old
        }
        previous?.collector?.cancel()
        collector.start()
    }

    /**
     * True if [transferId] is an external (swarm or hub) transfer row.
     */
    override fun isExternalRow(transferId: String): Boolean =
        externalControlFor(transferId) != null

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
        /** How long a finished or interrupted row is kept for the Transfers list after its last change (C-05). */
        public const val HISTORY_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** Shown on the receiving side when the assembled file does not match the digest the sender offered (ADR-068). */
        public const val INTEGRITY_FAILED_MESSAGE: String =
            "The file arrived damaged and was discarded. Try again to receive it afresh."

        /** Shown on the sending side when the receiver reports the assembled file as damaged (ADR-068). */
        public const val PEER_REPORTED_DAMAGE_MESSAGE: String =
            "The other device reports the file arrived damaged. Try again to send it afresh."

        public const val ACTION_PAUSE: String = "pause"
        public const val ACTION_RESUME: String = "resume"
        /** Stored on a 1:1 row paused because the Android foreground-service timeout stopped the service. */
        public const val REASON_PAUSED_BY_SYSTEM: String = "paused by system"
        public const val ACTION_CANCEL: String = "cancel"

        /**
         * Local-only intake actions for the inbound offer gate (#5). These never go on the wire as
         * an XFER `action` — accept maps to a RESUME sent to the peer, decline to a CANCEL. They
         * travel on [incomingControl] so the host can resolve/drop the deferred pipeline sink.
         */
        public const val ACTION_ACCEPT: String = "accept"
        public const val ACTION_DECLINE: String = "decline"

        /** The source [attachExternalRows] uses when the caller names none (the swarm binding before ADR-216). */
        public const val DEFAULT_EXTERNAL_SOURCE: String = "external"

        /** See the `retryReceiveWaitMs` constructor parameter. */
        public const val RETRY_RECEIVE_WAIT_MS: Long = 30_000L
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
    ): FlashResult<FlashTransferId> {
        val claimed = sendInterceptor?.let { interceptor ->
            try {
                interceptor.intercept(targetDevice, fileUri, displayName, fileSize)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A broken interceptor must not cost the user the send: the direct pipeline still gets it.
                FlashLog.w("TRANSFER", "send interceptor failed, using the direct path: ${t::class.simpleName}")
                null
            }
        }
        if (claimed != null) return FlashResult.Success(claimed)
        return sendFile(targetDevice, fileUri, displayName, fileSize, wireFileId = null)
    }

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

    // FA-6 (ADR-069): a size of 0 from a picker means "empty" or "the provider did not say". The chunker cannot
    // plan zero bytes, so it used to throw inside the send job and leave the row Queued for ever. Measure the
    // unknown size by streaming the source once; refuse a truly empty or unreadable file with a visible Failed row.
    // The call still returns Success: callers ignore Failure, so the row (Transfers list, chat bubble) is the message.
    val totalBytes = if (fileSize > 0L) fileSize else measureSourceBytes(fileUri)
    if (totalBytes == null || totalBytes <= 0L) {
        val message = if (totalBytes == null) TransferFailureText.UNREADABLE_FILE else TransferFailureText.EMPTY_FILE
        updateInternalTransfers {
            it + FlashTransfer(
                id = transferId,
                peerName = targetDevice.friendlyName,
                fileName = displayName,
                direction = FlashTransferDirection.Sending,
                bytesDone = 0L,
                bytesTotal = 0L,
                state = FlashTransferState.Failed,
                sourceUri = fileUri,
                wireFileId = fileId,
                peerDeviceId = targetDevice.id.value,
                errorMessage = message,
                isEncrypted = isPeerEncrypted(targetDevice.id.value),
            )
        }
        store?.insertTransfer(
            transferId = transferIdString,
            totalBytes = 0L,
            status = FlashTransferState.Failed.name,
        )
        runCatching { FlashLog.w("TRANSFER", "send refused transferId=$transferIdString reason=${if (totalBytes == null) "unreadable" else "empty"}") }
        return FlashResult.Success(transferId)
    }

    val initialTransfer = FlashTransfer(
        id = transferId,
        peerName = targetDevice.friendlyName,
        fileName = displayName,
        direction = FlashTransferDirection.Sending,
        bytesDone = 0L,
        bytesTotal = totalBytes,
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

    updateInternalTransfers { it + initialTransfer }
    store?.insertTransfer(
        transferId = transferIdString,
        totalBytes = totalBytes,
        status = FlashTransferState.Queued.name,
    )

        val job = repositoryScope.launch(workerDispatcher) {
            executeSend(
                transferId = transferIdString,
                fileId = fileId,
                fileUri = fileUri,
                displayName = displayName,
                fileSize = totalBytes,
                peerName = targetDevice.friendlyName,
                peerDeviceId = targetDevice.id.value,
            )
        }
        registryLock.withLock { runningJobs[transferIdString] = job }

        return FlashResult.Success(transferId)
    }

    /**
     * Counts the bytes [fileSourceOpener] yields for [fileUri] by reading it once. Null when it cannot be opened or
     * read. Only used when the picker gave no size, so the usual path never pays for it.
     */
    private suspend fun measureSourceBytes(fileUri: String): Long? = withContext(workerDispatcher) {
        try {
            val source = fileSourceOpener.open(fileUri).buffer()
            try {
                val scratch = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(scratch)
                    if (read < 0) break
                    total += read
                }
                total
            } finally {
                source.close()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
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
        val resolvedStreams = defaultStreams ?: transferProfile.streamCount
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
                    // R-01: wait for a tick that is mid-write, or it re-inserts chunk rows after they are deleted below.
                    progressJob.join()
                    if (result.verified == false) {
                        // The receiver assembled every chunk and its whole-file check failed (ADR-068). Not a
                        // success, and a retry must resend everything, so the confirmed set goes too.
                        updateTransferState(transferId) {
                            it.copy(
                                state = FlashTransferState.Failed,
                                errorMessage = PEER_REPORTED_DAMAGE_MESSAGE,
                                speedBytesPerSec = 0L,
                                etaSeconds = 0L,
                            )
                        }
                        store?.clearDoneChunks(transferId)
                        store?.setStatus(transferId, FlashTransferState.Failed.name)
                    } else {
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
                        // R-01: a finished transfer keeps no chunk rows (they were never deleted, so the table grew
                        // for ever and every start loaded all of it).
                        store?.clearDoneChunks(transferId)
                    }
                }

                is MultiStreamResult.Failed -> {
                    progressJob.cancel()
                    // Persist unconfirmed chunks if needed
                    val unconfirmed = result.unconfirmedIndexes
                    val failureText = TransferFailureText.friendly(result.reason, transferId)
                    updateTransferState(transferId) {
                        it.copy(
                            state = FlashTransferState.Failed,
                            errorMessage = failureText,
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
            val failureText = TransferFailureText.friendly(t.message ?: t::class.simpleName, transferId)
            updateTransferState(transferId) {
                it.copy(
                    state = FlashTransferState.Failed,
                    errorMessage = failureText,
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
        externalControlFor(transferId.value)?.let { external ->
            external.pause(transferId.value)
            return FlashResult.Success(Unit)
        }
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

    override suspend fun pauseForSystem(transferId: FlashTransferId, reason: String): FlashResult<Unit> {
        externalControlFor(transferId.value)?.let { external ->
            external.pauseForSystem(transferId.value, reason)
            return FlashResult.Success(Unit)
        }
        // A 1:1 transfer keeps its done-set while paused, but nothing resumes it by itself when the service
        // comes back, so the row says so instead of promising "continues automatically" (audit B-02).
        val result = pauseTransfer(transferId)
        if (result is FlashResult.Success) {
            updateTransferState(transferId.value) { it.copy(errorMessage = REASON_PAUSED_BY_SYSTEM) }
        }
        return result
    }

    override suspend fun resumeSystemPaused(): Int {
        val rows = _activeTransfers.value.filter {
            it.state == FlashTransferState.Paused && it.errorMessage == REASON_PAUSED_BY_SYSTEM && externalControlFor(it.id.value) == null
        }
        var resumed = 0
        for (row in rows) {
            if (resumeTransfer(row.id) is FlashResult.Success) resumed++
        }
        if (resumed > 0) runCatching { FlashLog.i("TRANSFER", "auto-resumed $resumed transfer(s) paused by the system") }
        return resumed
    }

    override suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit> {
        externalControlFor(transferId.value)?.let { external ->
            external.resume(transferId.value)
            return FlashResult.Success(Unit)
        }
        val transfer = _activeTransfers.value.find { it.id == transferId }
            ?: return FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Transfer not found: ${transferId.value}"))

        // Terminal transfers have nothing to resume.
        if (transfer.state == FlashTransferState.Completed) {
            return FlashResult.Success(Unit)
        }
        // Cancelled is a decision, not a fault (audit 2026-10-20 B-01): a cancelled or declined send
        // must never be relaunched by a tap on its bubble or a stale Retry, and a cancelled receive
        // must not re-request bytes the user stopped. Sending again is a new send from the composer.
        if (transfer.state == FlashTransferState.Cancelled) {
            runCatching { FlashLog.i("TRANSFER", "resumeTransfer ignored id=${transferId.value} state=Cancelled") }
            return FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Cancelled("transfer was cancelled"))
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

        // Outbound with no live worker — a Failed or stalled transfer being retried, or a resume
        // issued after executeSend already retired its registrations. Relaunch a fresh send job!
        if (transfer.direction == FlashTransferDirection.Sending && !liveSender) {
            relaunchSend(transfer, notifyPeer = true)
            return FlashResult.Success(Unit)
        }

        // A live dispatcher that is actually paused MUST be resumable regardless of the tracked
        // state: bailing out on a state mismatch left the wire paused with no way back.
        if (!wirePaused &&
            transfer.state != FlashTransferState.Paused &&
            transfer.state != FlashTransferState.Failed
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
            if (transfer.state == FlashTransferState.Failed) watchRetriedReceive(transferId.value, transfer.bytesDone)
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
        externalControlFor(transferId.value)?.let { external ->
            external.cancel(transferId.value)
            return FlashResult.Success(Unit)
        }
        val transfer = _activeTransfers.value.find { it.id == transferId }
        receiverRateMetersLock.withLock { receiverRateMeters.remove(transferId.value) }
        val job = registryLock.withLock { runningJobs.remove(transferId.value) }
        // Drop the pause intent BEFORE unpausing: a dispatcher registering concurrently must not
        // re-enter the paused state and swallow the cancellation.
        registryLock.withLock { pauseIntents.remove(transferId.value) }
        val dispatcher = registryLock.withLock { runningDispatchers.remove(transferId.value) }
        dispatcher?.setPaused(false) // unpause so cancellation lands at the next suspension point
        job?.cancel()

        if (transfer == null) {
            runCatching { FlashLog.w("TRANSFER", "cancelTransfer on unknown row id=${transferId.value}: torn down locally, peer not told") }
        }
        // errorMessage is cleared: a stale "paused by receiver" must not outlive the cancel (B-12).
        updateTransferState(transferId.value) {
            it.copy(state = FlashTransferState.Cancelled, speedBytesPerSec = 0L, etaSeconds = 0L, errorMessage = null)
        }
        store?.setStatus(transferId.value, FlashTransferState.Cancelled.name)
        forgetChunks(transferId.value, null)

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
    public fun onRemoteTransferControl(transferId: String, action: String, reason: String? = null) {
        runCatching { FlashLog.i("TRANSFER", "remote control action=$action transferId=$transferId${reason?.let { " reason=$it" } ?: ""}") }
        val transfer = _activeTransfers.value.find { it.id.value == transferId } ?: return
        // A late frame must not rewrite a finished transfer (audit B-03): PAUSE never applies once the
        // row is finished or failed, CANCEL never to a finished one, and RESUME only to a row that is
        // not Completed/Cancelled (a Failed send IS resumed by the receiver's Retry).
        val terminal = when (action) {
            ACTION_PAUSE -> transfer.state in setOf(
                FlashTransferState.Completed, FlashTransferState.Cancelled, FlashTransferState.Failed,
            )
            ACTION_CANCEL, ACTION_RESUME -> transfer.state == FlashTransferState.Completed ||
                transfer.state == FlashTransferState.Cancelled
            else -> false
        }
        if (terminal) {
            runCatching { FlashLog.i("TRANSFER", "remote control ignored action=$action transferId=$transferId state=${transfer.state}") }
            return
        }
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
                    // C-11: a cancel that names a refusal reason is the receiver being unable to take the file, not the
                    // person changing their mind, so the row fails with the receiver's reason and can be retried.
                    val refusal = TransferFailureText.refusalSentence(reason)
                    val endState = if (refusal != null) FlashTransferState.Failed else FlashTransferState.Cancelled
                    updateTransferState(transferId) {
                        it.copy(
                            state = endState,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                            errorMessage = refusal ?: "cancelled by receiver",
                        )
                    }
                    forgetChunks(transferId, endState)
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
                    forgetChunks(transferId, FlashTransferState.Cancelled)
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

    /**
     * Forgets the confirmed-chunk set of a finished transfer, in memory and in the store (R-01, sweep 2026-10-09).
     * [status], when given, is written first so the startup purge can still find the row if the delete is lost.
     */
    private fun forgetChunks(transferId: String, status: FlashTransferState?) {
        receiverDoneLock.withLock { receiverDone.remove(transferId) }
        val activeStore = store ?: return
        repositoryScope.launch(workerDispatcher) {
            if (status != null) activeStore.setStatus(transferId, status.name)
            activeStore.clearDoneChunks(transferId)
        }
    }

    /**
     * Warms [receiverDone] from persisted chunk rows. Call once during transport startup.
     *
     * R-01: rows of transfers already Completed or Cancelled are deleted first (no schema change; the rows carry no
     * timestamp, so the transfer's own status is the only age signal), and the rest are loaded in one linear pass: the
     * highest index per transfer is found first, each vector is allocated once and filled. The old loop re-allocated and
     * re-copied the vector for every row that raised the highest index, under [receiverDoneLock].
     * Rows of a receive that finished before the `transfers` row existed cannot be told from a live partial and are kept.
     */
    public suspend fun preloadReceiverProgress() {
        val activeStore = store ?: return
        try {
            val purged = activeStore.purgeFinishedChunks()
            if (purged > 0) runCatching { FlashLog.i("DATABASE", "purged $purged chunk rows of finished transfers") }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            runCatching { FlashLog.w("DATABASE", "chunk purge failed: ${e::class.simpleName}") }
        }
        val rows = activeStore.allDoneChunks()
        val highest = HashMap<String, Int>()
        for (row in rows) {
            if (row.chunkIndex < 0) continue
            val seen = highest[row.transferId]
            if (seen == null || row.chunkIndex > seen) highest[row.transferId] = row.chunkIndex
        }
        val built = HashMap<String, ResumeBitVector>(highest.size * 2)
        for ((id, top) in highest) built[id] = ResumeBitVector(top + 1)
        for (row in rows) {
            if (row.chunkIndex < 0) continue
            built[row.transferId]?.markReceived(row.chunkIndex)
        }
        receiverDoneLock.withLock {
            for ((id, bits) in built) {
                receiverDone[id]?.let { live -> bits.reconcile(live.doneIndexes()) }
                receiverDone[id] = bits
            }
        }
    }

    /** Synchronous resume seed for the receive pipeline; empty when nothing was persisted. */
    public fun receiverDoneIndexes(transferId: String): List<Int> =
        receiverDoneLock.withLock { receiverDone[transferId]?.doneIndexes() ?: emptyList() }

    /**
     * Records receiver-confirmed chunks (#20): updates memory immediately and persists only indexes
     * not already known. Negative wire-derived indexes are ignored. A finished (Completed / Cancelled)
     * transfer records nothing: a late confirmation must not re-create rows that were just deleted (R-01).
     */
    public fun onIncomingChunkConfirmed(transferId: String, indexes: List<Int>) {
        if (indexes.isEmpty()) return
        val state = _activeTransfers.value.find { it.id.value == transferId }?.state
        if (state == FlashTransferState.Completed || state == FlashTransferState.Cancelled) return
        val fresh = receiverDoneLock.withLock {
            val valid = indexes.filter { it >= 0 }
            if (valid.isEmpty()) return@withLock emptyList()
            val requiredChunks = valid.maxOrNull()!! + 1
            val existing = receiverDone[transferId]
            val bits = if (existing == null || existing.totalChunks < requiredChunks) {
                // Grow geometrically: exact-size growth copied the whole vector for every new highest index.
                val size = maxOf(requiredChunks, minOf((existing?.totalChunks ?: 0) * 2, ReceivePipeline.MAX_TOTAL_CHUNKS))
                ResumeBitVector(size).also { grown ->
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
        updateInternalTransfers { list ->
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
        // R-01: a receive had chunk rows but no `transfers` row, so nothing could ever tell a finished receive's rows from
        // a live partial one. The row (status Offered, then Completed / Cancelled) is what the startup purge keys on.
        store?.let { activeStore ->
            repositoryScope.launch(workerDispatcher) {
                activeStore.insertTransfer(transferId, totalBytes, FlashTransferState.Offered.name)
            }
        }
    }

    override suspend fun acceptIncoming(transferId: FlashTransferId): FlashResult<Unit> {
        externalControlFor(transferId.value)?.let { external ->
            external.accept(transferId.value)
            return FlashResult.Success(Unit)
        }
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

    /**
     * The storage gate for an accepted inbound offer (ADR-069, FA-2). Hosts call it first thing when they act on an
     * accept (the user's tap and auto-accept both end there) and stop when it returns false.
     *
     * Needs `bytesTotal - bytesDone` free (a resumed transfer already holds its written part). When that does not fit
     * the row becomes Failed with [TransferFailureText.notEnoughSpace], the host's pending session is dropped (the
     * local DECLINE action) and the sender abandons its parked send (a CANCEL). Without a [freeSpaceBytes] provider,
     * or for an unknown row, it admits: an unknown amount is no reason to refuse.
     */
    public fun admitIncoming(transferId: String): Boolean {
        val free = runCatching { freeSpaceBytes() }.getOrNull() ?: return true
        val transfer = _activeTransfers.value.find { it.id.value == transferId } ?: return true
        val needed = (transfer.bytesTotal - transfer.bytesDone).coerceAtLeast(0L)
        if (needed <= free) return true
        runCatching { FlashLog.w("TRANSFER", "inbound refused transferId=$transferId needed=$needed free=$free") }
        receiverRateMetersLock.withLock { receiverRateMeters.remove(transferId) }
        updateTransferState(transferId) {
            it.copy(
                state = FlashTransferState.Failed,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
                errorMessage = TransferFailureText.notEnoughSpace(needed, free),
            )
        }
        store?.let { activeStore ->
            repositoryScope.launch(workerDispatcher) {
                activeStore.setStatus(transferId, FlashTransferState.Failed.name)
            }
        }
        emitIncoming(transferId, ACTION_DECLINE)
        emitOutgoing(transferId, transfer.peerDeviceId, ACTION_CANCEL)
        return false
    }

    override suspend fun declineIncoming(transferId: FlashTransferId): FlashResult<Unit> {
        externalControlFor(transferId.value)?.let { external ->
            external.decline(transferId.value)
            return FlashResult.Success(Unit)
        }
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
        forgetChunks(id, FlashTransferState.Cancelled)
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
        updateInternalTransfers { list ->
            if (list.any { it.id.value == transferId }) {
                // Row already present (an accepted OFFER, or a retry re-opening a session for a
                // transfer that failed mid-flight): keep its identity but fill in the now-resolved
                // destination path and ensure it is Transferring.
                //
                // Cancelled and Completed are never downgraded — a decline that raced the sink
                // resolution must stay declined, and a finished receive that a late re-offer or a
                // second open of the sink reaches would otherwise return to "Downloading" with
                // nothing left to complete it (and re-post its progress notification). Failed IS
                // revived on purpose: this callback only
                // ever runs when a session just opened for writes, so the sender is streaming
                // again, and leaving the row Failed made the retry invisible (onIncomingProgress
                // only advances a Transferring row, so a resume showed zero progress until it
                // completed).
                list.map { existing ->
                    if (existing.id.value != transferId) {
                        existing
                    } else if (existing.state == FlashTransferState.Cancelled ||
                        existing.state == FlashTransferState.Completed
                    ) {
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
        forgetChunks(transferId, FlashTransferState.Completed)
    }

    /**
     * Finishes an inbound transfer whose chunks have all been written, after checking the assembled file against the
     * digest the sender offered (ADR-068, ERROR-098). The one completion path for every host.
     *
     * - [WholeFileCheck.MATCH]: completed and verified.
     * - [WholeFileCheck.UNVERIFIABLE] (no digest on record, unreadable file): completed, flagged unverified, as before.
     * - [WholeFileCheck.MISMATCH]: the file is deleted, the confirmed-chunk set is forgotten (memory and store) and the
     *   transfer is **Failed**, never Completed: the transfer list labels every Completed transfer "Verified", so a
     *   failed check that still completed would have been invisible. The host must also drop its pipeline session
     *   (`ReceivePipeline.cancelSession`) and answer the sender with `COMPLETE verified=false` so it fails too.
     *
     * Blocking: hashes the whole file on the calling thread.
     */
    public fun onIncomingFileAssembled(
        transferId: String,
        localPath: String?,
        expectedSha256Hex: String?,
        chunksVerified: Boolean = true,
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ): WholeFileCheck {
        val check = if (chunksVerified) {
            WholeFileVerifier.check(localPath, expectedSha256Hex, fileSystem)
        } else {
            WholeFileCheck.MISMATCH
        }
        when (check) {
            WholeFileCheck.MATCH -> onIncomingCompleted(transferId, verified = true, localPath = localPath)
            WholeFileCheck.UNVERIFIABLE -> onIncomingCompleted(transferId, verified = false, localPath = localPath)
            WholeFileCheck.MISMATCH -> {
                if (!localPath.isNullOrBlank()) {
                    try {
                        fileSystem.delete(localPath.toPath())
                    } catch (_: okio.IOException) {
                        // Best effort: the transfer is failed either way and a retry rewrites the file.
                    }
                }
                onIncomingIntegrityFailed(transferId)
            }
        }
        return check
    }

    private fun onIncomingIntegrityFailed(transferId: String) {
        receiverRateMetersLock.withLock { receiverRateMeters.remove(transferId) }
        receiverDoneLock.withLock { receiverDone.remove(transferId) }
        store?.let { activeStore ->
            repositoryScope.launch(workerDispatcher) {
                activeStore.clearDoneChunks(transferId)
                activeStore.setStatus(transferId, FlashTransferState.Failed.name)
            }
        }
        updateTransferState(transferId) { transfer ->
            transfer.copy(
                bytesDone = 0L,
                state = FlashTransferState.Failed,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
                errorMessage = INTEGRITY_FAILED_MESSAGE,
                localPath = null,
            )
        }
    }

    override fun onIncomingFailed(transferId: String, reason: String) {
        receiverRateMetersLock.withLock { receiverRateMeters.remove(transferId) }
        val failureText = TransferFailureText.friendly(reason, transferId)
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
                    errorMessage = failureText,
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L,
                )
            }
        }
    }

    /**
     * C-08: after Retry on a Failed receive the row shows "Downloading", but the receiver only asked the sender to
     * continue; a sender that is gone or already finished never will. If no new byte arrived within
     * [retryReceiveWaitMs] the row goes back to Failed with a sentence that says what to do.
     */
    private fun watchRetriedReceive(transferId: String, bytesAtRetry: Long) {
        repositoryScope.launch {
            delay(retryReceiveWaitMs)
            updateTransferState(transferId) { row ->
                if (row.direction == FlashTransferDirection.Receiving &&
                    row.state == FlashTransferState.Transferring &&
                    row.bytesDone <= bytesAtRetry
                ) {
                    row.copy(state = FlashTransferState.Failed, errorMessage = TransferFailureText.SENDER_DID_NOT_RESUME)
                } else {
                    row
                }
            }
        }
    }

    private fun updateTransferState(transferId: String, transform: (FlashTransfer) -> FlashTransfer) {
        updateInternalTransfers { list ->
            list.map { if (it.id.value == transferId) transform(it) else it }
        }
    }

    // ---- C-09 / C-05: a transfer's identity survives the process -------------------------------------------------

    /** What was last written for a row; a row is written again only when one of these changes (not on every progress tick). */
    private data class IdentitySignature(
        val state: FlashTransferState,
        val errorMessage: String?,
        val localPath: String?,
        val bytesTotal: Long,
    )

    /** Only the identity-persistence coroutine touches this. */
    private val persistedIdentity = HashMap<String, IdentitySignature>()

    private fun FlashTransfer.identitySignature() = IdentitySignature(state, errorMessage, localPath, bytesTotal)

    private fun persistDismissed(ids: List<String>) {
        val activeStore = store ?: return
        if (ids.isEmpty()) return
        repositoryScope.launch { runCatching { activeStore.dismissStored(ids) } }
    }

    /**
     * Brings the stored rows back into the list once at start (Failed, Cancelled and Paused rows as they were; a row that
     * was still running when the process died comes back Failed with a sentence, never resumed by itself), then keeps the
     * store current as rows change state. Rows the person dismissed, and rows untouched for [HISTORY_RETENTION_MS], are not
     * restored (C-05). Without a store nothing is restored and nothing is written.
     */
    private fun startIdentityPersistence() {
        val activeStore = store ?: return
        repositoryScope.launch {
            try {
                restoreStoredTransfers(activeStore)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                runCatching { FlashLog.w("TRANSFER", "restoring stored transfers failed: ${t::class.simpleName}") }
            }
            _internalTransfers.collect { rows ->
                for (row in rows) {
                    val signature = row.identitySignature()
                    if (persistedIdentity[row.id.value] == signature) continue
                    persistedIdentity[row.id.value] = signature
                    runCatching { activeStore.saveIdentity(row.toStored()) }
                }
            }
        }
    }

    private fun FlashTransfer.toStored() = TransferStore.StoredTransfer(
        transferId = id.value,
        peerDeviceId = peerDeviceId,
        peerName = peerName,
        fileName = fileName,
        direction = direction.name,
        totalBytes = bytesTotal,
        bytesDone = bytesDone,
        state = state.name,
        errorMessage = errorMessage,
        sourceUri = sourceUri,
        wireFileId = wireFileId,
        localPath = localPath,
        updatedAtMs = SystemTimeSource.nowMs(),
    )

    private suspend fun restoreStoredTransfers(activeStore: TransferStore) {
        val since = SystemTimeSource.nowMs() - HISTORY_RETENTION_MS
        activeStore.deleteStoredBefore(since)
        val restored = activeStore.restorableTransfers(since).mapNotNull { stored ->
            val state = FlashTransferState.entries.firstOrNull { it.name == stored.state } ?: return@mapNotNull null
            val direction = FlashTransferDirection.entries.firstOrNull { it.name == stored.direction } ?: return@mapNotNull null
            // A finished receive is in the chat; an offer nobody answered has no live session to answer on.
            if (state == FlashTransferState.Completed || state == FlashTransferState.Offered) return@mapNotNull null
            val interrupted = state == FlashTransferState.Queued ||
                state == FlashTransferState.Transferring || state == FlashTransferState.Verifying
            val row = FlashTransfer(
                id = FlashTransferId(stored.transferId),
                peerName = stored.peerName,
                fileName = stored.fileName,
                direction = direction,
                bytesDone = stored.bytesDone,
                bytesTotal = stored.totalBytes,
                state = if (interrupted) FlashTransferState.Failed else state,
                errorMessage = if (interrupted) TransferFailureText.INTERRUPTED_BY_RESTART else stored.errorMessage,
                sourceUri = stored.sourceUri,
                wireFileId = stored.wireFileId,
                peerDeviceId = stored.peerDeviceId,
                localPath = stored.localPath,
                isEncrypted = stored.peerDeviceId?.let(isPeerEncrypted) ?: false,
                restored = true,
            )
            row to interrupted
        }
        if (restored.isEmpty()) return
        updateInternalTransfers { current ->
            val have = current.mapTo(HashSet()) { it.id.value }
            current + restored.map { it.first }.filter { it.id.value !in have }
        }
        // A row restored as it was needs no rewrite; one converted to Failed is written so the store agrees with the list.
        for ((row, interrupted) in restored) if (!interrupted) persistedIdentity[row.id.value] = row.identitySignature()
        runCatching { FlashLog.i("TRANSFER", "restored ${restored.size} stored transfer(s)") }
    }

    init {
        startIdentityPersistence()
    }
}
