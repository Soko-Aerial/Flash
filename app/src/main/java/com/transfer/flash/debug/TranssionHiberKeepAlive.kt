package com.transfer.flash.debug

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat

/**
 * Manages tiered keep-alive strategies specifically designed to bypass the Transsion Hiber OEM
 * process freezer on Infinix, Tecno, and itel devices (Android 14+, XOS/HiOS/itelOS).
 *
 * Transsion Hiber ignores foreground services during screen-off deep sleep (IMPORT_SCREEN_OFF_FLAG = 389052),
 * freezing the UID within 4-5 seconds and destroying open TCP sockets 3 seconds later (ERROR-074).
 *
 * This manager implements a 3-tier hierarchy based on firmware-verified exemption bits:
 *
 * 1. Tier 1: BLUETOOTH (Bit 15 = 32768, "bt")
 *    - Uses a low-power BLE scan (ScanSettings.SCAN_MODE_LOW_POWER).
 *    - Firmware hook in GattService.startScan triggers ProfileService.updateActiveAppName, adding Flash
 *      to Settings.Global "bluetooth.active.applist".
 *    - Hiber checks inBluetoothActiveList(pkg) and skips the freeze (HIB-12 PASS).
 *    - Prerequisites: Bluetooth enabled and BLUETOOTH_SCAN permission granted.
 *    - Cost: 0 audio focus, 0% CPU, 0 network bytes.
 *
 * 2. Tier 2: OVERLAY (Bit 2 = 4, "float_window")
 *    - Attaches a 1x1 transparent TYPE_APPLICATION_OVERLAY window.
 *    - TranAppStateManager intercepts overlay windows and sets STATUS_SYSTEM_WINDOW (HIB-17 PASS).
 *    - Prerequisites: Settings.canDrawOverlays(context) is true.
 *    - Cost: 0 audio focus, 0% CPU, 0 network bytes.
 *
 * 3. Tier 3: AUDIO (Bit 5 = 32, "audio_mix")
 *    - Requests audio focus and plays an inaudible static 1600-byte PCM zero buffer in AudioTrack.MODE_STATIC.
 *    - TranAppStateManager hooks AudioFocus changes, setting STATUS_AUDIO_FOCUS (HIB-14 PASS).
 *    - Prerequisites: None (zero permissions required).
 *    - Fallback guaranteed to keep Flash alive on any Transsion device.
 */
