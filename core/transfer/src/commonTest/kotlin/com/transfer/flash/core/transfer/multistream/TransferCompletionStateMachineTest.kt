package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.transfer.multistream.TransferCompletionStateMachine.Outcome
import com.transfer.flash.core.transfer.multistream.TransferCompletionStateMachine.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Executes [TransferCompletionStateMachine] on **each** target (Phase 13B-3d).
 *
 * Two things make this suite load-bearing rather than decorative:
 *
 *  * **The class had no test at all before this.** It owns the ERROR-013 completion semantics
 *    (racing-ACK coverage vs the receiver's authoritative COMPLETE frame, and exactly-once
 *    emission of the coordination frame) and nothing in the repo exercised it directly — the only
 *    coverage was indirect, through `MultiStreamDispatcher`'s own re-implementation of the same
 *    contract. Moving a file to `commonMain` with zero direct coverage would have been the worst
 *    of both worlds, so 13B-3d writes the suite it should always have had (13B-1's
 *    [RollingRateMeterTest] set the precedent).
 *  * **It proves the 13B-3d conversion, not just the logic.** `private val lock = Any()` plus
 *    seven `synchronized(lock)` blocks became a
 *    [com.transfer.flash.core.transfer.concurrent.PlatformLock] and seven `withLock` blocks, six
 *    early exits became `return@withLock`, and `AtomicBoolean emittedOnce` became a plain flag
 *    guarded by that lock. Being in `commonTest` means `jvmTest` runs it too, which is what
 *    separates "the desktop lock compiles" from "the desktop lock excludes"
 *    (CONVENTIONS.md R3.1).
 *
 * Time is injected, so every transition below is deterministic; only
 * [contention_racingCoverage_emitsExactlyOneCompleteFrame] is concurrent, and it asserts an
 * invariant (exactly one resolving caller) rather than a schedule.
 */
class TransferCompletionStateMachineTest {

    /** Records every emitted `verified` value; size is the emission count. */
    private class EmitRecorder {
        val emitted = mutableListOf<Boolean>()
        fun callback(): (Boolean) -> Unit = { v -> emitted.add(v) }
    }

    @Test
    fun coverage_withZeroGrace_resolvesImmediatelyAndEmitsOnce() {
        val rec = EmitRecorder()
        val m = TransferCompletionStateMachine(
            totalChunks = 4,
            graceMs = 0L,
            nowMs = { 0L },
            onEmitCompleteFrame = rec.callback(),
        )
        assertEquals(Outcome.None, m.onConfirmedCount(3), "3 of 4 is not coverage")
        assertEquals(Phase.COLLECTING, m.currentPhase)

        val out = assertIs<Outcome.ResolvedCompleted>(m.onConfirmedCount(4), "coverage resolves")
        // graceMs == 0 means "no receiver will speak", so the verified flag is unknown and the
        // resolution is flagged as an upgrade-from-unknown for the caller's benefit.
        assertNull(out.verified, "no receiver COMPLETE arrived, so verified is unknown")
        assertTrue(out.upgradedFromUnknown, "zero grace resolves without authoritative input")
        assertEquals(Phase.RESOLVED, m.currentPhase)
        assertEquals(listOf(true), rec.emitted, "unknown verified is emitted as true")
    }

    @Test
    fun coverage_withGraceWindow_parksAwaitingReceiverComplete_withoutEmitting() {
        val rec = EmitRecorder()
        val m = machine(totalChunks = 2, graceMs = 500L, now = { 0L }, rec = rec)
        assertEquals(Outcome.None, m.onConfirmedCount(2), "coverage parks, it does not resolve")
        assertEquals(
            Phase.AWAITING_RECEIVER_COMPLETE,
            m.currentPhase,
            "the receiver still owns the verified flag",
        )
        assertEquals(emptyList(), rec.emitted, "nothing is emitted while awaiting the receiver")
        assertEquals(2, m.confirmedCountSnapshot)
    }

    @Test
    fun receiverComplete_whileAwaiting_resolvesWithTheReceiverVerifiedFlag() {
        val rec = EmitRecorder()
        val m = machine(totalChunks = 2, graceMs = 500L, now = { 0L }, rec = rec)
        m.onConfirmedCount(2)

        val out = assertIs<Outcome.ResolvedCompleted>(m.onReceiverComplete(verified = false))
        assertEquals(false, out.verified, "the receiver's flag is authoritative, even when false")
        assertFalse(out.upgradedFromUnknown, "an authoritative frame is not an upgrade")
        assertEquals(listOf(false), rec.emitted, "false is emitted verbatim, not defaulted to true")
        assertEquals(false, m.snapshotVerified())
    }

