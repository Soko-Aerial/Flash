package com.transfer.flash.core.network.sweep

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a sweep may start: the automatic fallback's conditions and both rate limits. */
class SweepPolicyTest {

    private val stranded = SweepSituation(pairedPeers = 2, liveSessions = 0, discoveredPeers = 0, connectionAllowsAuto = true)

    private fun policy() = SweepPolicy(quietBeforeAutoMs = 60_000, autoIntervalMs = 600_000, manualCooldownMs = 5_000)

    @Test
    fun `auto is due only after 60 seconds of nothing reachable`() {
        val p = policy()
        assertFalse(p.autoDue(0, stranded))
        assertFalse(p.autoDue(59_999, stranded))
        assertTrue(p.autoDue(60_000, stranded))
    }

    @Test
    fun `any sign of life restarts the quiet period`() {
        val p = policy()
        p.autoDue(0, stranded)
        assertFalse(p.autoDue(50_000, stranded.copy(discoveredPeers = 1)))
        // The clock restarts from the next stranded observation, not from 0.
        assertFalse(p.autoDue(55_000, stranded))
        assertFalse(p.autoDue(114_999, stranded))
        assertTrue(p.autoDue(115_000, stranded))
    }

    @Test
    fun `a live session, no paired peer or a mode that forbids it each prevent auto`() {
        for (blocked in listOf(
            stranded.copy(liveSessions = 1),
            stranded.copy(pairedPeers = 0),
            stranded.copy(connectionAllowsAuto = false),
        )) {
            val p = policy()
            p.autoDue(0, blocked)
            assertFalse(p.autoDue(120_000, blocked), "$blocked")
        }
    }

    @Test
    fun `auto covers a network once per 10 minutes`() {
        val p = policy()
        assertTrue(p.autoAllowedFor(0, "192.168.1.0/24"))
        p.recordSweep(1_000, listOf("192.168.1.0/24"))
        assertFalse(p.autoAllowedFor(600_999, "192.168.1.0/24"))
        assertTrue(p.autoAllowedFor(601_000, "192.168.1.0/24"))
        assertTrue(p.autoAllowedFor(2_000, "10.0.0.0/24"), "another network is independent")
    }

    @Test
    fun `manual scan has a short cooldown after the previous sweep ended`() {
        val p = policy()
        assertTrue(p.manualAllowed(0))
        p.recordSweep(10_000, listOf("192.168.1.0/24"))
        assertFalse(p.manualAllowed(14_999))
        assertTrue(p.manualAllowed(15_000))
    }
}
