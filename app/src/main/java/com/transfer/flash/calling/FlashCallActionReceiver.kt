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
 * C7 (calling): resolves [com.transfer.flash.core.calling.FlashCalling] actions
 * fired by the call notification's [android.app.Notification.CallStyle] buttons
 * (answer / decline / hang up).
 *
 * The engine lives in-process (DiscoveryEngineHolder), so these actions never
 * leave the app — a broadcast PendingIntent is the standard way to wire CallStyle
 * action buttons.
 */
class FlashCallActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val calling = DiscoveryEngineHolder.currentCalling()
        if (calling == null) {
            Log.w(TAG, "No call engine — ignoring action ${intent.action}")
            return
        }
        when (intent.action) {
            // ERROR-105: never accept from here. Answering needs the RECORD_AUDIO / CAMERA checks and the audio
            // router, which live in MainActivity; the activity's own answer path (EXTRA_ANSWER_CALL) runs them.
            ACTION_ANSWER -> bringAppToFront(context, answer = true)
            ACTION_DECLINE -> scope.launch { calling.decline() }
            ACTION_HANGUP -> scope.launch { calling.hangUp() }
            else -> Log.w(TAG, "Unknown call action ${intent.action}")
        }
    }

    /** Brings the app to front so the user lands on the call screen. */
    private fun bringAppToFront(context: Context, answer: Boolean = false) {
        val launch = Intent(context, com.transfer.flash.MainActivity::class.java).apply {
            if (answer) putExtra(EXTRA_ANSWER_CALL, true)
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