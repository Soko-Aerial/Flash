package com.transfer.flash.core.network.resilience

/**
 * Pure exponential-backoff-with-jitter reconnect policy (plan C4.2 / upgrade 1).
 *
 * Chosen jitter variant: **full jitter with a floor** —
 * `delay = base + random01 * (min(cap, base * 2^attempt) - base)`, i.e. the
 * AWS "Exponential Backoff and Jitter" Full-Jitter formula raised to a
 * `base` minimum. The floor guarantees a minimum spacing between attempts
 * (important for P2P, where an immediate retry after a socket failure is
 * always wasted work), while the random component de-synchronizes peers that
 * all observed the same Wi-Fi drop and would otherwise reconnect in a
 * thundering herd against the same access point / group owner.
 *
 * Why full (floored) jitter over decorrelated jitter: the AWS study found
 * full jitter achieves approximately the same completion time as decorrelated
 * jitter with noticeably less client work, and decorrelated's advantage only
 * appears under sustained server-side overload — a regime a phone-to-phone
 * P2P link rarely enters. Decorrelated also carries state (last sleep)
 * between attempts, which complicates deterministic testing for no measured
 * benefit here.
 *
 * Sources:
 * - https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
 * - https://brooker.co.za/blog/2022/08/11/backoff.html
 * - https://github.com/aws-samples/aws-arch-backoff-simulator
 *
 * Pure logic: randomness is injected ([random01]) so tests are deterministic;
 * give-up semantics are computed from caller-supplied elapsed time, never an
 * internal clock (`FlashTimeSource` lives in :core:common and is not visible
 * cross-module by test-design constraint).
 */
internal class ReconnectPolicy(
    /** Lower bound of every delay in ms. */
    val baseMs: Long = DEFAULT_BASE_MS,
    /** Upper bound of any delay in ms. */
    val capMs: Long = DEFAULT_CAP_MS,
    /**
     * Optional total-unstable-time budget in ms after which [shouldGiveUp]
     * turns true. `null` (default) retries forever — correct P2P semantics:
     * the peer may come back at any moment and there is no server to shed
     * load onto.
     */
    val giveUpAfterMs: Long? = null,
    /** Injected source of randomness in [0, 1). Seed it in tests. */
    private val random01: () -> Double,
) {

    init {
        require(baseMs > 0) { "baseMs must be > 0" }
        require(capMs >= baseMs) { "capMs must be >= baseMs" }
        require(giveUpAfterMs == null || giveUpAfterMs >= 0) { "giveUpAfterMs must be >= 0" }
    }

    /** Unjittered ceiling for [attempt]: min(cap, base * 2^attempt). */
    fun boundForAttempt(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(0)
        // Guard overflow: once bound reaches cap, further shifts are irrelevant.
        if (safeAttempt >= 62) return capMs
        val shifted = if (baseMs > capMs ushr safeAttempt) capMs else baseMs shl safeAttempt
        return shifted.coerceAtMost(capMs)
    }

    /**
     * Deterministic delay for [attempt] given a pre-drawn [random01Value].
     * Result lies in `[baseMs, boundForAttempt(attempt)]` inclusive.
     */
    fun delayForAttempt(attempt: Int, random01Value: Double): Long {
        val bound = boundForAttempt(attempt)
        val clampedRandom = random01Value.coerceIn(0.0, 1.0)
        return baseMs + ((bound - baseMs).toDouble() * clampedRandom).toLong()
    }

    /**
     * Draws randomness via [random01] and returns the delay for [attempt].
     */
    fun delayForAttempt(attempt: Int): Long = delayForAttempt(attempt, random01())

    // --- Stateful episode tracking -----------------------------------------
    //
    // The functions above are pure; callers that prefer the policy to count
    // its own attempts within one unstable episode use [nextDelay] and MUST
    // call [reset] once a connection is stably established so the next
    // failure restarts from base (plan upgrade 1 "reset on stable-connect").

    private var currentAttempt: Int = 0

    /** Attempts made since the last [reset]. */
    val currentAttemptValue: Int get() = currentAttempt

    /** Delay for the current attempt, advancing the internal counter. */
    fun nextDelay(): Long = delayForAttempt(currentAttempt++)

    /** Call on stable connect — backoff restarts from [baseMs]. */
    fun reset() {
        currentAttempt = 0
    }

    /**
     * Give-up rule: true when a give-up budget exists and the peer has been
     * continuously unstable for at least [elapsedUnstableMs]. Never true when
     * [giveUpAfterMs] is null (infinite retry per P2P semantics).
     */
    fun shouldGiveUp(elapsedUnstableMs: Long): Boolean =
        giveUpAfterMs != null && elapsedUnstableMs >= giveUpAfterMs

    companion object {
        const val DEFAULT_BASE_MS: Long = 1_000L
        const val DEFAULT_CAP_MS: Long = 30_000L
    }
}
