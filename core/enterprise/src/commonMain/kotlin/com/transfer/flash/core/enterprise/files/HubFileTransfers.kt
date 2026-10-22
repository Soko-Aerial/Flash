@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.enterprise.HubUrlPolicy
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.FileStates
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.HubFileIds
import com.transfer.flash.core.security.enterprise.HubFileHasher
import com.transfer.flash.core.security.enterprise.HubFileKey
import com.transfer.flash.core.security.enterprise.HubFileLayout
import com.transfer.flash.core.security.enterprise.HubFileSealing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** What to send through the hub. Exactly one of [toDeviceId] / [groupId]. */
@FlashInternalApi
public data class HubUploadRequest(
    public val hubUrl: String,
    /** From [HubFileSealing.newFileId]; the caller keeps it to resume, cancel or release this upload. */
    public val fileId: String,
    public val source: HubFileSource,
    public val toDeviceId: String? = null,
    public val groupId: String? = null,
    /** For the hub operator's log only; never shown to anyone else. */
    public val nameHint: String? = null,
    /** `HubInfo.maxFileBytes` as the hub answered it: the plaintext limit, 0 when the hub carries no files. */
    public val maxFileBytes: Long,
    /** The ciphertext chunk size; the hub's maximum unless a test wants many small chunks. */
    public val cipherChunkBytes: Int = HubContract.MAX_CHUNK_BYTES,
)

/**
 * What a recipient needs to fetch and open the file. P3 puts it inside the sealed chat payload; [key] is a secret and
 * the rest is metadata the hub knows anyway.
 */
@FlashInternalApi
public data class HubFileTicket(
    public val fileId: String,
    public val key: HubFileKey,
    public val cipherSha256: String,
    public val plainBytes: Long,
    public val cipherChunkBytes: Int,
    public val chunkCount: Int,
    /** When the hub deletes it (0 when unknown). */
    public val expiresAtMs: Long,
)

/** What to download. Everything but [hubUrl] comes from the sender's [HubFileTicket], never from the hub. */
@FlashInternalApi
public data class HubDownloadRequest(
    public val hubUrl: String,
    public val ticket: HubFileTicket,
    /** When known (the message's sender), the hub's `senderDeviceId` must equal it. */
    public val senderDeviceId: String? = null,
    /** When the message carries the plaintext hash, the assembled file must match it too. */
    public val plainSha256: String? = null,
)

/** Progress of one transfer, reported after every stored chunk. */
@FlashInternalApi
public data class HubTransferProgress(
    public val fileId: String,
    public val chunksDone: Int,
    public val chunkCount: Int,
    public val bytesDone: Long,
    public val totalBytes: Long,
)

@FlashInternalApi
public sealed interface HubUploadOutcome {
    /** The file is READY on the hub; [ticket] goes into the message. The local record stays until [HubFileTransfers.releaseUpload]. */
    public data class Done(public val ticket: HubFileTicket) : HubUploadOutcome

    /** Stopped on a transient error after the retry budget; calling [HubFileTransfers.upload] again continues it. */
    public data class Interrupted(public val error: HubFileError, public val progress: HubTransferProgress) : HubUploadOutcome

    /** Ended for good; nothing is kept locally. */
    public data class Failed(public val error: HubFileError) : HubUploadOutcome
}

@FlashInternalApi
public sealed interface HubDownloadOutcome {
    /** The whole file is at [path], verified against the sender's hashes. */
    public data class Done(public val path: String, public val plainBytes: Long, public val plainSha256: String) : HubDownloadOutcome

    /** Stopped on a transient error; calling [HubFileTransfers.download] again continues it. */
    public data class Interrupted(public val error: HubFileError, public val progress: HubTransferProgress) : HubDownloadOutcome

    /** Ended for good; the partial file and its record are gone. */
    public data class Failed(public val error: HubFileError) : HubDownloadOutcome
}

/**
 * How hard one run tries before it reports [HubUploadOutcome.Interrupted]. A run never gives up on a transfer: it ends
 * this attempt with the progress on disk, and the host calls again (network back, app opened, a timer).
 *
 * @property requestTimeoutMs one HTTP exchange longer than this counts as [HubFileError.Offline]: a socket left half-open
 *   by a Wi-Fi to mobile-data switch must not hang a transfer for ever.
 * @property maxConsecutiveFailures transient failures in a row, with no stored chunk between them, before the run stops.
 */
