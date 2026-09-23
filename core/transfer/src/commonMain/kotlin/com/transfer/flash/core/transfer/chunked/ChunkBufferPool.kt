package com.transfer.flash.core.transfer.chunked

import com.transfer.flash.core.common.perf.MemoryGovernor
import com.transfer.flash.core.common.perf.MemoryTrimLevel
import com.transfer.flash.core.common.perf.MemoryTrimListener
import com.transfer.flash.core.transfer.concurrent.PlatformLock

/**
 * High-performance, memory-governed chunk buffer pool for large-file transfers.
 *
 * Prevents GC churn and heap fragmentation on 2GB RAM devices by pooling reusable
 * [ByteArray] chunks up to a bounded capacity. Automatically clears pooled memory
 * upon receiving low memory warnings from [MemoryGovernor].
 */
public object ChunkBufferPool {
    private val lock = PlatformLock()
    private const val DEFAULT_MAX_POOLED_PER_SIZE = 8

    // Map from chunkSize to ArrayDeque of pooled ByteArrays
    private val pools = mutableMapOf<Int, ArrayDeque<ByteArray>>()

    init {
        // Automatically evict buffers on memory trim
        MemoryGovernor.registerListener(object : MemoryTrimListener {
            override fun onTrimMemory(level: MemoryTrimLevel) {
                if (level == MemoryTrimLevel.RUNNING_LOW ||
                    level == MemoryTrimLevel.RUNNING_CRITICAL ||
                    level == MemoryTrimLevel.UI_HIDDEN ||
                    level == MemoryTrimLevel.COMPLETE
                ) {
                    clear()
                }
            }
        })
    }

    /**
     * Borrows a [ByteArray] of exactly [size] bytes. If a matching buffer is
     * available in the pool, it is returned; otherwise, a new buffer is allocated.
     */
    public fun acquire(size: Int): ByteArray {
        require(size > 0) { "size must be > 0" }
        return lock.withLock {
            val deque = pools[size]
            if (deque != null && deque.isNotEmpty()) {
                deque.removeLast()
            } else {
                ByteArray(size)
            }
        }
    }

    /**
     * Returns a [ByteArray] to the pool if there is capacity.
     */
    public fun release(buffer: ByteArray) {
        val size = buffer.size
        lock.withLock {
            val deque = pools.getOrPut(size) { ArrayDeque() }
            if (deque.size < DEFAULT_MAX_POOLED_PER_SIZE) {
                deque.addLast(buffer)
            }
        }
    }

    /**
     * Clears all pooled buffers to free heap memory immediately.
     */
    public fun clear() {
        lock.withLock {
            pools.clear()
        }
    }

    /**
     * Returns current number of pooled buffers for a given [size] (for testing/diagnostics).
     */
    public fun pooledCount(size: Int): Int {
        return lock.withLock {
            pools[size]?.size ?: 0
        }
    }
}
