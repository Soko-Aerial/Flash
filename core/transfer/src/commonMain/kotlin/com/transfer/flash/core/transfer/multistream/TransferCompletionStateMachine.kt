package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.transfer.concurrent.PlatformLock

/**
 * Pure, single-threaded completion state machine for multi-stream transfers (ERROR-013 fix).
 *
 * Owns the ENTIRE completion semantics that v1 entangled with its worker loop:
 *
 * ```
 * COLLECTING ──coverage──> AWAITING_RECEIVER_COMPLETE ──receiver COMPLETE──> RESOLVED(verified)
 *     │                          │                                              ^  |
 *     │                          + grace expired (watcher) ─────────────────────>┘  | late COMPLETE
 *     ├─ receiver COMPLETE early ──────────────────────────────────────────────────>┘  (upgrade)
 *     │
 *     └─ all channels dead, coverage NOT full ──> RESOLVED(Failed)
 * ```
 *
 * ## The contract that killed v1 (racing-ACK vs exactly-once COMPLETE)
 *
 * - Local ACK coverage reaching [totalChunks] does **not** immediately resolve when the
 *   receiver's authoritative COMPLETE frame has not arrived yet: it enters
 *   [Phase.AWAITING_RECEIVER_COMPLETE] and starts a grace window. This preserves the receiver's
 *   `verified` flag for the UI (E2E asserts it).
 * - The receiver COMPLETE frame resolves from ANY phase — including *after* an
 *   ACK-coverage resolution — and **upgrades** the recorded `verified` value (late-authoritative-
 *   frame upgrade). This is what the racing-ACK contract requires.
 * - If [graceMs] is 0, coverage resolves immediately with `verified = receiverVerified ?: null`
 *   (callers without a receiver pass 0; the racing-ACK test uses this).
 * - Exactly-once COMPLETE emission: the emission callback fires at most once per machine,
 *   on the FIRST resolution that needs a coordination frame.
 * - Resolution is absorbing; every event on a resolved machine returns [Outcome.None].
 *
 * Thread-safety: internally guarded by a [PlatformLock] (13B-3d — the multiplatform stand-in for
 * `synchronized`, since `@Synchronized` has no `commonMain` equivalent); cheap critical sections
 * only, so callers may hold it across their own bookkeeping via returned outcomes.
 *
 * Determinism: time enters ONLY through explicit [nowMs] reads compared against injected
 * [graceMs]; tests either inject a fake clock or use graceMs = 0 for immediate transitions.
 */
