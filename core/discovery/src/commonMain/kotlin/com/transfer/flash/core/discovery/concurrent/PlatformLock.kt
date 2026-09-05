package com.transfer.flash.core.discovery.concurrent

/**
 * The smallest mutual-exclusion primitive that works on every target.
 *
 * Phase 08 seam, a deliberate **duplicate** of `:core:common`'s `PlatformLock`.
 * `CompositeDiscovery` holds 18 `synchronized(lock)` blocks and `kotlin.synchronized` is
 * JVM-only, so the class could not move to `commonMain` as written.
 *
 * It is copied rather than reused because `:core:common`'s copy is `internal` by an
 * explicit Phase 06 decision ("module-private plumbing, not published API"), and
 * `internal` does not cross a Gradle module boundary. Promoting it to `public` would add
 * a lock to `core-common`'s published ABI under `explicitApi()` and would mean editing a
 * second module's source in this phase — both out of scope here (R1, R4, R7). See the
 * Known issues in the Phase 08 log entry: a dedicated phase should hoist one
 * `@FlashInternalApi` lock before phases 09–12 make further copies.
 *
 * `withLock` is **not** `inline`: an `expect class` member cannot be. Two consequences,
 * both checked at every converted call site — a non-local `return` inside the block must
 * become `return@withLock`, and no `suspend` call may appear inside one.
 */
internal expect class PlatformLock() {
    /** Runs [block] while holding the lock, then releases it. */
    fun <T> withLock(block: () -> T): T
}
