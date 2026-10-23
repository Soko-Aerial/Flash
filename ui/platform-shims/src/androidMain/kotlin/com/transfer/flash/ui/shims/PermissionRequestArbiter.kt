package com.transfer.flash.ui.shims

import kotlinx.coroutines.CompletableDeferred

/**
 * Runs at most one system permission dialog at a time and answers every waiting caller exactly once.
 *
 * Why this exists: `rememberLauncherForActivityResult(RequestPermission())` has ONE callback and no
 * request id, so it can only serve one request at a time. The old shim held the pending continuation in
 * a single field, and a second `ensureGranted` raised while the first dialog was up overwrote it — the
 * first caller was suspended for ever and the second caller's continuation was resumed by whichever
 * dialog answered first, i.e. possibly with the answer to the OTHER permission.
 *
 * The queue is deliberately passive: [ensure] enqueues, [onResult] reports the one dialog that was up,
 * and the next request is raised only afterwards. A caller that goes away before its turn is dropped
 * without raising anything; one that goes away while its own dialog is up keeps its place until the
 * answer arrives, so the next request cannot be launched on top of a dialog the system still owns.
 *
 * Pure Kotlin (no `android.*`), so the concurrency rules are unit-tested on the host JVM.
 */
internal class PermissionRequestArbiter {

    private class Waiter(
        val permission: String,
        val isGranted: (String) -> Boolean,
        val launch: (String) -> Unit,
    ) {
        val result = CompletableDeferred<Boolean>()
    }

    private val lock = Any()
    private val waiting = ArrayDeque<Waiter>()

    /** The request whose dialog is up, if any. Guarded by [lock]. */
    private var inFlight: Waiter? = null

    /**
     * Answers whether [permission] is now granted, raising the system dialog if it is not.
     *
     * @param isGranted re-read before every dialog, so a request that a previous answer already
     *   satisfied (or that the user granted in Settings meanwhile) never asks twice.
     * @param launch raises the dialog and returns immediately; the answer comes back via [onResult].
     */
    suspend fun ensure(
        permission: String,
        isGranted: (String) -> Boolean,
        launch: (String) -> Unit,
    ): Boolean {
        if (isGranted(permission)) return true
        val waiter = Waiter(permission, isGranted, launch)
        synchronized(lock) { waiting.addLast(waiter) }
        pump()
        try {
            return waiter.result.await()
        } finally {
            // Still queued: the caller is gone before its turn, so drop the request rather than raise
            // a dialog nobody is waiting for. Already launched: it keeps its place until onResult, so
            // the next dialog cannot be raised on top of the one the system still owns.
            synchronized(lock) { if (inFlight !== waiter) waiting.remove(waiter) }
        }
    }

    /**
     * Reports the answer of the one dialog that was up. A result with nothing in flight (a delivery
     * after the Activity was recreated, or a duplicate) is ignored.
     */
    fun onResult(granted: Boolean) {
        val finished = synchronized(lock) { inFlight.also { inFlight = null } } ?: return
        finished.result.complete(granted)
        pump()
    }

    /** Starts the next dialog, skipping requests whose permission is already granted or already dead. */
    private fun pump() {
        while (true) {
            val next = takeNext() ?: return
            if (next.isGranted(next.permission)) {
                next.result.complete(true)
                clearInFlight(next)
                continue
            }
            val failure = try {
                next.launch(next.permission)
                null
            } catch (t: Throwable) {
                t
            }
            if (failure != null) {
                // The launcher can refuse when the host Activity is gone; free the queue instead of
                // leaving every later caller blocked behind a dialog that was never raised.
                clearInFlight(next)
                next.result.completeExceptionally(failure)
                continue
            }
            return
        }
    }

    private fun takeNext(): Waiter? = synchronized(lock) {
        if (inFlight != null) return@synchronized null
        var candidate = waiting.removeFirstOrNull()
        while (candidate != null && !candidate.result.isActive) candidate = waiting.removeFirstOrNull()
        candidate?.also { inFlight = it }
    }

    private fun clearInFlight(waiter: Waiter) {
        synchronized(lock) { if (inFlight === waiter) inFlight = null }
    }
}