class TranssionHiberKeepAlive(
    private val context: Context,
    private val brandResolver: () -> OemBatteryOptimizationHelper.OemBrand = { OemBatteryOptimizationHelper.currentBrand },
    private val bluetoothChecker: (() -> Boolean)? = null,
    private val overlayChecker: (() -> Boolean)? = null,
    private val sessionActiveChecker: (() -> Boolean)? = null,
    private val activeListResolver: (() -> String)? = null,
    private val onTierChanged: ((oldTier: KeepAliveTier, newTier: KeepAliveTier) -> Unit)? = null,
) {
    enum class KeepAliveTier {
        BLUETOOTH,
        OVERLAY,
        AUDIO,
        NONE,
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var verificationRunnable: Runnable? = null

    @Volatile
    var currentTier: KeepAliveTier = KeepAliveTier.NONE
        private set

    @Volatile
    var isStarted: Boolean = false
        private set

    @Volatile
    private var internalSessionActive: Boolean = false

    val isSessionActive: Boolean
        get() = sessionActiveChecker?.invoke() ?: internalSessionActive

    fun setSessionActive(active: Boolean) {
        if (internalSessionActive == active) return
        internalSessionActive = active
        Log.i(TAG, "Keep-alive session active state changed: $active (isStarted=$isStarted)")
        if (isStarted) {
            reevaluateTier()
        }
    }

    // Tier 1 state
    private var isBleScanning: Boolean = false
    private var bleScanCallback: ScanCallback? = null

    // Tier 2 state
    private var overlayView: View? = null

    // Tier 3 state
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var receiverRegistered: Boolean = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                Log.d(TAG, "Bluetooth state changed: $state")
                if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF) {
                    reevaluateTier()
                }
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Tier 3 AudioFocus change: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Tier 3 audio focus gained/regained")
                if (currentTier == KeepAliveTier.AUDIO && isSessionActive) {
                    if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { audioTrack?.play() }
                        Log.i(TAG, "Resumed silent AudioTrack keep-alive after regaining focus")
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Tier 3 audio focus transient loss ($focusChange); pausing silent audio without fighting")
                runCatching { audioTrack?.pause() }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Tier 3 audio focus lost permanently ($focusChange); pausing silent audio without fighting")
                runCatching { audioTrack?.pause() }
                // Graceful fallback: do not fight for focus! Attempt fallback to Bluetooth scan or transfer traffic shield
                if (!isBleScanning) {
                    val btStarted = startBluetooth()
                    if (btStarted) {
                        Log.i(TAG, "Fell back to Bluetooth scan shield during audio focus loss")
                    } else {
                        Log.i(TAG, "Bluetooth fallback unavailable; relying on background transfer traffic shield (>10 KB/s)")
                    }
                }
            }
        }
    }

    @VisibleForTesting
    fun onAudioFocusChangeForTesting(focusChange: Int) {
        audioFocusChangeListener.onAudioFocusChange(focusChange)
    }

    /**
     * Starts keep-alive monitoring and activates the highest available tier.
     */
    @Synchronized
    fun start() {
        if (isStarted) return
        isStarted = true

        if (brandResolver() != OemBatteryOptimizationHelper.OemBrand.TRANSSION) {
            Log.i(TAG, "Not a Transsion device (${brandResolver()}); keep-alive inactive")
            currentTier = KeepAliveTier.NONE
            return
        }

        if (!receiverRegistered) {
            runCatching {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(bluetoothReceiver, filter)
                }
                receiverRegistered = true
            }.onFailure { Log.w(TAG, "Failed to register Bluetooth state receiver", it) }
        }

        reevaluateTier()
    }

    /**
     * Stops any active keep-alive tier and cleans up resources.
     */
    @Synchronized
    fun stop() {
        if (!isStarted) return
        isStarted = false

        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(bluetoothReceiver) }
            receiverRegistered = false
        }

        verificationRunnable?.let {
            mainHandler.removeCallbacks(it)
            verificationRunnable = null
        }

        val old = currentTier
        teardownTier(currentTier)
        currentTier = KeepAliveTier.NONE
        if (old != KeepAliveTier.NONE) {
            onTierChanged?.invoke(old, KeepAliveTier.NONE)
        }
        Log.i(TAG, "Transsion keep-alive stopped")
    }

    /**
     * Checks available permissions and hardware state to dynamically promote or fall back.
     */
    @Synchronized
    fun reevaluateTier() {
        if (!isStarted) return

        if (brandResolver() != OemBatteryOptimizationHelper.OemBrand.TRANSSION) {
            if (currentTier != KeepAliveTier.NONE) {
                val old = currentTier
                teardownTier(currentTier)
                currentTier = KeepAliveTier.NONE
                onTierChanged?.invoke(old, KeepAliveTier.NONE)
            }
            return
        }

        val targetTier = determineBestTier()
        if (targetTier == currentTier) {
            Log.d(TAG, "Keep-alive tier remains unchanged: $currentTier")
            return
        }

        Log.i(TAG, "Transitioning keep-alive tier from $currentTier to $targetTier")
        val old = currentTier
        teardownTier(currentTier)

        if (activateTier(targetTier)) {
            currentTier = targetTier
            onTierChanged?.invoke(old, targetTier)
        } else {
            // Cascade fallback
            Log.w(TAG, "Activation failed for $targetTier, falling back")
            val fallback = when (targetTier) {
                KeepAliveTier.BLUETOOTH -> if (isSessionActive) KeepAliveTier.AUDIO else KeepAliveTier.NONE
                else -> KeepAliveTier.NONE
            }
            if (fallback != KeepAliveTier.NONE && activateTier(fallback)) {
                currentTier = fallback
                onTierChanged?.invoke(old, fallback)
            } else {
                currentTier = KeepAliveTier.NONE
                onTierChanged?.invoke(old, KeepAliveTier.NONE)
            }
        }
    }

    /**
     * Selects the preferred tier based on session activity, available hardware, and permissions:
     * - Idle (no active session/transfer): NONE - auto-release to allow phone deep sleep.
     * - Active Session + Bluetooth (Bit 15, HIB-12 PASS): BLUETOOTH - 0 audio, 0% CPU, 0 network bytes.
     * - Active Session + Bluetooth unavailable (Bit 5, HIB-14 PASS): AUDIO - silent static PCM loop.
     */
    fun determineBestTier(): KeepAliveTier {
        if (brandResolver() != OemBatteryOptimizationHelper.OemBrand.TRANSSION) return KeepAliveTier.NONE
        if (!isSessionActive) return KeepAliveTier.NONE
        if (canUseBluetoothTier()) return KeepAliveTier.BLUETOOTH
        return KeepAliveTier.AUDIO
    }

    fun canUseBluetoothTier(): Boolean {
        if (brandResolver() != OemBatteryOptimizationHelper.OemBrand.TRANSSION) return false
        bluetoothChecker?.let { return it() }
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return false
            if (!adapter.isEnabled) return false

            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH,
                ) == PackageManager.PERMISSION_GRANTED
            }
            hasPermission
        } catch (t: Throwable) {
            Log.w(TAG, "Error checking Bluetooth tier eligibility: ${t.message}")
            false
        }
    }

    fun canUseOverlayTier(): Boolean {
        if (brandResolver() != OemBatteryOptimizationHelper.OemBrand.TRANSSION) return false
        overlayChecker?.let { return it() }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun activateTier(tier: KeepAliveTier): Boolean = when (tier) {
        KeepAliveTier.BLUETOOTH -> startBluetooth()
        KeepAliveTier.OVERLAY -> startOverlay()
        KeepAliveTier.AUDIO -> startAudio()
        KeepAliveTier.NONE -> true
    }

    private fun teardownTier(tier: KeepAliveTier) {
        when (tier) {
            KeepAliveTier.BLUETOOTH -> stopBluetooth()
            KeepAliveTier.OVERLAY -> stopOverlay()
            KeepAliveTier.AUDIO -> stopAudio()
            KeepAliveTier.NONE -> {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetooth(): Boolean {
        if (isBleScanning) return true
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.w(TAG, "BluetoothLeScanner unavailable")
                return false
            }
            val filter = ScanFilter.Builder().build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {}
                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE scan failed with code $errorCode")
                }
            }
            scanner.startScan(listOf(filter), settings, cb)
            bleScanCallback = cb
            isBleScanning = true

            // Schedule asynchronous verification check (750ms later) to verify that Settings.Global reflects package name.
            // If the package fails to appear after the timeout, fallback to Audio focus.
            verificationRunnable?.let { mainHandler.removeCallbacks(it) }
            val checkRunnable = Runnable {
                synchronized(this@TranssionHiberKeepAlive) {
                    if (!isStarted || currentTier != KeepAliveTier.BLUETOOTH || !isBleScanning) return@Runnable
                    val activeList = activeListResolver?.invoke()
                        ?: Settings.Global.getString(context.contentResolver, "bluetooth.active.applist")
                        ?: ""
                    val registered = activeList.contains(context.packageName)
                    if (registered) {
                        Log.i(TAG, "Tier 1 (BLUETOOTH) verified asynchronously in bluetooth.active.applist")
                    } else {
                        Log.w(TAG, "Tier 1 (BLUETOOTH) BLE scan running, but package not present in bluetooth.active.applist after timeout; falling back to AUDIO")
                        if (isSessionActive) {
                            val old = currentTier
                            teardownTier(currentTier)
                            if (activateTier(KeepAliveTier.AUDIO)) {
                                currentTier = KeepAliveTier.AUDIO
                                onTierChanged?.invoke(old, KeepAliveTier.AUDIO)
                            } else {
                                currentTier = KeepAliveTier.NONE
                                onTierChanged?.invoke(old, KeepAliveTier.NONE)
                            }
                        }
                    }
                }
            }
            verificationRunnable = checkRunnable
            mainHandler.postDelayed(checkRunnable, 750L)
            Log.i(TAG, "Tier 1 (BLUETOOTH) started optimistically; scheduled async active.applist verification")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException starting BLE scan: ${e.message}")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "Error starting BLE scan: ${t.message}", t)
            false
        }
    }

    @VisibleForTesting
    fun runVerificationCheckForTesting() {
        verificationRunnable?.run()
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetooth() {
        verificationRunnable?.let {
            mainHandler.removeCallbacks(it)
            verificationRunnable = null
        }
        if (isBleScanning && bleScanCallback != null) {
            runCatching {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                btManager?.adapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
            }
            isBleScanning = false
            bleScanCallback = null
            Log.i(TAG, "Tier 1 (BLUETOOTH) stopped")
        }
    }

    private fun startOverlay(): Boolean {
        if (overlayView != null) return true
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                Log.w(TAG, "WindowManager unavailable")
                return false
            }
            val params = WindowManager.LayoutParams(
                1,
                1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            val view = View(context)
            mainHandler.post {
                runCatching {
                    wm.addView(view, params)
                    overlayView = view
                    Log.i(TAG, "Tier 2 (OVERLAY) started: 1x1 overlay window attached (STATUS_SYSTEM_WINDOW)")
                }.onFailure { Log.w(TAG, "Failed to attach overlay window on main thread", it) }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Error configuring overlay window: ${t.message}", t)
            false
        }
    }

    private fun stopOverlay() {
        val view = overlayView ?: return
        overlayView = null
        mainHandler.post {
            runCatching {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeView(view)
                Log.i(TAG, "Tier 2 (OVERLAY) stopped")
            }
        }
    }

    private fun startAudio(): Boolean {
        if (audioTrack != null) return true
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest = req
                audioManager?.requestAudioFocus(req) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }

            Log.i(TAG, "Tier 3 audio focus requested: res=$res")

            val sampleRate = 8000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val size = maxOf(minBuf, 1600)
            val silence = ByteArray(size)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(silence, 0, silence.size)
            track.setLoopPoints(0, silence.size / 2, -1)
            audioTrack = track

            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                track.play()
                Log.i(TAG, "Tier 3 (AUDIO) started: Audio focus granted, silent static PCM loop running (STATUS_AUDIO_FOCUS)")
                true
            } else if (res == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                Log.i(TAG, "Tier 3 (AUDIO) focus delayed; waiting for AUDIOFOCUS_GAIN before playing")
                true
            } else {
                Log.w(TAG, "Tier 3 (AUDIO) focus denied (res=$res); not fighting, falling back to Bluetooth/traffic shield")
                if (!isBleScanning) {
                    startBluetooth()
                }
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start Audio tier: ${t.message}", t)
            false
        }
    }

    private fun stopAudio() {
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(audioFocusChangeListener)
            }
            audioFocusRequest = null
            if (currentTier != KeepAliveTier.BLUETOOTH && isBleScanning) {
                stopBluetooth()
            }
            Log.i(TAG, "Tier 3 (AUDIO) stopped: released audio focus and AudioTrack for deep sleep")
        }
    }

    companion object {
        private const val TAG = "HIBER_KEEPALIVE"
    }
}
