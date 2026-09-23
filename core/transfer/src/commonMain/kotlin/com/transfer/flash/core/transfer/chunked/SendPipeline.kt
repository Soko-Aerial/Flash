package com.transfer.flash.core.transfer.chunked

/**
 * Send-side orchestration for chunked transfers (C5.3/C5.6). Pure logic — the transport is an
 * injected `suspend (ByteArray) -> Boolean` and inbound receiver feedback is pushed back through
 * [onFrame], so this class is fully deterministic under JVM tests without any coroutine test
 * utilities.
 *
 * ## Flow
 *
 * 1. Resolve the whole-file digest ([send]'s `fileSha256Hex` or a streaming [Chunker.hashOnly]
 *    pre-pass — required because `FILE_START` carries it, like LocalSend's `/prepare-upload`:
 *    https://github.com/localsend/protocol §4.1).
 * 2. Emit `FILE_START`, then pull CHUNKs lazily from [Chunker.openChunkStream] (constant memory,
 *    single pass) sending each through [send].
 * 3. `resumeFrom(doneIndexes)` skips completed chunks: they are still read+hashed by the
 *    ChunkStream (keeping the single-pass whole-file digest intact for the source-identity
 *    guard) but are NOT sent. v1 skip semantics are linear read-and-discard; random-access
 *    seeking via a future `SeekableSource` interface is reserved (see [Chunker] KDoc).
 * 4. Inbound `ACK_BATCH` frames merge into the confirmed mirror via
 *    [ResumeBitVector.reconcile] (monotonic union); `COMPLETE.verified` records final state.
 *
 * ## Failure / resume contract
 *
 * A `false` from [send] aborts immediately ([SendResult.Aborted]) carrying the exact index that
 * failed plus every confirmed-so-far index — feed those into `resumeFrom` on a fresh pipeline
 * after reconnect (C5.6 reconcile-on-reconnect). The mirror vectors survive via
 * [confirmedSnapshot]/[sentSnapshot] for persistence into `TransferChunkEntity`.
 */