    @Test
    fun graceExpiry_resolvesUpgradedFromUnknown() {
        val rec = EmitRecorder()
        var now = 1_000L
        val m = machine(totalChunks = 2, graceMs = 300L, now = { now }, rec = rec)
        m.onConfirmedCount(2)

        now = 1_299L
        assertEquals(Outcome.None, m.tryGraceExpire(), "1 ms short of the window")
        assertEquals(emptyList(), rec.emitted, "an unexpired poll emits nothing")

        now = 1_300L
        val out = assertIs<Outcome.ResolvedCompleted>(m.tryGraceExpire(), "the window elapsed")
        assertNull(out.verified, "the receiver never spoke")
        assertTrue(out.upgradedFromUnknown)
        assertEquals(listOf(true), rec.emitted)
    }

    @Test
    fun graceExpiry_outsideAwaitingPhase_isNoOp() {
        val rec = EmitRecorder()
        val m = machine(totalChunks = 2, graceMs = 300L, now = { 9_999L }, rec = rec)
        // COLLECTING: the watcher may poll before coverage is ever reached.
        assertEquals(Outcome.None, m.tryGraceExpire(), "no coverage, no grace window")
        m.onConfirmedCount(2)
        m.onReceiverComplete(verified = true)
        // RESOLVED: the watcher usually loses the race with the COMPLETE frame.
        assertEquals(Outcome.None, m.tryGraceExpire(), "already resolved")
        assertEquals(listOf(true), rec.emitted, "the watcher never adds an emission")
    }

    @Test
    fun receiverComplete_fromCollecting_resolvesImmediately() {
        val rec = EmitRecorder()
        val m = machine(totalChunks = 10, graceMs = 5_000L, now = { 0L }, rec = rec)
        // The receiver can finish before the sender's own ACK bookkeeping catches up: its COMPLETE
        // frame resolves from ANY phase, so partial local coverage does not hold the session open.
        m.onConfirmedCount(1)
        val out = assertIs<Outcome.ResolvedCompleted>(m.onReceiverComplete(verified = true))
        assertEquals(true, out.verified)
        assertFalse(out.upgradedFromUnknown)
        assertEquals(Phase.RESOLVED, m.currentPhase, "resolution does not wait for full coverage")
        assertEquals(listOf(true), rec.emitted)
    }

    @Test
    fun lateReceiverComplete_afterResolution_isAbsorbedWithNoSecondEmission() {
        val rec = EmitRecorder()
        val m = machine(totalChunks = 2, graceMs = 0L, now = { 0L }, rec = rec)
        assertIs<Outcome.ResolvedCompleted>(m.onConfirmedCount(2), "coverage resolved first")
        assertEquals(1, rec.emitted.size)

        // "Late" by definition means send() already returned to the caller, so there is nothing
        // left to mutate and — critically — nothing left to emit. This is the property the
        // AtomicBoolean CAS used to guard and the lock-guarded flag now guards.
        assertEquals(Outcome.None, m.onReceiverComplete(verified = false), "late frame is absorbed")
        assertEquals(1, rec.emitted.size, "no second coordination frame")
        assertNull(m.snapshotVerified(), "a late frame does not rewrite the recorded flag")
    }

    @Test
    fun resolution_isAbsorbing_everyFurtherEventReturnsNone() {
        val rec = EmitRecorder()
        val m = machine(totalChunks = 3, graceMs = 0L, now = { 0L }, rec = rec)
        assertIs<Outcome.ResolvedCompleted>(m.onConfirmedCount(3))

        assertEquals(Outcome.None, m.onConfirmedCount(3), "repeat coverage")
        assertEquals(Outcome.None, m.onConfirmedCount(0), "regressed count")
        assertEquals(Outcome.None, m.onReceiverComplete(verified = true), "late COMPLETE")
        assertEquals(Outcome.None, m.tryGraceExpire(), "watcher poll")
        assertEquals(Outcome.None, m.onAllChannelsDead("teardown"), "channel teardown")
        assertEquals(1, rec.emitted.size, "exactly one emission survives the whole storm")
        assertEquals(Phase.RESOLVED, m.currentPhase)
    }

    @Test
    fun allChannelsDead_withIncompleteCoverage_resolvesFailed() {
        val rec = EmitRecorder()
        val m = machine(totalChunks = 4, graceMs = 0L, now = { 0L }, rec = rec)
        m.onConfirmedCount(3)
        val out = assertIs<Outcome.ResolvedFailed>(m.onAllChannelsDead("all 3 channels died"))
        assertEquals("all 3 channels died", out.reason)
        assertEquals(Phase.RESOLVED, m.currentPhase)
        assertEquals(emptyList(), rec.emitted, "a failure emits no COMPLETE frame")
    }

