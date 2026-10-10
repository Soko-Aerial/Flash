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
import kotlinx.coroutines.launch

/**
 * Handles notification action button clicks for file transfers (Cancel / Resume / Accept / Decline)
 * via explicit broadcast, ensuring reliable delivery even when the app or background
 * service is not foregrounded.
 *
 * Uses [goAsync] to guarantee Android OS does not kill or freeze the receiver process
 * while the asynchronous repository operations are completing.
 */
class FlashTransferActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return
        Log.i(TAG, "Received transfer action: $action for transfer $transferId")

        val pendingResult = goAsync()
        scope.launch {
            try {
                var repo = DiscoveryEngineHolder.currentTransfers()
                if (repo == null) {
                    runCatching { DiscoveryEngineHolder.ensureStarted(context.applicationContext) }
                    repo = DiscoveryEngineHolder.currentTransfers()
                }

                when (action) {
                    ACTION_CANCEL_TRANSFER -> {
                        FlashNotificationManager.clearTransfer(context, transferId)
                        if (repo != null) {
                            runCatching {
                                repo.cancelTransfer(FlashTransferId(transferId))
                            }.onFailure { Log.w(TAG, "Failed to cancel transfer $transferId", it) }
                        } else {
                            Log.w(TAG, "No transfer repository found to cancel $transferId")
                        }
                    }
                    ACTION_RESUME_TRANSFER -> {
                        if (repo != null) {
                            runCatching {
                                repo.resumeTransfer(FlashTransferId(transferId))
                            }.onFailure { Log.w(TAG, "Failed to resume transfer $transferId", it) }
                        } else {
                            Log.w(TAG, "No transfer repository found to resume $transferId")
                        }
                    }
                    ACTION_ACCEPT_TRANSFER -> {
                        FlashNotificationManager.clearTransfer(context, transferId)
                        if (repo != null) {
                            runCatching {
                                repo.acceptIncoming(FlashTransferId(transferId))
                            }.onFailure { Log.w(TAG, "Failed to accept transfer $transferId", it) }
                        } else {
                            Log.w(TAG, "No transfer repository found to accept $transferId")
                        }
                    }
                    ACTION_DECLINE_TRANSFER -> {
                        FlashNotificationManager.clearTransfer(context, transferId)
                        if (repo != null) {
                            runCatching {
                                repo.declineIncoming(FlashTransferId(transferId))
                            }.onFailure { Log.w(TAG, "Failed to decline transfer $transferId", it) }
                        } else {
                            Log.w(TAG, "No transfer repository found to decline $transferId")
                        }
                    }
                    else -> Log.w(TAG, "Unknown action: $action")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "XFERACT"
        const val ACTION_CANCEL_TRANSFER = "com.transfer.flash.action.CANCEL_TRANSFER"
        const val ACTION_RESUME_TRANSFER = "com.transfer.flash.action.RESUME_TRANSFER"
        const val ACTION_ACCEPT_TRANSFER = "com.transfer.flash.action.ACCEPT_TRANSFER"
        const val ACTION_DECLINE_TRANSFER = "com.transfer.flash.action.DECLINE_TRANSFER"
        const val EXTRA_TRANSFER_ID = "extra_transfer_id"
    }
}
