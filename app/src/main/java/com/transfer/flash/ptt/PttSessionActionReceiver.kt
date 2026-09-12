package com.transfer.flash.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.transfer.flash.debug.DiscoveryEngineHolder
import com.transfer.flash.core.ptt.FlashPtt

/**
 * Resolves the PTT session notification's Stop/Leave button in-process.
 *
 * One action for both roles: the floor machine routes `LocalStopPress` by role
 * (talker stops + fans `stop`, listener stops + sends `leave`), so the button needs
 * no role knowledge. Stop is fire-and-forget — unlike answering a call, nothing must
 * surface, so there is no bring-to-front here.
 */
public class PttSessionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) {
            Log.w(TAG, "Unknown PTT action ${intent.action}")
            return
        }
        val engine: FlashPtt? = DiscoveryEngineHolder.currentPttSession()
        if (engine == null) {
            Log.w(TAG, "No PTT engine — ignoring stop")
            return
        }
        Log.i(TAG, "Stop action received — routing to session engine")
        engine.stopLocal()
    }

    public companion object {
        private const val TAG = "PTTACT"
        public const val ACTION_STOP: String = "com.transfer.flash.ptt.ACTION_STOP_SESSION"
    }
}
