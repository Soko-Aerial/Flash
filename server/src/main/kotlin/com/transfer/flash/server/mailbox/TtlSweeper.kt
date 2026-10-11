package com.transfer.flash.server.mailbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic background task that cleans up expired mailbox envelopes.
 * Executes on a managed [CoroutineScope] with a configurable sweep interval.
 *
 * Deletes records where `expires_at < System.currentTimeMillis()`.
 */
class TtlSweeper(
    private val mailboxQueue: MailboxQueueSeam,
    val sweepIntervalMs: Long = DEFAULT_SWEEP_INTERVAL_MS,
    private val coroutineScope: CoroutineScope,
) : AutoCloseable {

    companion object {
        const val DEFAULT_SWEEP_INTERVAL_MS: Long = 60_000L // 1 minute
    }

    private var activeJob: Job? = null

    /**
     * Starts the background sweeping loop if not already running.
     */
    @Synchronized
    fun start(): Job {
        val existing = activeJob
        if (existing != null && existing.isActive) {
            return existing
        }

        val job = coroutineScope.launch {
            while (isActive) {
                try {
                    sweepOnce()
                } catch (_: Throwable) {
                    // Swallow exception and continue periodic sweep on subsequent interval
                }
                delay(sweepIntervalMs)
            }
        }
        activeJob = job
        return job
    }

    /**
     * Stops the background sweeping loop and cancels the active coroutine.
     */
    @Synchronized
    fun stop() {
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * Performs a single synchronous sweep of expired envelopes.
     * Useful for deterministic testing and manual maintenance triggers.
     *
     * @return Number of expired envelope records deleted.
     */
    fun sweepOnce(): Int {
        return mailboxQueue.deleteExpired()
    }

    override fun close() {
        stop()
    }
}