internal class TransferCompletionStateMachine(
    private val totalChunks: Int,
    private val graceMs: Long,
    private val nowMs: () -> Long,
    private val onEmitCompleteFrame: ((verified: Boolean) -> Unit)? = null,
) {

    init {
        require(totalChunks > 0) { "totalChunks must be > 0" }
        require(graceMs >= 0) { "graceMs must be >= 0" }
    }

    enum class Phase { COLLECTING, AWAITING_RECEIVER_COMPLETE, RESOLVED }

    /** What the caller must do after feeding an event. */
    sealed interface Outcome {
        /** Keep going; nothing to act on. */
        data object None : Outcome

        /** Session resolved successfully — set terminal to this Completed result. */
        data class ResolvedCompleted(
            val verified: Boolean?,
            val upgradedFromUnknown: Boolean,
        ) : Outcome

        /** Session resolved as failure — set terminal to this Failed result. */
        data class ResolvedFailed(val reason: String) : Outcome
    }

    private val lock = PlatformLock()
    private var phase: Phase = Phase.COLLECTING
    private var confirmedCount: Int = 0
    private var receiverVerified: Boolean? = null
    private var coverageAtMs: Long? = null
    private var failedReason: String? = null

    /**
     * Guarded by [lock] — every path that reads or writes it runs inside [lock], so a plain flag is
     * exactly as strong as the `AtomicBoolean` CAS it replaced in 13B-3d (see
     * [resolveCompletedLocked]).
     */
    private var emittedOnce = false

    val currentPhase: Phase get() = lock.withLock { phase }
    val confirmedCountSnapshot: Int get() = lock.withLock { confirmedCount }

    /**
     * Call after marking [newTotal] chunks confirmed locally (ACK ingestion or reconcile).
     * Triggers coverage transition when [newTotal] reaches [totalChunks].
     */
    fun onConfirmedCount(newTotal: Int): Outcome = lock.withLock {
        if (phase == Phase.RESOLVED) return@withLock Outcome.None
        require(newTotal in 0..totalChunks) { "confirmed count out of range: $newTotal" }
        confirmedCount = maxOf(confirmedCount, newTotal)
        if (confirmedCount >= totalChunks && phase == Phase.COLLECTING) enterCoverageLocked()
        else Outcome.None
    }

    /** Receiver COMPLETE frame ingested: authoritative verification; upgrades late arrivals. */
    fun onReceiverComplete(verified: Boolean): Outcome = lock.withLock {
        if (phase == Phase.RESOLVED) return@withLock upgradeLateCompleteLocked(verified)
        receiverVerified = verified
        resolveCompletedLocked(upgradedFromUnknown = false)
    }

    /** Watcher poll: resolves after the grace window expires in AWAITING_RECEIVER_COMPLETE. */
    fun tryGraceExpire(): Outcome = lock.withLock {
        if (phase != Phase.AWAITING_RECEIVER_COMPLETE) return@withLock Outcome.None
        val elapsed = nowMs() - (coverageAtMs ?: return@withLock Outcome.None)
        if (elapsed < graceMs) return@withLock Outcome.None
        resolveCompletedLocked(upgradedFromUnknown = true)
    }

    /** Every channel died while coverage is incomplete: hard failure. */
    fun onAllChannelsDead(reason: String): Outcome = lock.withLock {
        if (phase == Phase.RESOLVED) return@withLock Outcome.None
        // coverage wins over death race
        if (confirmedCount >= totalChunks) return@withLock Outcome.None
        phase = Phase.RESOLVED
        Outcome.ResolvedFailed(reason)
    }

    fun snapshotVerified(): Boolean? = lock.withLock { receiverVerified }

    // ---- internals ------------------------------------------------------------------------------

    private fun enterCoverageLocked(): Outcome {
        receiverVerified?.let { v -> return resolveCompletedLocked(upgradedFromUnknown = false, verifiedOverride = v) }
        if (graceMs == 0L) return resolveCompletedLocked(upgradedFromUnknown = true)
        coverageAtMs = nowMs()
        phase = Phase.AWAITING_RECEIVER_COMPLETE
        return Outcome.None
    }

    private fun resolveCompletedLocked(upgradedFromUnknown: Boolean, verifiedOverride: Boolean? = null): Outcome {
        val wasResolved = phase == Phase.RESOLVED
        phase = Phase.RESOLVED
        val verified = verifiedOverride ?: receiverVerified
        if (!wasResolved || upgradedFromUnknown) {
            // First resolution OR a legitimate late upgrade emits/refreshes the coordination
            // frame exactly once. Every caller of this function already holds [lock]
            // (onConfirmedCount → enterCoverageLocked, onReceiverComplete, tryGraceExpire), so
            // the plain flag is exactly as strong as the AtomicBoolean CAS it replaced: the
            // read-modify-write below cannot interleave.
            if (!emittedOnce) {
                emittedOnce = true
                onEmitCompleteFrame?.invoke(verified ?: true)
            }
        }
        return Outcome.ResolvedCompleted(verified = verified, upgradedFromUnknown = upgradedFromUnknown)
    }

    private fun upgradeLateCompleteLocked(verified: Boolean): Outcome {
        // Resolved already. A receiver COMPLETE carrying NEW verification information would
        // upgrade the record — but the send() result has already returned to the caller by
        // definition of "late", so there is nothing left to mutate. No-op (no second emit).
        return Outcome.None
    }
}
