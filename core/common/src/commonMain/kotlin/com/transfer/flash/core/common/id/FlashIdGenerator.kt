package com.transfer.flash.core.common.id

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Injectable identifier generator (C0.4). Consumers depend on this interface;
 * production wiring uses [UuidIdGenerator], tests inject a deterministic fake.
 *
 * `@FlashInternalApi` rather than plain `public`: cross-module visible without
 * widening the published library ABI (CONVENTIONS.md R7).
 */
@FlashInternalApi
public interface FlashIdGenerator {
    /** Returns a fresh, unique identifier string. */
    public fun newId(): String
}

/**
 * UUID v4 generator. Production default.
 *
 * The object itself stays in `commonMain`; only the random-UUID call is a seam
 * ([randomUuidString]). `java.util.UUID` exists on both Android and desktop JVM but not on
 * Kotlin/Native, and D1 = B rules out a shared `jvmAndAndroidMain` shortcut, so each target
 * supplies its own `actual`. Keeping the object common (rather than making it an
 * `expect object` per target) leaves its published FQN and shape untouched — cross-module
 * callers such as `PairingCoordinator` were unaffected by the Phase 06 conversion.
 * Call sites should still depend on [FlashIdGenerator], never on this object.
 */
@FlashInternalApi
public object UuidIdGenerator : FlashIdGenerator {
    override fun newId(): String = randomUuidString()
}