@FlashInternalApi
public data class HubTransferPolicy(
    public val requestTimeoutMs: Long = 60_000L,
    public val maxConsecutiveFailures: Int = 6,
    public val baseBackoffMs: Long = 1_000L,
    public val maxBackoffMs: Long = 30_000L,
    /** A `Retry-After` above this is capped; the run would rather stop and be resumed later than sleep for an hour. */
    public val maxRetryAfterMs: Long = 120_000L,
) {
    init {
        require(requestTimeoutMs > 0 && maxConsecutiveFailures >= 1 && baseBackoffMs >= 0 && maxBackoffMs >= baseBackoffMs)
    }
}

/**
 * The resumable sealed-file engine (HF-C4): uploads and downloads one file through the hub in numbered chunks, with the
 * set of finished chunks saved after every chunk, so a run can stop anywhere and the next run only redoes the chunk in
 * flight. The engine does not care which network carries the requests: a run that started on Wi-Fi and is resumed on
 * mobile data, through another access point or after the hub restarted, continues from the same bitmap, because the
 * state belongs to the file id and the hub, not to a connection.
 *
 * Guarantees, each covered by `HubFileTransfersTest`:
 * - **Upload resume** (situation 1): the key is saved before any byte leaves, sealing is deterministic per index, and a
 *   resumed upload re-hashes its source first; a changed source ends the upload ([HubFileError.SourceChanged]) instead
 *   of mixing two versions.
 * - **Download resume** (situation 2): plaintext is written and synced before its chunk is marked done; only missing
 *   chunks are requested.
 * - **Trust nothing from the hub**: the hub's status must match the sender's ticket, every chunk must open under the
 *   file key at its own index, and the assembled file is re-sealed and hashed against the sender's ciphertext hash
 *   before it is handed over. One full re-download is allowed after a mismatch (a corrupted local disk), never two.
 * - **One run per file and direction**: a second call for the same file waits for the first and then continues it.
 *
 * Blocking storage work runs on [ioDispatcher]; delays and timeouts use the caller's clock, so tests run on virtual time.
 */
