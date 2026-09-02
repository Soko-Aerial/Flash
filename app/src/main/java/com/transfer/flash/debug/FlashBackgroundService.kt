package com.transfer.flash.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background discovery host (P3.5/D-M4 skeleton).
 *
 * Type choice per current platform rules (research 2026-08-23, see
 * docs/android-platform-notes.md): `connectedDevice` — no dataSync-style
 * 6h/24h timeout applies, and the precondition is satisfied by our declared
 * CHANGE_WIFI_MULTICAST_STATE permission. The service hosts advertise+browse
 * while Flash is screened; actual background RECEIVING of messages/files is
 * deferred until C4 (always-on listener) + C5/C6 (receive pipeline) land.
 *
 * Started from [com.transfer.flash.MainActivity.onStart] while the app is user-visible, which
 * satisfies Android 12+ foreground-service start restrictions. It deliberately remains running
 * after the activity stops so discovery and established mesh sessions survive in the background.
 */
class FlashBackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Order matters (ERROR-020): engine FIRST, foreground promotion LAST.
        //
        // When the system restarts this START_STICKY service after killing the process,
        // the app is backgrounded and startForeground() throws
        // ForegroundServiceStartNotAllowedException on Android 12+. The old code called
        // startAsForeground() first with no catch, so every sticky restart was a FATAL
        // crash that killed the whole mesh — the visible "goes offline after a few
        // seconds" bug. Now the failure path is caught below: the engine is already up,
        // the process stays alive, and we stop the service instance cleanly instead of
        // crashing (which also stops the crash-restart loop).
        //
        // The CPU + Wi-Fi power locks deliberately do NOT live here (ERROR-026). This service
        // instance can be destroyed while the engine keeps running — exactly what the refused path
        // below does — and onDestroy releasing the locks disarmed the mesh in the one situation the
        // locks exist for. DiscoveryEngineHolder now holds them for the engine's lifetime.
        //
        // The screen-on / user-present receiver is gone from here for the same reason (ERROR-031):
        // it used to be registered in onCreate and unregistered in onDestroy, so the refused path
        // below tore down the engine's only way to notice the screen coming back — leaving it with
        // no foreground service AND no re-arm. DiscoveryEngineHolder registers it for the engine's
        // lifetime instead.
        // ensureStarted is NonCancellable inside the holder, so stopSelf() below cannot abort a
        // half-built engine even though it cancels this scope.
        scope.launch {
            runCatching { DiscoveryEngineHolder.ensureStarted(applicationContext) }
                .onFailure { Log.w(TAG, "engine start failed in background service", it) }
        }
        if (startAsForeground()) {
            promotionRefused.set(false)
        } else {
            // Remember the refusal so a later moment when the app is allowed to start a foreground
            // service again ([retryPromotionIfRefused], driven by screen-on and Wi-Fi rejoin) can
            // try once more instead of the process staying unprotected until the user next opens it.
            promotionRefused.set(true)
            Log.w(TAG, "Foreground promotion refused (background start restriction) — stopping service instance; engine keeps running in-process with its power locks held, promotion will be retried")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        // Engine itself keeps running (with its power locks and its screen-on receiver) if the Dev
        // Console or a refused foreground promotion wants it; stopping the service only releases
        // foreground priority. Full stop is explicit: DiscoveryEngineHolder.stopAll().
        super.onDestroy()
    }

    /**
     * Promotes this service to foreground. Returns false (never throws) when Android
     * 12+ rejects the promotion because the app is backgrounded — typically a sticky
     * restart after the system/OEM killed the process. See [onCreate] for why throwing
     * here was the Bug 6 killer.
     */
    private fun startAsForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Flash nearby presence",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Flash is discoverable")
            .setContentText("Nearby devices can find and reach this phone.")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (error: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) is the expected one;
            // catch broadly so no OEM variant (SecurityException, IllegalStateException,
            // RuntimeException subclasses from Transsion's framework) can crash the app.
            Log.e(TAG, "startForeground refused", error)
            false
        }
    }

    companion object {
        private const val TAG = "SERVICE"
        private const val CHANNEL_ID = "flash_discovery_bg"
        private const val NOTIFICATION_ID = 41

        /**
         * True while the process is running the mesh **without** foreground-service protection
         * because a promotion attempt was refused (ERROR-031 / D7).
         *
         * Set by [onCreate]'s failure path, cleared as soon as a promotion succeeds. Lives in the
         * companion, not the instance, precisely because the refused instance destroys itself:
         * the fact that protection is missing has to outlive it.
         */
        private val promotionRefused = AtomicBoolean(false)

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, FlashBackgroundService::class.java)
            runCatching {
                // ContextCompat falls back to startService below API 26 (the project supports API 24+).
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure { error ->
                Log.e(TAG, "Unable to start background mesh foreground service", error)
            }
        }

        /**
         * Best-effort second chance at foreground protection after a refused promotion.
         *
         * Called from [DiscoveryEngineHolder.onScreenOn] and from the Wi-Fi rejoin hook
         * (`WsFlashNetwork(onUsableNetwork = …)`) — two moments where the app has plausibly become
         * allowed to start a foreground service again. Starting the service creates a fresh
         * instance, so `onCreate` re-runs `startAsForeground()`; if the platform refuses again the
         * catch inside it turns that into `false` and the instance stops itself, so a failed retry
         * costs nothing and the flag stays armed for the next attempt.
         *
         * No-op when protection is already held, so the hot paths that call this on every screen-on
         * and every network change do not churn service instances.
         */
        fun retryPromotionIfRefused(context: Context) {
            if (!promotionRefused.get()) return
            Log.i(TAG, "Retrying refused foreground promotion")
            start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlashBackgroundService::class.java))
        }
    }
}
