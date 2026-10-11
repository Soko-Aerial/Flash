package com.transfer.flash.core.common.perf

import com.transfer.flash.core.common.concurrent.PlatformLock

/**
 * Thread-safe bounded buffer pool for 16KB and 32KB byte arrays.
 *
 * Avoids allocating in ART Large Object Space (LOS) on Android during hot network I/O
 * (e.g. WebSocket frame decoding, chunk framing) which triggers severe GC churn
 * and pauses on low-memory devices (BelFone SCP810).
 */
public object ByteArrayPool {
    public const val SIZE_16KB: Int = 16 * 1024
    public const val SIZE_32KB: Int = 32 * 1024
    public const val MAX_POOL_CAPACITY: Int = 8

    private val lock: PlatformLock = PlatformLock()
    private val pool16k: ArrayList<ByteArray> = ArrayList(MAX_POOL_CAPACITY)
    private val pool32k: ArrayList<ByteArray> = ArrayList(MAX_POOL_CAPACITY)

    init {
        MemoryGovernor.registerListener { level ->
            if (level == MemoryTrimLevel.RUNNING_LOW ||
                level == MemoryTrimLevel.RUNNING_CRITICAL ||
                level == MemoryTrimLevel.COMPLETE
            ) {
                clear()
            }
        }
    }

    /**
     * Acquires a buffer of at least [minCapacity] bytes.
     * Buffers up to 16KB return a 16KB buffer from the pool if available.
     * Buffers between 16KB and 32KB return a 32KB buffer from the pool if available.
     * Requests over 32KB allocate a new unpooled ByteArray.
     */
    public fun acquire(minCapacity: Int): ByteArray {
        if (minCapacity <= 0) return ByteArray(0)
        return if (minCapacity <= SIZE_16KB) {
            lock.withLock {
                if (pool16k.isNotEmpty()) pool16k.removeAt(pool16k.size - 1) else null
            } ?: ByteArray(SIZE_16KB)
        } else if (minCapacity <= SIZE_32KB) {
            lock.withLock {
                if (pool32k.isNotEmpty()) pool32k.removeAt(pool32k.size - 1) else null
            } ?: ByteArray(SIZE_32KB)
        } else {
            ByteArray(minCapacity)
        }
    }

    /**
     * Returns a buffer to the pool. Discards buffers whose sizes do not match 16KB or 32KB,
     * or if the respective pool has reached [MAX_POOL_CAPACITY].
     */
    public fun release(buffer: ByteArray) {
        when (buffer.size) {
            SIZE_16KB -> lock.withLock {
                if (pool16k.size < MAX_POOL_CAPACITY) {
                    pool16k.add(buffer)
                }
            }
            SIZE_32KB -> lock.withLock {
                if (pool32k.size < MAX_POOL_CAPACITY) {
                    pool32k.add(buffer)
                }
            }
        }
    }

    /**
     * Clears all cached buffers.
     */
    public fun clear() {
        lock.withLock {
            pool16k.clear()
            pool32k.clear()
        }
    }
}

/**
 * Executes [block] with a rented [ByteArray] of at least [minCapacity], releasing it afterwards.
 */
public inline fun <R> ByteArrayPool.use(minCapacity: Int, block: (ByteArray) -> R): R {
    val buffer = acquire(minCapacity)
    return try {
        block(buffer)
    } finally {
        release(buffer)
    }
}
