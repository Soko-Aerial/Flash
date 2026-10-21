package com.transfer.flash.core.engine.hubfiles

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.engine.FlashPathSanitizer
import com.transfer.flash.core.engine.concurrent.PlatformLock
import com.transfer.flash.core.enterprise.files.HubDownloadOutcome
import com.transfer.flash.core.enterprise.files.HubDownloadRequest
import com.transfer.flash.core.enterprise.files.HubFileError
import com.transfer.flash.core.enterprise.files.HubFileResult
import com.transfer.flash.core.enterprise.files.HubFileText
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.enterprise.files.HubFileTransfers
import com.transfer.flash.core.enterprise.files.HubFilesApi
import com.transfer.flash.core.enterprise.files.HubTransferProgress
import com.transfer.flash.core.enterprise.files.HubUploadOutcome
import com.transfer.flash.core.enterprise.files.HubUploadRequest
import com.transfer.flash.core.messaging.hub.HubFileRoute
import com.transfer.flash.core.persistence.db.dao.HubFileDao
import com.transfer.flash.core.persistence.db.entity.HubFileEntity
import com.transfer.flash.core.persistence.db.entity.HubFileStates
import com.transfer.flash.core.security.enterprise.HubFileKey
import com.transfer.flash.core.security.enterprise.HubFileSealing
import com.transfer.flash.core.transfer.ExternalTransferControl
import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.TransferSendInterceptor
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/**
 * Files through the organisation hub on one device (ADR-215, ADR-216, HUB-FILES P3): the single place that wires the
 * sealed-file engine ([HubFileTransfers]) into the transfer list and the chat.
 *
 * - **Sending.** As [RealFlashTransferRepository.sendInterceptor] it takes a 1:1 send when the peer has no direct path
 *   ([isDirectlyReachable] is false) and the hub can carry the file sealed to them ([fileRoute]). It records the file in
 *   `hub_file` and uploads it; when the upload finishes the ticket is stored and [queueTicket] hands it to the chat,
 *   which sends it sealed through the hub. A peer that is reachable directly, or a device without the hub, keeps the
 *   LAN path exactly as before. A send the hub cannot carry (no sealing key, too large, an older hub) becomes a Failed row
 *   with the plan's sentence, never a silent fallback that could leave the key readable.
 * - **Receiving.** The chat stores incoming tickets as OFFERED rows; Accept (or [autoDownload]) downloads, verifies and
 *   moves the file to `<receivedDirectory>/<transferId>/<name>`.
 * - **Rows and control.** Every row is published to the Transfers screen (and so to the bubble) through
 *   [RealFlashTransferRepository.attachExternalRows]; pause, resume, cancel, accept and decline come back through its
 *   [ExternalTransferControl].
 * - **Resume.** All progress is on disk (the engine's chunk bitmap, this table). [start] resumes every unfinished row; a
 *   run that the network interrupted tries again with a growing delay for as long as the row stays unfinished.
 *
 * - **Group files (ADR-217).** [claimGroup] uploads a group's file once, for the members the direct pipeline could not
 *   reach, and records who they are; the chat then sends each of them a sealed ticket. The members the direct pipeline did
 *   reach never see the hub copy.
 */
