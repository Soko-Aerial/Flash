@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.transfer.chunked

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.transfer.concurrent.PlatformLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Receive-side orchestration for chunked transfers (C5.5/C5.6). Pure logic — all I/O sits behind
 * the injected [ChunkSink] and optional [WholeFileDigestProvider]; zero Android types.
 *
 * ## Contract
 *
 * - Feed wire bytes to [onFrame] (any frame type; wrong-direction frames are rejected, not
 *   thrown on). Returned events carry every actionable output (ACK batches, COMPLETE,
 *   rejections) so transports stay dumb pipes.
 * - **Verify-before-write (C5.5):** each CHUNK's embedded raw SHA-256 is recomputed and compared
 *   in constant time BEFORE [ChunkSink.write]. A mismatched chunk produces
 *   [ReceiveEvent.Rejected] with [RejectReason.HASH_MISMATCH], is never written, never marked,
 *   and never ACKed — its absence from ACK batches is the implicit NACK that drives targeted
 *   single-chunk repair, mirroring LocalSend's per-file `422` checksum-mismatch response
 *   narrowed to chunk granularity (https://github.com/localsend/protocol §4.2; BitTorrent-style
 *   piece-level verification: https://bittorrent.org/bittorrentecon.pdf).
 * - **Duplicates are idempotent:** an already-received index is still ACKed (the sender's mirror
 *   must converge) but never rewritten.
 * - **Out-of-order tolerance:** chunks may arrive in any order; the sink receives them verbatim
 *   with their index. Strict sequencing remains a transport-layer concern (C5.7 multi-stream
 *   assigns ranges).
 * - **ACK batching:** one `ACK_BATCH` is emitted every [ackEvery] distinct received chunks
 *   ([flushPendingAck] forces a partial batch — call it on idle timers or connection loss).
 * - **COMPLETE** is emitted only once, when the resume bit-vector is complete; if
 *   [recheckWholeFileDigest] is enabled, `verified` additionally requires the injected
 *   [WholeFileDigestProvider] digest to match `FILE_START.fileSha256Hex`.
 */
public class ReceivePipeline(
    private val sink: ChunkSink,
    private val ackEvery: Int = DEFAULT_ACK_EVERY,
    private val recheckWholeFileDigest: Boolean = false,
    private val wholeFileDigest: WholeFileDigestProvider? = null,
    private val maxConcurrentSessions: Int = DEFAULT_MAX_SESSIONS,
    /**
     * Optional per-transfer sink resolution (C5.9 seam). Invoked once when a valid FILE_START
     * opens a new session; the returned [ChunkSink] receives that transfer's chunks exclusively.
     * This lets hosts bind each transfer to its own destination (e.g. a random-access file
     * handle sized by `totalBytes`, offset by `index * chunkSize`) instead of sharing one
     * sequential sink — REQUIRED for correct out-of-order multi-stream assembly.
     * When null (default), all transfers share [sink] (legacy behavior).
     */
    private val sinkFactory: ((ChunkFrame.FileStart) -> ChunkSink)? = null,
    /** When true, [ReceiveEvent.SessionStarted] is emitted on session open (default off). */
    private val emitSessionStarted: Boolean = false,
    /**
     * When true (#5), a fresh FILE_START opens a session in an *awaiting-acceptance* state: the
     * [sinkFactory] is NOT invoked (no destination file is created) and any chunk that arrives
     * before [acceptSession] is dropped ([RejectReason.AWAITING_ACCEPTANCE]) instead of written.
     * The host surfaces the offer to the user off [ReceiveEvent.SessionStarted] and then calls
     * [acceptSession] (resolves the sink, chunks flow) or [declineSession] (drops the session).
     *
     * A compliant sender parks after FILE_START until it receives the accept (RESUME), so the
     * drop path only ever fires for a misbehaving/legacy sender — belt-and-suspenders that
     * guarantees the "no bytes on disk before consent" property regardless of sender behavior.
     *
     * A fully-seeded resume (every chunk already persisted) still finalizes immediately: the
     * resume seed implies the user accepted in the pre-restart session.
     */
    private val requireAcceptance: Boolean = false,
    /**
     * Optional resume seam (#20). Invoked once when a valid FILE_START opens a new session; the
     * returned indexes are pre-marked in the fresh bit-vector so a receiver that persisted partial
     * progress across a restart does NOT wait for chunks the sender already skips (sender-side
     * resume reads its own done-set and omits confirmed chunks — without this the receive vector
     * would never complete). Must be pure and fast: it is called under the pipeline lock, so the
     * host supplies an in-memory (pre-warmed) done-set, not a blocking DAO read. Indexes outside
     * `[0, totalChunks)` are ignored. The partial destination file's bytes for these indexes are
     * assumed already on disk (the sink handle reopens without truncating); the host's whole-file
     * digest recheck is the integrity backstop.
     */
    private val resumeIndexesProvider: ((ChunkFrame.FileStart) -> List<Int>)? = null,
    /**
     * How many FINISHED sessions are kept (oldest evicted first) so a late duplicate CHUNK for a completed transfer is
     * still answered with idempotent silence instead of UNKNOWN_TRANSFER. Finished sessions do not count toward
     * [maxConcurrentSessions]: before this, 32 completed receives in one process made every later offer SESSION_FULL.
     */
    private val maxFinishedSessionsRetained: Int = DEFAULT_FINISHED_RETAINED,
) {
    init {
        require(ackEvery > 0) { "ackEvery must be > 0" }
        require(maxConcurrentSessions > 0) { "maxConcurrentSessions must be > 0" }
        require(maxFinishedSessionsRetained >= 0) { "maxFinishedSessionsRetained must be >= 0" }
        if (recheckWholeFileDigest) {
            requireNotNull(wholeFileDigest) {
                "recheckWholeFileDigest=true requires a WholeFileDigestProvider"
            }
        }
    }

    private val sessions = LinkedHashMap<String, Session>()

    /**
     * Phase 13B-3e replaced eight `@Synchronized` annotations with [PlatformLock] blocks, because
     * `@Synchronized` resolves to `kotlin.jvm.Synchronized` and does not exist in common code (the
     * same substitution `multistream.MultiStreamProgress` made in 13B-1).
     *
     * The monitor changed with it: a `@Synchronized` *public* method locks on `this`, so an outside
     * caller could in principle have contended with these methods via `synchronized(pipeline) { }`.
     * A repo-wide scan found no such call site, which is what makes the swap to a private monitor
     * behaviour-preserving rather than merely narrower. [PlatformLock.withLock] is a plain
     * `synchronized(monitor)` on both actuals, so it stays reentrant and exception-safe; but it is
     * not `inline` (an `expect class` member cannot be), so every non-local `return` inside one of
     * these blocks had to become `return@withLock`.
     */
    private val lock = PlatformLock()

    /** Snapshot of per-transfer progress vectors keyed by transferId. */
    public val progressVectors: Map<String, ResumeBitVector>
        get() = sessions.mapValues { (_, s) -> s.vector }

    public fun activeTransferIds(): Set<String> = sessions.keys.toSet()

    /**
     * Processes one inbound frame payload.
     * Never throws on untrusted input; malformed/hostile frames surface as
     * [ReceiveEvent.Rejected]([RejectReason.MALFORMED_FRAME]).
     *
     * Thread-safe: frames may arrive concurrently from WebSocket and data-channel readers.
     */
    public fun onFrame(bytes: ByteArray): List<ReceiveEvent> = lock.withLock {
        when (val frame = ChunkFrame.parse(bytes)) {
            null -> listOf(ReceiveEvent.Rejected(RejectReason.MALFORMED_FRAME, null))
            is ChunkFrame.FileStart -> handleFileStart(frame)
            is ChunkFrame.Chunk -> handleChunk(frame)
            is ChunkFrame.AckBatch -> listOf(reject(RejectReason.UNEXPECTED_DIRECTION, frame.transferId))
            is ChunkFrame.Complete -> listOf(reject(RejectReason.UNEXPECTED_DIRECTION, frame.transferId))
        }
    }

    /** Emits (and clears) any pending partial ACK batch; null when nothing pending. */
    public fun flushPendingAck(): ReceiveEvent? = lock.withLock {
        for ((transferId, session) in sessions) {
            if (!session.finished && session.pending.isNotEmpty()) {
                return@withLock buildAck(session, transferId)
            }
        }
        null
    }

    public fun doneIndexes(transferId: String): List<Int>? = lock.withLock {
        sessions[transferId]?.vector?.doneIndexes()
    }

    /**
     * Verified bytes held for [transferId] (resume-seeded chunks included), maintained incrementally so a host can
     * report progress in O(1) per ACK batch instead of summing every done index (quadratic over a big file).
     * Null for an unknown id.
     */
    public fun doneBytes(transferId: String): Long? = lock.withLock {
        sessions[transferId]?.doneBytes
    }

    /** Serialized bit-vector for persistence (C5.6 `TransferChunkEntity`); null if unknown id. */
    public fun serializedProgress(transferId: String): ByteArray? = lock.withLock {
        sessions[transferId]?.vector?.toSerialized()
    }

    /**
     * Drops a receive session (remote CANCEL). Returns true when a live session existed.
     * The destination sink handle is closed by the HOST (it owns the handle map).
     */
    public fun cancelSession(transferId: String): Boolean = lock.withLock {
        sessions.remove(transferId) != null
    }

    /**
     * #5: accepts a pending offer — resolves the deferred destination sink (invoking
     * [sinkFactory], which creates the file) and opens the session for writes. Returns true when
     * an awaiting session existed. If the session was already open (or fully-seeded resume), this
     * is a no-op returning false.
     */
    public fun acceptSession(transferId: String): Boolean = lock.withLock {
        val session = sessions[transferId] ?: return@withLock false
        if (!session.awaitingAcceptance) return@withLock false
        session.resolvedSink = sinkFactory?.invoke(session.start) ?: sink
        session.awaitingAcceptance = false
        true
    }

    /**
     * #5: declines a pending offer — drops the session. No sink was ever resolved, so nothing is
     * on disk to clean up. Returns true when an awaiting session existed.
     */
    public fun declineSession(transferId: String): Boolean = lock.withLock {
        val session = sessions[transferId] ?: return@withLock false
        if (!session.awaitingAcceptance) return@withLock false
        sessions.remove(transferId)
        true
    }

    public fun clear(): Unit = lock.withLock { sessions.clear() }

    private fun handleFileStart(frame: ChunkFrame.FileStart): List<ReceiveEvent> {
        val validationError = validateFileStart(frame)
        if (validationError != null) return listOf(reject(validationError, frame.transferId))

        val existing = sessions[frame.transferId]
        if (existing != null) {
            // Identical re-offer == resume restart: keep accumulated progress, no event.
            // Different facts for the same id is a hard protocol conflict.
            return if (existing.start == frame) {
                emptyList()
            } else {
                listOf(reject(RejectReason.SESSION_CONFLICT, frame.transferId))
            }
        }
        evictFinishedSessions()
        if (sessions.values.count { !it.finished } >= maxConcurrentSessions) {
            return listOf(reject(RejectReason.SESSION_FULL, frame.transferId))
        }
        val vector = ResumeBitVector(frame.totalChunks)
        var seededBytes = 0L
        // #20: pre-mark chunks the receiver already persisted before a restart, so the vector can
        // reach completion even though the resuming sender skips re-sending them.
        resumeIndexesProvider?.invoke(frame)?.forEach { index ->
            if (index in 0 until frame.totalChunks && vector.markReceived(index)) {
                seededBytes += chunkLength(frame, index)
            }
        }
        val fullySeeded = vector.isComplete()
        // #5: a fresh offer awaits explicit acceptance — defer the sink (no destination file yet).
        // A fully-seeded resume bypasses the gate (the user accepted pre-restart) and finalizes.
        val awaiting = requireAcceptance && !fullySeeded
        val session = Session(
            start = frame,
            vector = vector,
            resolvedSink = if (awaiting) null else (sinkFactory?.invoke(frame) ?: sink),
            awaitingAcceptance = awaiting,
        )
        session.doneBytes = seededBytes
        sessions[frame.transferId] = session
        val events = ArrayList<ReceiveEvent>(2)
        if (emitSessionStarted) {
            events.add(ReceiveEvent.SessionStarted(frame))
        }
        // A fully-seeded resume (every chunk already persisted, only the COMPLETE handshake was
        // lost pre-restart) must finalize now — the sender has nothing left to send (#20).
        if (fullySeeded) {
            session.finished = true
            events.add(buildComplete(session, frame.transferId))
        }
        return events
    }

    private fun handleChunk(frame: ChunkFrame.Chunk): List<ReceiveEvent> {
        val session = sessions[frame.transferId]
            ?: return listOf(reject(RejectReason.UNKNOWN_TRANSFER, frame.transferId))
        if (frame.fileId != session.start.fileId) {
            return listOf(reject(RejectReason.FILE_ID_MISMATCH, frame.transferId))
        }
        if (session.finished) {
            // Late duplicate after COMPLETE: idempotent silence — sender's mirror
            // already holds every index once coverage was reached (C5.3 contract).
            return emptyList()
        }
        if (session.awaitingAcceptance) {
            // #5: offer not yet accepted — never write to disk. A compliant sender parks after
            // FILE_START and sends nothing here; this only fires for a misbehaving/legacy sender.
            return listOf(
                ReceiveEvent.Rejected(RejectReason.AWAITING_ACCEPTANCE, frame.transferId, frame.index),
            )
        }
        val totalChunks = session.start.totalChunks
        if (frame.index < 0 || frame.index >= totalChunks) {
            return listOf(
                ReceiveEvent.Rejected(RejectReason.INDEX_OUT_OF_RANGE, frame.transferId, frame.index),
            )
        }
        val expectedLength = expectedChunkLength(session, frame.index)
        if (frame.data.size != expectedLength) {
            return listOf(
                ReceiveEvent.Rejected(RejectReason.CHUNK_SIZE_MISMATCH, frame.transferId, frame.index),
            )
        }

        val alreadyReceived = session.vector.isReceived(frame.index)
        val computed = Sha256.digest(frame.data)
        if (!Sha256.rawEqualsConstantTime(computed, frame.chunkSha256)) {
            // Verify-before-write: reject WITHOUT writing, marking, or ACKing (implicit NACK).
            return listOf(
                ReceiveEvent.Rejected(RejectReason.HASH_MISMATCH, frame.transferId, frame.index),
            )
        }

        if (!alreadyReceived) {
            // R-06 (sweep 2026-10-09): a failed write used to return no event at all, so a full disk, a revoked storage grant or
            // an I/O error left the chunk un-ACKed, the sender retrying or stalled and the receiver showing no cause. It is now a
            // typed rejection the host turns into a Failed transfer. Cancellation passes through; an Error (out of memory) is
            // not an I/O failure and is not caught.
            val failure = try {
                session.resolvedSink?.write(frame.index, frame.data)
                null
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                e
            }
            if (failure != null) {
                // The session is dropped: nothing more is written for it, a later chunk answers UNKNOWN_TRANSFER (which makes the
                // sender stop), and an identical re-offer opens a fresh session instead of being swallowed as a resume restart.
                sessions.remove(frame.transferId)
                runCatching {
                    FlashLog.w("STORAGE", "chunk write failed transferId=${frame.transferId} index=${frame.index} error=${failure::class.simpleName}")
                }
                return listOf(ReceiveEvent.Rejected(RejectReason.WRITE_FAILED, frame.transferId, frame.index))
            }
        }
        val newlyMarked = session.vector.markReceived(frame.index)
        if (newlyMarked) session.doneBytes += frame.data.size
        session.pending.add(frame.index)

        if (session.vector.isComplete()) {
            val events = ArrayList<ReceiveEvent>(2)
            if (session.pending.isNotEmpty()) {
                events.add(buildAck(session, frame.transferId))
            }
            session.finished = true
            events.add(buildComplete(session, frame.transferId))
            return events
        }
        return if (newlyMarked && session.pending.size >= ackEvery) {
            listOf(buildAck(session, frame.transferId))
        } else {
            emptyList()
        }
    }

    private fun buildAck(session: Session, transferId: String): ReceiveEvent.AckBatchReady {
        // `.sorted()` is what keeps this wire-identical after 13B-3e swapped [Session.pending] from
        // a `sortedSetOf` (TreeSet) to a `HashSet`: these indexes become `ChunkFrame.AckBatch`
        // bytes, and the documented contract on [ReceiveEvent.AckBatchReady] is "deduplicated
        // ascending". The set still dedupes; the ordering moved here.
        val indexes = session.pending.toList().sorted()
        session.pending.clear()
        return ReceiveEvent.AckBatchReady(
            ChunkFrame.AckBatch(transferId, session.start.fileId, indexes),
        )
    }

    private fun buildComplete(session: Session, transferId: String): ReceiveEvent.Completed {
        var verified = true
        if (recheckWholeFileDigest) {
            val observed = wholeFileDigest?.currentDigestHex()
            verified = observed != null &&
                Sha256.hexEqualsConstantTime(
                    Sha256.normalizeHex(observed),
                    Sha256.normalizeHex(session.start.fileSha256Hex),
                )
        }
        return ReceiveEvent.Completed(
            ChunkFrame.Complete(transferId, session.start.fileId, verified),
        )
    }

    private fun validateFileStart(frame: ChunkFrame.FileStart): RejectReason? {
        if (!Sha256.isValidHex(frame.fileSha256Hex)) return RejectReason.INVALID_FILE_START
        if (frame.totalBytes <= 0 || frame.totalChunks <= 0) return RejectReason.INVALID_FILE_START
        if (frame.chunkSize < Chunker.MIN_CHUNK_SIZE_BYTES ||
            frame.chunkSize > Chunker.MAX_CHUNK_SIZE_BYTES
        ) {
            return RejectReason.INVALID_FILE_START
        }
        val expectedChunks = (frame.totalBytes + frame.chunkSize - 1) / frame.chunkSize
        if (expectedChunks != frame.totalChunks.toLong()) return RejectReason.INVALID_FILE_START
        // R-07: the resume vector is allocated here, before anyone has accepted the offer. Without a cap a frame claiming
        // totalChunks near Int.MAX_VALUE cost about 268 MB, times 32 sessions.
        if (frame.totalChunks > MAX_TOTAL_CHUNKS) return RejectReason.INVALID_FILE_START
        return null
    }

    /** Drops the oldest finished sessions beyond [maxFinishedSessionsRetained]; live sessions are never touched. */
    private fun evictFinishedSessions() {
        var finished = sessions.values.count { it.finished }
        if (finished <= maxFinishedSessionsRetained) return
        val it = sessions.entries.iterator()
        while (it.hasNext() && finished > maxFinishedSessionsRetained) {
            if (it.next().value.finished) {
                it.remove()
                finished--
            }
        }
    }

    private fun expectedChunkLength(session: Session, index: Int): Int = chunkLength(session.start, index)

    private fun chunkLength(start: ChunkFrame.FileStart, index: Int): Int {
        val fullEnd = (index + 1).toLong() * start.chunkSize
        return if (fullEnd <= start.totalBytes) start.chunkSize
        else (start.totalBytes - index.toLong() * start.chunkSize).toInt()
    }

    private fun reject(reason: RejectReason, transferId: String?): ReceiveEvent.Rejected =
        ReceiveEvent.Rejected(reason, transferId)

    private class Session(
        val start: ChunkFrame.FileStart,
        val vector: ResumeBitVector,
        var resolvedSink: ChunkSink?,
        var awaitingAcceptance: Boolean = false,
    ) {
        /**
         * `HashSet` rather than the original `sortedSetOf` (`java.util.TreeSet`): the JDK
         * sorted-set types have no common equivalent, and the sort order this set used to supply is
         * applied in [buildAck] instead, where the bytes are actually built.
         */
        val pending = HashSet<Int>()
        var finished = false

        /** Verified bytes held so far (see [ReceivePipeline.doneBytes]). */
        var doneBytes: Long = 0L
    }

    public companion object {

        /** Shared default batch size — both pipelines must agree (C5.7 keeps this constant). */
        public const val DEFAULT_ACK_EVERY: Int = 32

        private const val DEFAULT_MAX_SESSIONS: Int = 32

        /**
         * Most chunks one offer may declare (R-07). The resume bit-vector costs one bit per chunk, so this bounds an
         * unaccepted offer at 2 MiB (32 sessions: 64 MiB). 16 777 216 chunks is 1 TiB at the default 64 KiB chunk and
         * 256 GiB at the 16 KiB minimum; a larger file is refused as an invalid offer.
         */
        public const val MAX_TOTAL_CHUNKS: Int = 16_777_216

        private const val DEFAULT_FINISHED_RETAINED: Int = 64
    }
}

// Fully common since Phase 13B-3e, which cleared the last two reasons this file was Android-bound:
// eight `@Synchronized` annotations (→ [PlatformLock], see the `lock` property) and `sortedSetOf`
// (→ `HashSet` + an explicit `.sorted()` in `buildAck`, see [ReceivePipeline.Session.pending]).
// Everything else it needs had already gone common: [ChunkSink] in 13B-2, `Sha256` in 13B-3a,
// `ChunkFrame` in 13B-3b and `ResumeBitVector` in 13B-3c.

/**
 * Optional whole-file digest seam for final re-checks (e.g. hashing the assembled destination
 * via random access after all chunks landed). Returning null defers to per-chunk trust.
 */
public fun interface WholeFileDigestProvider {

    public fun currentDigestHex(): String?
}

public sealed interface ReceiveEvent {

    /**
     * Emitted once per transfer when a valid FILE_START opened a session. Hosts can use this
     * to finalize destination bookkeeping (the sink itself was already resolved via
     * [ReceivePipeline.sinkFactory] before this event fires).
     */
    public data class SessionStarted(val frame: ChunkFrame.FileStart) : ReceiveEvent

    /** Receiver → sender confirmation carrying deduplicated ascending verified indexes. */
    public data class AckBatchReady(val frame: ChunkFrame.AckBatch) : ReceiveEvent

    /** Emitted exactly once per session when the last verified chunk lands. */
    public data class Completed(val frame: ChunkFrame.Complete) : ReceiveEvent

    /**
     * Graceful rejection: the pipeline stays usable; the reason drives engine-level retry /
     * targeted repair decisions.
     */
    public data class Rejected(
        val reason: RejectReason,
        val transferId: String?,
        val index: Int = -1,
    ) : ReceiveEvent
}

public enum class RejectReason {
    /** Unparseable bytes: bad magic/version/type, truncation, trailing garbage. */
    MALFORMED_FRAME,

    /** CHUNK/ACK/COMPLETE for a transferId this pipeline never saw FILE_START for. */
    UNKNOWN_TRANSFER,

    /** FILE_START re-declared with different facts than the original session. */
    SESSION_CONFLICT,

    /** FILE_START failed math/size validation (totalChunks math, size>0, chunk bounds). */
    INVALID_FILE_START,

    /** Session capacity exhausted — back off and retry later. */
    SESSION_FULL,

    /** Chunk belongs to the right transfer but the wrong file. */
    FILE_ID_MISMATCH,

    /** Data arrived after the session already completed. */
    SESSION_FINISHED,

    /** Index outside [0, totalChunks). */
    INDEX_OUT_OF_RANGE,

    /** Data length does not match the expected length for its index. */
    CHUNK_SIZE_MISMATCH,

    /** Embedded hash failed constant-time comparison — NOT written, NOT acked (repair path). */
    HASH_MISMATCH,

    /** Sender-only frames (ACK_BATCH/COMPLETE) fed into the receive side. */
    UNEXPECTED_DIRECTION,

    /** #5: a chunk arrived for a session whose offer the user has not yet accepted. */
    AWAITING_ACCEPTANCE,

    /**
     * R-06: the destination sink threw while writing a verified chunk (full disk, revoked storage grant, I/O error). The session
     * is dropped and the host fails the transfer with a sentence a person can act on.
     */
    WRITE_FAILED,
}
