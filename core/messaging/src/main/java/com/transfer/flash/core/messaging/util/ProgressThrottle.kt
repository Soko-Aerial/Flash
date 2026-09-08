package com.transfer.flash.core.messaging.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Rate-limits a hot state flow to at most one value per [windowMs], **leading edge first**.
 *
 * The transfer layer publishes progress on a 10 ms watcher tick — 100 emissions a second, for the
 * whole duration of a transfer. That cadence is right for the sender's own bookkeeping and wrong for
 * anything that re-derives a screen from it: joining live progress onto a conversation re-maps every
 * row in the open thread (a `FlashMessageUi` per message, quoted-reply lookups, reaction grouping,
 * a timestamp format per message, two whole-list copies for grouping) and then deep-compares the
 * result. At 100 Hz that is tens of thousands of short-lived objects a second on a device that may
 * have 2 GB of RAM, to move a progress bar that cannot draw faster than the display refreshes.
 *
 * Semantics, in the order they matter:
 * - **The first value is emitted immediately.** A trailing-edge operator (`sample`) would delay it
 *   by a whole window, and this flow feeds a `combine` that cannot emit until every input has — so
 *   a trailing edge would hold the entire conversation blank on open. That is the ERROR-034 failure
 *   mode and is not acceptable to trade for allocation count.
 * - **After a value is emitted, the window is closed for [windowMs].** Upstream is *suspended*, not
 *   buffered, for that time.
 * - **The newest value always eventually arrives.** Suspending upstream is what makes this cheap:
 *   the intended callers are rooted in a `StateFlow`, which conflates while its collector is parked,
 *   so an upstream `map` in between does not run either — the values that would have been computed
 *   and thrown away are never computed. Terminal states (a completed transfer, a stamped path) are
 *   the last value written, so they are what the next window delivers.
 *
 * That last point is a real precondition. Against a *buffered* upstream the operator still emits
 * every value and never emits a wrong one, but it would emit them one window apart and lag behind
 * rather than skip ahead. Every wiring of `attachmentProgress` is StateFlow-rooted; keep it that way.
 *
 * A non-positive [windowMs] disables throttling entirely, which keeps the operator honest to test.
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
