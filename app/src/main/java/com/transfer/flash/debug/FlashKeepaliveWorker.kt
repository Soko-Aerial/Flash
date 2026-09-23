package com.transfer.flash.debug

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Periodic "flush and announce" wake-up (ADR-041, investigation §1.3 D).
 *
 * When the user swipes Flash away — or an OEM task killer ends the process — nothing else brings it
 * back: `FlashBackgroundService` is `START_STICKY`, but a stopped-by-the-user service is not
 * restarted, and messages already written to the outbox then sit there until their 30-minute
 * give-up budget expires. JobScheduler (via WorkManager) survives that: it wakes the app inside a
 * Doze maintenance window, which is enough to reconnect and drain.
 *
 * **Deliberately not a "stay online" mechanism.** The process was killed; the user does not expect
 * a permanent resident. So the worker starts the engine, gives a session a bounded window to come
 * up (the engine's own `notifyPeerSessionUp` drains the outbox as soon as one does), and then —
 * *only if this worker was the thing that started the engine* — stops it again, releasing the power
 * locks it took. An engine that was already running (service alive, or the UI open) is left
 * completely alone.
 *
 * Honest limits, so the next reader does not over-trust this:
 * - 15 minutes is WorkManager's floor for periodic work; this is a safety net, not delivery latency.
 * - The aggressive OEMs that motivate it (Xiaomi/HyperOS, Transsion, Huawei) also restrict jobs for
 *   an app the user "force stopped" or that lacks autostart — the OEM deep links in
 *   [OemBatteryOptimizationHelper] are the other half of this fix.
 * - It cannot promote itself to a foreground service from the background (Android 12+), so it does
 *   short connect-and-flush work, not a full mesh resume.
 */
class FlashKeepaliveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (DiscoveryEngineHolder.isRunning()) {
            // Service or UI already owns the engine: nothing to do, and stopping it would be wrong.
            return Result.success()
        }

        val started = runCatching { DiscoveryEngineHolder.ensureStarted(applicationContext) }
        if (started.isFailure) {
            Log.w(TAG, "keepalive: engine start failed", started.exceptionOrNull())
            // Transient by assumption (no network yet, radio busy): let WorkManager back off.
            return Result.retry()
        }

        try {
            val network = DiscoveryEngineHolder.currentNetwork()
            val sawSession = network != null && withTimeoutOrNull(SESSION_WINDOW_MS) {
                network.activeSessions.first { it.isNotEmpty() }
            } != null

            if (sawSession) {
                // A session is up, so the engine has already fired notifyPeerSessionUp and the
                // outbox drain is running. Hold the process open briefly so it can finish.
                kotlinx.coroutines.delay(DRAIN_WINDOW_MS)
            }
            Log.i(TAG, "keepalive: wake complete sawSession=$sawSession")
        } finally {
            // We started it, so we put it back: an engine left running headless would hold the
            // partial wake lock (ERROR-025/026 acquires it for the engine's lifetime) every 15
            // minutes forever, which is exactly the battery drain this app must not cause.
            //
            // Unless someone took ownership while we worked — the user opening the app, or the
            // service starting — in which case stopping would tear down THEIR live session.
            if (FlashBackgroundService.isActive() || isAppInForeground()) {
                Log.i(TAG, "keepalive: engine adopted by service/UI, leaving it running")
            } else {
                runCatching { DiscoveryEngineHolder.stopAll() }
                    .onFailure { Log.w(TAG, "keepalive: engine stop failed", it) }
            }
        }
        return Result.success()
    }

    /** Cheap foreground check — no lifecycle-process dependency for one boolean. */
    private fun isAppInForeground(): Boolean = runCatching {
        val state = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(state)
        state.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }.getOrDefault(false)

    companion object {
        private const val TAG = "FlashKeepalive"

        /** Unique name so re-scheduling on every process start cannot stack duplicate chains. */
        const val WORK_NAME = "flash-keepalive"

        /** WorkManager's minimum periodic interval; anything smaller is silently clamped to it. */
        const val INTERVAL_MINUTES = 15L

        /** How long to wait for discovery + dial + handshake to produce a session. */
        private const val SESSION_WINDOW_MS = 45_000L

        /** Grace for the outbox drain once a session exists. */
        private const val DRAIN_WINDOW_MS = 10_000L

        /**
         * Network-connected (there is nothing to flush without a link) and battery-not-low (a
         * background safety net must never be the reason a phone dies).
         */
        fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /**
         * Enqueues the periodic wake-up, keeping any already-scheduled copy so the interval is not
         * restarted on every app launch.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FlashKeepaliveWorker>(
                INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).setConstraints(constraints()).build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { Log.w(TAG, "keepalive: could not schedule", it) }
        }
    }
}
