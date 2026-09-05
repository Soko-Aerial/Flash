package com.transfer.flash.core.transfer.chunked

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
) {
    init {
        require(ackEvery > 0) { "ackEvery must be > 0" }
        require(maxConcurrentSessions > 0) { "maxConcurrentSessions must be > 0" }
        if (recheckWholeFileDigest) {
            requireNotNull(wholeFileDigest) {
                "recheckWholeFileDigest=true requires a WholeFileDigestProvider"
            }
        }
    }

    private val sessions = LinkedHashMap<String, Session>()

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
    @Synchronized
    public fun onFrame(bytes: ByteArray): List<ReceiveEvent> {
        return when (val frame = ChunkFrame.parse(bytes)) {
            null -> listOf(ReceiveEvent.Rejected(RejectReason.MALFORMED_FRAME, null))
            is ChunkFrame.FileStart -> handleFileStart(frame)
            is ChunkFrame.Chunk -> handleChunk(frame)
            is ChunkFrame.AckBatch -> listOf(reject(RejectReason.UNEXPECTED_DIRECTION, frame.transferId))
            is ChunkFrame.Complete -> listOf(reject(RejectReason.UNEXPECTED_DIRECTION, frame.transferId))
        }
    }

    /** Emits (and clears) any pending partial ACK batch; null when nothing pending. */
    @Synchronized
    public fun flushPendingAck(): ReceiveEvent? {
        for ((transferId, session) in sessions) {
            if (!session.finished && session.pending.isNotEmpty()) {
                return buildAck(session, transferId)
            }
        }
        return null
    }

    @Synchronized
    public fun doneIndexes(transferId: String): List<Int>? =
        sessions[transferId]?.vector?.doneIndexes()

    /** Serialized bit-vector for persistence (C5.6 `TransferChunkEntity`); null if unknown id. */
    @Synchronized
    public fun serializedProgress(transferId: String): ByteArray? =
        sessions[transferId]?.vector?.toSerialized()

    /**
     * Drops a receive session (remote CANCEL). Returns true when a live session existed.
     * The destination sink handle is closed by the HOST (it owns the handle map).
     */
    @Synchronized
    public fun cancelSession(transferId: String): Boolean = sessions.remove(transferId) != null

    /**
     * #5: accepts a pending offer — resolves the deferred destination sink (invoking
     * [sinkFactory], which creates the file) and opens the session for writes. Returns true when
     * an awaiting session existed. If the session was already open (or fully-seeded resume), this
     * is a no-op returning false.
     */
    @Synchronized
    public fun acceptSession(transferId: String): Boolean {
        val session = sessions[transferId] ?: return false
        if (!session.awaitingAcceptance) return false
        session.resolvedSink = sinkFactory?.invoke(session.start) ?: sink
        session.awaitingAcceptance = false
        return true
    }

    /**
     * #5: declines a pending offer — drops the session. No sink was ever resolved, so nothing is
     * on disk to clean up. Returns true when an awaiting session existed.
     */
    @Synchronized
    public fun declineSession(transferId: String): Boolean {
        val session = sessions[transferId] ?: return false
        if (!session.awaitingAcceptance) return false
        sessions.remove(transferId)
        return true
    }

    @Synchronized
    public fun clear(): Unit = sessions.clear()

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
        if (sessions.size >= maxConcurrentSessions) {
            return listOf(reject(RejectReason.SESSION_FULL, frame.transferId))
        }
        val vector = ResumeBitVector(frame.totalChunks)
        // #20: pre-mark chunks the receiver already persisted before a restart, so the vector can
        // reach completion even though the resuming sender skips re-sending them.
        resumeIndexesProvider?.invoke(frame)?.forEach { index ->
            if (index in 0 until frame.totalChunks) {
                vector.markReceived(index)
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
            session.resolvedSink?.write(frame.index, frame.data)
        }
        val newlyMarked = session.vector.markReceived(frame.index)
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
        val indexes = session.pending.toList()
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
        return null
    }

    private fun expectedChunkLength(session: Session, index: Int): Int {
        val start = session.start
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
        val pending = sortedSetOf<Int>()
        var finished = false
    }

    public companion object {

        /** Shared default batch size — both pipelines must agree (C5.7 keeps this constant). */
        public const val DEFAULT_ACK_EVERY: Int = 32

        private const val DEFAULT_MAX_SESSIONS: Int = 32
    }
}

// [ChunkSink] — the destination abstraction this pipeline writes verified chunks through — moved
// to `commonMain/chunked/ChunkSink.kt` in Phase 13B-2. Its signature needed no re-typing; only
// the file it lived in was Android-bound. The pipeline itself stays here (`ChunkFrame`, `Sha256`,
// `ResumeBitVector`, `sortedSetOf`: all 13B-3 scope).

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
}
