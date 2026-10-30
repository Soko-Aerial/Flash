package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SEC-D26-01: liveness is judged on a monotonic clock. [System.currentTimeMillis] moves with NTP and
 * manual changes, so a forward jump reaped healthy sessions and a backward one hid a dead peer.
 */
@OptIn(FlashInternalApi::class)
class LivenessClockTest {

    @Test
    fun `costampsAreOnTheMonotonicClockNotTheWallClock`() {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        server.accept()
        val connection = WsConnection(
            socket = client,
            maskOutboundFrames = true,
            remoteLabel = "clock-test",
            listener = object : WsConnection.Listener {
                override fun onTextMessage(connection: WsConnection, text: String) {}
                override fun onBinaryMessage(connection: WsConnection, data: ByteArray) {}
                override fun onConnectionClosed(connection: WsConnection, reason: String) {}
            },
        )
        try {
            val stamp = connection.lastInboundAtMs
            val offMonotonic = kotlin.math.abs(monotonicNowMs() - stamp)
            val offWallClock = kotlin.math.abs(System.currentTimeMillis() - stamp)
            assertTrue(offMonotonic < 5_000, "the stamp must be a fresh monotonic reading, off by ${offMonotonic}ms")
            assertTrue(
                offWallClock > 5_000,
                "the stamp must NOT be a wall-clock reading (JVM monotonic starts at an arbitrary origin); off by ${offWallClock}ms",
            )
        } finally {
            connection.close("test over")
            server.close()
        }
    }

    @Test
    fun `readings only move forward`() {
        var previous = monotonicNowMs()
        repeat(1_000) {
            val now = monotonicNowMs()
            assertTrue(now >= previous, "monotonic clock went backwards: $previous -> $now")
            previous = now
        }
    }
}
