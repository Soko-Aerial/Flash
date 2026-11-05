package com.transfer.flash.core.security.pairing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-peer throttle for pairing attempts.
 *
 * A wrong comparison code already fails its session, but nothing stopped an attacker (or a confused
 * user) from opening a new request immediately and trying again. Five failed attempts inside ten
 * minutes now lock that peer out for five minutes. A successful pairing clears the record.
 *
 * The state is in memory on purpose: a process restart is a new pairing window, and persisting an
 * attacker-controlled counter would add a write per attempt to the trust store. The map is pruned on
 * every call and capped, so a peer that never returns cannot grow it without bound.
 */
public object PairingAttemptLimiter {

    public const val MAX_FAILURES: Int = 5
    public const val WINDOW_MS: Long = 10 * 60_000L
    public const val LOCKOUT_MS: Long = 5 * 60_000L
    public const val REASON_TOO_MANY_ATTEMPTS: String = "too-many-attempts"

    private const val MAX_TRACKED_PEERS: Int = 512

    private val mutex = Mutex()
    private val attempts = mutableMapOf<String, Attempts>()

    private data class Attempts(
        var failures: Int = 0,
        var windowStartedMs: Long = 0L,
        var lastAttemptMs: Long = 0L,
        var lockedUntilMs: Long = 0L,
    )

    /** True when [peerId] may start a new pairing attempt at [nowMs]. */
    public suspend fun allow(peerId: String, nowMs: Long): Boolean = mutex.withLock {
        prune(nowMs)
        val record = attempts[peerId] ?: return@withLock true
        if (record.lockedUntilMs > nowMs) return@withLock false
        if (nowMs - record.windowStartedMs > WINDOW_MS) {
            record.failures = 0
            record.windowStartedMs = nowMs
        }
        true
    }

    /** Records one failed attempt; the fifth one starts the lockout. */
    public suspend fun recordFailure(peerId: String, nowMs: Long) {
        if (peerId.isBlank()) return
        mutex.withLock {
            prune(nowMs)
            val record = attempts.getOrPut(peerId) { Attempts(windowStartedMs = nowMs) }
            if (nowMs - record.windowStartedMs > WINDOW_MS) {
                record.failures = 0
                record.windowStartedMs = nowMs
            }
            record.failures += 1
            record.lastAttemptMs = nowMs
            if (record.failures >= MAX_FAILURES) {
                record.failures = 0
                record.windowStartedMs = nowMs
                record.lockedUntilMs = nowMs + LOCKOUT_MS
            }
        }
    }

    /** A completed pairing clears the peer's record. */
    public suspend fun recordSuccess(peerId: String) {
        if (peerId.isBlank()) return
        mutex.withLock { attempts.remove(peerId) }
    }

    /** Test-only reset; the object is process-wide and tests must not inherit each other's state. */
    internal suspend fun clearForTest() {
        mutex.withLock { attempts.clear() }
    }

    private fun prune(nowMs: Long) {
        val iterator = attempts.entries.iterator()
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            val expired = record.lockedUntilMs <= nowMs && nowMs - record.lastAttemptMs > WINDOW_MS
            if (expired) iterator.remove()
        }
        if (attempts.size > MAX_TRACKED_PEERS) {
            val oldest = attempts.entries.minByOrNull { it.value.lastAttemptMs }?.key ?: return
            attempts.remove(oldest)
        }
    }
}
