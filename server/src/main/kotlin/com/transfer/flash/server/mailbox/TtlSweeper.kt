package com.transfer.flash.server.mailbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Periodic background task that removes expired mailbox rows (REL-11).
 *
 * Differences from the first version: a failed sweep is LOGGED and counted (it used to be swallowed silently), the
 * sweep repeats in batches until nothing expired is left (so a backlog drains in one pass instead of one batch per
 * interval), and every row it removes is reported to [onExpired] so the relay can tell an online sender that its
 * message expired undelivered (`FLASH_QUEUE_STATE state=EXPIRED`). [onMaintenance] runs after each pass for housekeeping
 * such as `PRAGMA incremental_vacuum`.
 */
class TtlSweeper(
    private val store: MailboxStore,
    val sweepIntervalMs: Long = DEFAULT_SWEEP_INTERVAL_MS,
    private val coroutineScope: CoroutineScope,
    private val batchSize: Int = DEFAULT_BATCH,
    private val onExpired: suspend (List<ExpiredRow>) -> Unit = {},
    private val onMaintenance: suspend () -> Unit = {},
) : AutoCloseable {

    companion object {
        const val DEFAULT_SWEEP_INTERVAL_MS: Long = 60_000L // 1 minute
        const val DEFAULT_BATCH: Int = 500
    }

    private val logger = LoggerFactory.getLogger(TtlSweeper::class.java)
    private var activeJob: Job? = null
    private val expiredTotal = AtomicLong()
    private val failures = AtomicLong()

    /** Rows this sweeper has removed since it was created. */
    val expiredCount: Long get() = expiredTotal.get()

    /** Passes that failed since it was created. */
    val failureCount: Long get() = failures.get()

    /** Starts the background sweeping loop if not already running. */
    @Synchronized
    fun start(): Job {
        val existing = activeJob
        if (existing != null && existing.isActive) return existing
        val job = coroutineScope.launch {
            while (isActive) {
                sweepSafely()
                delay(sweepIntervalMs)
            }
        }
        activeJob = job
        return job
    }

    /** Stops the background sweeping loop and cancels the active coroutine. */
    @Synchronized
    fun stop() {
        activeJob?.cancel()
        activeJob = null
    }

    /** One pass that never throws (except cancellation): used by the loop. */
    private suspend fun sweepSafely() {
        try {
            sweepOnce()
            onMaintenance()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failures.incrementAndGet()
            logger.warn("Mailbox sweep failed (will retry in {} ms): {}", sweepIntervalMs, t.toString())
        }
    }

    /**
     * One full pass: deletes expired rows batch by batch until a batch comes back short, notifying [onExpired] per
     * batch. Throws on a storage failure so a test can see it; the loop logs it.
     *
     * @return the number of rows removed.
     */
    suspend fun sweepOnce(): Int {
        var total = 0
        while (true) {
            val batch = store.deleteExpired(batchSize)
            if (batch.isEmpty()) break
            total += batch.size
            expiredTotal.addAndGet(batch.size.toLong())
            try {
                onExpired(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("Telling senders about {} expired messages failed: {}", batch.size, t.toString())
            }
            if (batch.size < batchSize) break
        }
        if (total > 0) logger.info("Mailbox sweep removed {} expired messages", total)
        return total
    }

    override fun close() {
        stop()
    }
}
