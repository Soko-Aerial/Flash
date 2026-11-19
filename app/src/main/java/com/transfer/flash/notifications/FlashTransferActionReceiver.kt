package com.transfer.flash.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.debug.DiscoveryEngineHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles notification action button clicks for file transfers (Cancel / Resume / Accept / Decline)
 * via explicit broadcast, ensuring reliable delivery even when the app or background
 * service is not foregrounded.
 *
 * Uses [goAsync] with an explicit [ACTION_TIMEOUT_MS] ceiling so cold-start engine initialization
 * never exceeds Android's ~10s BroadcastReceiver ANR window.
 */
class FlashTransferActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return
        Log.i(TAG, "Received transfer action: $action for transfer $transferId")

        val pendingResult = goAsync()
        // One scope per broadcast, cancelled when it finishes (audit B-10): a receiver instance is short-lived,
        // so a scope kept in a field was never cancelled.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val appContext = context.applicationContext
                val completed = withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                    var repo = DiscoveryEngineHolder.currentTransfers()
                    if (repo == null) {
                        runCatching { DiscoveryEngineHolder.ensureStarted(appContext) }
                        repo = DiscoveryEngineHolder.currentTransfers()
                    }
                    if (repo == null) {
                        // Nothing was done, so nothing is cleared: the notification stays and the button can be pressed again.
                        Log.w(TAG, "No transfer repository found for $action on $transferId")
                        return@withTimeoutOrNull
                    }

                    // The notification is cleared AFTER the work, and only if the work did not throw (audit B-10).
                    // A refused result (stale offer, unknown row) means the notification is out of date, so it goes too.
                    val done = runCatching {
                        when (action) {
                            ACTION_CANCEL_TRANSFER -> {
                                val row = repo.activeTransfers.value.find { it.id.value == transferId }
                                if (row?.groupOrigin == true) {
                                    // A stale button from before the origin cancel left the notification (audit B-04).
                                    Log.w(TAG, "Refusing to cancel group file $transferId from a notification")
                                    return@runCatching false
                                }
                                repo.cancelTransfer(FlashTransferId(transferId))
                                true
                            }
                            ACTION_RESUME_TRANSFER -> {
                                repo.resumeTransfer(FlashTransferId(transferId))
                                false
                            }
                            ACTION_ACCEPT_TRANSFER -> {
                                repo.acceptIncoming(FlashTransferId(transferId))
                                true
                            }
                            ACTION_DECLINE_TRANSFER -> {
                                repo.declineIncoming(FlashTransferId(transferId))
                                true
                            }
                            else -> {
                                Log.w(TAG, "Unknown action: $action")
                                false
                            }
                        }
                    }.onFailure { Log.w(TAG, "Failed to run $action on transfer $transferId; notification kept", it) }
                    if (done.getOrDefault(false)) {
                        FlashNotificationManager.clearTransfer(appContext, transferId)
                    }
                }
                if (completed == null) {
                    Log.w(TAG, "Timed out waiting for $action on transfer $transferId before ANR window")
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "XFERACT"
        internal const val ACTION_TIMEOUT_MS = 8_000L
        const val ACTION_CANCEL_TRANSFER = "com.transfer.flash.action.CANCEL_TRANSFER"
        const val ACTION_RESUME_TRANSFER = "com.transfer.flash.action.RESUME_TRANSFER"
        const val ACTION_ACCEPT_TRANSFER = "com.transfer.flash.action.ACCEPT_TRANSFER"
        const val ACTION_DECLINE_TRANSFER = "com.transfer.flash.action.DECLINE_TRANSFER"
        const val EXTRA_TRANSFER_ID = "extra_transfer_id"
    }
}
