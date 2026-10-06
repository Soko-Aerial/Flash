package com.transfer.flash.ui.splash

import android.content.Intent
import android.os.SystemClock
import com.transfer.flash.calling.FlashCallActionReceiver
import com.transfer.flash.core.ptt.PttSessionEngine
import com.transfer.flash.ui.theme.FlashLaunchSplashGate

/**
 * The Android launch splash's one gate per process (UI-056, ADR-080).
 *
 * A process-wide object, not Activity state, because the rules are about the process: the Ink splash
 * plays when the process starts and Flash's window opens for the first time, an Activity recreated
 * mid-splash (rotation, a theme change) resumes it, and a later Activity in the same process (back to
 * the app after the foreground service kept the process alive) never replays it. The clock is
 * [SystemClock.uptimeMillis], which does not jump when the user changes the wall clock.
 */
object FlashLaunchSplashProcess {
    val gate: FlashLaunchSplashGate = FlashLaunchSplashGate(nowMillis = SystemClock::uptimeMillis)

    /**
     * A launch that must not wait 3.5 s for an animation: answering a call from its notification, or
     * a hardware push-to-talk press. The splash is skipped for this process.
     */
    fun isTimeCriticalLaunch(intent: Intent?): Boolean =
        intent?.getBooleanExtra(FlashCallActionReceiver.EXTRA_ANSWER_CALL, false) == true ||
            intent?.getBooleanExtra(PttSessionEngine.EXTRA_PTT_PRESS, false) == true
}
