package com.transfer.flash.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure auto-connect admission gate ([AutoConnectGate]).
 * No Android runtime — this is bookkeeping + a time window only.
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
}
