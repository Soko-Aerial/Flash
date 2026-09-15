// FlashProtocol is @FlashInternalApi; this suite is inside the owning module.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.multicast

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.core.EndpointDirectory
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The concurrency contract of [MulticastTransport], which no single-threaded test can see.
 *
 * The transport runs **one blocking receive loop per interface**, all on the same dispatcher, and the
 * default dispatcher is `Dispatchers.IO` — so on a phone with Wi-Fi plus tethering, or a laptop with
 * Ethernet plus Wi-Fi, several datagrams can enter [MulticastTransport.handleDatagram] at the same
 * instant, in parallel. The sweep loop is a third writer. The state they share is
 * [StandardEndpointDirectory], whose own KDoc says it is **not thread-safe** and that "the owner
 * guards access" — so overlap here is a defect whether or not a given run happens to corrupt a
 * `LinkedHashMap`.
 *
 * That is not hypothetical: an earlier revision of the transport documented its state as
 * "lane-confined, therefore lock-free", which was false for exactly this reason. This suite exists so
 * the guard cannot be removed by someone who reads that sentence again.
 *
 * JVM-only: it needs real threads, a real barrier and a real sleep, none of which `commonTest` can
 * express.
 */
class MulticastTransportConcurrencyTest {

    /**
     * Delegates to a real directory while counting how many threads are inside it at once, and
     * dwelling in [applySeen] so that an unguarded implementation overlaps with certainty rather than
     * by luck.
     */
    private class OverlapDetectingDirectory : EndpointDirectory {
        private val delegate = StandardEndpointDirectory()
        private val inside = AtomicInteger(0)

        @Volatile
        var observedMax: Int = 0
            private set

        private fun <T> counting(block: () -> T): T {
            val now = inside.incrementAndGet()
            if (now > observedMax) observedMax = now
            try {
                return block()
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun applySeen(endpoint: FlashDiscoveredEndpoint, nowMs: Long): EndpointDirectory.Diff =
            counting {
                // A window wide enough that concurrent callers cannot miss each other.
                Thread.sleep(ENTRY_DWELL_MS)
                delegate.applySeen(endpoint, nowMs)
            }

        override fun applyLost(deviceId: FlashDeviceId): EndpointDirectory.Diff =
            counting { delegate.applyLost(deviceId) }

        override fun sweepExpired(graceWindowMs: Long, nowMs: Long): List<EndpointDirectory.Diff.Lost> =
            counting { delegate.sweepExpired(graceWindowMs, nowMs) }

        override fun snapshot(): List<EndpointDirectory.Entry> = counting { delegate.snapshot() }

        override fun get(deviceId: FlashDeviceId): EndpointDirectory.Entry? = counting { delegate.get(deviceId) }
    }

    /** Binds nothing; these tests never touch a socket. */
    private class NoSocketFactory : MulticastSocketFactory {
        override fun bind(group: String, port: Int): List<MulticastSocketBinding> = emptyList()
    }

    private fun identity(id: String) = FlashAdvertisedIdentity(
        deviceId = FlashDeviceId(id),
        friendlyName = "Peer $id",
        deviceModel = "Test",
        protocolVersion = FlashProtocol.VERSION,
    )

    private fun datagram(id: String, source: String) = MulticastDatagram(
        payload = MulticastProtocol.encode(identity(id), 45822).encodeToByteArray(),
        sourceAddress = source,
    )

    @Test
    fun datagramsArrivingOnSeveralInterfacesAtOnce_neverTouchTheDirectoryConcurrently() {
        val directory = OverlapDetectingDirectory()
        val transport = MulticastTransport(
            socketFactory = NoSocketFactory(),
            directory = directory,
            logInfo = { },
            logWarn = { _, _ -> },
        )

        // Every thread waits at the barrier, so they all call in at the same moment; without the
        // guard, the dwell inside applySeen makes overlap certain rather than occasional — a
        // lock-free regression cannot hide behind timing luck.
        val barrier = CyclicBarrier(THREADS)
        val threads = List(THREADS) { index ->
            thread(name = "receive-loop-$index") {
                barrier.await()
                transport.handleDatagram(
                    datagram(id = "peer-$index", source = "192.168.0.${index + 1}"),
                )
            }
        }
        threads.forEach { it.join() }

        assertEquals(1, directory.observedMax, "two threads were inside the directory at once")
        assertEquals(
            THREADS,
            directory.snapshot().size,
            "every concurrent datagram must still land: a lost write is the other half of the race",
        )
    }

    @Test
    fun aSweepRacingInboundDatagrams_neverTouchesTheDirectoryConcurrently() {
        // The sweep loop and the receive loops are separate lanes, and both write: the sweep removes
        // expired leases and calls applyLost while a receive loop may be calling applySeen.
        val directory = OverlapDetectingDirectory()
        val transport = MulticastTransport(
            socketFactory = NoSocketFactory(),
            directory = directory,
            logInfo = { },
            logWarn = { _, _ -> },
        )
        for (index in 0 until THREADS) {
            transport.handleDatagram(datagram(id = "peer-$index", source = "192.168.0.${index + 1}"))
        }

        val barrier = CyclicBarrier(THREADS)
        val threads = List(THREADS) { index ->
            thread(name = "mixed-$index") {
                barrier.await()
                if (index % 2 == 0) {
                    transport.handleDatagram(
                        datagram(id = "peer-$index", source = "192.168.0.${index + 1}"),
                    )
                } else {
                    // Far past every lease: this sweep really does remove entries.
                    transport.sweepLeases(nowMs = Long.MAX_VALUE / 2)
                }
            }
        }
        threads.forEach { it.join() }

        assertEquals(1, directory.observedMax, "a sweep overlapped a sighting inside the directory")
    }

    private companion object {
        /** Threads = interfaces in this test; four is more than any real host we ship to. */
        const val THREADS = 4

        /** Long enough to be observed, short enough that the suite stays fast. */
        const val ENTRY_DWELL_MS = 20L
    }
}
