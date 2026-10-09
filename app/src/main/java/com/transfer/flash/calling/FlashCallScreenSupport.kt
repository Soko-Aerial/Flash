package com.transfer.flash.calling

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Rational
import androidx.annotation.RequiresApi

/**
 * Android-only call-screen support (ADR-067, UI-050f): picture-in-picture and the proximity screen-off.
 * Both are verified against developer.android.com (see docs/android-platform-notes.md); neither is device-verified yet.
 */
private const val TAG = "CALLUI"

internal object FlashCallPictureInPicture {

    /** PiP exists from API 26 and only where the device declares the feature. */
    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun params(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4))
        // setAutoEnterEnabled is API 31: below it the user enters from the More panel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    /** Enters the PiP window now. Fire and forget: the system refuses while the app is not resumed. */
    fun enter(activity: Activity) {
        // The explicit SDK check is what lint reads (minSdk is 24, PiP is API 26); supported() repeats it plus the feature check.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supported(activity)) return
        runCatching { activity.enterPictureInPictureMode(params(autoEnter = false)) }
            .onFailure { Log.w(TAG, "enter PiP failed: ${it.message}") }
    }

    /** Lets the system shrink a live video call into PiP when the user leaves the app (API 31+). */
    fun setAutoEnter(activity: Activity, on: Boolean) {
        if (!supported(activity) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching { activity.setPictureInPictureParams(params(autoEnter = on)) }
            .onFailure { Log.w(TAG, "set PiP params failed: ${it.message}") }
    }
}

/** Turns the screen off while a voice call is held to the ear, with the platform proximity wake lock. */
internal class FlashCallProximity(context: Context) {

    private val lock: PowerManager.WakeLock? = run {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "flash:call-proximity")
                .apply { setReferenceCounted(false) }
        } else {
            null
        }
    }

    /** Holds (true) or releases (false) the lock. Idempotent; a device without the sensor does nothing. */
    fun hold(on: Boolean) {
        val l = lock ?: return
        runCatching {
            if (on && !l.isHeld) l.acquire(MAX_HOLD_MS) else if (!on && l.isHeld) l.release()
        }.onFailure { Log.w(TAG, "proximity lock failed: ${it.message}") }
    }

    private companion object {
        /** A safety net, not a feature: a call that outlives it simply stops turning the screen off. */
        const val MAX_HOLD_MS = 2L * 60 * 60 * 1000
    }
}

/** The [Activity] behind a Compose [Context], or null. */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
