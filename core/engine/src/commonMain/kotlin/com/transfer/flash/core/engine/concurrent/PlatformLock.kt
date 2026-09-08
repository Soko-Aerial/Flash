package com.transfer.flash.core.engine.concurrent

/**
 * The smallest mutual-exclusion primitive that works on every target.
 *
 * Phase 12 seam, and the **third** copy of this class — after `:core:common` (Phase 06) and
 * `:core:discovery` (Phase 08). `AutoConnectGate` guarded both of its methods with
 * `@Synchronized`, which resolves to `kotlin.jvm.Synchronized` and is JVM-only, so the class
 * could not move to `commonMain` as written. It is the only production file in this module
 * that can reach `commonMain` at all, so without this seam the conversion would leave
 * `commonMain` empty and `compileKotlinJvm` would certify nothing (CONVENTIONS.md R2 step 1
 * vs. step 2 — step 2 was chosen; see the Phase 12 log entry).
 *
 * It is copied rather than reused because `:core:common`'s copy is `internal` by an explicit
 * Phase 06 decision ("module-private plumbing, not published API"), and `internal` does not
 * cross a Gradle module boundary. Promoting it to `public` would add a lock to
 * `core-common`'s published ABI under `explicitApi()` and would mean editing a second
 * module's source and build file in this phase — both out of scope here (R1, R4, R7).
 *
 * The Phase 08 log asked for a dedicated phase to hoist one `@FlashInternalApi` lock
 * "before phases 09–12 make further copies". That did not happen, and this is the further
 * copy it warned about. Re-filed as a Known issue on the Phase 12 entry: the hoist is now
 * three call sites wide and there is no phase in the plan that performs it.
 *
 * `withLock` is **not** `inline`: an `expect class` member cannot be. Two consequences, both
 * checked at every converted call site — a non-local `return` inside the block must become
 * `return@withLock`, and no `suspend` call may appear inside one.
 */
internal expect class PlatformLock() {
    /** Runs [block] while holding the lock, then releases it. */
    fun <T> withLock(block: () -> T): T
}