@OptIn(FlashInternalApi::class)
public class HubFileBinding(
    scope: CoroutineScope,
    private val dao: HubFileDao,
    private val transfers: HubFileTransfers,
    private val api: HubFilesApi,
    private val transferRepository: RealFlashTransferRepository,
    /** The hub this device is enrolled with, or null when it is not. */
    private val hubUrl: () -> String?,
    private val fileRoute: suspend (peerId: String) -> HubFileRoute,
    /** The upload of the given transfer finished: queue its ticket message (the chat repository's `queueHubFile`). */
    private val queueTicket: suspend (transferId: String) -> Boolean,
    /** An outgoing file was cancelled or failed for good: the chat gives its message up (`hubFileEnded`). */
    private val outgoingEnded: suspend (transferId: String) -> Unit,
    /** A Failed outgoing file was retried: the chat puts its message back to waiting (`hubFileRetried`, C-07). */
    private val outgoingRetried: suspend (transferId: String) -> Unit = {},
    private val openSource: FileSourceOpener,
    /** Where received files go; each one gets its own sub-directory named by its transfer id. */
    private val receivedDirectory: () -> String,
    /** True when the peer has a live session or is seen by discovery: then the direct path sends. */
    private val isDirectlyReachable: (peerId: String) -> Boolean,
    private val peerName: (peerId: String) -> String?,
    /**
     * Whether a file from [peerId] of type [mime] downloads without a tap: the host's per-type auto-download setting, and
     * only for a paired peer (AGENTS.md section 19; a hub colleague who is not paired is asked about, deviation D18).
     */
    private val autoDownload: (peerId: String, mime: String) -> Boolean,
    /** Whether a file of the group may go through the hub now (the chat repository's `hubGroupFileRoute`). */
    private val groupFileRoute: (groupId: String) -> HubFileRoute = { HubFileRoute.UNAVAILABLE },
    /** The group's title, for the Transfers list. */
    private val groupName: (groupId: String) -> String? = { null },
    /** A group file's upload was started for chat message [messageId] (the chat repository's `registerHubGroupFile`). */
    private val registerGroupFile: (messageId: String, transferId: String) -> Unit = { _, _ -> },
    /** A received file was moved to [path] under [receivedDirectory] (the desktop host records it for "Clear received files"). */
    private val onPlaced: (path: String) -> Unit = {},
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val timeSource: FlashTimeSource = SystemTimeSource,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val maxRetryDelayMs: Long = DEFAULT_MAX_RETRY_DELAY_MS,
    private val infoTtlMs: Long = DEFAULT_INFO_TTL_MS,
) {
    private val bindingJob: Job = SupervisorJob(scope.coroutineContext[Job])
    private val bindingScope = CoroutineScope(
        scope.coroutineContext + bindingJob + CoroutineExceptionHandler { _, t ->
            FlashLog.e("HUB", "hub file binding coroutine failed: ${t::class.simpleName}", t)
        },
    )

    private val jobsLock = PlatformLock()
    private val jobs = HashMap<String, Job>()
    private val running = MutableStateFlow<Set<String>>(emptySet())
    private val live = MutableStateFlow<Map<String, HubFileLive>>(emptyMap())

    private val infoLock = Mutex()
    private var cachedLimit: Long? = null
    private var cachedAtMs: Long = Long.MIN_VALUE

    /** Every hub file row as a transfer, newest first. */
    public val rows: StateFlow<List<FlashTransfer>> =
        combine(dao.observeRecent(ROW_LIMIT), live, running) { entities, progress, active ->
            entities.map {
                val group = it.groupId
                val name = if (it.direction == HubFileStates.OUT && group != null) groupName(group) else peerName(it.peerId)
                HubFileRows.toTransfer(it, progress[it.transferId], it.transferId in active, name)
            }
        }.stateIn(bindingScope, SharingStarted.Eagerly, emptyList())

    private val control = object : ExternalTransferControl {
        override fun owns(transferId: String): Boolean =
            transferId.startsWith(HubFileStates.TRANSFER_PREFIX_OUT) || transferId.startsWith(HubFileStates.TRANSFER_PREFIX_IN)

        override suspend fun accept(transferId: String) = this@HubFileBinding.accept(transferId)
        override suspend fun decline(transferId: String) {
            dao.moveState(transferId, HubFileStates.OFFERED, HubFileStates.DECLINED, null, timeSource.nowMs())
        }

        override suspend fun pause(transferId: String) {
            dao.setPaused(transferId, true, timeSource.nowMs())
            stop(transferId)
        }

        override suspend fun resume(transferId: String) {
            resumeFailed(transferId)
            dao.setPaused(transferId, false, timeSource.nowMs())
            launchRun(transferId)
        }

        override suspend fun cancel(transferId: String) = this@HubFileBinding.cancel(transferId)

        /** The system ran out of background time: stop now, keep everything, and continue on the next [start]. */
        override suspend fun pauseForSystem(transferId: String, reason: String) = stop(transferId)
    }

    private val interceptor = TransferSendInterceptor { target, fileUri, displayName, fileSize ->
        intercept(target, fileUri, displayName, fileSize)
    }

    init {
        transferRepository.attachExternalRows(rows, control, source = EXTERNAL_SOURCE)
        transferRepository.sendInterceptor = interceptor
        // A ticket the chat stored arrives as a new OFFERED row; with automatic acceptance on it downloads at once.
        bindingScope.launch(workerDispatcher) {
            dao.observeRecent(ROW_LIMIT)
                .map { list -> list.filter { it.direction == HubFileStates.IN && it.state == HubFileStates.OFFERED && !it.paused } }
                .distinctUntilChanged { a, b -> a.map { it.transferId } == b.map { it.transferId } }
                .collect { offered -> acceptAutomatically(offered) }
        }
    }

    /** Resumes every unfinished row (at start-up, and after the host returns from the background). Idempotent. */
    public fun start() {
        bindingScope.launch(workerDispatcher) {
            val now = timeSource.nowMs()
            val pruned = dao.prune(terminalBeforeMs = now - PRUNE_FINISHED_AFTER_MS, expiredBeforeMs = now - PRUNE_EXPIRED_AFTER_MS)
            if (pruned > 0) FlashProbe.emit("hubfile.rows.pruned", "count" to pruned)
            for (row in dao.runnable(HubFileStates.RUNNING)) launchRun(row.transferId)
            acceptAutomatically(dao.runnable(OFFERED_ONLY))
        }
    }

    /** Stops every run (state stays on disk) and gives the send path back to the direct pipeline. */
    public fun detach() {
        if (transferRepository.sendInterceptor === interceptor) transferRepository.sendInterceptor = null
        bindingJob.cancel()
    }

    // ----- sending ----------------------------------------------------------------------------------------------

    private suspend fun intercept(target: FlashDevice, fileUri: String, displayName: String, fileSize: Long): FlashTransferId? {
        val peer = target.id.value
        if (peer.isBlank() || isDirectlyReachable(peer)) return null
        val route = fileRoute(peer)
        if (route == HubFileRoute.UNAVAILABLE) return null
        val name = peerName(peer)?.ifBlank { null } ?: target.friendlyName.ifBlank { peer.take(8) }
        return record(Outgoing(peerId = peer, groupId = null, recipients = null, route = route, label = name), fileUri, displayName, fileSize)
    }

    /**
     * Takes group [groupId]'s file for the members in [recipients] (the ones the direct pipeline could not reach): uploads it
     * once and records who the tickets are for. Null when the hub cannot carry a group file now (the caller keeps what it
     * has), otherwise the transfer id, which is a Failed row when the hub refused the file (too large, an older hub). The
     * upload is linked to the chat message [messageId] through [registerGroupFile].
     */
    public suspend fun claimGroup(
        groupId: String,
        messageId: String,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        recipients: List<String>,
    ): FlashTransferId? {
        val ids = recipients.filter { it.isNotBlank() && ',' !in it }.distinct()
        if (groupId.isBlank() || ids.isEmpty()) return null
        val route = groupFileRoute(groupId)
        if (route == HubFileRoute.UNAVAILABLE) return null
        val label = groupName(groupId)?.ifBlank { null } ?: groupId.take(8)
        val claimed = record(Outgoing(peerId = groupId, groupId = groupId, recipients = ids.joinToString(","), route = route, label = label), fileUri, displayName, fileSize)
        if (claimed != null) registerGroupFile(messageId, claimed.value)
        return claimed
    }

    /**
     * Stops the upload of [transferId] because the person deleted its chat message for everyone (or while it was still
     * uploading), and deletes what the hub holds. Does nothing for a download or an unknown id: a received file is the
     * recipient's own copy.
     */
    public suspend fun cancelOutgoing(transferId: String) {
        if (dao.get(transferId)?.direction == HubFileStates.OUT) cancel(transferId)
    }

    /** Where an outgoing file goes: one peer, or one group's [recipients] (comma separated). [label] names them in a sentence. */
    private class Outgoing(val peerId: String, val groupId: String?, val recipients: String?, val route: HubFileRoute, val label: String)

    /** Records the file in `hub_file` and starts its upload, or records why the hub will not take it. Null leaves the send to the caller. */
    private suspend fun record(to: Outgoing, fileUri: String, displayName: String, fileSize: Long): FlashTransferId? {
        val hub = hubUrl()?.takeIf { it.isNotBlank() } ?: return null
        val size = if (fileSize > 0L) fileSize else withContext(workerDispatcher) {
            runCatching { StreamingHubFileSource.measure(openSource, fileUri) }.getOrDefault(0L)
        }
        // An empty file (or one that cannot be read) stays on the direct path, which reports it (situation 11).
        if (size <= 0L) return null

        val failure = if (to.route == HubFileRoute.NEEDS_SEALING) {
            HubFileText.needsSealing(to.label)
        } else {
            val limit = maxFileBytes(hub)
            when {
                limit == 0L -> HubFileText.HUB_OLD
                limit != null && size > limit -> HubFileText.tooLarge(TransferFailureText.formatSize(size), TransferFailureText.formatSize(limit))
                else -> null
            }
        }
        val fileId = HubFileSealing.newFileId()
        val transferId = HubFileStates.TRANSFER_PREFIX_OUT + fileId
        val now = timeSource.nowMs()
        dao.insert(
            HubFileEntity(
                transferId = transferId,
                direction = HubFileStates.OUT,
                fileId = fileId,
                peerId = to.peerId,
                hubUrl = hub,
                // A display name, made safe here so the recipient's checks never refuse the ticket for it.
                fileName = FlashPathSanitizer.sanitizeFileName(displayName),
                // The chat message knows the type; the ticket takes it from there.
                mime = DEFAULT_MIME,
                plainBytes = size,
                sourceUri = fileUri,
                fileKey = null,
                cipherSha256 = null,
                cipherChunkBytes = 0,
                chunkCount = 0,
                expiresAtMs = 0L,
                messageId = null,
                state = if (failure == null) HubFileStates.UPLOADING else HubFileStates.FAILED,
                paused = false,
                errorText = failure,
                bytesDone = 0L,
                localPath = null,
                createdAtMs = now,
                updatedAtMs = now,
                groupId = to.groupId,
                recipients = to.recipients,
            ),
        )
        FlashProbe.emit(
            "hubfile.send.claimed",
            "id" to FlashProbe.short(fileId),
            "peer" to FlashProbe.short(to.peerId),
            "bytes" to size,
            "outcome" to if (failure == null) "upload" else "refused",
            "group" to (to.groupId != null),
        )
        if (failure == null) launchRun(transferId)
        return FlashTransferId(transferId)
    }

    /** The hub's per-file limit, cached for [infoTtlMs]; the last known value when the hub cannot be asked; null if never known. */
    private suspend fun maxFileBytes(hub: String): Long? = infoLock.withLock {
        val now = timeSource.nowMs()
        val cached = cachedLimit
        if (cached != null && now - cachedAtMs in 0 until infoTtlMs) return@withLock cached
        when (val r = api.hubInfo(hub)) {
            is HubFileResult.Ok -> r.value.maxFileBytes.also {
                cachedLimit = it
                cachedAtMs = now
            }
            is HubFileResult.Err -> cached
        }
    }

    // ----- runs -------------------------------------------------------------------------------------------------

    private fun launchRun(transferId: String) {
        val job = jobsLock.withLock {
            if (jobs[transferId]?.isActive == true) {
                null
            } else {
                bindingScope.launch(workerDispatcher, start = CoroutineStart.LAZY) { runUntilSettled(transferId) }
                    .also { jobs[transferId] = it }
            }
        } ?: return
        job.invokeOnCompletion {
            // Only the current run clears the row's live state: a pause and resume in quick succession must not have the
            // old run's end hide the new one.
            val current = jobsLock.withLock {
                if (jobs[transferId] === job) jobs.remove(transferId)
                jobs[transferId] == null
            }
            if (current) {
                running.update { it - transferId }
                live.update { it - transferId }
            }
        }
        running.update { it + transferId }
        job.start()
    }

    private fun stop(transferId: String) {
        jobsLock.withLock { jobs.remove(transferId) }?.cancel()
        running.update { it - transferId }
        live.update { it - transferId }
    }

    /** Runs [transferId] until it finishes, fails, is paused, or stops being runnable; retries interruptions with backoff. */
    private suspend fun runUntilSettled(transferId: String) {
        var wait = retryDelayMs
        while (true) {
            val row = dao.get(transferId) ?: return
            if (row.paused) return
            val again = try {
                when {
                    row.direction == HubFileStates.OUT && row.state == HubFileStates.UPLOADING -> upload(row)
                    row.direction == HubFileStates.OUT && row.state == HubFileStates.READY -> {
                        queueTicket(transferId)
                        false
                    }
                    row.direction == HubFileStates.IN && row.state == HubFileStates.DOWNLOADING -> download(row)
                    else -> false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A bug or an unexpected platform error: keep the row, say so in a sentence, and try again later.
                FlashLog.w("HUB", "hub file run ${transferId.take(14)} stopped: ${t::class.simpleName}")
                dao.setErrorText(transferId, HubFileText.reason(HubFileError.LocalStorage("run")), timeSource.nowMs())
                true
            }
            if (!again) return
            if (giveUpOnStale(row)) return
            running.update { it - transferId }
            delay(wait)
            wait = (wait * 2).coerceAtMost(maxRetryDelayMs)
            running.update { it + transferId }
        }
    }

    /**
     * C-07: a run that keeps being interrupted must not retry for ever. An upload stops at the hub's own 24 hour window
     * (counted from the row's creation, so it survives a restart); a download stops once the file has left the hub. The row
     * becomes Failed with a sentence, and Retry ([resumeFailed]) starts it again.
     * @return true when the row was failed and the run must end.
     */
    private suspend fun giveUpOnStale(row: HubFileEntity): Boolean {
        val now = timeSource.nowMs()
        return when {
            row.direction == HubFileStates.OUT && row.state == HubFileStates.UPLOADING && now - row.createdAtMs > UPLOAD_WINDOW_MS -> {
                if (dao.moveState(row.transferId, HubFileStates.UPLOADING, HubFileStates.FAILED, HubFileText.UPLOAD_WINDOW_ENDED, now) == 1) {
                    outgoingEnded(row.transferId)
                }
                true
            }
            row.direction == HubFileStates.IN && row.state == HubFileStates.DOWNLOADING && row.expiresAtMs in 1 until now -> {
                failIncoming(row, HubFileError.NotFound, peerName(row.peerId))
                true
            }
            else -> false
        }
    }

    /** Retry on a Failed hub row: back to the state it failed from, error cleared, and run again. */
    private suspend fun resumeFailed(transferId: String) {
        val row = dao.get(transferId) ?: return
        if (row.state != HubFileStates.FAILED) return
        val to = if (row.direction == HubFileStates.OUT) HubFileStates.UPLOADING else HubFileStates.DOWNLOADING
        if (dao.moveState(transferId, HubFileStates.FAILED, to, null, timeSource.nowMs()) == 1) {
            FlashLog.i("HUB", "hub file ${transferId.take(14)} retried from Failed")
            // The chat message was given up when the upload failed; it waits for a ticket again.
            if (to == HubFileStates.UPLOADING) outgoingRetried(transferId)
        }
    }

    /** @return true when the run was interrupted and should be tried again later. */
    private suspend fun upload(row: HubFileEntity): Boolean {
        val id = row.transferId
        val uri = row.sourceUri ?: return failOutgoing(row, HubFileError.LocalStorage("source"))
        val source = StreamingHubFileSource(openSource, uri, row.plainBytes)
        val outcome = try {
            transfers.upload(
                HubUploadRequest(
                    hubUrl = row.hubUrl,
                    fileId = row.fileId,
                    source = source,
                    // A group file is addressed to the group: the hub checks its roster on every read.
                    toDeviceId = if (row.groupId == null) row.peerId else null,
                    groupId = row.groupId,
                    // No name on the hub: the name travels only inside the sealed ticket.
                    nameHint = null,
                    maxFileBytes = maxFileBytes(row.hubUrl) ?: Long.MAX_VALUE,
                ),
                onProgress = progressSink(id),
            )
        } finally {
            withContext(workerDispatcher) { runCatching { source.close() } }
        }
        val now = timeSource.nowMs()
        return when (outcome) {
            is HubUploadOutcome.Done -> {
                val t = outcome.ticket
                if (dao.markUploaded(id, t.key.encode(), t.cipherSha256, t.cipherChunkBytes, t.chunkCount, t.expiresAtMs, now) == 1) {
                    transfers.releaseUpload(row.fileId)
                    queueTicket(id)
                }
                false
            }
            is HubUploadOutcome.Interrupted -> {
                dao.setProgress(id, outcome.progress.bytesDone, now)
                dao.setErrorText(id, HubFileText.reason(outcome.error), now)
                true
            }
            is HubUploadOutcome.Failed -> failOutgoing(row, outcome.error)
        }
    }

    private suspend fun failOutgoing(row: HubFileEntity, error: HubFileError): Boolean {
        if (dao.moveState(row.transferId, HubFileStates.UPLOADING, HubFileStates.FAILED, sentence(error, row, null), timeSource.nowMs()) == 1) {
            outgoingEnded(row.transferId)
        }
        return false
    }

    private suspend fun download(row: HubFileEntity): Boolean {
        val id = row.transferId
        val sender = peerName(row.peerId)
        val key = row.fileKey?.let(HubFileKey::decode)
        val sha = row.cipherSha256
        if (key == null || sha == null) return failIncoming(row, HubFileError.Integrity, sender)
        val ticket = HubFileTicket(row.fileId, key, sha, row.plainBytes, row.cipherChunkBytes, row.chunkCount, row.expiresAtMs)
        val hub = hubUrl()?.takeIf { it.isNotBlank() } ?: row.hubUrl
        val outcome = transfers.download(HubDownloadRequest(hubUrl = hub, ticket = ticket, senderDeviceId = row.peerId), onProgress = progressSink(id))
        val now = timeSource.nowMs()
        return when (outcome) {
            is HubDownloadOutcome.Done -> {
                val placed = withContext(workerDispatcher) { runCatching { place(outcome.path, row) }.getOrNull() }
                    ?: return failIncoming(row, HubFileError.LocalStorage("place"), sender)
                if (dao.markDownloaded(id, placed, now) == 1) {
                    FlashProbe.emit("hubfile.receive.done", "id" to FlashProbe.short(row.fileId), "bytes" to outcome.plainBytes)
                } else {
                    // Cancelled while the last chunk arrived: the cancel wins, and its file goes.
                    withContext(workerDispatcher) { runCatching { fileSystem.delete(placed.toPath(), mustExist = false) } }
                }
                false
            }
            is HubDownloadOutcome.Interrupted -> {
                dao.setProgress(id, outcome.progress.bytesDone, now)
                dao.setErrorText(id, HubFileText.reason(outcome.error), now)
                true
            }
            is HubDownloadOutcome.Failed -> failIncoming(row, outcome.error, sender)
        }
    }

    private suspend fun failIncoming(row: HubFileEntity, error: HubFileError, sender: String?): Boolean {
        dao.moveState(row.transferId, HubFileStates.DOWNLOADING, HubFileStates.FAILED, sentence(error, row, sender), timeSource.nowMs())
        transfers.discardDownload(row.fileId)
        return false
    }

    /** Moves a verified file out of the engine's store to `<receivedDirectory>/<transferId>/<name>`. Blocking. */
    private fun place(enginePath: String, row: HubFileEntity): String {
        val dir = receivedDirectory().toPath() / row.transferId
        fileSystem.createDirectories(dir)
        val target = dir / FlashPathSanitizer.sanitizeFileName(row.fileName)
        fileSystem.delete(target, mustExist = false)
        val from = enginePath.toPath()
        try {
            fileSystem.atomicMove(from, target)
        } catch (e: IOException) {
            // Another volume (Android's app-specific external storage): copy, then remove the engine's copy.
            fileSystem.copy(from, target)
            fileSystem.delete(from, mustExist = false)
        }
        runCatching { onPlaced(target.toString()) }
        return target.toString()
    }

    private fun progressSink(transferId: String): (HubTransferProgress) -> Unit {
        var firstAtMs = Long.MIN_VALUE
        var firstBytes = 0L
        return { p ->
            val now = timeSource.nowMs()
            if (firstAtMs == Long.MIN_VALUE) {
                firstAtMs = now
                firstBytes = p.bytesDone
            }
            val elapsed = now - firstAtMs
            val speed = if (elapsed > 0L) (p.bytesDone - firstBytes).coerceAtLeast(0L) * 1000L / elapsed else 0L
            val eta = if (speed > 0L) (p.totalBytes - p.bytesDone).coerceAtLeast(0L) / speed else 0L
            live.update { it + (transferId to HubFileLive(p.bytesDone, speed, eta)) }
        }
    }

    // ----- control ----------------------------------------------------------------------------------------------

    private suspend fun accept(transferId: String) {
        if (dao.moveState(transferId, HubFileStates.OFFERED, HubFileStates.DOWNLOADING, null, timeSource.nowMs()) == 1) {
            launchRun(transferId)
        }
    }

    private suspend fun acceptAutomatically(offered: List<HubFileEntity>) {
        for (row in offered) {
            if (row.direction == HubFileStates.IN && autoDownload(row.peerId, row.mime)) accept(row.transferId)
        }
    }

    private suspend fun cancel(transferId: String) {
        val row = dao.get(transferId) ?: return
        val now = timeSource.nowMs()
        if (row.direction == HubFileStates.OUT) {
            val moved = OUT_CANCELLABLE.any { from -> dao.moveState(transferId, from, HubFileStates.CANCELLED, null, now) == 1 }
            stop(transferId)
            if (!moved) return
            // The hub deletes what it holds; offline, its retention removes it later. The recipient then sees "no longer on the hub".
            transfers.cancelUpload(row.hubUrl, row.fileId)
            outgoingEnded(transferId)
        } else {
            val moved = IN_CANCELLABLE.any { from -> dao.moveState(transferId, from, HubFileStates.CANCELLED, null, now) == 1 }
            stop(transferId)
            if (moved) transfers.discardDownload(row.fileId)
        }
        FlashProbe.emit("hubfile.cancel", "id" to FlashProbe.short(row.fileId), "direction" to row.direction.lowercase())
    }

    private fun sentence(error: HubFileError, row: HubFileEntity, senderName: String?): String = when {
        error is HubFileError.TooLarge -> {
            val limit = error.limitBytes
            if (limit != null && limit in 1 until Long.MAX_VALUE) {
                HubFileText.tooLarge(TransferFailureText.formatSize(row.plainBytes), TransferFailureText.formatSize(limit))
            } else {
                HubFileText.sentence(error, senderName.orEmpty())
            }
        }
        else -> HubFileText.sentence(error, senderName.orEmpty())
    }

    public companion object {
        /** The transfer repository's name for this source of external rows. */
        public const val EXTERNAL_SOURCE: String = "hub"

        /** The hub keeps an unfinished upload this long; after it a retrying upload is given up (C-07). */
        public const val UPLOAD_WINDOW_MS: Long = 24L * 60 * 60 * 1000

        public const val DEFAULT_RETRY_DELAY_MS: Long = 15_000L
        public const val DEFAULT_MAX_RETRY_DELAY_MS: Long = 10L * 60_000L
        public const val DEFAULT_INFO_TTL_MS: Long = 10L * 60_000L

        private const val ROW_LIMIT = 200

        /** A finished row nothing shows any more is removed after this long. */
        private const val PRUNE_FINISHED_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        /** A row whose file left the hub is removed this long after it expired. */
        private const val PRUNE_EXPIRED_AFTER_MS = 24L * 60 * 60 * 1000
        private const val DEFAULT_MIME = "application/octet-stream"
        private val OFFERED_ONLY = listOf(HubFileStates.OFFERED)
        private val OUT_CANCELLABLE = listOf(HubFileStates.UPLOADING, HubFileStates.READY, HubFileStates.QUEUED)
        private val IN_CANCELLABLE = listOf(HubFileStates.OFFERED, HubFileStates.DOWNLOADING)
    }
}
