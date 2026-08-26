package com.transfer.flash.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
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
 * Started only from user-visible UI actions (Dev Console), which satisfies
 * background-start restrictions.
 */
class FlashBackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Keeps the CPU running while the screen is off / device is dozing. Without it the CPU is
     * throttled, the WebSocket 15s keepalive pings stall, the 45s read timeout fires, and every
     * mesh session tears down and never recovers. Held for the whole service lifetime.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Keeps the Wi-Fi radio fully powered while backgrounded. In power-save the radio parks
     * between beacons, dropping packets that our sockets and mDNS multicast reception depend on.
     * WIFI_MODE_FULL_LOW_LATENCY (API 29+) additionally biases the radio toward low latency,
     * which suits the interactive mesh. Held for the whole service lifetime.
     */
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Screen-on / user-present re-arm: screen-off + Doze can silently drop sessions and stall
     * mDNS even with the locks held, so when the screen returns we restart discovery browsing
     * and force an immediate auto-connect sweep so returning peers come back online at once.
     * Registered at runtime (these broadcasts cannot be declared in the manifest).
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "Screen-on (${intent.action}) — re-arming discovery + auto-connect")
                    DiscoveryEngineHolder.onScreenOn()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        acquireLocks()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        scope.launch {
            runCatching { DiscoveryEngineHolder.ensureStarted(applicationContext) }
                .onFailure { Log.w(TAG, "engine start failed in background service", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        releaseLocks()
        // Engine itself keeps running if the Dev Console wants it; stopping the
        // service only releases foreground priority. Full stop is explicit.
        super.onDestroy()
    }

    /**
     * Acquires the CPU wake lock and Wi-Fi lock that keep the mesh alive across screen-off/Doze.
     * See the [wakeLock]/[wifiLock] fields for the failure mode each one guards against. Both are
     * released in [onDestroy].
     */
    private fun acquireLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(wifiMode, WIFI_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "Acquired wake lock + Wi-Fi lock (mode=$wifiMode) for background mesh")
    }

    /** Releases both locks, guarding against double-release (only release if held). */
    private fun releaseLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Flash nearby presence",
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Flash is discoverable")
            .setContentText("Nearby devices can find and reach this phone.")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SERVICE"
        private const val CHANNEL_ID = "flash_discovery_bg"
        private const val NOTIFICATION_ID = 41
        private const val WAKE_LOCK_TAG = "flash:ws-mesh"
        private const val WIFI_LOCK_TAG = "flash:ws-mesh-wifi"

        fun start(context: Context) {
            val intent = Intent(context, FlashBackgroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlashBackgroundService::class.java))
        }
    }
}
