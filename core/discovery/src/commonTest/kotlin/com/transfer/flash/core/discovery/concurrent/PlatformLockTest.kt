package com.transfer.flash.core.discovery.concurrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Executes [PlatformLock] on **each** target (Phase 08).
 *
 * `CompositeDiscovery` moved to `commonMain` by replacing 18 `synchronized(lock)` blocks with
 * `lock.withLock { }`, so every one of its critical sections now runs through this seam. The 19
 * pre-existing `CompositeDiscoveryTest` cases live in `androidHostTest` and therefore only ever
 * exercise the `androidMain` `actual`; this suite is in `commonTest`, so `jvmTest` runs it too —
 * which is what distinguishes "the desktop lock compiles" from "the desktop lock locks"
 * (CONVENTIONS.md R3.1). A future Kotlin/Native target inherits it and must pass it before its
 * `actual` can be trusted, which matters more there than here: Native has no JVM monitor to
 * delegate to.
 */
class PlatformLockTest {

    @Test
    fun withLock_returns_the_block_value() {
        val lock = PlatformLock()
        assertEquals("value", lock.withLock { "value" })
        assertEquals(7, lock.withLock { 3 + 4 })
    }

    @Test
    fun withLock_releases_the_lock_when_the_block_throws() {
        val lock = PlatformLock()
        assertFailsWith<IllegalStateException> {
            lock.withLock { error("boom") }
        }
        // Would deadlock, or fail here, if the failing block had leaked the lock.
        assertEquals("reacquired", lock.withLock { "reacquired" })
    }

    @Test
    fun withLock_serialises_concurrent_increments() = runTest {
        val lock = PlatformLock()
        var counter = 0
        // Dispatchers.Default is genuinely multi-threaded on both current targets, so an
        // unsynchronised `counter += 1` loses updates here; only real mutual exclusion lands
        // exactly WORKERS * INCREMENTS.
        withContext(Dispatchers.Default) {
            List(WORKERS) {
                launch {
                    repeat(INCREMENTS) {
                        lock.withLock { counter += 1 }
                    }
                }
            }.joinAll()
        }
        assertEquals(WORKERS * INCREMENTS, counter)
    }

    private companion object {
        const val WORKERS = 8
        const val INCREMENTS = 5_000
    }
}
