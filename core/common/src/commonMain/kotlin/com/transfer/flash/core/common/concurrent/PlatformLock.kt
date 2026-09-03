package com.transfer.flash.core.common.concurrent

/**
 * The smallest mutual-exclusion primitive that works on every target.
 *
 * Phase 06 seam. `kotlin.synchronized` is JVM-only, so the three `synchronized(lock)`
 * blocks in `FlashLogger` could not move to `commonMain` as written. This wraps the
 * monitor instead of the call site, which keeps the shared code readable
 * (`lock.withLock { ... }`) and leaves each platform free to pick its own mechanism —
 * Kotlin/Native has no monitors at all and will need a different `actual` in Phase 09.
 *
 * `internal`: this is module-private plumbing, not published API. Anything in the repo
 * that needs a lock across modules should keep using coroutines primitives instead —
 * Phase 05's audit found the codebase already runs on coroutines, with zero `Thread(`,
 * `Executors`, or `AtomicReference` in production source.
 */
internal expect class PlatformLock() {
    /** Runs [block] while holding the lock, then releases it. */
    fun <T> withLock(block: () -> T): T
}
