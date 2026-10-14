package com.transfer.flash.server.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * Token-bucket rate limiter keyed by an arbitrary string (client IP, principal, admin username), for the endpoints an
 * unauthenticated caller can reach (SEC-09: the auth routes, the enrol routes, admin login).
 *
 * The map is BOUNDED: idle buckets expire after [idleTtlMs] and, if the map is still full, new keys share one
 * `overflow` bucket instead of growing memory (an attacker rotating keys therefore gets throttled together, not
 * unlimited service). [clock] is injectable so tests do not sleep.
 */
class TokenBucketLimiter(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val maxKeys: Int = 10_000,
    private val idleTtlMs: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillPerSecond > 0.0) { "refill rate must be positive" }
    }

    private class Bucket(var tokens: Double, var updatedAt: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** True when one more request from [key] is allowed (and consumes a token). */
    fun tryAcquire(key: String, cost: Int = 1): Boolean {
        val now = clock()
        val bucket = bucketFor(key, now)
        synchronized(bucket) {
            refill(bucket, now)
            if (bucket.tokens >= cost) {
                bucket.tokens -= cost
                return true
            }
            return false
        }
    }

    /** Seconds until [key] has a token again (at least 1), for the `Retry-After` header. */
    fun retryAfterSeconds(key: String): Long {
        val now = clock()
        val bucket = buckets[key] ?: return 1L
        synchronized(bucket) {
            refill(bucket, now)
            val missing = 1.0 - bucket.tokens
            return if (missing <= 0.0) 1L else kotlin.math.ceil(missing / refillPerSecond).toLong().coerceAtLeast(1L)
        }
    }

    /** Removes idle buckets. Called by a timer and opportunistically when the map is full. */
    fun sweep() {
        val now = clock()
        buckets.entries.removeIf { now - it.value.updatedAt > idleTtlMs }
    }

    val size: Int get() = buckets.size

    private fun bucketFor(key: String, now: Long): Bucket {
        buckets[key]?.let { return it }
        if (buckets.size >= maxKeys) sweep()
        val effective = if (buckets.size >= maxKeys) OVERFLOW_KEY else key
        return buckets.computeIfAbsent(effective) { Bucket(capacity.toDouble(), now) }
    }

    private fun refill(bucket: Bucket, now: Long) {
        val elapsed = (now - bucket.updatedAt).coerceAtLeast(0L)
        if (elapsed > 0) {
            bucket.tokens = minOf(capacity.toDouble(), bucket.tokens + elapsed / 1000.0 * refillPerSecond)
            bucket.updatedAt = now
        }
    }

    private companion object {
        const val OVERFLOW_KEY = "\u0000overflow"
    }
}

/**
 * The named limiters of the hub with their default budgets, so a route asks one object (`limits.enroll.tryAcquire(ip)`)
 * and a test can replace the whole set.
 */
class HubRateLimits(
    clock: () -> Long = System::currentTimeMillis,
    /** Per client IP on `/auth/challenge` and `/auth/verify`: a burst of 30, then 1 per second. */
    val auth: TokenBucketLimiter = TokenBucketLimiter(30, 1.0, clock = clock),
    /** Per client IP on `/enroll`, `/enroll/renew` and the landing page: burst 10, then 1 every 6 s. */
    val enroll: TokenBucketLimiter = TokenBucketLimiter(10, 1.0 / 6.0, clock = clock),
    /** Per client IP on admin login / setup: burst 8, then 1 every 15 s. */
    val adminLogin: TokenBucketLimiter = TokenBucketLimiter(8, 1.0 / 15.0, clock = clock),
    /** Per authenticated principal on directory and device routes: burst 120, then 10 per second. */
    val principal: TokenBucketLimiter = TokenBucketLimiter(120, 10.0, clock = clock),
    /** Per client IP for requests that carry no valid bearer token on protected routes: burst 20, then 1 per second. */
    val unauthenticated: TokenBucketLimiter = TokenBucketLimiter(20, 1.0, clock = clock),
    /** Per device on `PUT|DELETE /device/sealing-key`: burst 5, then 1 every 30 s (a key changes rarely; ADR-124). */
    val sealingKey: TokenBucketLimiter = TokenBucketLimiter(5, 1.0 / 30.0, clock = clock),
    /**
     * Per authenticated device on `GET /api/v1/directory/sync`: burst 60, then 1 per second. A full sync of 20,000 members is
     * 40 pages of 500, and a rejected page restarts the whole sync (the client stages all pages first), so the burst must
     * hold one complete sync (verifier defect 3, ORGT-U56). Steady-state polling is still bounded to 60 pages a minute.
     */
    val directorySync: TokenBucketLimiter = TokenBucketLimiter(60, 1.0, clock = clock),
    /** Per authenticated device on `GET /api/v1/directory/people*`: burst 30, then 1 per 2 s (30/min, A2 §3.3). */
    val directoryPeople: TokenBucketLimiter = TokenBucketLimiter(30, 0.5, clock = clock),
) {
    fun sweepAll() {
        auth.sweep(); enroll.sweep(); adminLogin.sweep(); principal.sweep(); unauthenticated.sweep(); sealingKey.sweep(); directorySync.sweep(); directoryPeople.sweep()
    }

    companion object {
        /** Effectively unlimited, for tests that are not about rate limiting. */
        fun unlimited(): HubRateLimits {
            fun big() = TokenBucketLimiter(1_000_000, 1_000_000.0)
            return HubRateLimits(auth = big(), enroll = big(), adminLogin = big(), principal = big(), unauthenticated = big(), sealingKey = big(), directorySync = big())
        }
    }
}
