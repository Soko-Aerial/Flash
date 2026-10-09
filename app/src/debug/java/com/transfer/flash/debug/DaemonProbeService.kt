package com.transfer.flash.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * HIB-10 (debug builds only): does a SECOND PROCESS of Flash survive the Transsion freezer?
 *
 * Hypothesis under test: the owner proposed an isolated daemon process (`:lan_daemon`) to escape
 * Hiber. ERROR-074 logged `freeze uid: com.transfer.flash`, i.e. the freezer acts on the whole
 * app UID, which a second process shares, so this probe is expected to freeze with the app. It is
 * cheap proof either way, and it must run BEFORE the engine is moved out of the main process
 * (a large change: Room, state flows and the UI all live in-process today).
 *
 * What it does: runs in process `:lan_daemon`, is a foreground service, and logs once a second
 * `DAEMON_PROBE pid=… seq=… gapMs=…`, and a WARN `THAWED after …ms` when a beat arrives late. `gapMs` is the real elapsed time since the previous beat
 * on the monotonic clock; a frozen process runs nothing, so after thaw it shows one big gap
 * (the screen-off duration) with `seq` continuing. It touches no Flash state.
 *
 * Watch: `adb logcat -v time DAEMON_PROBE:I Hiber:* *:S` (the `Hiber` lines are the system's freeze/unfreeze
 * decisions for the app UID). Run (Infinix/Tecno, Android 14): see TEST-BACKLOG HIB-10. Start with
 * `adb shell am start-foreground-service -n com.transfer.flash/.debug.DaemonProbeService`
 * (application id may carry a debug suffix), stop with `... am stopservice ...`.
 */
class DaemonProbeService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var seq = 0L
    private var lastBeat = 0L

    private val beat = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val gap = if (lastBeat == 0L) 0L else now - lastBeat
            lastBeat = now
            seq += 1
            Log.i(TAG, "pid=${Process.myPid()} uid=${Process.myUid()} seq=$seq gapMs=$gap")
            if (gap > FROZEN_GAP_MS) {
                // A running process beats every second, so a long gap means it was not scheduled:
                // frozen (Hiber) or the CPU asleep. Compare with `Hiber` lines from system_server.
                Log.w(TAG, "THAWED after ${gap}ms without running (expected ${BEAT_MS}ms) seq=$seq pid=${Process.myPid()}")
            }
            handler.postDelayed(this, BEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("daemon-probe").also { it.start() }
        handler = Handler(thread.looper)
        promote()
        handler.post(beat)
        Log.i(TAG, "started pid=${Process.myPid()} uid=${Process.myUid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        Log.i(TAG, "stopped pid=${Process.myPid()} beats=$seq")
        super.onDestroy()
    }

    private fun promote() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Daemon probe (debug)", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setChannelId(CHANNEL)
        }
            .setContentTitle("Daemon probe (debug)")
            .setContentText("HIB-10 heartbeat in :lan_daemon")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, notification)
        }
    }

    private companion object {
        const val TAG = "DAEMON_PROBE"
        const val CHANNEL = "flash_daemon_probe"
        const val ID = 4242
        const val BEAT_MS = 1_000L
        const val FROZEN_GAP_MS = 5_000L
    }
}
