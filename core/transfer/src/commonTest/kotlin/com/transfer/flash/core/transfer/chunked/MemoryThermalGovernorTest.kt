package com.transfer.flash.core.transfer.chunked

import com.transfer.flash.core.common.perf.DefaultThermalGovernor
import com.transfer.flash.core.common.perf.FlashThermalStatus
import com.transfer.flash.core.common.perf.MemoryGovernor
import com.transfer.flash.core.common.perf.MemoryTrimLevel
import com.transfer.flash.core.common.perf.ThermalGovernor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryThermalGovernorTest {

    @AfterTest
    fun tearDown() {
        ChunkBufferPool.clear()
        ThermalGovernor.setInstance(DefaultThermalGovernor)
    }

    @Test
    fun bufferPool_acquiresAndReusesExactBuffer() {
        val size = 64 * 1024
        val b1 = ChunkBufferPool.acquire(size)
        assertEquals(size, b1.size)

        ChunkBufferPool.release(b1)
        assertEquals(1, ChunkBufferPool.pooledCount(size))

        val b2 = ChunkBufferPool.acquire(size)
        assertSame(b1, b2, "Acquired buffer must be the exact recycled instance from the pool")
        assertEquals(0, ChunkBufferPool.pooledCount(size))
    }

    @Test
    fun bufferPool_clearsOnMemoryTrimRunningLow() {
        val size = 32 * 1024
        val b1 = ChunkBufferPool.acquire(size)
        ChunkBufferPool.release(b1)
        assertEquals(1, ChunkBufferPool.pooledCount(size))

        MemoryGovernor.notifyTrim(MemoryTrimLevel.RUNNING_LOW)
        assertEquals(0, ChunkBufferPool.pooledCount(size), "Buffer pool must be purged on RUNNING_LOW")
    }

    @Test
    fun bufferPool_clearsOnMemoryTrimRunningCritical() {
        val size = 128 * 1024
        val b1 = ChunkBufferPool.acquire(size)
        ChunkBufferPool.release(b1)
        assertEquals(1, ChunkBufferPool.pooledCount(size))

        MemoryGovernor.notifyTrim(MemoryTrimLevel.RUNNING_CRITICAL)
        assertEquals(0, ChunkBufferPool.pooledCount(size), "Buffer pool must be purged on RUNNING_CRITICAL")
    }

    @Test
    fun bufferPool_boundsMaxPooledArrays() {
        val size = 16 * 1024
        val buffers = List(15) { ChunkBufferPool.acquire(size) }
        for (b in buffers) {
            ChunkBufferPool.release(b)
        }
        // Pool is capped at 8 per size
        assertTrue(ChunkBufferPool.pooledCount(size) <= 8, "Pool size must be bounded")
    }

    @Test
    fun sha256_inPlaceMatchesCopyOfRange() {
        val fullData = ByteArray(100) { (it * 3).toByte() }
        val offset = 20
        val length = 50

        val inPlaceHash = Sha256.digest(fullData, offset, length)
        val copyHash = Sha256.digest(fullData.copyOfRange(offset, offset + length))

        assertTrue(
            Sha256.rawEqualsConstantTime(inPlaceHash, copyHash),
            "In-place SHA-256 digest must match slice digest",
        )
    }

    @Test
    fun thermalGovernor_notifiesStatusChanges() {
        var observedStatus: FlashThermalStatus? = null
        val testGovernor = object : ThermalGovernor {
            private var _status = FlashThermalStatus.NONE
            private val listeners = mutableListOf<(FlashThermalStatus) -> Unit>()

            override val status: FlashThermalStatus get() = _status

            fun setStatus(next: FlashThermalStatus) {
                _status = next
                for (l in listeners) l(next)
            }

            override fun registerListener(listener: (FlashThermalStatus) -> Unit) {
                listeners.add(listener)
            }

            override fun unregisterListener(listener: (FlashThermalStatus) -> Unit) {
                listeners.remove(listener)
            }
        }

        ThermalGovernor.setInstance(testGovernor)
        ThermalGovernor.get().registerListener { observedStatus = it }

        assertEquals(FlashThermalStatus.NONE, ThermalGovernor.get().status)

        testGovernor.setStatus(FlashThermalStatus.SEVERE)
        assertEquals(FlashThermalStatus.SEVERE, ThermalGovernor.get().status)
        assertEquals(FlashThermalStatus.SEVERE, observedStatus)
    }
}
