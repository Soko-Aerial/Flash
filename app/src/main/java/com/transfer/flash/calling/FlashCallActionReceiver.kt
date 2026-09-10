package com.transfer.flash.calling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.transfer.flash.debug.DiscoveryEngineHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * C7 (calling): resolves [com.transfer.flash.core.calling.CallCoordinator] actions
 * fired by the call notification's [android.app.Notification.CallStyle] buttons
 * (answer / decline / hang up).
 *
 * The coordinator lives in-process (DiscoveryEngineHolder), so these actions never
 * leave the app — a broadcast PendingIntent is the standard way to wire CallStyle
 * action buttons.
 */
class FlashCallActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val coordinator = DiscoveryEngineHolder.currentCallCoordinator()
        if (coordinator == null) {
            Log.w(TAG, "No call coordinator — ignoring action ${intent.action}")
            return
        }
        when (intent.action) {
            ACTION_ANSWER -> {
                scope.launch { coordinator.accept() }
                bringAppToFront(context)
            }
            ACTION_DECLINE -> scope.launch { coordinator.decline() }
            ACTION_HANGUP -> scope.launch { coordinator.hangUp() }
            else -> Log.w(TAG, "Unknown call action ${intent.action}")
        }
    }

    /** Brings the app to front so the user lands on the call screen. */
    private fun bringAppToFront(context: Context) {
        val launch = Intent(context, com.transfer.flash.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launch) }
    }

    companion object {
        private const val TAG = "CALLACT"
        const val ACTION_ANSWER = "com.transfer.flash.calling.ACTION_ANSWER"
        const val ACTION_DECLINE = "com.transfer.flash.calling.ACTION_DECLINE"
        const val ACTION_HANGUP = "com.transfer.flash.calling.ACTION_HANGUP"
        const val EXTRA_ANSWER_CALL = "com.transfer.flash.calling.EXTRA_ANSWER_CALL"
    }
}