internal class SendPipeline(
    private val chunker: Chunker,
    private val send: suspend (ByteArray) -> Boolean,
) {

    private var confirmed: ResumeBitVector? = null

    private var sent: ResumeBitVector? = null

    /** Null until a COMPLETE frame arrives; otherwise its verified flag. */
    var receiverVerified: Boolean? = null
        private set

    /** Chunks the receiver has hash-verified (union-merged ACK/COMPLETE knowledge). */
    val confirmedSnapshot: List<Int>?
        get() = confirmed?.doneIndexes()

    /** Chunks this pipeline actually pushed through [send] in the latest attempt. */
    val sentSnapshot: List<Int>?
        get() = sent?.doneIndexes()

    /** Sent-but-not-yet-confirmed holes — the resend candidate set. */
    val pendingConfirmation: List<Int>?
        get() {
            val s = sent ?: return null
            val c = confirmed ?: return null
            return s.doneIndexes().filter { !c.isReceived(it) }
        }

    /**
     * Feeds one inbound receiver frame (`ACK_BATCH` / `COMPLETE`). Returns true when consumed.
     * Frames referencing unknown transferIds are ignored (stale late ACKs must not crash).
     */
    fun onFrame(bytes: ByteArray): Boolean {
        when (val frame = ChunkFrame.parse(bytes)) {
            null -> return false
            is ChunkFrame.AckBatch -> {
                if (frame.transferId == transferId && frame.fileId == fileId) {
                    confirmed?.reconcile(frame.indexes)
                    return true
                }
                return false
            }

            is ChunkFrame.Complete -> {
                if (frame.transferId == transferId) {
                    if (frame.fileId == fileId) {
                        receiverVerified = frame.verified
                        return true
                    }
                    return false
                }
                return false
            }

            else -> return false
        }
    }

    /**
     * Sends one file end-to-end.
     *
     * @param fileSha256Hex pre-computed whole-file digest; when null a streaming hash-only
     * pre-pass runs first (one extra linear read of [source]).
     * @param doneIndexes receiver-confirmed chunk indexes to skip (resume).
     */
    suspend fun send(
        meta: FileMeta,
        source: ChunkSource,
        requestedChunkSize: Int = Chunker.DEFAULT_CHUNK_SIZE_BYTES,
        fileSha256Hex: String? = null,
        doneIndexes: Collection<Int> = emptyList(),
    ): SendResult {
        require(receiverVerified == null || meta.transferId != transferId) {
            "transfer ${meta.transferId} already completed on this pipeline"
        }
        transferId = meta.transferId
        fileId = meta.fileId

        val plan = chunker.plan(meta, requestedChunkSize)
        val digest = fileSha256Hex?.let { Sha256.normalizeHex(it) } ?: chunker.hashOnly(source)

        val resumeSet = HashSet<Int>()
        for (i in doneIndexes) {
            if (i in 0 until plan.totalChunks) resumeSet.add(i)
        }
        confirmed = ResumeBitVector(plan.totalChunks).apply { reconcile(resumeSet) }
        sent = ResumeBitVector(plan.totalChunks)
        val sentVec = sent!!

        val startFrame = chunker.fileStart(meta, plan, digest)
        if (!send(ChunkFrame.serialize(startFrame))) {
            return SendResult.Aborted(
                chunksSentBeforeFailure = 0,
                failedIndex = -1,
                resumeCandidates = confirmed!!.doneIndexes(),
            )
        }

        var chunksSent = 0L
        var bytesSent = 0L
        var chunksSkipped = 0L
        var bytesSkipped = 0L
        val stream = chunker.openChunkStream(source, meta, plan, expectFileSha256Hex = digest)
        try {
            var index = 0
            while (index < plan.totalChunks) {
                val frame = stream.next()
                if (index in resumeSet) {
                    // Linear-skip: chunk was already read+hashed by the stream; drop silently.
                    chunksSkipped++
                    bytesSkipped += frame.data.size
                    ChunkBufferPool.release(frame.data)
                } else {
                    val ok = send(ChunkFrame.serialize(frame))
                    ChunkBufferPool.release(frame.data)
                    if (!ok) {
                        return SendResult.Aborted(
                            chunksSentBeforeFailure = chunksSent.toInt(),
                            failedIndex = index,
                            resumeCandidates = confirmed!!.doneIndexes(),
                        )
                    }
                    sentVec.markReceived(index)
                    chunksSent++
                    bytesSent += frame.data.size
                }
                index++
            }
        } finally {
            stream.close()
        }

        return SendResult.Completed(
            totalChunks = plan.totalChunks,
            chunksSent = chunksSent.toInt(),
            chunksSkippedResume = chunksSkipped.toInt(),
            bytesSent = bytesSent,
            bytesSkippedResume = bytesSkipped,
            fileSha256Hex = digest,
            fullyConfirmedByReceiver = confirmed!!.isComplete(),
        )
    }

    /**
     * Named resume entry point (C5.6): identical to [send] with `doneIndexes` prefilled from the
     * receiver's persisted done-set (reconcile-on-reconnect).
     */
    suspend fun resumeFrom(
        meta: FileMeta,
        source: ChunkSource,
        doneIndexes: Collection<Int>,
        requestedChunkSize: Int = Chunker.DEFAULT_CHUNK_SIZE_BYTES,
        fileSha256Hex: String? = null,
    ): SendResult = send(meta, source, requestedChunkSize, fileSha256Hex, doneIndexes)

    fun reset() {
        confirmed = null
        sent = null
        receiverVerified = null
        transferId = null
        fileId = null
    }

    private var transferId: String? = null

    private var fileId: String? = null
}

internal sealed interface SendResult {

    data class Completed(
        val totalChunks: Int,
        val chunksSent: Int,
        val chunksSkippedResume: Int,
        val bytesSent: Long,
        val bytesSkippedResume: Long,
        val fileSha256Hex: String,
        /** True only if ACKs for all chunks had already arrived at send-loop exit. */
        val fullyConfirmedByReceiver: Boolean,
    ) : SendResult

    data class Aborted(
        val chunksSentBeforeFailure: Int,
        /** Index whose send returned false; -1 when FILE_START itself failed. */
        val failedIndex: Int,
        val resumeCandidates: List<Int>,
    ) : SendResult
}
