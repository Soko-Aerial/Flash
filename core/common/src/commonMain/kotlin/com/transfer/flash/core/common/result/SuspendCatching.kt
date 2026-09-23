package com.transfer.flash.core.common.result

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for code that suspends: identical, except that [CancellationException] is rethrown
 * instead of being captured as a failure (audit 2026-09-23, B3).
 *
 * Plain `runCatching` around a suspend call swallows cancellation, so a cancelled coroutine keeps
 * executing past the point where it should have stopped: it logs, releases or sends as if it had not
 * been cancelled, until its next suspension point throws again.
 *
 * (Not a reason: `withTimeoutOrNull { runCatching { … } }` still returns `null` on its own timeout.
 * kotlinx.coroutines discards the block's result in that case; `SuspendCatchingTest` pins it.)
 *
 * Inline so the block may call suspend functions when the caller is suspending.
 */
@FlashInternalApi
public inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
