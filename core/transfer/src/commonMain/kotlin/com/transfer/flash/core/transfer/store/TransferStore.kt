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

    /**
     * Forgets every confirmed chunk of [transferId]. Used when an assembled file failed whole-file verification
     * (ADR-068): a retry must start from zero, not skip chunks that were written wrongly. Defaults to a no-op so an
     * adapter written before this existed still compiles (it simply keeps the old behaviour).
     */
    public suspend fun clearDoneChunks(transferId: String) {}

    /**
     * Deletes the chunk rows of every transfer whose own row is already Completed or Cancelled and returns how many rows
     * went (R-01, sweep 2026-10-09). Runs once at startup, without a schema change: it cleans up rows that earlier builds
     * never deleted and rows a crash left behind. Defaults to a no-op so an older adapter still compiles.
     */
    public suspend fun purgeFinishedChunks(): Int = 0

    /** Lightweight `(transferId, chunkIndex)` projection returned by [allDoneChunks]. */
    public data class ChunkRef(public val transferId: String, public val chunkIndex: Int)
}
