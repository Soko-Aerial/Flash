package com.transfer.flash.core.discovery.multicast

import java.net.DatagramSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DR2 over REAL sockets on this machine: what [JvmMulticastSocketFactory]'s bindings actually do with a directed
 * broadcast. The unit suites use fakes and cannot show that a socket bound the way the factory binds it (wildcard
 * address, `SO_REUSEADDR`) receives a datagram sent to a subnet broadcast address; this can.
 *
 * It uses a free port, not 45823, so a running Flash on the same machine is not disturbed. When the machine has no
 * interface with a broadcast address (a build agent in a container) it reports that and passes, since there is
 * nothing to send to; it is not a network test of the router, only of this code on this OS.
 */
class JvmDirectedBroadcastSocketTest {

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    @Test
    fun aDirectedBroadcastIsReceivedByTheSocketsTheFactoryBinds() {
        val port = freeUdpPort()
        val factory = JvmMulticastSocketFactory()
        val bindings = factory.bind(MulticastTransport.DEFAULT_GROUP, port)
        try {
            val sender = bindings.firstOrNull { it.broadcastTargets.isNotEmpty() }
            if (sender == null) {
                println("JvmDirectedBroadcastSocketTest: no interface with a broadcast address here; nothing to send to")
                return
            }
            println("JvmDirectedBroadcastSocketTest: sending on ${sender.label} to ${sender.broadcastTargets}")
            val payload = "flash-dr2-${System.nanoTime()}".encodeToByteArray()

            assertTrue(sender.sendBroadcast(payload), "the send itself failed on ${sender.label}")

            // Any binding may hear it (on some OSes every socket sharing the port does, on others one). The
            // sender's own binding counts: a host hearing its own broadcast is what proves the socket accepts one.
            val deadline = System.nanoTime() + 3_000_000_000L
            var heard: MulticastDatagram? = null
            while (heard == null && System.nanoTime() < deadline) {
                for (binding in bindings) {
                    heard = binding.receive(200)?.takeIf { it.payload.contentEquals(payload) }
                    if (heard != null) break
                }
            }
            val received = heard
            assertTrue(received != null, "no binding received the directed broadcast sent on ${sender.label}")
            assertEquals(payload.decodeToString(), received.payload.decodeToString())
        } finally {
            bindings.forEach { it.close() }
            factory.close()
        }
    }
}
