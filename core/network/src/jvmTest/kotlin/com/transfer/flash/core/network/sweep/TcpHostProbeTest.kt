package com.transfer.flash.core.network.sweep

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The desktop probe against real loopback sockets, and the desktop subnet source's invariants. */
class TcpHostProbeTest {

    @Test
    fun `an open port answers and a closed one does not`() = runBlocking {
        val probe = TcpHostProbe()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        assertTrue(probe.isOpen("127.0.0.1", port, 300))
        server.close()
        assertFalse(probe.isOpen("127.0.0.1", port, 300))
    }

    @Test
    fun `a sweep over real sockets finds only the listening host`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        try {
            val hits = SubnetSweeper(TcpHostProbe(), concurrency = 4, timeoutMs = 300)
                .sweep(listOf("127.0.0.2", "127.0.0.1", "127.0.0.3"), server.localPort)
            // Windows and Linux both treat all of 127/8 as loopback, but only the wildcard-less bind above listens.
            assertEquals(listOf("127.0.0.1"), hits)
        } finally {
            server.close()
        }
    }

    @Test
    fun `an unparseable host is a miss, not an exception`() = runBlocking {
        assertFalse(TcpHostProbe().isOpen("not a host", 45822, 300))
    }

    @Test
    fun `the desktop subnet source never lists loopback, link-local or a wild prefix`() {
        for (subnet in JvmLocalSubnets.lanSubnets(includeVirtual = true)) {
            assertFalse(subnet.address.startsWith("127."), "$subnet")
            assertFalse(subnet.address.startsWith("169.254."), "$subnet")
            assertTrue(subnet.prefixLength in 0..32, "$subnet")
        }
    }

    @Test
    fun `hiding virtual adapters never adds an address`() {
        val all = JvmLocalSubnets.lanSubnets(includeVirtual = true).toSet()
        val physical = JvmLocalSubnets.lanSubnets(includeVirtual = false).toSet()
        assertTrue(all.containsAll(physical))
    }
}
