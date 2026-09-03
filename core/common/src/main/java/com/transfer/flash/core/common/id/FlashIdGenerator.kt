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
 * UUID v4 generator backed by [java.util.UUID.randomUUID]. Production default.
 *
 * `java.util.UUID` exists on both Android and desktop JVM but not on Kotlin/Native, and
 * D1 = B rules out a shared `jvmAndAndroidMain` shortcut, so under KMP this object moves to
 * `androidMain`/`jvmMain` as the `actual` side of an `expect` declared in `commonMain`.
 * That is why call sites depend on [FlashIdGenerator], never on this object — see Phase 07.
 */
@FlashInternalApi
public object UuidIdGenerator : FlashIdGenerator {
    override fun newId(): String = java.util.UUID.randomUUID().toString()
}
