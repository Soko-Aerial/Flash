package com.transfer.flash.core.common.time

/**
 * Epoch milliseconds from the platform's wall clock.
 *
 * Phase 06 seam for [SystemTimeSource]. `System.currentTimeMillis()` is a `java.lang`
 * call, so it cannot live in `commonMain`; `kotlin.time.Clock` would avoid the seam but
 * is still `@ExperimentalTime` in Kotlin 2.2.10 and this module's declarations reach the
 * published ABI, so the stable `expect`/`actual` is used instead.
 *
 * D1 = B (strict `commonMain`, iOS in scope) rules out a shared `jvmAndAndroidMain`, so
 * the one-line `actual` is duplicated in `androidMain` and `jvmMain` on purpose.
 */
internal expect fun currentTimeMillisPlatform(): Long