    @Test
    fun allChannelsDead_afterFullCoverage_isNoOp_becauseCoverageWinsTheRace() {
        val rec = EmitRecorder()
        val m = machine(totalChunks = 4, graceMs = 5_000L, now = { 0L }, rec = rec)
        m.onConfirmedCount(4)
        // Channels closing after the last ACK landed is the normal end of a transfer, not a
        // failure — the machine must stay parked for the receiver's COMPLETE.
        assertEquals(Outcome.None, m.onAllChannelsDead("sockets closed"), "coverage wins")
        assertEquals(Phase.AWAITING_RECEIVER_COMPLETE, m.currentPhase)
        val out = assertIs<Outcome.ResolvedCompleted>(m.onReceiverComplete(verified = true))
        assertEquals(true, out.verified)
    }

    @Test
    fun confirmedCount_isMonotonic_andRangeChecked() {
        val m = machine(totalChunks = 5, graceMs = 0L, now = { 0L }, rec = EmitRecorder())
        m.onConfirmedCount(4)
        m.onConfirmedCount(1)
        assertEquals(4, m.confirmedCountSnapshot, "a lower total never regresses the high-water mark")

        assertFailsWith<IllegalArgumentException>("above totalChunks") { m.onConfirmedCount(6) }
        assertFailsWith<IllegalArgumentException>("negative") { m.onConfirmedCount(-1) }
        assertEquals(4, m.confirmedCountSnapshot, "a rejected update leaves the state untouched")
    }

    @Test
    fun constructorRejectsImpossibleArguments() {
        assertFailsWith<IllegalArgumentException>("zero chunks") {
            TransferCompletionStateMachine(totalChunks = 0, graceMs = 0L, nowMs = { 0L })
        }
        assertFailsWith<IllegalArgumentException>("negative grace") {
            TransferCompletionStateMachine(totalChunks = 1, graceMs = -1L, nowMs = { 0L })
        }
    }

    @Test
    fun aNullEmitCallbackIsTolerated() {
        // Callers with no receiver to coordinate with (the graceMs = 0 path) pass no callback at
        // all; the emission site must stay a no-op rather than throwing.
        val m = TransferCompletionStateMachine(totalChunks = 1, graceMs = 0L, nowMs = { 0L })
        assertIs<Outcome.ResolvedCompleted>(m.onConfirmedCount(1))
        assertEquals(Phase.RESOLVED, m.currentPhase)
    }

    @Test
    fun contention_racingCoverage_emitsExactlyOneCompleteFrame() = runTest {
        val rec = EmitRecorder()
        val m = machine(totalChunks = WORKERS * ROUNDS, graceMs = 0L, now = { 0L }, rec = rec)
        // Each coroutine tallies ITS OWN resolutions, so a lost write cannot mask a double
        // resolution — the same reason RollingRateMeterTest uses a per-worker array.
        val resolutions = IntArray(WORKERS)
        val phases = arrayOfNulls<Phase>(WORKERS)
        withContext(Dispatchers.Default) {
            List(WORKERS) { w ->
                launch {
                    repeat(ROUNDS) { r ->
                        // Every worker drives the count all the way to coverage, so all eight race
                        // to be the one that transitions COLLECTING → RESOLVED.
                        val out = m.onConfirmedCount((r + 1) * WORKERS)
                        if (out is Outcome.ResolvedCompleted) resolutions[w]++
                        phases[w] = m.currentPhase
                    }
                }
            }.joinAll()
        }
        assertEquals(
            1,
            resolutions.sum(),
            "exactly one caller may transition to RESOLVED; got ${resolutions.toList()}",
        )
        assertEquals(1, rec.emitted.size, "exactly one COMPLETE frame: ${rec.emitted}")
        assertEquals(Phase.RESOLVED, m.currentPhase)
        assertTrue(
            phases.all { it != null },
            "every worker observed a phase (no exception escaped the lock)",
        )
    }

    private fun machine(
        totalChunks: Int,
        graceMs: Long,
        now: () -> Long,
        rec: EmitRecorder,
    ) = TransferCompletionStateMachine(
        totalChunks = totalChunks,
        graceMs = graceMs,
        nowMs = now,
        onEmitCompleteFrame = rec.callback(),
    )

    private companion object {
        const val WORKERS = 8
        const val ROUNDS = 500
    }
}
