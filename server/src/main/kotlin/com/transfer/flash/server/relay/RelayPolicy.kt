package com.transfer.flash.server.relay

import com.transfer.flash.core.hubprotocol.HubContract
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server-side limits of the relay (REL-04, REL-05, REL-06). Not part of the wire contract (the contract announces only
 * the frame and payload sizes in `FLASH_HUB_WELCOME.limits`); a deployment tunes them. The defaults are sized for a
 * company hub of a few thousand devices on one node.
 *
 * @property handshakeTimeoutMs how long a connection may take to answer the challenge ([HubContract.HANDSHAKE_TIMEOUT_MS]).
 * @property maxConnections hard cap on simultaneous sockets (handshaking and established).
 * @property maxConnectionsPerIp the same, per remote address. The key is the client address the hub resolves
 *   (REV-01): behind a trusted proxy the right-most untrusted `X-Forwarded-For` entry, never the first (client-controlled) one.
 * @property frameBurst / [framesPerSecond] per-session token bucket: a burst of [frameBurst] frames, refilled at
 *   [framesPerSecond]. A frame over the limit is answered `ERR_RATE_LIMIT` and not processed.
 * @property abusiveRejectionsBeforeClose a session whose frames are refused (rate limit, malformed) this many times
 *   since it connected is disconnected.
 * @property outboundQueueCapacity frames queued for one slow client before it is declared a slow consumer and closed.
 * @property sendTimeoutMs one socket write may take this long before the session is closed.
 * @property maxInFlightPerSession live messages handed to one session and not yet acknowledged; beyond this a message
 *   goes through the (durable) mailbox instead, which bounds hub memory per client.
 * @property drainPageSize rows read per mailbox page when a reconnected device is drained.
 * @property shutdownTimeoutMs how long graceful shutdown waits for sessions to flush and close.
 * @property pingPeriodMs / [pongTimeoutMs] the WebSocket protocol ping that detects a half-open connection (Ktor's
 *   heartbeat; a session that does not answer within [pongTimeoutMs] is closed and its messages go to the mailbox).
 * @property sessionExpirySkewMs a session is closed with `ERR_EXPIRED` this long after its certificate or delegation ends.
 * @property groupFanoutConcurrency members handled concurrently when a group message is fanned out.
 */
data class RelayPolicy(
    val handshakeTimeoutMs: Long = HubContract.HANDSHAKE_TIMEOUT_MS,
    val maxConnections: Int = 5_000,
    val maxConnectionsPerIp: Int = 100,
    val frameBurst: Int = 120,
    val framesPerSecond: Double = 60.0,
    val abusiveRejectionsBeforeClose: Int = 200,
    val outboundQueueCapacity: Int = 256,
    val sendTimeoutMs: Long = 10_000L,
    val maxInFlightPerSession: Int = 256,
    val drainPageSize: Int = 100,
    val shutdownTimeoutMs: Long = 5_000L,
    val pingPeriodMs: Long = 15_000L,
    val pongTimeoutMs: Long = 30_000L,
    val groupFanoutConcurrency: Int = 16,
    /** How long past the end of its certificate (or the hub's delegation) a session may live: the clock skew the handshake tolerates (REV-05). */
    val sessionExpirySkewMs: Long = 60_000L,
) {
    init {
        require(handshakeTimeoutMs > 0) { "handshakeTimeoutMs must be positive" }
        require(maxConnections > 0 && maxConnectionsPerIp > 0) { "connection caps must be positive" }
        require(frameBurst > 0 && framesPerSecond > 0) { "rate limit must be positive" }
        require(outboundQueueCapacity > 0) { "outboundQueueCapacity must be positive" }
        require(sendTimeoutMs > 0) { "sendTimeoutMs must be positive" }
        require(maxInFlightPerSession >= 0) { "maxInFlightPerSession must not be negative" }
        require(drainPageSize in 1..1000) { "drainPageSize must be 1..1000" }
        require(groupFanoutConcurrency > 0) { "groupFanoutConcurrency must be positive" }
    }
}

/** A token bucket: [capacity] tokens, refilled at [refillPerSecond]. Not thread safe: one owner (the session's read loop). */
class TokenBucket(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private var tokens: Double = capacity.toDouble()
    private var lastNanos: Long = nanoClock()

    /** Takes one token; false when the bucket is empty. */
    fun tryTake(): Boolean {
        val now = nanoClock()
        val elapsedSeconds = (now - lastNanos) / 1_000_000_000.0
        lastNanos = now
        tokens = minOf(capacity.toDouble(), tokens + elapsedSeconds * refillPerSecond)
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

/** Counts open sockets globally and per remote address. [tryAcquire] and [release] must be paired. */
class ConnectionLimiter(private val maxTotal: Int, private val maxPerKey: Int) {
    private val total = AtomicInteger()
    private val perKey = ConcurrentHashMap<String, Int>()

    val openCount: Int get() = total.get()

    /** Which cap refused, or null when the connection is admitted (and counted). */
    fun tryAcquire(key: String): Refusal? {
        if (total.incrementAndGet() > maxTotal) {
            total.decrementAndGet()
            return Refusal.GLOBAL
        }
        var admitted = false
        perKey.compute(key) { _, current ->
            val n = current ?: 0
            if (n >= maxPerKey) {
                current
            } else {
                admitted = true
                n + 1
            }
        }
        if (!admitted) {
            total.decrementAndGet()
            return Refusal.PER_IP
        }
        return null
    }

    fun release(key: String) {
        total.decrementAndGet()
        perKey.compute(key) { _, current -> if (current == null || current <= 1) null else current - 1 }
    }

    enum class Refusal { GLOBAL, PER_IP }
}