@FlashInternalApi
public class HubFileTransfers(
    private val api: HubFilesApi,
    private val storage: HubFileStorage,
    private val policy: HubTransferPolicy = HubTransferPolicy(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
) {

    private val locksGuard = Mutex()
    private val locks = mutableMapOf<String, Pair<Mutex, Int>>()

    // ----- upload -----------------------------------------------------------------------------------------------

    public suspend fun upload(
        request: HubUploadRequest,
        onProgress: (HubTransferProgress) -> Unit = {},
    ): HubUploadOutcome {
        val fileId = request.fileId
        if (!HubFileIds.isFileId(fileId)) return HubUploadOutcome.Failed(HubFileError.Refused(BAD_ID))
        if ((request.toDeviceId == null) == (request.groupId == null)) return HubUploadOutcome.Failed(HubFileError.Refused(BAD_RECIPIENT))
        if (request.maxFileBytes <= 0L) return HubUploadOutcome.Failed(HubFileError.HubTooOld)
        return locked("u:$fileId") { UploadRun(request, onProgress).run() }
    }

    /** Stops an upload for good: the hub deletes what it has, the local record goes. Safe to call twice. */
    public suspend fun cancelUpload(hubUrl: String, fileId: String): HubFileResult<Unit> {
        if (!HubFileIds.isFileId(fileId)) return HubFileResult.Err(HubFileError.Refused(BAD_ID))
        return locked("u:$fileId") {
            val result = timed { api.cancel(hubUrl, fileId) }
            if (result is HubFileResult.Ok || (result as HubFileResult.Err).error.transient.not()) {
                io { storage.deleteState(HubTransferDirection.UPLOAD, fileId) }
            }
            probe("hubfile.upload", fileId, "cancel", reason = (result as? HubFileResult.Err)?.error)
            result
        }
    }

    /** Forgets a finished upload's local record once the caller has stored its [HubFileTicket] (P3: the message is saved). */
    public suspend fun releaseUpload(fileId: String) {
        if (!HubFileIds.isFileId(fileId)) return
        locked("u:$fileId") { io { storage.deleteState(HubTransferDirection.UPLOAD, fileId) } }
    }

    // ----- download ---------------------------------------------------------------------------------------------

    public suspend fun download(
        request: HubDownloadRequest,
        onProgress: (HubTransferProgress) -> Unit = {},
    ): HubDownloadOutcome {
        val t = request.ticket
        if (!HubFileIds.isFileId(t.fileId)) return HubDownloadOutcome.Failed(HubFileError.Refused(BAD_ID))
        val layout = HubFileLayout.of(t.plainBytes, t.cipherChunkBytes)
        if (layout == null || layout.chunkCount != t.chunkCount || !HubFileIds.isSha256(t.cipherSha256)) {
            return HubDownloadOutcome.Failed(HubFileError.Integrity)
        }
        return locked("d:${t.fileId}") { DownloadRun(request, layout, onProgress).run() }
    }

    /** Throws away a partial download and its record (situation 9: a recipient cancels). Safe to call twice. */
    public suspend fun discardDownload(fileId: String) {
        if (!HubFileIds.isFileId(fileId)) return
        locked("d:$fileId") {
            io {
                storage.deletePart(fileId)
                storage.deleteState(HubTransferDirection.DOWNLOAD, fileId)
            }
        }
        probe("hubfile.download", fileId, "discard")
    }

    /** File ids with an unfinished (or, for uploads, unreleased) record, for a host to resume at start-up. */
    public suspend fun pending(direction: HubTransferDirection): List<String> =
        runSuspendCatching { io { storage.stateIds(direction) } }.getOrElse { emptyList() }

    // ----- upload run -------------------------------------------------------------------------------------------

    private inner class UploadRun(val request: HubUploadRequest, val onProgress: (HubTransferProgress) -> Unit) {
        val fileId = request.fileId
        val source = request.source
        lateinit var state: UploadState
        lateinit var layout: HubFileLayout
        lateinit var key: HubFileKey

        suspend fun run(): HubUploadOutcome {
            val stored = io { storage.readState(HubTransferDirection.UPLOAD, fileId) }?.let(HubFileStateCodec::decodeUpload)
            if (stored != null && !HubUrlPolicy.sameHub(stored.hubUrl, request.hubUrl)) {
                // The device moved to another hub: the old hub's half-upload is unreachable from here and expires there.
                return fail(HubFileError.NotThisHub)
            }
            if (stored != null && (stored.toDeviceId != request.toDeviceId || stored.groupId != request.groupId)) {
                return fail(HubFileError.Conflict)
            }
            if (stored?.completed == true) return HubUploadOutcome.Done(ticketOf(stored))

            val size = runCatching { source.size }.getOrDefault(-1L)
            if (size < 0L) return fail(HubFileError.LocalStorage("read"))
            if (size == 0L) return fail(HubFileError.Empty)
            if (size > request.maxFileBytes) return fail(HubFileError.TooLarge(request.maxFileBytes))
            layout = HubFileLayout.of(size, request.cipherChunkBytes) ?: return fail(HubFileError.TooLarge(request.maxFileBytes))

            key = if (stored == null) {
                HubFileKey.generate()
            } else {
                // A record whose key does not decode cannot re-seal what already left: withdraw it and say so.
                HubFileKey.decode(stored.keyText) ?: run {
                    timed { api.cancel(request.hubUrl, fileId) }
                    return fail(HubFileError.LocalStorage("state"))
                }
            }
            val sha = when (val hashed = hashSource()) {
                is HubFileResult.Ok -> hashed.value
                is HubFileResult.Err -> return fail(hashed.error)
            }
            if (stored != null &&
                (stored.cipherSha256 != sha || stored.plainBytes != size || stored.cipherChunkBytes != layout.cipherChunkBytes)
            ) {
                // Same id, other bytes: never seal a second plaintext under a key and index that already left the device.
                timed { api.cancel(request.hubUrl, fileId) }
                return fail(HubFileError.SourceChanged)
            }
            state = stored ?: UploadState(
                fileId = fileId,
                hubUrl = request.hubUrl,
                keyText = key.encode(),
                toDeviceId = request.toDeviceId,
                groupId = request.groupId,
                nameHint = request.nameHint,
                plainBytes = size,
                cipherChunkBytes = layout.cipherChunkBytes,
                chunkCount = layout.chunkCount,
                cipherSha256 = sha,
                created = false,
                completed = false,
                expiresAtMs = 0L,
                done = HubChunkBitmap(layout.chunkCount),
            )
            // The key is on disk BEFORE the first byte leaves: a process death after any PUT can still re-seal identically.
            // Later save failures (a full disk) only interrupt: the record on disk is the previous one, which is still true.
            save()?.let { return if (stored == null) fail(it) else interrupted(it) }
            probe("hubfile.upload", fileId, if (stored == null) "start" else "resume", state.done.count(), layout.chunkCount)
            return body().also { outcome -> report(outcome) }
        }

        private suspend fun body(): HubUploadOutcome {
            var restarts = 0
            while (true) {
                if (!state.created) {
                    when (val created = retrying { api.create(request.hubUrl, createRequest()) }) {
                        is HubFileResult.Ok -> {
                            state.created = true
                            save()?.let { return interrupted(it) }
                        }
                        is HubFileResult.Err -> when {
                            created.error.transient -> return interrupted(created.error)
                            // The id exists and is no longer uploading: our own earlier run may have completed it.
                            created.error == HubFileError.Conflict -> {
                                resolveCreateConflict(restarts)?.let { return it }
                                restarts++
                                continue
                            }
                            else -> return fail(created.error)
                        }
                    }
                } else {
                    when (val st = retrying { api.status(request.hubUrl, fileId) }) {
                        is HubFileResult.Ok -> {
                            if (st.value.state == FileStates.READY) return finish(st.value)
                            if (!matches(st.value)) return fail(HubFileError.Conflict)
                            if (st.value.receivedChunks < state.done.count()) {
                                // The hub lost chunks we had sent (a restore, a reconcile): send everything again;
                                // the ones it still has answer 204 without changing anything.
                                state.done.clear()
                                save()?.let { return interrupted(it) }
                            }
                        }
                        is HubFileResult.Err -> when {
                            st.error.transient -> return interrupted(st.error)
                            st.error == HubFileError.NotFound && restarts < MAX_RESTARTS -> {
                                // Swept after the 24 h upload window (situation 4): same id, same key, from chunk 0.
                                restarts++
                                restart()?.let { return interrupted(it) }
                                continue
                            }
                            else -> return fail(st.error)
                        }
                    }
                }

                for (index in 0 until layout.chunkCount) {
                    if (state.done[index]) continue
                    val cipher = when (val sealed = retrying { sealedChunk(index) }) {
                        is HubFileResult.Ok -> sealed.value
                        is HubFileResult.Err -> return if (sealed.error.transient) interrupted(sealed.error) else fail(sealed.error)
                    }
                    val chunkSha = HubFileHasher().also { it.update(cipher) }.digestHex()
                    when (val put = retrying { api.putChunk(request.hubUrl, fileId, index, cipher, chunkSha) }) {
                        is HubFileResult.Ok -> {
                            state.done.set(index)
                            save()?.let { return interrupted(it) }
                            onProgress(progress())
                        }
                        is HubFileResult.Err -> when {
                            put.error.transient -> return interrupted(put.error)
                            put.error == HubFileError.Conflict -> return finishFromStatus() ?: fail(HubFileError.Conflict)
                            put.error == HubFileError.NotFound && restarts < MAX_RESTARTS -> {
                                restarts++
                                restart()?.let { return interrupted(it) }
                                break
                            }
                            else -> return fail(put.error)
                        }
                    }
                }
                if (!state.created) continue // restarted inside the chunk loop

                when (val done = retrying { api.complete(request.hubUrl, fileId) }) {
                    is HubFileResult.Ok -> return finish(done.value)
                    is HubFileResult.Err -> when {
                        done.error.transient -> return interrupted(done.error)
                        done.error == HubFileError.Conflict && restarts < MAX_RESTARTS -> {
                            // The hub says a chunk is missing although we sent it: resend everything once.
                            restarts++
                            state.done.clear()
                            save()?.let { return interrupted(it) }
                        }
                        done.error == HubFileError.NotFound && restarts < MAX_RESTARTS -> {
                            restarts++
                            restart()?.let { return interrupted(it) }
                        }
                        else -> return fail(done.error)
                    }
                }
            }
        }

        private fun createRequest() = CreateFileRequestDto(
            fileId = fileId,
            totalBytes = layout.cipherBytes,
            chunkSize = layout.cipherChunkBytes,
            chunkCount = layout.chunkCount,
            sha256 = state.cipherSha256,
            toDeviceId = state.toDeviceId,
            groupId = state.groupId,
            nameHint = state.nameHint,
        )

        private fun matches(st: FileStatusDto): Boolean = st.fileId == fileId && st.sha256 == state.cipherSha256 &&
            st.totalBytes == layout.cipherBytes && st.chunkSize == layout.cipherChunkBytes && st.chunkCount == layout.chunkCount

        /**
         * The hub holds this id with other metadata or no longer uploading. READY and ours: our own earlier run completed
         * it. Still uploading under other metadata: an earlier run of ours whose record was lost (a torn state file), so
         * the key that sealed its chunks is gone; withdraw it and start over (null = try create again). Anything else ends.
         */
        private suspend fun resolveCreateConflict(restarts: Int): HubUploadOutcome? {
            val st = retrying { api.status(request.hubUrl, fileId) }
            if (st !is HubFileResult.Ok) return if ((st as HubFileResult.Err).error.transient) interrupted(st.error) else fail(HubFileError.Conflict)
            if (st.value.state == FileStates.READY) return finish(st.value)
            if (st.value.state != FileStates.UPLOADING || restarts >= MAX_RESTARTS) return fail(HubFileError.Conflict)
            val cancelled = retrying { api.cancel(request.hubUrl, fileId) }
            if (cancelled is HubFileResult.Err) return if (cancelled.error.transient) interrupted(cancelled.error) else fail(HubFileError.Conflict)
            return restart()?.let { interrupted(it) }
        }

        private suspend fun finishFromStatus(): HubUploadOutcome? {
            val st = retrying { api.status(request.hubUrl, fileId) }
            return if (st is HubFileResult.Ok && st.value.state == FileStates.READY) finish(st.value) else null
        }

        private suspend fun finish(st: FileStatusDto): HubUploadOutcome {
            if (!matches(st)) return fail(HubFileError.Conflict)
            state.completed = true
            state.expiresAtMs = st.expiresAtMs
            state.done.apply { for (i in 0 until size) set(i) }
            save()?.let { return interrupted(it) }
            onProgress(progress())
            return HubUploadOutcome.Done(ticketOf(state))
        }

        private suspend fun restart(): HubFileError? {
            probe("hubfile.upload", fileId, "restart", state.done.count(), layout.chunkCount)
            state.created = false
            state.done.clear()
            return save()
        }

        /** Seals every chunk once to learn the ciphertext hash the hub and the recipient check. O(file) reads. */
        private suspend fun hashSource(): HubFileResult<String> = io {
            val hasher = HubFileHasher()
            for (index in 0 until layout.chunkCount) {
                when (val sealed = sealedChunkBlocking(index)) {
                    is HubFileResult.Ok -> hasher.update(sealed.value)
                    is HubFileResult.Err -> return@io sealed
                }
            }
            HubFileResult.Ok(hasher.digestHex())
        }

        private suspend fun sealedChunk(index: Int): HubFileResult<ByteArray> = io { sealedChunkBlocking(index) }

        private fun sealedChunkBlocking(index: Int): HubFileResult<ByteArray> {
            val plain = try {
                source.read(layout.plainOffset(index), layout.plainLength(index))
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val transient = isTransientRead(e)
                return HubFileResult.Err(HubFileError.LocalStorage("read", transient = transient))
            }
            if (plain.size != layout.plainLength(index)) return HubFileResult.Err(HubFileError.SourceChanged)
            return HubFileResult.Ok(HubFileSealing.sealChunk(key, fileId, layout, index, plain))
        }

        private fun isTransientRead(e: Throwable): Boolean {
            val name = e::class.simpleName ?: ""
            if (name == "FileNotFoundException" || name == "NoSuchFileException" || name == "EOFException") return false
            if (e.message?.contains("shorter than expected") == true) return false
            if (e is IllegalArgumentException || e is IndexOutOfBoundsException || e is IllegalStateException) return false
            return true
        }

        private suspend fun save(): HubFileError? = runSuspendCatching {
            io { storage.writeState(HubTransferDirection.UPLOAD, fileId, HubFileStateCodec.encode(state)) }
        }.exceptionOrNull()?.let { HubFileError.LocalStorage("save") }

        private fun progress(): HubTransferProgress {
            val done = state.done.count()
            val bytes = (0 until layout.chunkCount).filter { state.done[it] }.sumOf { layout.plainLength(it).toLong() }
            return HubTransferProgress(fileId, done, layout.chunkCount, bytes, layout.plainBytes)
        }

        private fun interrupted(error: HubFileError): HubUploadOutcome = HubUploadOutcome.Interrupted(error, progress())

        private suspend fun fail(error: HubFileError): HubUploadOutcome {
            // A refused upload the hub already holds is withdrawn now instead of waiting for the 24 h sweep.
            if (::state.isInitialized && state.created && !state.completed && error != HubFileError.Conflict && !error.transient) {
                timed { api.cancel(request.hubUrl, fileId) }
            }
            runSuspendCatching { io { storage.deleteState(HubTransferDirection.UPLOAD, fileId) } }
            return HubUploadOutcome.Failed(error)
        }

        private fun report(outcome: HubUploadOutcome) {
            when (outcome) {
                is HubUploadOutcome.Done -> probe("hubfile.upload", fileId, "done", layout.chunkCount, layout.chunkCount)
                is HubUploadOutcome.Interrupted -> probe(
                    "hubfile.upload", fileId, "interrupted", outcome.progress.chunksDone, outcome.progress.chunkCount, outcome.error,
                )
                is HubUploadOutcome.Failed -> probe("hubfile.upload", fileId, "failed", reason = outcome.error)
            }
        }

        private fun ticketOf(s: UploadState): HubFileTicket = HubFileTicket(
            fileId = s.fileId,
            key = HubFileKey.decode(s.keyText) ?: key,
            cipherSha256 = s.cipherSha256,
            plainBytes = s.plainBytes,
            cipherChunkBytes = s.cipherChunkBytes,
            chunkCount = s.chunkCount,
            expiresAtMs = s.expiresAtMs,
        )
    }

    // ----- download run -----------------------------------------------------------------------------------------

    private inner class DownloadRun(
        val request: HubDownloadRequest,
        val layout: HubFileLayout,
        val onProgress: (HubTransferProgress) -> Unit,
    ) {
        val ticket = request.ticket
        val fileId = ticket.fileId
        lateinit var state: DownloadState

        suspend fun run(): HubDownloadOutcome {
            val stored = io { storage.readState(HubTransferDirection.DOWNLOAD, fileId) }?.let(HubFileStateCodec::decodeDownload)
            val reusable = stored != null && HubUrlPolicy.sameHub(stored.hubUrl, request.hubUrl) &&
                stored.cipherSha256 == ticket.cipherSha256 && stored.plainBytes == ticket.plainBytes &&
                stored.cipherChunkBytes == ticket.cipherChunkBytes && stored.chunkCount == ticket.chunkCount
            if (!reusable) runSuspendCatching { io { storage.deletePart(fileId) } }
            state = if (stored != null && reusable) stored else DownloadState(
                fileId = fileId,
                hubUrl = request.hubUrl,
                cipherSha256 = ticket.cipherSha256,
                plainBytes = ticket.plainBytes,
                cipherChunkBytes = ticket.cipherChunkBytes,
                chunkCount = ticket.chunkCount,
                verifyFailures = 0,
                done = HubChunkBitmap(ticket.chunkCount),
            )
            save()?.let { return fail(it) }
            probe("hubfile.download", fileId, if (reusable) "resume" else "start", state.done.count(), layout.chunkCount)
            return body().also { report(it) }
        }

        private suspend fun body(): HubDownloadOutcome {
            when (val st = retrying { api.status(request.hubUrl, fileId) }) {
                is HubFileResult.Ok -> {
                    if (st.value.state != FileStates.READY) return interrupted(HubFileError.NotReady)
                    if (!matches(st.value)) return fail(HubFileError.Integrity)
                }
                is HubFileResult.Err -> return if (st.error.transient) interrupted(st.error) else fail(st.error)
            }
            while (true) {
                val missingBytes = (0 until layout.chunkCount).filter { !state.done[it] }.sumOf { layout.plainLength(it).toLong() }
                val usable = runSuspendCatching { io { storage.usableBytes() } }.getOrDefault(Long.MAX_VALUE)
                if (missingBytes + SPACE_MARGIN_BYTES > usable) {
                    return interrupted(HubFileError.NoSpace(missingBytes + SPACE_MARGIN_BYTES, usable))
                }
                for (index in 0 until layout.chunkCount) {
                    if (state.done[index]) continue
                    val plain = when (val got = fetchAndOpen(index)) {
                        is HubFileResult.Ok -> got.value
                        is HubFileResult.Err -> return if (got.error.transient) interrupted(got.error) else fail(got.error)
                    }
                    // Data first, synced; then the bit. A crash in between costs one re-download, never a hole.
                    runSuspendCatching { io { storage.writePart(fileId, layout.plainOffset(index), plain) } }
                        .exceptionOrNull()?.let { return interrupted(HubFileError.LocalStorage("write")) }
                    state.done.set(index)
                    save()?.let { return interrupted(it) }
                    onProgress(progress())
                }
                when (val verified = verify()) {
                    is HubFileResult.Ok -> {
                        val path = runSuspendCatching { io { storage.finishPart(fileId) } }.getOrElse {
                            return interrupted(HubFileError.LocalStorage("finish"))
                        }
                        runSuspendCatching { io { storage.deleteState(HubTransferDirection.DOWNLOAD, fileId) } }
                        return HubDownloadOutcome.Done(path, layout.plainBytes, verified.value)
                    }
                    is HubFileResult.Err -> {
                        if (verified.error != HubFileError.Integrity || state.verifyFailures >= 1) return fail(verified.error)
                        // Every chunk opened, yet the assembled file is wrong: the local copy is damaged. Fetch it all once more.
                        state.verifyFailures++
                        state.done.clear()
                        runSuspendCatching { io { storage.deletePart(fileId) } }
                        save()?.let { return fail(it) }
                        probe("hubfile.download", fileId, "refetch", 0, layout.chunkCount, HubFileError.Integrity)
                    }
                }
            }
        }

        private fun matches(st: FileStatusDto): Boolean = st.fileId == fileId && st.sha256 == ticket.cipherSha256 &&
            st.totalBytes == layout.cipherBytes && st.chunkSize == layout.cipherChunkBytes && st.chunkCount == layout.chunkCount &&
            (request.senderDeviceId == null || st.senderDeviceId == request.senderDeviceId)

        /** One chunk, opened. A chunk that does not open is asked for once more (a flipped bit in transit), then refused. */
        private suspend fun fetchAndOpen(index: Int): HubFileResult<ByteArray> {
            repeat(2) {
                when (val got = retrying { api.readChunk(request.hubUrl, fileId, index, layout.cipherLength(index)) }) {
                    is HubFileResult.Err -> if (got.error != HubFileError.Integrity) return got
                    is HubFileResult.Ok -> HubFileSealing.openChunk(ticket.key, fileId, layout, index, got.value)
                        ?.let { return HubFileResult.Ok(it) }
                }
            }
            return HubFileResult.Err(HubFileError.Integrity)
        }

        /**
         * Re-seals the assembled plaintext with the sender's key and compares the ciphertext hash with the ticket's, and the
         * plaintext hash with the message's when given. Proves the file on disk is the file that was sent, whatever happened
         * to the part file between runs. O(file) reads and one AES pass.
         */
        private suspend fun verify(): HubFileResult<String> = io {
            val cipherHash = HubFileHasher()
            val plainHash = HubFileHasher()
            for (index in 0 until layout.chunkCount) {
                val plain = runCatching { storage.readPart(fileId, layout.plainOffset(index), layout.plainLength(index)) }
                    .getOrNull() ?: return@io HubFileResult.Err(HubFileError.Integrity)
                plainHash.update(plain)
                cipherHash.update(HubFileSealing.sealChunk(ticket.key, fileId, layout, index, plain))
            }
            val plainSha = plainHash.digestHex()
            when {
                cipherHash.digestHex() != ticket.cipherSha256 -> HubFileResult.Err(HubFileError.Integrity)
                request.plainSha256 != null && request.plainSha256.lowercase() != plainSha -> HubFileResult.Err(HubFileError.Integrity)
                else -> HubFileResult.Ok(plainSha)
            }
        }

        private suspend fun save(): HubFileError? = runSuspendCatching {
            io { storage.writeState(HubTransferDirection.DOWNLOAD, fileId, HubFileStateCodec.encode(state)) }
        }.exceptionOrNull()?.let { HubFileError.LocalStorage("save") }

        private fun progress(): HubTransferProgress {
            val bytes = (0 until layout.chunkCount).filter { state.done[it] }.sumOf { layout.plainLength(it).toLong() }
            return HubTransferProgress(fileId, state.done.count(), layout.chunkCount, bytes, layout.plainBytes)
        }

        private fun interrupted(error: HubFileError): HubDownloadOutcome = HubDownloadOutcome.Interrupted(error, progress())

        private suspend fun fail(error: HubFileError): HubDownloadOutcome {
            runSuspendCatching {
                io {
                    storage.deletePart(fileId)
                    storage.deleteState(HubTransferDirection.DOWNLOAD, fileId)
                }
            }
            return HubDownloadOutcome.Failed(error)
        }

        private fun report(outcome: HubDownloadOutcome) {
            when (outcome) {
                is HubDownloadOutcome.Done -> probe("hubfile.download", fileId, "done", layout.chunkCount, layout.chunkCount)
                is HubDownloadOutcome.Interrupted -> probe(
                    "hubfile.download", fileId, "interrupted", outcome.progress.chunksDone, outcome.progress.chunkCount, outcome.error,
                )
                is HubDownloadOutcome.Failed -> probe("hubfile.download", fileId, "failed", reason = outcome.error)
            }
        }
    }

    // ----- shared -----------------------------------------------------------------------------------------------

    /** One API call under the request timeout; a call that runs out of time is [HubFileError.Offline]. */
    private suspend fun <T> timed(call: suspend () -> HubFileResult<T>): HubFileResult<T> =
        withTimeoutOrNull(policy.requestTimeoutMs) { call() } ?: HubFileResult.Err(HubFileError.Offline)

    /**
     * Retries [call] on transient errors with jittered exponential backoff (and the hub's `Retry-After`, capped), up to
     * [HubTransferPolicy.maxConsecutiveFailures] attempts. The budget is per call: a call that succeeds starts the next
     * one fresh, so a long transfer on a flaky network keeps going as long as chunks keep landing.
     */
    private suspend fun <T> retrying(call: suspend () -> HubFileResult<T>): HubFileResult<T> {
        var failures = 0
        while (true) {
            val result = timed(call)
            if (result is HubFileResult.Ok || !(result as HubFileResult.Err).error.transient) return result
            failures++
            if (failures >= policy.maxConsecutiveFailures) return result
            val backoff = (policy.baseBackoffMs shl (failures - 1).coerceAtMost(20)).coerceAtMost(policy.maxBackoffMs)
            val jittered = backoff / 2 + if (backoff > 1) random.nextLong(backoff / 2 + 1) else 0L
            val wait = (result.error as? HubFileError.RateLimited)?.let { (it.retryAfterSeconds * 1000L).coerceAtMost(policy.maxRetryAfterMs) }
                ?.coerceAtLeast(jittered) ?: jittered
            FlashLog.i(TAG, "retry op in ${wait}ms after ${result.error::class.simpleName} ($failures/${policy.maxConsecutiveFailures})")
            delay(wait)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }

    private suspend fun <T> locked(key: String, block: suspend () -> T): T {
        val mutex = locksGuard.withLock {
            val (m, users) = locks[key] ?: (Mutex() to 0)
            locks[key] = m to users + 1
            m
        }
        try {
            return mutex.withLock { block() }
        } finally {
            locksGuard.withLock {
                val (m, users) = locks.getValue(key)
                if (users <= 1) locks.remove(key) else locks[key] = m to users - 1
            }
        }
    }

    private fun probe(
        name: String,
        fileId: String,
        step: String,
        done: Int? = null,
        total: Int? = null,
        reason: HubFileError? = null,
    ) {
        FlashProbe.emit(
            name,
            "step" to step,
            "id" to FlashProbe.short(fileId),
            "chunks" to if (done != null && total != null) "$done/$total" else null,
            "reason" to reason?.let { it::class.simpleName },
        )
    }

    private companion object {
        const val TAG = "TRANSFER"
        const val BAD_ID = "BAD_FILE_ID"
        const val BAD_RECIPIENT = "BAD_RECIPIENT"

        /** How many times one run starts an upload over after the hub lost it (swept, restored). */
        const val MAX_RESTARTS = 2

        /** Free space kept on top of the missing bytes before a download continues. */
        const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
    }
}
