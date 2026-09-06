package com.transfer.flash.core.network.resilience

import com.transfer.flash.core.network.FlashConnectionHealth
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Moved to `commonTest` in Phase 15-1. The aggregator is a pure resolve-plus-`StateFlow` holder in
 * `commonMain`, and `StateFlow` is `commonMain` in coroutines, so nothing here needed a platform
 * JVM. The only edit is the JUnit 4 → `kotlin.test` import swap; no assertion carries a message.
 */
class ConnectionHealthAggregatorTest {

    private fun healthOf(
        peers: Int = 0,
        attempts: Int = 0,
        online: Int = 0,
        degraded: Int = 0,
    ): FlashConnectionHealth = ConnectionHealthAggregator.resolve(peers, attempts, online, degraded)

    @Test
    fun `no signals at all is offline`() {
        assertEquals(FlashConnectionHealth.Offline, healthOf())
    }

    @Test
    fun `healthy sessions with no degraded is connected`() {
        assertEquals(FlashConnectionHealth.Connected, healthOf(online = 1))
        assertEquals(FlashConnectionHealth.Connected, healthOf(peers = 5, online = 3))
    }

    @Test
    fun `all sessions degraded is degraded`() {
        assertEquals(FlashConnectionHealth.Degraded, healthOf(degraded = 1))
        assertEquals(FlashConnectionHealth.Degraded, healthOf(peers = 2, degraded = 4))
    }

    @Test
    fun `mixed healthy and degraded collapses to connected - documented precedence`() {
        assertEquals(FlashConnectionHealth.Connected, healthOf(online = 1, degraded = 3))
    }

    @Test
    fun `attempts in flight are connecting`() {
        assertEquals(FlashConnectionHealth.Connecting, healthOf(attempts = 1))
        assertEquals(FlashConnectionHealth.Connecting, healthOf(attempts = 7))
    }

    @Test
    fun `peers discovered without sessions is connecting`() {
        assertEquals(FlashConnectionHealth.Connecting, healthOf(peers = 1))
        assertEquals(FlashConnectionHealth.Connecting, healthOf(peers = 12))
    }

    @Test
    fun `session states outrank attempts and peer counts`() {
        // Degraded beats Connecting signals.
        assertEquals(FlashConnectionHealth.Degraded, healthOf(peers = 3, attempts = 2, degraded = 1))
        // Connected beats everything.
        assertEquals(
            FlashConnectionHealth.Connected,
            healthOf(peers = 0, attempts = 0, online = 1),
        )
    }

    @Test
    fun `holder exposes stateflow updated by apply`() {
        val aggregator = ConnectionHealthAggregator()
        val holder: StateFlow<FlashConnectionHealth> = aggregator.health

        assertEquals(FlashConnectionHealth.Offline, holder.value)

        aggregator.apply(peerCountDiscovered = 2, connectingAttempts = 0, onlineSessions = 0, degradedSessions = 0)
        assertEquals(FlashConnectionHealth.Connecting, holder.value)

        aggregator.apply(peerCountDiscovered = 2, connectingAttempts = 1, onlineSessions = 0, degradedSessions = 0)
        assertEquals(FlashConnectionHealth.Connecting, holder.value)

        aggregator.apply(peerCountDiscovered = 2, connectingAttempts = 0, onlineSessions = 1, degradedSessions = 0)
        assertEquals(FlashConnectionHealth.Connected, holder.value)

        aggregator.apply(peerCountDiscovered = 0, connectingAttempts = 0, onlineSessions = 0, degradedSessions = 1)
        assertEquals(FlashConnectionHealth.Degraded, holder.value)

        aggregator.apply(peerCountDiscovered = 0, connectingAttempts = 0, onlineSessions = 0, degradedSessions = 0)
        assertEquals(FlashConnectionHealth.Offline, holder.value)
    }
}
