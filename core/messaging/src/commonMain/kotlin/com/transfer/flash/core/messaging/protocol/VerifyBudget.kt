package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.concurrent.SyncMap

/**
 * A per-peer cap on signature verifications (ADR-044 V1, plan D5.8).
 *
 * A signature check costs real CPU on a phone, and a hostile paired peer can send bundles full of
 * certs that fail verification for as long as it likes. Each peer therefore gets
 * [perWindow] verifications per [windowMs]; a frame that would exceed it is dropped unread.
 * Fixed windows, not a sliding log: the point is a bound, not a fair schedule.
 *
 * Read-modify-write on a [SyncMap] is not atomic, so two threads racing on one peer can
 * over-admit by a few verifications. That is harmless for a CPU bound and avoids a global lock on
 * the receive path.
 */
@OptIn(FlashInternalApi::class)
internal class VerifyBudget(
    private val perWindow: Int = GroupPolicy.BUNDLE_VERIFICATIONS_PER_WINDOW,
    private val windowMs: Long = GroupPolicy.VERIFY_WINDOW_MS,
) {
    private class Window(val startedAtMs: Long, val used: Int)

    private val windows = SyncMap<String, Window>()

    /** Reserves [count] verifications for [peerId]; false (and nothing reserved) when it does not fit. */
    fun tryConsume(peerId: String, count: Int, nowMs: Long): Boolean {
        if (count <= 0) return true
        val current = windows[peerId]?.takeIf { nowMs - it.startedAtMs < windowMs }
        val used = current?.used ?: 0
        if (used + count > perWindow) return false
        windows[peerId] = Window(current?.startedAtMs ?: nowMs, used + count)
        return true
    }
}
