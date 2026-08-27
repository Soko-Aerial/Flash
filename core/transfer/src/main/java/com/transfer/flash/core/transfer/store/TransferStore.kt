package com.transfer.flash.core.transfer.store

/**
 * Storage port **owned by the transfer module** (Phase 4 dependency inversion, ADR-024).
 *
 * Captures exactly the persistence operations [com.transfer.flash.core.transfer.RealFlashTransferRepository]
 * performs — no more — so `core:transfer` has **zero** compile dependency on Room / SQLCipher.
 * A concrete adapter (e.g. `core:engine`'s `RoomTransferStore`) implements it against the real DB.
 *
 * Nullable at every call site: a `null` store means "run without persistence" — transfers still
 * work, only *resume-across-restart* is disabled (the pre-existing DB-less behavior). Methods are
 * `suspend`; the repository already calls them from coroutine contexts.
 */
public interface TransferStore {

    /** Insert (or replace) a transfer-level progress row at `bytesDone = 0`. */
    public suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String)

    /** Update the byte counter for an existing transfer row. */
    public suspend fun setBytesDone(transferId: String, bytesDone: Long)

    /** Update the status string for an existing transfer row. */
    public suspend fun setStatus(transferId: String, status: String)

    /** Sorted indexes of chunks already confirmed done for [transferId] (the resume bit-vector). */
    public suspend fun doneChunks(transferId: String): List<Int>

    /** Mark the given chunk [indexes] done for [transferId] (insert-or-ignore semantics). */
    public suspend fun markChunksDone(transferId: String, indexes: List<Int>)

    /** All confirmed (done) chunk rows across every transfer, for warming the receiver done-set (#20). */
    public suspend fun allDoneChunks(): List<ChunkRef>

    /** Lightweight `(transferId, chunkIndex)` projection returned by [allDoneChunks]. */
    public data class ChunkRef(public val transferId: String, public val chunkIndex: Int)
}
