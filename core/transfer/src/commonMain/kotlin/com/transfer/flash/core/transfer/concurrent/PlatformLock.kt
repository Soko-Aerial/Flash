package com.transfer.flash.core.transfer.concurrent

/**
 * The smallest mutual-exclusion primitive that works on every target.
 *
 * Phase 13B-1 seam, and the **fourth** copy of this class — after `:core:common` (Phase 06),
 * `:core:discovery` (Phase 08) and `:core:engine` (Phase 12). CONVENTIONS.md R2 predicted this
 * one by name ("Phases 13-16 will likely want a fourth copy").
 *
 * `RollingRateMeter` guarded all three of its methods with `@Synchronized`, which resolves to
 * `kotlin.jvm.Synchronized` and is JVM-only, so `multistream/MultiStreamProgress.kt` could not
 * move to `commonMain` as written. No import line reveals that pin — it is one of the two
 * stdlib traps R6.1 exists to catch, and `compileKotlinJvm` would have accepted the file
 * unchanged.
 *
 * It is copied rather than reused because `:core:common`'s copy is `internal` by an explicit
 * Phase 06 decision ("module-private plumbing, not published API"), and `internal` does not
 * cross a Gradle module boundary. Promoting it to `public` would add a lock to `core-common`'s
 * published ABI under `explicitApi()` and would mean editing a second module's source and build
 * file in this phase — both out of scope here (R1, R4, R7).
 *
 * The Phase 08 log asked for a dedicated phase to hoist one `@FlashInternalApi` lock "before
 * phases 09–12 make further copies"; Phase 12 re-filed that as a Known issue when it made the
 * third. This is the fourth, and there is still no phase in the plan that performs the hoist.
 *
 * `withLock` is **not** `inline`: an `expect class` member cannot be. Two consequences, both
 * checked at every converted call site — a non-local `return` inside the block must become
 * `return@withLock`, and no `suspend` call may appear inside one.
 */
internal expect class PlatformLock() {
    /** Runs [block] while holding the lock, then releases it. */
    fun <T> withLock(block: () -> T): T
}
