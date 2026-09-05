package com.transfer.flash.core.discovery.core

/**
 * Deterministic capped-exponential-backoff policy for continuous browsing
 * restarts (plan C3.3).
 *
 * Pure math only: delay doubles per attempt from [baseDelayMs] up to
 * [maxDelayMs]; attempt numbering starts at 1. NO JITTER by contract —
 * determinism is required for unit tests. Call sites that talk to real radios
 * add their own jitter on top of the returned value before sleeping.
 *
 * Give-up signal: [delayForAttempt] returns null once [maxAttempts] attempts
 * have been consumed (caller should surface a persistent-failure state).
 */
internal class DiscoveryRetryPolicy(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /**
     * Delay before retrying attempt number [attempt] (1-based), or null when
     * the caller should give up ([attempt] exceeds [maxAttempts], or negative/
     * zero input).
     */
    fun delayForAttempt(attempt: Int): Long? {
        if (attempt < 1 || attempt > maxAttempts) return null
        var delay = baseDelayMs
        repeat(attempt - 1) {
            delay = if (delay >= maxDelayMs) maxDelayMs else (delay * 2).coerceAtMost(maxDelayMs)
        }
        return delay
    }

    /**
     * Clears any internal state. This implementation is stateless (pure
     * function of [attempt]), so reset() is a no-op kept for API stability:
     * call sites call it after successful (re)start so future stateful
     * refinements (e.g., seeded jitter, failure counters) stay drop-in.
     */
    fun reset() {
        // Intentionally empty — see KDoc.
    }

    companion object {
        const val DEFAULT_BASE_DELAY_MS: Long = 1_000L
        const val DEFAULT_MAX_DELAY_MS: Long = 30_000L
        const val DEFAULT_MAX_ATTEMPTS: Int = 5
    }
}
