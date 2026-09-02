package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import kotlinx.coroutines.flow.Flow

/**
 * Outbox drain semantics (invariants encoded in C1.8 and relied on by C6):
 * 1. claim = read via [dueForDelivery] + atomic [incrementAttempts] per claimed row;
 * 2. delivery commit = [delete], driven by the peer's `DeliveryReceipt` — **not** by a successful
 *    socket write. A write into a half-open socket succeeds, so committing on it lost the frame for
 *    good (ERROR-031); a written-but-unacknowledged row instead stays claimed and is resent on the
 *    caller's backoff ladder until the receipt arrives or the wall-clock budget expires;
 * 3. re-claiming after delete yields nothing — no double delivery. Before the delete a resend is
 *    possible and safe: the receiver's insert is idempotent and it re-acks replays.
 */
@Dao
public interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun enqueue(item: OutboxEntity): Long

    @Query(
        "SELECT * FROM outbox WHERE nextAttemptAt <= :now " +
            "ORDER BY nextAttemptAt ASC LIMIT :limit",
    )
    public suspend fun dueForDelivery(now: Long, limit: Int): List<OutboxEntity>

    /**
     * Reconnect reset (Bug 5): make every pending outbox row retryable immediately —
     * `attempts -> 0`, `nextAttemptAt -> now` — so the drain flushes queued messages the
     * instant a peer session returns, instead of waiting out the exponential backoff a
     * peer-away failure set. Safe to call on every session-up: rows whose peer is still
     * unreachable simply fail again on the next drain pass and re-enter backoff. Give-up is the
     * caller's wall-clock budget measured from [OutboxEntity.createdAt], which this does not
     * touch, so resetting [attempts] can never extend a row's life indefinitely.
     */
    @Query("UPDATE outbox SET attempts = 0, nextAttemptAt = :now")
    public suspend fun makePendingDue(now: Long)

    /** Single-statement atomic increment; safe under concurrent claimers. */
    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE localId = :localId")
    public suspend fun incrementAttempts(localId: String)

    /**
     * Failed-delivery reschedule (#21): atomically bump the attempt count AND push the next retry
     * into the future so a failed send backs off instead of being re-claimed every drain tick.
     * Combined into one statement so a concurrent claimer never sees the incremented count without
     * the advanced [nextAttemptAt]. [dueForDelivery] then skips the row until its backoff elapses.
     */
    @Query("UPDATE outbox SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt WHERE localId = :localId")
    public suspend fun rescheduleAttempt(localId: String, nextAttemptAt: Long)

    @Query("DELETE FROM outbox WHERE localId = :localId")
    public suspend fun delete(localId: String)

    @Query("SELECT COUNT(*) FROM outbox")
    public fun observeCount(): Flow<Int>
}
