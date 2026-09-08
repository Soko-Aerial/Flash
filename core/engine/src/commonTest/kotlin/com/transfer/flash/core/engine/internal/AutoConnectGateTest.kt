package com.transfer.flash.core.engine.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Executes [AutoConnectGate] on **each** target (Phase 12).
 *
 * This module had exactly one production file that could reach `commonMain`, and reaching it
 * cost a content edit: two `@Synchronized` annotations became `PlatformLock.withLock` blocks.
 * `DefaultFlashEngineTest` cannot cover that — it lives in `androidHostTest`, has one `@Test`,
 * and never touches the gate. So this suite exists to do two things the rest of the module
 * cannot:
 *
 *  * pin the gate's admission contract in the module that ships it. The five semantic cases
 *    below are the same five that `app/src/test/.../net/AutoConnectGateTest.kt` asserts against
 *    the app's independent copy of this class; until now `core:engine`'s copy had no test at
 *    all, so a divergence between the two was invisible.
 *  * prove the swapped-in lock actually excludes, on both `actual`s rather than one. Being in
 *    `commonTest` means `jvmTest` runs it too, which is what separates "the desktop lock
 *    compiles" from "the desktop lock locks" (CONVENTIONS.md R3.1).
 */
class AutoConnectGateTest {

    @Test
    fun firstAttempt_admitted_thenSuppressedWithinWindow() {
        val gate = AutoConnectGate(suppressMs = 1_000L)
        assertTrue(gate.tryBegin("peer", hasSession = false, nowMs = 0L))
        gate.end("peer")
        // A retry inside the window is suppressed even after the prior attempt finished.
        assertFalse(gate.tryBegin("peer", hasSession = false, nowMs = 500L))
    }

    @Test
    fun retryAdmitted_afterWindowElapses() {
        val gate = AutoConnectGate(suppressMs = 1_000L)
        assertTrue(gate.tryBegin("peer", hasSession = false, nowMs = 0L))
        gate.end("peer")
        // The comparison is `nowMs - last < suppressMs`, so elapsed == the window is admitted.
        assertTrue(gate.tryBegin("peer", hasSession = false, nowMs = 1_000L))
    }

    @Test
    fun concurrentAttempt_forSamePeer_rejectedUntilEnded() {
        val gate = AutoConnectGate(suppressMs = 1_000L)
        assertTrue(gate.tryBegin("peer", hasSession = false, nowMs = 0L))
        // Still in flight (no end()) → a second lane cannot start a duplicate dial.
        assertFalse(gate.tryBegin("peer", hasSession = false, nowMs = 10L))
    }

    @Test
    fun havingSession_clearsWindow_soDropReArmsImmediately() {
        val gate = AutoConnectGate(suppressMs = 10_000L)
        assertTrue(gate.tryBegin("peer", hasSession = false, nowMs = 0L))
        gate.end("peer")
        // Session came up: gate returns false but forgets the attempt/suppression.
        assertFalse(gate.tryBegin("peer", hasSession = true, nowMs = 100L))
        // Session dropped shortly after — re-dial admitted despite being inside the window.
        assertTrue(gate.tryBegin("peer", hasSession = false, nowMs = 200L))
    }

    @Test
    fun distinctPeers_areIndependent() {
        val gate = AutoConnectGate(suppressMs = 1_000L)
        assertTrue(gate.tryBegin("a", hasSession = false, nowMs = 0L))
        assertTrue(gate.tryBegin("b", hasSession = false, nowMs = 0L))
    }

    @Test
    fun defaultWindow_is15s() {
        // Flash.kt's auto-connect sweep relies on the default rather than passing one, so the
        // constant is part of the contract, not an implementation detail.
        val gate = AutoConnectGate()
        assertTrue(gate.tryBegin("peer", hasSession = false, nowMs = 0L))
        gate.end("peer")
        assertFalse(gate.tryBegin("peer", hasSession = false, nowMs = 14_999L))
        assertTrue(gate.tryBegin("peer", hasSession = false, nowMs = 15_000L))
    }

    @Test
    fun contendedTryBegin_admitsExactlyOnePerPeer() = runTest {
        val gate = AutoConnectGate(suppressMs = 1_000L)
        // The exact hazard the `@Synchronized` -> `withLock` swap could have introduced: two
        // callers both read `deviceId in inFlight` as false before either one inserts, and the
        // engine dials the same peer twice. `end` is deliberately never called, so admission
        // depends only on the in-flight set.
        //
        // Each coroutine records into its OWN slot. Counting into a shared per-peer int would
        // make the check unsound in exactly the case it exists to catch: if the lock leaked and
        // two callers were admitted, their racing `+= 1` could still land on 1.
        val admitted = BooleanArray(PEERS * WORKERS)
        withContext(Dispatchers.Default) {
            List(PEERS * WORKERS) { i ->
                launch {
                    admitted[i] = gate.tryBegin("peer-${i % PEERS}", hasSession = false, nowMs = 0L)
                }
            }.joinAll()
        }
        val perPeer = List(PEERS) { p -> (0 until PEERS * WORKERS).count { it % PEERS == p && admitted[it] } }
        assertEquals(List(PEERS) { 1 }, perPeer)
    }

    @Test
    fun concurrent_tryBegin_and_end_keepBookkeepingConsistent() = runTest {
        // suppressMs = 0 removes the time window entirely (`nowMs - last < 0` is never true), so
        // every rejection here is an in-flight rejection and the two private collections are
        // mutated as fast as the dispatcher allows. An unguarded HashMap/HashSet under this load
        // does not merely lose an update on the JVM — it can throw or corrupt its table.
        val gate = AutoConnectGate(suppressMs = 0L)
        withContext(Dispatchers.Default) {
            List(WORKERS) {
                launch {
                    repeat(ROUNDS) { r ->
                        val peer = "peer-${r % PEERS}"
                        if (gate.tryBegin(peer, hasSession = false, nowMs = 0L)) gate.end(peer)
                    }
                }
            }.joinAll()
        }
        // Every admitted attempt was ended, so the in-flight set must be empty again: one fresh
        // admission per peer, and a second one refused while the first is outstanding.
        repeat(PEERS) { p ->
            assertTrue(gate.tryBegin("peer-$p", hasSession = false, nowMs = 0L))
            assertFalse(gate.tryBegin("peer-$p", hasSession = false, nowMs = 0L))
        }
    }

    private companion object {
        const val PEERS = 64
        const val WORKERS = 8
        const val ROUNDS = 2_000
    }


}
