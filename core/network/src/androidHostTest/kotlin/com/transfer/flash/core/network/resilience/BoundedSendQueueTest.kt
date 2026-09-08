package com.transfer.flash.core.network.resilience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedSendQueueTest {

    @Test
    fun `enqueue accepts up to capacity then rejects with QueueFull`() {
        val q = BoundedSendQueue<Int>(capacity = 4)
        repeat(4) { i ->
            assertEquals(EnqueueResult.Enqueued<Int>(i + 1), q.enqueue(i))
        }
        val rejected = q.enqueue(99)
        assertTrue(rejected is EnqueueResult.Rejected)
        assertEquals(RejectReason.QueueFull, (rejected as EnqueueResult.Rejected).reason)
        assertEquals(4, q.size)
    }

    @Test
    fun `rejection never evicts existing items`() {
        val q = BoundedSendQueue<Int>(capacity = 2)
        q.enqueue(1); q.enqueue(2)
        repeat(10) { q.enqueue(it) } // all rejected
        assertEquals(2, q.size)
        assertEquals(1, q.poll())
        assertEquals(2, q.poll())
        assertNull(q.poll())
    }

    @Test
    fun `fifo order preserved across drainInto`() {
        val q = BoundedSendQueue<String>(capacity = 64)
        val expected = (0 until 64).map { "f$it" }
        expected.forEach { q.enqueue(it) }
        val drained = ArrayList<String>()
        val count = q.drainInto { drained.add(it) }
        assertEquals(expected.size, count)
        assertEquals(expected, drained)
        assertEquals(0, q.size)
    }

    @Test
    fun `drainInto on empty queue returns zero`() {
        val q = BoundedSendQueue<Int>()
        assertEquals(0, q.drainInto { })
    }

    @Test
    fun `closed queue rejects new items but keeps buffered ones drainable`() {
        val q = BoundedSendQueue<Int>(capacity = 8)
        q.enqueue(1); q.enqueue(2)
        q.close()
        val r = q.enqueue(3)
        assertEquals(RejectReason.Closed, (r as EnqueueResult.Rejected).reason)
        val out = ArrayList<Int>()
        q.drainInto { out.add(it) }
        assertEquals(listOf(1, 2), out)
    }

    @Test
    fun `takeOrNull times out on empty queue`() {
        val q = BoundedSendQueue<Int>()
        val start = System.nanoTime()
        assertNull(q.takeOrNull(50))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("returned too early: ${elapsedMs}ms", elapsedMs >= 40)
    }

    @Test
    fun `takeOrNull unblocks when item arrives`() {
        val q = BoundedSendQueue<Int>()
        Executors.newSingleThreadExecutor().execute {
            Thread.sleep(30)
            q.enqueue(42)
        }
        assertEquals(42, q.takeOrNull(2_000))
    }

    @Test
    fun `concurrent producers single consumer smoke - no lost accepted items and fifo per stream`() {
        val q = BoundedSendQueue<Int>(capacity = 64)
        val producerCount = 4
        val perProducer = 500
        val pool = Executors.newFixedThreadPool(producerCount)
        val done = CountDownLatch(producerCount)

        repeat(producerCount) { p ->
            pool.execute {
                var enqueued = 0
                while (enqueued < perProducer) {
                    when (val r = q.enqueue(p * perProducer + enqueued)) {
                        is EnqueueResult.Enqueued -> enqueued++
                        is EnqueueResult.Rejected -> Thread.yield() // backpressure observed, retry
                    }
                }
                done.countDown()
            }
        }

        val consumed = Collections.synchronizedList(ArrayList<Int>())
        val consumer = Executors.newSingleThreadExecutor().submit<Int> {
            while (!done.await(1, TimeUnit.MILLISECONDS) || q.size > 0 || consumed.size < producerCount * perProducer) {
                q.drainInto { consumed.add(it) }
            }
            q.drainInto { consumed.add(it) }
            0
        }

        assertTrue(done.await(20, TimeUnit.SECONDS))
        // Give the consumer a moment to finish draining the tail.
        val deadline = System.currentTimeMillis() + 5_000
        while (consumed.size < producerCount * perProducer && System.currentTimeMillis() < deadline) {
            q.drainInto { consumed.add(it) }
            Thread.sleep(1)
        }
        consumer.cancel(true)

        assertEquals(producerCount * perProducer, consumed.size)
        // Global uniqueness: no accepted item was ever dropped or duplicated.
        assertEquals(consumed.size, consumed.distinct().size)
        // Per-producer FIFO ordering held.
        for (p in 0 until producerCount) {
            val streamValues = consumed.filter { it / perProducer == p }
            assertEquals((0 until perProducer).toList(), streamValues.map { it % perProducer })
        }
        pool.shutdownNow()
    }

    @Test
    fun `default capacity is documented value 64`() {
        assertEquals(64, BoundedSendQueue<String>().capacity)
        assertEquals(64, BoundedSendQueue.DEFAULT_CAPACITY)
    }
}
