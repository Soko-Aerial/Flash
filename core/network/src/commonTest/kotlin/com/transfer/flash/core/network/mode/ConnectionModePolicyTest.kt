package com.transfer.flash.core.network.mode

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.perf.FlashTransportProfile
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.network.presence.PresenceCodec
import com.transfer.flash.core.network.presence.PresenceConfig
import com.transfer.flash.core.network.ws.WsKeepaliveTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PC5: plan §3.4's knob table, per mode and tier. */
class ConnectionModePolicyTest {

    @Test
    fun `STANDARD is the tier's profile unchanged, so STANDARD behaves as before PC5`() {
        for (tier in FlashPerformanceMode.entries) {
            val p = ConnectionModePolicy.of(FlashDiscoveryMode.STANDARD, tier)
            assertEquals(tier.transport, p.transport)
            assertEquals(PresenceConfig.STANDARD, p.presence)
            assertEquals(FlashTransportProfile.DEFAULT_RECONNECT_BASE_MS, p.transport.reconnectBaseMs)
            assertFalse(p.limitsSessions)
        }
    }

    @Test
    fun `Ghost and receive kiosk use STANDARD's connection policy`() {
        assertEquals(ConnectionStrategy.STANDARD, ConnectionStrategy.of(FlashDiscoveryMode.GHOST))
        assertEquals(ConnectionStrategy.STANDARD, ConnectionStrategy.of(FlashDiscoveryMode.RECEIVE_KIOSK))
        assertEquals(ConnectionStrategy.ECO, ConnectionStrategy.of(FlashDiscoveryMode.ECO))
        assertEquals(ConnectionStrategy.BOOST, ConnectionStrategy.of(FlashDiscoveryMode.BOOST))
    }

    @Test
    fun `ECO row`() {
        val p = ConnectionModePolicy.of(FlashDiscoveryMode.ECO, FlashPerformanceMode.HIGH)
        assertEquals(30_000L, p.transport.pingIntervalMs)
        assertEquals(75_000L, p.transport.livenessTimeoutMs)
        assertEquals(2_000L, p.transport.reconnectBaseMs)
        assertEquals(30_000L, p.transport.reconnectCapMs)
        assertEquals(60_000L, p.presence.refreshMs)
        assertEquals(90_000L, p.presence.maxAgeMs)
        assertTrue(p.limitsSessions)
        // Only the named knobs change: call timings stay the tier's.
        assertEquals(FlashTransportProfile.HIGH.callDisconnectGraceMs, p.transport.callDisconnectGraceMs)
    }

    @Test
    fun `BOOST row, and a LOW-tier phone keeps LOW's liveness floor`() {
        val high = ConnectionModePolicy.of(FlashDiscoveryMode.BOOST, FlashPerformanceMode.HIGH)
        assertEquals(5_000L, high.transport.pingIntervalMs)
        assertEquals(15_000L, high.transport.livenessTimeoutMs)
        assertEquals(250L, high.transport.reconnectBaseMs)
        assertEquals(5_000L, high.transport.reconnectCapMs)
        assertEquals(10_000L, high.presence.refreshMs)
        assertEquals(20_000L, high.presence.maxAgeMs)
        assertEquals(250L, high.presence.minFrameGapMs)

        val low = ConnectionModePolicy.of(FlashDiscoveryMode.BOOST, FlashPerformanceMode.LOW)
        assertEquals(5_000L, low.transport.pingIntervalMs)
        assertEquals(FlashTransportProfile.LOW.livenessTimeoutMs, low.transport.livenessTimeoutMs)
    }

    @Test
    fun `every mode and tier gives a keepalive pair the connection accepts`() {
        for (mode in FlashDiscoveryMode.entries) {
            for (tier in FlashPerformanceMode.entries) {
                val t = ConnectionModePolicy.of(mode, tier).transport
                // Throws when liveness is not above ping x STALL_FACTOR.
                WsKeepaliveTiming(t.pingIntervalMs, t.livenessTimeoutMs)
                assertTrue(t.reconnectBaseMs <= t.reconnectCapMs, "$mode/$tier")
            }
        }
    }

    @Test
    fun `presence refresh stays inside what a hello may announce`() {
        for (c in listOf(PresenceConfig.ECO, PresenceConfig.STANDARD, PresenceConfig.BOOST)) {
            assertTrue(c.refreshMs in PresenceCodec.MIN_REFRESH_MS..PresenceCodec.MAX_REFRESH_MS)
            assertTrue(c.maxAgeMs >= c.refreshMs * 3 / 2, "a mode must hold its own reports until its next refresh")
        }
    }
}
