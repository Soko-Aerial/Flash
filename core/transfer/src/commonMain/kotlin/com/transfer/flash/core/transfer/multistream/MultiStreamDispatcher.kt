@file:OptIn(ExperimentalAtomicApi::class)

package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkPlan
import com.transfer.flash.core.transfer.chunked.ChunkSource
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.ChunkStream
import com.transfer.flash.core.transfer.chunked.FileMeta
import com.transfer.flash.core.transfer.chunked.ResumeBitVector
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.concurrent.PlatformLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Send-side orchestrator for C5.7 MULTI-STREAM transfer — **v3**, modeled on proven segmented-
 * download architecture (media-downloader `SegmentedDownloader`, compared 2026-08-23; full defect
 * history for v1/v2 in ERROR-013).
 *
 * ## Design — each point fixes a concrete v1 defect
 *
 * - **Static assignment** (`idx % N`): the single materializer routes each serialized chunk to a
 *   per-worker feed channel. No dynamic claiming, shared cursor, or exclusive end-game owner —
 *   the three structures behind v1's Heisenberg race.
 * - **Single materializer** owns all [ChunkStream] reading (streams are strictly sequential),
 *   eliminating read-under-lock and double-materialization races.
 * - **Lock-free hot path**: confirmed bytes/chunks live in atomics touched by ACK ingestion;
 *   per-worker state is owned by exactly one coroutine. Tiny [PlatformLock] blocks guard snapshots.
 * - **Throttled progress publisher job** (10 ms) — same shape as SegmentedDownloader's.
 * - **Receiver-authoritative completion**: resolution happens on the receiver's COMPLETE frame
 *   (`verified`), with fallbacks: local-coverage grace expiry, all-channels-dead fail-fast.
 * - **At-least-once wire, exactly-once write**: dead workers redistribute unconfirmed frames into
 *   a shared queue drained by survivors; the receive pipeline dedups duplicates.
 * - **Late ingestion safe**: inbound frames remain processable after any resolution outcome.
 *
 * ## Concurrent multi-peer transfers
 *
 * Instances hold no global/static mutable state — run as many simultaneously as needed.
 */
