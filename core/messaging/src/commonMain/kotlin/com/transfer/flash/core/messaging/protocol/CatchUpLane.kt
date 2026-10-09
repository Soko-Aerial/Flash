package com.transfer.flash.core.messaging.protocol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Who answers a group catch-up (ADR-106, review G3): one holder at a time.
 *
 * Before, a member that needed history asked EVERY holder for the same window; each holder paged the whole of it, so a new
 * member of a 20-member group received up to 19 copies of every row, each copy costing a signature check and a store attempt.
 * The lane serialises the chains of one group on one device:
 *
 * - The holder in charge is the head. Further holders (offered by session-up edges, the join card, a reconnect) wait behind it.
 * - The next holder is started only when the head FAILED (it could not be asked, an incomplete page, or it went quiet for
 *   [stallMs]) or when it completed but delivered NOTHING ("reported less": a holder that joined later or keeps no history).
 * - A holder that delivered a complete chain ends the episode: the waiting holders are dropped, and no other holder is asked
 *   for [episodeMs]. The caller orders holders so that the one served longest ago goes first, which rotates the lead from
 *   episode to episode and lets rows that one holder missed arrive from another over time.
 * - A holder offered again while it is the head (its session came back) restarts its own chain.
 *
 * Pure bookkeeping with an injected clock; the repository sends the requests and arms the stall watchdog. A suspend [Mutex]
 * serialises the callers (inbound handlers, the watchdog and the edge callbacks run on different coroutines).
 */
internal class CatchUpLane(
    private val stallMs: Long = GroupPolicy.CATCH_UP_STALL_MS,
    private val episodeMs: Long = GroupPolicy.CATCH_UP_EPISODE_MS,
) {
    private val mutex = Mutex()
    private var head: String? = null
    private var lastProgressAtMs: Long = 0L
    private var rows: Int = 0
    private val waiting = ArrayList<String>()
    private val failed = HashSet<String>()
    private var satisfiedAtMs: Long = NEVER

    /** The result of a watchdog check: whether [holder] still leads, and whom to start instead when it was replaced. */
    internal class Step(val stillLeads: Boolean, val next: String?)

    /**
     * [holders] (best first) want to be asked. Returns the holder to start NOW, or null when the lane is busy with a healthy
     * head (they wait) or an episode just ended (they are dropped).
     */
    suspend fun offer(holders: List<String>, nowMs: Long): String? = mutex.withLock {
        if (satisfiedAtMs != NEVER && nowMs - satisfiedAtMs < episodeMs) return@withLock null
        satisfiedAtMs = NEVER
        val current = head
        if (current != null) {
            if (current in holders) {
                // The session of the holder in charge is back: its old chain died with the old session.
                lastProgressAtMs = nowMs
                rows = 0
                return@withLock current
            }
            if (nowMs - lastProgressAtMs < stallMs) {
                holders.forEach { enqueue(it) }
                return@withLock null
            }
            failed.add(current)
            head = null
        }
        holders.forEach { enqueue(it) }
        startNext(nowMs)
    }

    /** The head showed life: [pushedRows] rows arrived (0 for a page marker). */
    suspend fun progress(holder: String, pushedRows: Int, nowMs: Long) {
        mutex.withLock {
            if (head != holder) return@withLock
            lastProgressAtMs = nowMs
            rows += pushedRows
        }
    }

    /**
     * [holder]'s chain ended: [complete] when it reached the last page. Returns the holder to start next, or null.
     * A complete chain that delivered rows satisfies the episode.
     */
    suspend fun finish(holder: String, complete: Boolean, nowMs: Long): String? = mutex.withLock {
        if (head != holder) return@withLock null
        head = null
        if (complete && rows > 0) {
            satisfiedAtMs = nowMs
            waiting.clear()
            failed.clear()
            return@withLock null
        }
        failed.add(holder)
        startNext(nowMs)
    }

    /**
     * Watchdog: when [holder] still leads but has been quiet for [stallMs] it is replaced. A holder that pushed rows but never
     * sent a page marker (an older build) counts as complete, exactly as the one-chain-per-holder behaviour treated it.
     */
    suspend fun checkStall(holder: String, nowMs: Long): Step = mutex.withLock {
        if (head != holder) return@withLock Step(stillLeads = false, next = null)
        if (nowMs - lastProgressAtMs < stallMs) return@withLock Step(stillLeads = true, next = null)
        head = null
        if (rows > 0) {
            satisfiedAtMs = nowMs
            waiting.clear()
            failed.clear()
            return@withLock Step(stillLeads = false, next = null)
        }
        failed.add(holder)
        Step(stillLeads = false, next = startNext(nowMs))
    }

    /** The user chose or widened the history: a new episode, whatever the old one was doing. */
    suspend fun reset() {
        mutex.withLock {
            head = null
            rows = 0
            waiting.clear()
            failed.clear()
            satisfiedAtMs = NEVER
        }
    }

    /** Test and probe view: the holder in charge. */
    suspend fun leader(): String? = mutex.withLock { head }

    private fun enqueue(holder: String) {
        failed.remove(holder)
        if (holder != head && holder !in waiting) waiting.add(holder)
    }

    private fun startNext(nowMs: Long): String? {
        val next = waiting.firstOrNull { it !in failed } ?: return null
        waiting.remove(next)
        head = next
        lastProgressAtMs = nowMs
        rows = 0
        return next
    }

    private companion object {
        const val NEVER: Long = Long.MIN_VALUE
    }
}
