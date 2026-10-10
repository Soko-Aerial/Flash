package com.transfer.flash.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

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
 * `DAEMON_PROBE pid=… seq=… gapMs=…`, and a WARN `LATE BEAT` (with a verdict: whole device suspended, or this process not scheduled) when a beat arrives late. `gapMs` is the real elapsed time since the previous beat
 * on the monotonic clock; a frozen process runs nothing, so after thaw it shows one big gap
 * (the screen-off duration) with `seq` continuing. It touches no Flash state.
 *
 * Watch: `adb logcat -v time | findstr /i "DAEMON_PROBE Hiber"` (the tag is `Hiber/sceneManager`, so a
 * `Hiber:*` tag filter matches nothing; `Hiber` lines are the system's freeze/unfreeze decisions for the app UID). Run (Infinix/Tecno, Android 14): see TEST-BACKLOG HIB-10. Start with
 * `adb shell am start-foreground-service -n com.transfer.flash/.debug.DaemonProbeService --ez wakelock true`
 * (application id may carry a debug suffix), stop with `... am stopservice ...`.
 */
class DaemonProbeService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var seq = 0L
    private var lastBeat = 0L
    private var lastUptime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var trafficOn = false
    @Volatile private var loopbackOn = false
    private var gattServer: BluetoothGattServer? = null
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var overlayView: android.view.View? = null
    private var isScanning = false
    private var scanCallback: android.bluetooth.le.ScanCallback? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "screen ${if (intent?.action == Intent.ACTION_SCREEN_ON) "ON" else "OFF"} seq=$seq")
        }
    }

    private val beat = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val up = SystemClock.uptimeMillis()
            val gap = if (lastBeat == 0L) 0L else now - lastBeat
            val upGap = if (lastUptime == 0L) 0L else up - lastUptime
            lastBeat = now
            lastUptime = up
            seq += 1
            val power = getSystemService(PowerManager::class.java)
            val tx = TrafficStats.getUidTxBytes(Process.myUid())
            val rx = TrafficStats.getUidRxBytes(Process.myUid())
            Log.i(
                TAG,
                "pid=${Process.myPid()} uid=${Process.myUid()} seq=$seq gapMs=$gap upGapMs=$upGap " +
                    "txBytes=$tx rxBytes=$rx interactive=${power?.isInteractive} idle=${power?.isDeviceIdleMode} wakeLock=${wakeLock?.isHeld == true}",
            )
            if (gap > FROZEN_GAP_MS) {
                // gapMs counts time the CPU was suspended, upGapMs does not. Large gapMs with small upGapMs
                // = the whole device slept (not the freezer). Both large = this process was not scheduled
                // while the CPU was awake: frozen (Hiber) or throttled. Compare with the Hiber lines.
                val verdict = if (upGap > FROZEN_GAP_MS) "NOT SCHEDULED while CPU awake (frozen/throttled)" else "CPU WAS SUSPENDED"
                Log.w(TAG, "LATE BEAT gapMs=$gap upGapMs=$upGap: $verdict seq=$seq pid=${Process.myPid()}")
            }
            handler.postDelayed(this, BEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("daemon-probe").also { it.start() }
        handler = Handler(thread.looper)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        promote()
        handler.post(beat)
        Log.i(TAG, "started pid=${Process.myPid()} uid=${Process.myUid()}")
    }

    /** `--ez wakelock true` holds a PARTIAL_WAKE_LOCK so the CPU cannot suspend; without it the probe measures plain device sleep too. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wantLock = intent?.getBooleanExtra(EXTRA_WAKELOCK, false) == true
        if (wantLock && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flash:daemon-probe")
                ?.also { it.setReferenceCounted(false); it.acquire() }
        }
        Log.i(TAG, "start command wakelock=$wantLock")
        intent?.getIntExtra(EXTRA_TRAFFIC_KBPS, 0)?.takeIf { it > 0 }?.let { startTraffic(it, intent.getStringExtra(EXTRA_TRAFFIC_URL) ?: DEFAULT_TRAFFIC_URL) }
        if (intent?.getBooleanExtra(EXTRA_LOOPBACK, false) == true) {
            val kbps = intent.getIntExtra(EXTRA_LOOPBACK_KBPS, 15)
            startLoopback(kbps)
        }
        if (intent?.getBooleanExtra(EXTRA_AUDIO, false) == true) {
            startAudio()
        }
        if (intent?.getBooleanExtra(EXTRA_GATT, false) == true) openGatt()
        if (intent?.getBooleanExtra(EXTRA_OVERLAY, false) == true) startOverlay()
        if (intent?.getBooleanExtra(EXTRA_BT_SCAN, false) == true) startBtScan()
        return START_NOT_STICKY
    }

    /**
     * HIB-13: Hiber protects an app that has an ongoing notification AND moves at least 10 KB/s
     * (`TranImportantScene.isTrafficActive`, docs/network/TRANSSION-HIBER-INTERNALS.md). Pulls a
     * public test file at about [kbps] KB/s so that rule can be tried with the screen off.
     */
    private fun startTraffic(kbps: Int, url: String) {
        if (trafficOn) return
        trafficOn = true
        Thread({
            val chunk = ByteArray(kbps * 100) // 100 ms of data per read
            while (trafficOn) {
                runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 5000
                    c.readTimeout = 10000
                    c.inputStream.use { input ->
                        var window = SystemClock.elapsedRealtime()
                        while (trafficOn) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            val wait = 100 - (SystemClock.elapsedRealtime() - window)
                            if (wait > 0) Thread.sleep(wait)
                            window = SystemClock.elapsedRealtime()
                        }
                    }
                }.onFailure { Log.w(TAG, "traffic: ${it.javaClass.simpleName}: ${it.message}"); Thread.sleep(2000) }
            }
        }, "probe-traffic").start()
        Log.i(TAG, "traffic started about $kbps KB/s")
    }

    /**
     * Tests whether internal TCP loopback (127.0.0.1) traffic increments Linux kernel eBPF UID accounting
     * (TrafficStats.getUidTxBytes) and satisfies Hiber's traffic >= 10 KB/s check without consuming external network bandwidth.
     */
    private fun startLoopback(kbps: Int) {
        if (loopbackOn) return
        loopbackOn = true
        Thread({
            runCatching {
                val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                val port = server.localPort
                val client = Socket("127.0.0.1", port)
                val accepted = server.accept()
                val out = client.getOutputStream()
                val input = accepted.getInputStream()
                val chunk = ByteArray(kbps * 102) // ~100ms slice of data
                val readBuf = ByteArray(chunk.size)

                Thread({
                    while (loopbackOn) {
                        runCatching {
                            while (loopbackOn) {
                                val r = input.read(readBuf)
                                if (r < 0) break
                            }
                        }
                    }
                }, "probe-loopback-reader").start()

                var window = SystemClock.elapsedRealtime()
                while (loopbackOn) {
                    out.write(chunk)
                    out.flush()
                    val wait = 100 - (SystemClock.elapsedRealtime() - window)
                    if (wait > 0) Thread.sleep(wait)
                    window = SystemClock.elapsedRealtime()
                }
                runCatching { client.close() }
                runCatching { accepted.close() }
                runCatching { server.close() }
            }.onFailure { Log.w(TAG, "loopback: ${it.javaClass.simpleName}: ${it.message}") }
        }, "probe-loopback-writer").start()
        Log.i(TAG, "loopback started about $kbps KB/s")
    }

    /**
     * Hiber Bit 5 (STATUS_AUDIO_FOCUS / "audio_mix"):
     * Holding audio focus with an active silent AudioTrack sets AppStatusType.STATUS_AUDIO_FOCUS
     * in TranAppStateManager. When screen turns off, TranImportantScene grants "audio_mix" exemption!
     */
    private fun startAudio() {
        if (audioTrack != null) return
        runCatching {
            val audioManager = getSystemService(AudioManager::class.java)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focus -> Log.i(TAG, "audio focus change: $focus") }
                    .build()
                audioFocusRequest = req
                val res = audioManager?.requestAudioFocus(req)
                Log.i(TAG, "audio focus requested: res=$res")
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                Log.i(TAG, "audio focus requested: res=$res")
            }

            val sampleRate = 8000
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val size = maxOf(minBuf, 1600)
            val silence = ByteArray(size)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(silence, 0, silence.size)
            track.setLoopPoints(0, silence.size / 2, -1)
            track.play()
            audioTrack = track
            Log.i(TAG, "silent AudioTrack playing (loop mode)")
        }.onFailure { Log.w(TAG, "audio: ${it.javaClass.simpleName}: ${it.message}", it) }
    }

    private fun stopAudio() {
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            val audioManager = getSystemService(AudioManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
            audioFocusRequest = null
            Log.i(TAG, "audio stopped")
        }
    }

    /**
     * HIB-12: Hiber protects packages listed in the global setting `bluetooth.active.applist`.
     * Opens a GATT server (needs BLUETOOTH_CONNECT, grant with adb) and logs the setting so what puts a package on that list can be measured.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun openGatt() {
        runCatching {
            val manager = getSystemService(BluetoothManager::class.java)
            gattServer = manager?.openGattServer(this, object : BluetoothGattServerCallback() {})
            Log.i(TAG, "gatt server opened=${gattServer != null}")
        }.onFailure { Log.w(TAG, "gatt: ${it.javaClass.simpleName}: ${it.message}") }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startBtScan() {
        if (isScanning) return
        runCatching {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            val scanner = adapter?.bluetoothLeScanner
            val filter = android.bluetooth.le.ScanFilter.Builder().build()
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            val cb = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {}
            }
            scanCallback = cb
            scanner?.startScan(listOf(filter), settings, cb)
            isScanning = true
            Log.i(TAG, "bluetooth LE scan started (triggers updateActiveAppName)")
        }.onFailure { Log.w(TAG, "bt scan: ${it.javaClass.simpleName}: ${it.message}", it) }
    }

    private fun stopBtScan() {
        runCatching {
            if (isScanning && scanCallback != null) {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                @Suppress("MissingPermission")
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                isScanning = false
                scanCallback = null
                Log.i(TAG, "bluetooth LE scan stopped")
            }
        }
    }

    private fun startOverlay() {
        if (overlayView != null) return
        runCatching {
            val wm = getSystemService(android.view.WindowManager::class.java)
            val params = android.view.WindowManager.LayoutParams(
                1, 1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    android.view.WindowManager.LayoutParams.TYPE_PHONE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSPARENT,
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 0
            }
            val view = android.view.View(this)
            wm?.addView(view, params)
            overlayView = view
            Log.i(TAG, "overlay window attached (TYPE_APPLICATION_OVERLAY)")
        }.onFailure { Log.w(TAG, "overlay: ${it.javaClass.simpleName}: ${it.message}", it) }
    }

    private fun stopOverlay() {
        runCatching {
            overlayView?.let {
                val wm = getSystemService(android.view.WindowManager::class.java)
                wm?.removeView(it)
                overlayView = null
                Log.i(TAG, "overlay window removed")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        trafficOn = false
        loopbackOn = false
        stopAudio()
        stopOverlay()
        stopBtScan()
        runCatching { @Suppress("MissingPermission") gattServer?.close() }
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(ID, notification, types)
        } else {
            startForeground(ID, notification)
        }
    }

    private companion object {
        const val TAG = "DAEMON_PROBE"
        const val EXTRA_WAKELOCK = "wakelock"
        const val EXTRA_TRAFFIC_KBPS = "trafficKbps"
        const val EXTRA_TRAFFIC_URL = "trafficUrl"
        const val DEFAULT_TRAFFIC_URL = "https://speed.cloudflare.com/__down?bytes=100000000"
        const val EXTRA_LOOPBACK = "loopback"
        const val EXTRA_LOOPBACK_KBPS = "loopbackKbps"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_GATT = "gatt"
        const val EXTRA_OVERLAY = "overlay"
        const val EXTRA_BT_SCAN = "btScan"
        const val CHANNEL = "flash_daemon_probe"
        const val ID = 4242
        const val BEAT_MS = 1_000L
        const val FROZEN_GAP_MS = 5_000L
    }
}