internal class MultiStreamDispatcher(
    private val chunker: Chunker,
    private val meta: FileMeta,
    private val source: ChunkSource,
    private val factory: StreamChannelFactory,
    private val streamCount: Int = DEFAULT_STREAM_COUNT,
    private val requestedChunkSize: Int = Chunker.DEFAULT_CHUNK_SIZE_BYTES,
    private val fileSha256Hex: String? = null,
    doneIndexes: Collection<Int> = emptyList(),
    @Suppress("UNUSED_PARAMETER") endGameChunks: Int = END_GAME_CHUNKS,
    private val speedWindowMs: Long = MultiStreamProgress.DEFAULT_WINDOW_MS,
    private val nowMs: () -> Long = SystemTimeSource::nowMs,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCompleteFrame: ((ByteArray) -> Unit)? = null,
    /** Local-coverage fallback delay before resolving without the receiver COMPLETE frame. */
    private val completeGraceMs: Long = DEFAULT_COMPLETE_GRACE_MS,
    /** Intended recipient device id, forwarded to [StreamChannelFactory.open] for peer routing. */
    private val peerDeviceId: String? = null,
    private val feedBufferFrames: Int = FEED_BUFFER_FRAMES,
    private val sharedBufferFrames: Int = SHARED_BUFFER_FRAMES,
) {
    init {
        require(streamCount in 1..MAX_STREAMS) { "streamCount must be in 1..$MAX_STREAMS" }
        require(completeGraceMs >= 0) { "completeGraceMs must be >= 0" }
        require(feedBufferFrames > 0) { "feedBufferFrames must be > 0" }
        require(sharedBufferFrames > 0) { "sharedBufferFrames must be > 0" }
    }

    private val plan: ChunkPlan = chunker.plan(meta, requestedChunkSize)
    private val resumeDone: List<Int> = doneIndexes.filter { it in 0 until plan.totalChunks }
    private val pendingIndexes: List<Int> =
        (0 until plan.totalChunks).filter { it !in resumeDone }
    private val resumedBytes: Long = resumeDone.sumOf { chunkBytes(it) }

    private val _progress = MutableStateFlow(
        MultiStreamProgress(bytesDone = resumedBytes, totalBytes = meta.totalBytes),
    )

    /** Aggregate telemetry across ALL streams (UI-016 card feed). Monotonic in bytesDone. */
    val progress: StateFlow<MultiStreamProgress> = _progress.asStateFlow()

    // ---- lock-free shared state -----------------------------------------------------------------

    private val confirmedVector = ResumeBitVector(plan.totalChunks).also { it.reconcile(resumeDone) }
    private val confirmedBytes = AtomicLong(resumedBytes)
    private val confirmedCount = AtomicInt(resumeDone.size)
    private val chunksSentTotal = AtomicInt(0)
    private val bytesSentTotal = AtomicLong(0L)
    private val aliveWorkers = AtomicInt(0)
    private val started = AtomicBoolean(false)

    @Volatile private var receiverVerifiedField: Boolean? = null
    @Volatile private var coverageReachedAtMs: Long? = null
    @Volatile private var resolvedDigest: String = ""
    /** Set once all workers exited while uncovered; deadline after which un-ACKed work fails. */
    @Volatile private var ackDrainDeadlineMs: Long? = null

    // Terminal bookkeeping: tiny critical sections, never held across I/O.
    private val terminalLock = PlatformLock()
    private var terminalResult: MultiStreamResult? = null
    private var terminalDeferred: CompletableDeferred<MultiStreamResult>? = null

    /**
     * Guarded by [terminalLock] — all seven accesses (two open-failure paths, [markDead],
     * [shouldRedistribute], [deadChannelsSnapshot] and the two result builders) already ran inside
     * it, so the `java.util.Collections.synchronizedList` wrapper this replaced in 13B-3d was
     * redundant double-locking. No import line revealed that pin: it was fully qualified.
     */
    private val deadIds = mutableListOf<Int>()

    private val rateMeter = RollingRateMeter(nowMs, speedWindowMs)
    private val completeEmittedOnce = AtomicBoolean(false)
    @Volatile private var completeFrameBytesHolder: ByteArray? = null

    /**
     * Cooperative pause (application-level flow control, ADR-018): when true, workers and the
     * materializer park before their next chunk. Unlike job cancellation this survives blocking
     * socket writes and is reversible via [setPaused](resume) — the receiver's PAUSE control
     * frame flips this on the sender within one poll interval.
     */
    @Volatile private var externallyPaused = false

    /** Streams actually opened for this session (set in send()); used for all-dead checks. */
    @Volatile private var plannedStreams: Int = 0

    /**
     * Voice-call quiet hint, set by the host via [RealFlashTransferRepository.voiceCallActive].
     * While true the progress watcher below polls at [WATCH_QUIET_POLL_MS] instead of
     * [WATCH_POLL_MS]: 25x fewer wakeups, StateFlow emissions and repository-collector passes
     * per second for the whole transfer, so a file moving during a call stops preempting the
     * audio path on 4-core hardware. Terminal resolution only ever waits out one extra poll
     * interval (≤250 ms against second-scale grace deadlines), and every ACK still ingests
     * immediately on arrival — this slows telemetry, never the wire.
     */
    @Volatile internal var quietWatcherHint: Boolean = false

    private data class PreparedFrame(val index: Int, val frameBytes: ByteArray)

    // ---- public API ------------------------------------------------------------------------------

    /**
     * Application-level pause/resume of chunk transmission. Instant and reversible; the transfer
     * stays fully assembled (feeds intact) so resume continues exactly where it stopped.
     */
    fun setPaused(paused: Boolean) {
        val wasPaused = externallyPaused
        externallyPaused = paused
        if (wasPaused == paused) return
        if (wasPaused) {
            // Resuming: drop the samples spanning the paused gap, otherwise the first post-resume
            // reading averages real bytes over pause wall-clock and reports a near-zero speed.
            rateMeter.reset()
        }
        // Publish immediately so the UI reflects the pause within one frame instead of waiting
        // for the watcher tick (and, while paused, stops showing a stale speed/ETA).
        publishProgress()
    }

    /** True while transmission is cooperatively paused (diagnostics / host-side reconciliation). */
    val isPaused: Boolean get() = externallyPaused

    /**
     * Parks the caller while paused. Returns early once the transfer has resolved: `send()` joins
     * every child, so a materializer/worker still parked here after a terminal outcome (e.g. the
     * receiver's COMPLETE landed during a pause) would keep `send()` suspended forever.
     */
    private suspend fun awaitUnpause() {
        while (externallyPaused && currentCoroutineContext().isActive) {
            if (terminalDeferred?.isCompleted == true) return
            delay(PAUSE_POLL_MS)
        }
    }

    /**
     * Runs the entire multi-stream transfer. Single-use. Inbound receiver feedback (`ACK_BATCH`
     * / `COMPLETE` arriving on any channel) must be pushed via [onInboundFrame].
     */
    suspend fun send(): MultiStreamResult {
        check(started.compareAndSet(false, true)) { "MultiStreamDispatcher is single-use" }
        resolvedDigest = fileSha256Hex?.let(Sha256::normalizeHex) ?: chunker.hashOnly(source)

        return coroutineScope {
            val deferred = CompletableDeferred<MultiStreamResult>()
            terminalDeferred = deferred

            if (pendingIndexes.isEmpty()) {
                deferred.complete(resolvedCompleted(chunksSent = 0))
                return@coroutineScope deferred.await()
            }

            val effectiveStreams = streamCount.coerceIn(1, pendingIndexes.size)
            aliveWorkers.store(effectiveStreams)
            plannedStreams = effectiveStreams

            // BOUNDED (AGENTS §18): the materializer paces with the network instead of
            // serializing the whole file into RAM. UNLIMITED queues made every paused/stalled
            // attempt hold its entire remaining file on the heap → OOM by the third try.
            val feeds = List(effectiveStreams) { Channel<PreparedFrame>(feedBufferFrames) }
            val shared = Channel<PreparedFrame>(sharedBufferFrames)
            val ownFeedsOpen = AtomicInt(effectiveStreams)

            // Watcher: throttled progress publishing + non-inline resolutions
            // (local-coverage grace expiry, all-channels-dead fail-fast).
            launch(workerDispatcher) {
                while (isActive && !deferred.isCompleted) {
                    publishProgress()
                    maybeResolveFromState(deferred, forceCoverageResolve = false)
                    delay(if (quietWatcherHint) WATCH_QUIET_POLL_MS else WATCH_POLL_MS)
                }
            }

            // Materializer: sole reader of the strictly-sequential ChunkStream; closes feeds done.
            launch(workerDispatcher) {
                try {
                    var pos = 0
                    var stream: ChunkStream? = null
                    try {
                        for (index in pendingIndexes) {
                            // Terminal outcome already reached (receiver COMPLETE, or every
                            // channel died): stop reading the source instead of serializing the
                            // rest of the file into queues nobody will send.
                            if (deferred.isCompleted) break
                            awaitUnpause()
                            val s = stream ?: chunker.openChunkStream(
                                source, meta, plan, resolvedDigest,
                            ).also { stream = it }
                            while (pos < index) {
                                s.next() // defensive skip
                                pos++
                            }
                            val frame = s.next()
                            pos++
                            feeds[index % effectiveStreams].send(
                                PreparedFrame(index, ChunkFrame.serialize(frame)),
                            )
                        }
                    } finally {
                        stream?.closeQuietly()
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        failWith(deferred, "source read failed: ${e.message}")
                    }
                } finally {
                    feeds.forEach { it.close() }
                }
            }

            // Announce the transfer on EVERY live channel: the receiver pipeline registers its
            // session on FILE_START — chunks arriving first would be rejected UNKNOWN_TRANSFER.
            //
            // A channel that refuses (null) or throws on open is NOT fatal: per StreamChannel's
            // contract we proceed with the wires that DID open. The failed slot becomes a dead
            // worker (startOk = false) that still drains its own feed and hands frames to survivors
            // via `shared` — so the single materializer never blocks on a full feed. If EVERY slot
            // fails, all workers start dead, aliveWorkers hits 0, and maybeResolveFromState fails
            // the transfer fast ("all channels failed"). The previous `return@coroutineScope
            // failWith(...)` deadlocked instead: it left the already-launched materializer parked
            // forever in feeds[i].send() (buffer full, no worker draining), so coroutineScope —
            // which joins all children — could never return and send() hung permanently.
            val channels = (0 until effectiveStreams).map { id ->
                val channel = try {
                    factory.open(id, peerDeviceId)
                } catch (t: Throwable) {
                    null
                }
                if (channel == null) {
                    terminalLock.withLock { deadIds.add(id) }
                    return@map Pair(DeadStreamChannel(id), false)
                }
                val startOk = runCatching {
                    channel.sendFrame(ChunkFrame.serialize(chunker.fileStart(meta, plan, resolvedDigest)))
                }.getOrDefault(false)
                if (!startOk) {
                    terminalLock.withLock { deadIds.add(id) }
                }
                Pair(channel, startOk)
            }
            val workers = channels.mapIndexed { idx, (channel, startOk) ->
                launch(workerDispatcher) {
                    runWorker(idx, feeds[idx], shared, ownFeedsOpen, deferred, channel, startOk)
                }
            }
            workers.joinAll()

            // Deterministic final resolution (watcher may have resolved already; first-wins).
            maybeResolveFromState(deferred, forceCoverageResolve = true)
            deferred.await().also { publishProgress() }
        }
    }

    /**
     * Feeds one receiver frame that arrived on [channelId]. Returns true when consumed;
     * stale/unknown frames are ignored so late ACKs never crash the session. Safe to call after
     * resolution — late authoritative frames still update bookkeeping/emission.
     */
    fun onInboundFrame(channelId: Int, bytes: ByteArray): Boolean =
        when (val frame = ChunkFrame.parse(bytes)) {
            is ChunkFrame.AckBatch -> ingestAckBatch(frame)
            is ChunkFrame.Complete -> ingestComplete(frame)
            else -> false
        }

    /** Snapshot: distinct chunk indexes the receiver has confirmed so far. */
    fun confirmedCountSnapshot(): Int = confirmedCount.load()

    /** Snapshot: all receiver-confirmed chunk indexes (resume bit-vector mirror). */
    fun confirmedIndexesSnapshot(): List<Int> =
        terminalLock.withLock { confirmedVector.doneIndexes() }

    /**
     * Snapshot: receiver-confirmed indexes that [known] does not already hold.
     *
     * The delta form exists because [confirmedIndexesSnapshot] allocates one boxed `Int` per
     * confirmed chunk, and the send-side progress collector polls the confirmed set at this class's
     * [WATCH_POLL_MS] watcher cadence — 100 times a second, for the whole transfer. Diffing whole
     * snapshots there was quadratic in the chunk count (EXP-008), and it built a list thousands of
     * entries long inside [terminalLock], which the ACK path needs for every [markRangeConfirmed]
     * and every [maybeResolveFromState]. This holds the lock for word arithmetic instead.
     */
    fun confirmedIndexesNotIn(known: ResumeBitVector): List<Int> =
        terminalLock.withLock { confirmedVector.receivedIndexesNotIn(known) }

    /** Chunk count of the resolved plan; the size a caller's mirror bit-vector must have. */
    val totalChunks: Int get() = plan.totalChunks

    /** Snapshot: channel ids marked dead during the session. */
    fun deadChannelsSnapshot(): List<Int> = terminalLock.withLock { deadIds.toList() }

    // ---- inbound feedback -------------------------------------------------------------------------

    private fun ingestAckBatch(frame: ChunkFrame.AckBatch): Boolean {
        if (frame.transferId != meta.transferId || frame.fileId != meta.fileId) return false
        markRangeConfirmed(frame.indexes)
        val covered = confirmedCount.load() >= plan.totalChunks
        if (covered && completeGraceMs == 0L) {
            emitCompleteFrameOnce(receiverVerifiedField ?: true)
        }
        publishProgress()
        // Coverage may have just completed (e.g., manually-fed sessions): resolve now.
        terminalDeferred?.let { maybeResolveFromState(it, forceCoverageResolve = false) }
        return true
    }

    private fun ingestComplete(frame: ChunkFrame.Complete): Boolean {
        if (frame.transferId != meta.transferId || frame.fileId != meta.fileId) return false
        receiverVerifiedField = frame.verified
        markRangeConfirmed((0 until plan.totalChunks).toList()) // receiver is authoritative
        emitCompleteFrameOnce(frame.verified)
        publishProgress()
        terminalDeferred?.let { maybeResolveFromState(it, forceCoverageResolve = true) }
        return true
    }

    private fun markRangeConfirmed(indexes: List<Int>) {
        terminalLock.withLock {
            indexes.forEach { idx ->
                if (!confirmedVector.isReceived(idx)) {
                    confirmedVector.markReceived(idx)
                    confirmedBytes.addAndFetch(chunkBytes(idx))
                    confirmedCount.incrementAndFetch()
                }
            }
            if (confirmedCount.load() >= plan.totalChunks && coverageReachedAtMs == null) {
                coverageReachedAtMs = nowMs()
            }
        }
        publishProgress()
    }

    // ---- resolution --------------------------------------------------------------------------------

    private fun maybeResolveFromState(
        deferred: CompletableDeferred<MultiStreamResult>,
        forceCoverageResolve: Boolean,
    ) {
        if (deferred.isCompleted) return
        val covered = confirmedCount.load() >= plan.totalChunks
        val coverageAge = coverageReachedAtMs?.let { nowMs() - it } ?: 0L

        val completed = when {
            covered && receiverVerifiedField != null -> resolvedCompleted(chunksSentTotal.load())
            covered && (forceCoverageResolve || coverageAge >= completeGraceMs) ->
                resolvedCompleted(chunksSentTotal.load(), verified = receiverVerifiedField)
            else -> null
        }
        if (completed != null) {
            deferred.complete(completed)
            return
        }
        // All workers exited but coverage is incomplete: sends are fire-and-forget (socket
        // buffer), so ACKs legitimately lag behind worker exit. Wait a bounded grace for the
        // outstanding ACK_BATCH/COMPLETE before declaring failure — instant failure here
        // misreported healthy transfers ("all channels failed" at first-ACK ~20%) whenever
        // the last chunks left the socket buffer after the final worker finished.
        if (!covered && aliveWorkers.load() <= 0) {
            // Nothing ever reached a wire (every channel failed on its first frame): there is no
            // ACK in flight, so waiting out the drain grace would only stall a certain failure.
            if (chunksSentTotal.load() == 0) {
                deferred.complete(failedLocked("all channels failed"))
                return
            }
            // A PAUSED transfer must never be failed by the drain grace: a paused receiver
            // deliberately stops draining and ACKing, so the missing ACKs are expected, not a
            // fault. Disarm the deadline so resuming starts a fresh grace window.
            if (externallyPaused) {
                terminalLock.withLock { ackDrainDeadlineMs = null }
                return
            }
            val now = nowMs()
            val deadline = terminalLock.withLock {
                (ackDrainDeadlineMs ?: now.also { ackDrainDeadlineMs = it }) + ACK_DRAIN_GRACE_MS
            }
            if (now >= deadline) {
                deferred.complete(
                    failedLocked(
                        "ack drain timeout: ${confirmedVector.missingIndexes().size} chunk(s) unconfirmed after all streams exited",
                    ),
                )
            }
        }
    }

    private fun resolvedCompleted(
        chunksSent: Int,
        verified: Boolean? = receiverVerifiedField,
    ): MultiStreamResult.Completed {
        emitCompleteFrameOnce(verified ?: true)
        return MultiStreamResult.Completed(
            totalChunks = plan.totalChunks,
            chunksSent = chunksSent,
            chunksSkippedResume = plan.totalChunks - pendingIndexes.size,
            bytesSent = bytesSentTotal.load(),
            bytesSkippedResume = resumedBytes,
            fileSha256Hex = resolvedDigest,
            verified = verified,
            deadChannelIds = terminalLock.withLock { deadIds.toList() },
            completeFrameBytes = completeFrameBytesHolder,
        )
    }

    private fun failedLocked(reason: String): MultiStreamResult.Failed =
        MultiStreamResult.Failed(
            reason = reason,
            deadChannelIds = terminalLock.withLock { deadIds.toList() },
            unconfirmedIndexes = confirmedVector.missingIndexes(),
        )

    private fun failWith(
        deferred: CompletableDeferred<MultiStreamResult>,
        reason: String,
    ): MultiStreamResult {
        val result = failedLocked(reason)
        deferred.complete(result)
        return result
    }

    private fun emitCompleteFrameOnce(verified: Boolean): ByteArray? =
        if (completeEmittedOnce.compareAndSet(false, true)) {
            val bytes = ChunkFrame.serialize(ChunkFrame.Complete(meta.transferId, meta.fileId, verified))
            completeFrameBytesHolder = bytes
            onCompleteFrame?.invoke(bytes)
            bytes
        } else completeFrameBytesHolder

    // ---- workers -----------------------------------------------------------------------------------

    /**
     * One worker drives exactly one wire.
     *
     * It drains its statically-assigned feed and — while its wire is healthy — *concurrently*
     * helps drain the shared redistribution queue (`select` over both channels). Concurrency here
     * is a correctness requirement, not an optimisation: with bounded queues a survivor that only
     * looked at `shared` after exhausting its own assignment would let a dead worker fill `shared`,
     * stall on it, stop draining its own feed, and block the materializer forever (ERROR-016).
     *
     * On wire failure the worker flips to "dead": it stops touching its broken wire but keeps
     * draining its own feed to closure — so the materializer never blocks on a full feed — and
     * hands every remaining frame to [redistribute].
     *
     * Exit bookkeeping runs in `finally` on EVERY path: the own-feed slot is released (the last
     * release closes `shared`, which is how survivors learn to stop) and the live-worker count is
     * decremented exactly once. The first bounded-channel revision skipped both on its early
     * `return` paths, so `shared` never closed and `aliveWorkers` never hit zero — nothing
     * resolved and `send()` never returned.
     */
    private suspend fun runWorker(
        id: Int,
        ownFeed: Channel<PreparedFrame>,
        shared: Channel<PreparedFrame>,
        ownFeedsOpen: AtomicInt,
        deferred: CompletableDeferred<MultiStreamResult>,
        wire: StreamChannel,
        startOk: Boolean,
    ) {
        var dead = !startOk
        var ownOpen = true
        var sharedOpen = true
        var ownFeedReleased = false
        var aliveReleased = false

        // Releases this worker's own-feed slot; the last one closes the shared queue.
        fun releaseOwnFeed() {
            if (!ownFeedReleased) {
                ownFeedReleased = true
                if (ownFeedsOpen.decrementAndFetch() == 0) shared.close()
            }
        }

        // Retires this worker as a sender (idempotent), then re-checks all-dead detection.
        fun releaseAlive() {
            if (!aliveReleased) {
                aliveReleased = true
                aliveWorkers.decrementAndFetch()
                failIfAllChannelsDead(deferred)
            }
        }

        if (dead) {
            markDead(id)
            releaseAlive() // FILE_START never landed: this wire can never send.
        }

        try {
            while (true) {
                if (!ownOpen) releaseOwnFeed()
                val pull = when {
                    // Healthy: take whichever queue has work first.
                    ownOpen && sharedOpen && !dead ->
                        select<Pair<Boolean, ChannelResult<PreparedFrame>>> {
                            ownFeed.onReceiveCatching { false to it }
                            shared.onReceiveCatching { true to it }
                        }
                    // Dead workers never consume `shared` — they cannot send it onward.
                    ownOpen -> false to ownFeed.receiveCatching()
                    sharedOpen && !dead -> true to shared.receiveCatching()
                    else -> break
                }
                val (fromShared, received) = pull
                val prepared = received.getOrNull()
                if (prepared == null) { // channel closed and drained
                    if (fromShared) sharedOpen = false else ownOpen = false
                    continue
                }

                awaitUnpause()

                // Already resolved (receiver COMPLETE, or a pause that outlived the transfer):
                // keep draining to closure — the materializer may be blocked in feeds[i].send()
                // and breaking out here would strand it — but never touch the wire again.
                if (deferred.isCompleted) continue

                if (dead) {
                    redistribute(shared, prepared, deferred)
                    continue
                }

                chunksSentTotal.incrementAndFetch()
                bytesSentTotal.addAndFetch(chunkBytes(prepared.index))
                val ok = runCatching { wire.sendFrame(prepared.frameBytes) }.getOrDefault(false)
                if (ok) continue

                chunksSentTotal.decrementAndFetch()
                bytesSentTotal.addAndFetch(-chunkBytes(prepared.index))
                dead = true
                markDead(id)
                // Retire the wire before handing the frame back: all-dead detection must see this
                // channel as gone while we finish draining our assignment.
                releaseAlive()
                redistribute(shared, prepared, deferred)
            }
        } finally {
            releaseOwnFeed()
            releaseAlive()
        }
    }

    /**
     * Hands an unsent frame to the shared queue so a survivor can retry it (at-least-once wire,
     * exactly-once write — the receive pipeline dedups).
     *
     * Never blocks indefinitely: `shared` is bounded, so a full queue is polled only while a live
     * channel could still drain it. Once every channel is dead, the queue is closed, or the
     * transfer has resolved, the frame is dropped and terminal resolution reports it unconfirmed —
     * blocking there would deadlock, because the only possible consumers are already gone.
     */
    private suspend fun redistribute(
        shared: Channel<PreparedFrame>,
        prepared: PreparedFrame,
        deferred: CompletableDeferred<MultiStreamResult>,
    ) {
        while (true) {
            val result = shared.trySend(prepared)
            if (result.isSuccess || result.isClosed) return
            if (!shouldRedistribute(deferred)) return
            delay(REDISTRIBUTE_POLL_MS)
        }
    }

    private fun markDead(id: Int) {
        terminalLock.withLock {
            if (!deadIds.contains(id)) deadIds.add(id)
        }
    }

    /**
     * True while handing work to the bounded shared queue can still pay off: the transfer is
     * unresolved and at least one live worker remains to drain it.
     */
    private fun shouldRedistribute(deferred: CompletableDeferred<MultiStreamResult>): Boolean {
        if (deferred.isCompleted) return false
        if (aliveWorkers.load() <= 0) return false
        val allDead = terminalLock.withLock {
            (0 until plannedStreams).all { deadIds.contains(it) }
        }
        return !allDead
    }

    private fun failIfAllChannelsDead(deferred: CompletableDeferred<MultiStreamResult>) {
        val alive = aliveWorkers.load()
        if (alive == 0 && !deferred.isCompleted) {
            // Do NOT fail instantly: sends are fire-and-forget, ACKs lag behind worker exit.
            // Arm the bounded ack-drain deadline; the watcher resolves (covered → Completed,
            // expiry → ack-drain-timeout failure). Skipped while paused — the grace is armed on
            // resume instead, so a long pause cannot time the transfer out.
            if (externallyPaused) return
            terminalLock.withLock {
                if (ackDrainDeadlineMs == null) ackDrainDeadlineMs = nowMs()
            }
        }
    }

    private fun publishProgress() {
        val done = confirmedBytes.load()
        rateMeter.record(done)
        // Paused transfers report a hard zero instead of a decaying rolling average: the sender is
        // deliberately idle, so "slowing down" telemetry (and an ETA extrapolated from it) is a lie.
        val paused = externallyPaused
        val rate = if (paused) 0.0 else rateMeter.instantBytesPerSec(nowMs())
        val eta = if (rate > 0.0 && done < meta.totalBytes) {
            ((meta.totalBytes - done) / rate * 1000.0).toLong()
        } else {
            -1L
        }
        _progress.value = MultiStreamProgress(done, meta.totalBytes, rate, eta)
    }

    private fun chunkBytes(index: Int): Long {
        val start = index.toLong() * plan.chunkSize
        return minOf(plan.chunkSize.toLong(), meta.totalBytes - start)
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val DEFAULT_STREAM_COUNT = 2

        @Suppress("UNUSED")
        const val END_GAME_CHUNKS = 8

        /** Bounded per-feed queue depth — constant memory regardless of file size (AGENTS §18). */
        const val FEED_BUFFER_FRAMES = 8
        const val SHARED_BUFFER_FRAMES = 32

        /** Cooperative-pause poll cadence (ms). */
        const val PAUSE_POLL_MS = 25L

        /** Retry cadence while the bounded shared queue is full (ERROR-016). */
        const val REDISTRIBUTE_POLL_MS = 5L

        const val WATCH_POLL_MS = 10L

        /**
         * Watcher cadence while [quietWatcherHint] holds (a voice call is ACTIVE). 4 Hz keeps
         * the UI progress bar and the ACK-drain bookkeeping moving — every grace deadline it
         * guards is second-scale — at 1/25th of the wakeups and emissions.
         */
        const val WATCH_QUIET_POLL_MS = 250L
        const val DEFAULT_COMPLETE_GRACE_MS = 2_000L

        /**
         * Grace after the last worker exits for outstanding ACK_BATCH/COMPLETE frames to
         * arrive. Covers normal socket-buffer drain lag plus receiver disk-write pacing.
         */
        const val ACK_DRAIN_GRACE_MS = 15_000L
    }
}

private fun ChunkStream.closeQuietly() {
    try {
        close()
    } catch (_: RuntimeException) {
        // Best-effort close on teardown paths; never masks the original outcome.
    }
}

/**
 * Stand-in wire for a slot whose real [StreamChannel] never opened. The slot's worker starts dead
 * (`startOk = false`) and therefore never calls [sendFrame] — it only drains its feed and
 * redistributes frames to survivors — but the method returns false defensively. This lets an
 * open-failure reuse the tested dead-worker drain/redistribute path instead of early-returning and
 * stranding the materializer on a full feed.
 */
private class DeadStreamChannel(override val id: Int) : StreamChannel {
    override suspend fun sendFrame(frameBytes: ByteArray): Boolean = false
}
