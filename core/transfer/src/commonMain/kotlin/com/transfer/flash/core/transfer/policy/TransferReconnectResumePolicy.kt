package com.transfer.flash.core.transfer.policy

import com.transfer.flash.core.transfer.concurrent.PlatformLock
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState

/**
 * Decides which outbound transfers to restart when a peer's session comes back (ERROR-035).
 *
 * ## The gap this closes
 *
 * Byte-accurate resume already worked. `RealFlashTransferRepository.resumeTransfer` accepts a
 * `Failed` transfer as well as a `Paused` one, and `relaunchSend` reproduces the original
 * `wireFileId` and `sourceUri` exactly so the receiver treats the re-offer as a resume of the session
 * it already has and keeps the chunks it already verified. **Nothing ever called it.** A send killed
 * by a Wi-Fi roam went to `Failed` with an error message and stayed there until a human noticed and
 * tapped retry — on a device that walks between mesh APs mid-transfer, which is the reported field
 * condition, that is every transfer.
 *
 * ## Why it is capped, and why the cap resets on progress
 *
 * A resume that fails for a reason unrelated to the network — the source file was deleted, a content
 * URI permission lapsed, storage filled — fails again immediately and lands back in `Failed`. Session
 * up/down edges are plentiful on a bad link, so an uncapped rule turns that into an unbounded retry
 * loop that burns battery and floods the peer with re-offers.
 *
 * The cap is per transfer and counts only attempts that achieved **nothing**: each attempt records
 * `bytesDone`, and an attempt that later turns out to have moved that number past its recorded value
 * clears the count. So a 2 GB file crossing ten APs resumes ten times, while a transfer whose source
 * is genuinely gone gets [maxAttemptsPerTransfer] tries and is then left alone for the user to deal
 * with. Progress, not elapsed time, is the thing worth spending attempts on.
 *
 * Pure and guarded by [PlatformLock]; no coroutines, Android types, or repository reference. The
 * caller polls it on the session-up edge and issues the returned ids to `resumeTransfer`.
 */
public class TransferReconnectResumePolicy(
    private val maxAttemptsPerTransfer: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private class Attempts(var count: Int, var bytesDoneAtLastAttempt: Long)

    private val lock = PlatformLock()
    private val attempts = mutableMapOf<String, Attempts>()

    /**
     * Transfers to hand to `resumeTransfer` now, in [transfers] order. Records one attempt against
     * each id returned, so calling this twice for the same session-up edge spends two attempts —
     * call it once per edge.
     *
     * @param peerDeviceId the peer whose session just came up. Transfers to other peers are left
     *   alone: their sessions are not up, so a resume would only fail and spend an attempt.
     */
    public fun onPeerSessionUp(
        peerDeviceId: String,
        transfers: List<FlashTransfer>,
    ): List<FlashTransferId> = lock.withLock {
        val eligible = transfers.filter { transfer -> isEligible(transfer, peerDeviceId) }
        eligible.mapNotNull { transfer ->
            val record = attempts.getOrPut(transfer.id.value) {
                Attempts(count = 0, bytesDoneAtLastAttempt = transfer.bytesDone)
            }
            // An attempt that moved bytes earned the next one. Checked here rather than on a progress
            // callback so the policy needs no subscription to the repository.
            if (transfer.bytesDone > record.bytesDoneAtLastAttempt) {
                record.count = 0
            }
            if (record.count >= maxAttemptsPerTransfer) return@mapNotNull null
            record.count += 1
            record.bytesDoneAtLastAttempt = transfer.bytesDone
            transfer.id
        }
    }

    /**
     * Drops bookkeeping for every transfer not in [liveIds]. Call with the same snapshot passed to
     * [onPeerSessionUp] so the map cannot outgrow the repository's own list.
     *
     * There is deliberately no way to refill one transfer's budget by hand. A user who fixes the
     * underlying problem and taps retry gets the reset for free: the next attempt sees `bytesDone`
     * past its recorded mark and clears the count.
     */
    public fun retainOnly(liveIds: Set<FlashTransferId>) {
        val live = liveIds.mapTo(mutableSetOf()) { id -> id.value }
        lock.withLock { attempts.keys.retainAll(live) }
    }

    /** Attempts currently charged against [transferId]; for tests and diagnostics. */
    public fun attemptsFor(transferId: FlashTransferId): Int =
        lock.withLock { attempts[transferId.value]?.count ?: 0 }

    /**
     * Outbound, failed, addressed to this peer, and re-openable.
     *
     * `Paused` is deliberately excluded: a pause is a user decision, and resuming it because the
     * network hiccuped would override them. A null [FlashTransfer.sourceUri] is excluded because
     * `relaunchSend` has nothing to read — `fileName` is a display label, not a source.
     */
    private fun isEligible(transfer: FlashTransfer, peerDeviceId: String): Boolean =
        transfer.direction == FlashTransferDirection.Sending &&
            transfer.state == FlashTransferState.Failed &&
            transfer.peerDeviceId == peerDeviceId &&
            !transfer.sourceUri.isNullOrBlank()

    public companion object {
        /**
         * 3. Enough to ride out a burst of roams during one transfer, few enough that a broken source
         * stops re-offering within a few seconds of session churn instead of forever.
         */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 3
    }
}
