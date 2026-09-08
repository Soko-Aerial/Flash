package com.transfer.flash.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Rate-limits a hot state flow to at most one value per [windowMs], **leading edge first**.
 *
 * Semantics, in the order they matter:
 * - **The first value is emitted immediately**, not one window late. A trailing-edge operator
 *   (kotlinx's `sample`) would leave a composable that collects this with nothing to show for up to a
 *   window — which for a screen that reports on this device's own state is the ERROR-034 failure mode.
 * - **After a value is emitted the window is closed for [windowMs], with upstream *suspended*** rather
 *   than buffered. That is what makes it cheap: the intended callers are rooted in a `StateFlow`, which
 *   conflates while its collector is parked, so intermediate operators do not run either.
 * - **The newest value always eventually arrives**, because it is simply the last one written.
 *
 * A non-positive [windowMs] disables throttling entirely, which keeps the operator honest to test.
 *
 * ### Why this is a second copy
 * `:core:messaging` has the same operator, `internal`, for the same reason on the chat side. There is
 * deliberately no shared home:
 * - `:core:common` is the only module both `:app` and `:core:messaging` depend on, and it has **no
 *   coroutines dependency at all** — it is the live Phase-06 KMP pilot, and adding one to its
 *   `commonMain` to host eight lines is not a trade worth making.
 * - Widening `:core:messaging`'s copy to `public` would put a generic flow operator into a **published
 *   library ABI** to serve an app-internal need, permanently.
 *
 * So: two copies, each with its own test. If you change the semantics of one, change the other.
 */
internal fun <T> Flow<T>.throttleLatest(windowMs: Long): Flow<T> = flow {
    if (windowMs <= 0L) {
        collect { emit(it) }
        return@flow
    }
    collect { value ->
        emit(value)
        delay(windowMs)
    }
}
