package com.transfer.flash.calling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.transfer.flash.core.calling.model.FlashCallAudioRoute
import com.transfer.flash.core.calling.model.FlashCallAudioRouting
import com.transfer.flash.core.calling.model.FlashCallAudioRoutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform audio routing for the duration of a call (ADR-025, C7).
 *
 * WebRTC controls the codec, the jitter buffer and the audio device module, but not the platform
 * audio *mode* — and on Android the mode is what decides which capture/playout path the whole
 * stack runs on. Without [AudioManager.MODE_IN_COMMUNICATION] the OS treats a call like media
 * playback: the long media output buffer, no platform echo cancellation on the mic (so hardware
 * AEC never engages no matter what the ADM asked for), no ducking of other apps, and the earpiece
 * is not even a routing candidate. That is both a latency cost and, on speaker, an echo cost.
 *
 * This is also the piece that makes the speaker button do something. `FlashCallSession.setSpeaker`
 * only flips a flag in the UI state; the flag has to land on the platform, which happens here.
 *
 * **Requires `MODIFY_AUDIO_SETTINGS`** (install-time, no prompt). Every platform call below is
 * refused without it, and because they are all best-effort the symptom is not a crash — it is a
 * call that quietly runs on the media path with a dead speaker button.
 *
 * Routing policy, in priority order when the user has not picked anything: Bluetooth headset, then
 * wired/USB headset, then the earpiece. Speaker ON pins the built-in speaker and drops any SCO link.
 * The earpiece is not pinned unless the user picked it (ADR-067) — pinning it unasked would mute a
 * connected headset — it is what the platform falls back to once nothing else is selected.
 *
 * **Picking an output (ADR-067).** [routes] lists what is available and what is in force, and
 * [setRoute] is the user's explicit pick. The pick is honoured while the device exists; unplug the
 * headset and it is forgotten and the automatic order takes over, rather than leaving the call
 * silent on a device that is gone. The rule itself is [FlashCallAudioRouting.resolve], which is pure
 * and unit-tested in `core:calling`.
 *
 * Bluetooth is version-split. From API 31 `setCommunicationDevice` covers it: selecting a
 * Bluetooth device brings up the SCO link as a side effect. Below 31 `MODE_IN_COMMUNICATION`
 * alone routes to the earpiece even with a headset connected, so SCO has to be started by hand
 * and `setBluetoothScoOn(true)` deferred until the headset reports CONNECTED — flipping it early
 * is the classic reason Bluetooth "connects" and then carries silence.
 *
 * Everything is best-effort and idempotent: OEM audio HALs reject mode and device changes in
 * states that are undocumented, and a call with mediocre routing beats a crash. Attach/detach are
 * driven from the call overlay's lifecycle, and detach restores whatever mode was in force before.
 */
class FlashCallAudioRouter(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var previousMode: Int? = null
    private var attached = false

    /**
     * The user's explicit pick (the speaker button, or the picker), or null for the automatic order. Re-applied
     * whenever the set of devices changes, and dropped when the device it names goes away.
     */
    private var chosen: FlashCallAudioRoute? = null

    private val _routes = MutableStateFlow(FlashCallAudioRoutes())

    /** What this phone can play the call through now, and the route in force. Empty before [attach]. */
    val routes: StateFlow<FlashCallAudioRoutes> = _routes.asStateFlow()
    private var scoStarted = false
    private var deviceCallback: AudioDeviceCallback? = null
    private var scoReceiver: BroadcastReceiver? = null

    /**
     * Takes voice-communication focus and puts the device in communication mode. Idempotent;
     * [speakerOn] is applied on the way in so the first frame of audio already comes out of the
     * right transducer.
     *
     * Focus is requested *before* the mode change on purpose: from Android 12 an app that owns
     * neither focus nor a telecom call is not allowed to set [AudioManager.MODE_IN_COMMUNICATION].
     */
    fun attach(speakerOn: Boolean) {
        val am = audioManager ?: return
        if (attached) {
            // Re-attached on every call state change by the host: the user's pick stays, only the route is re-applied.
            applyRoute(am)
            return
        }
        chosen = if (speakerOn) FlashCallAudioRoute.SPEAKER else null
        attached = true
        runCatching {
            previousMode = am.mode
            requestFocus(am)
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            registerDeviceCallback(am)
            registerScoReceiver()
            Log.i(TAG, "audio attached mode=${am.mode} speakerOn=$speakerOn")
        }.onFailure { Log.w(TAG, "audio attach failed", it) }
        applyRoute(am)
    }

    /**
     * Releases focus, tears down the SCO link, restores the previous mode and unpins the
     * routing. Idempotent, and safe to call from `onDispose` after the call is already gone.
     */
    fun detach() {
        val am = audioManager ?: return
        if (!attached) return
        attached = false
        chosen = null
        _routes.value = FlashCallAudioRoutes()
        runCatching {
            unregisterDeviceCallback(am)
            unregisterScoReceiver()
            stopSco(am)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = previousMode ?: AudioManager.MODE_NORMAL
            abandonFocus(am)
            Log.i(TAG, "audio detached mode=${am.mode}")
        }.onFailure { Log.w(TAG, "audio detach failed", it) }
        previousMode = null
    }

    /**
     * Routes playout to the speaker or back to the best available headset/earpiece. Safe to call
     * before [attach] (it simply has less effect) and safe to call repeatedly with the same value.
     */
    fun setSpeaker(on: Boolean) {
        chosen = if (on) FlashCallAudioRoute.SPEAKER else null
        applyRoute(audioManager ?: return)
    }

    /**
     * The user's explicit pick from the output list (ADR-067). Null goes back to the automatic order. A route that is
     * not available right now is ignored by [applyRoute] (and forgotten), so a stale tap cannot strand the call.
     */
    fun setRoute(route: FlashCallAudioRoute?) {
        chosen = route
        applyRoute(audioManager ?: return)
    }

    private fun applyRoute(am: AudioManager) {
        runCatching {
            val available = availableRoutes(am)
            if (!FlashCallAudioRouting.stillValid(available, chosen)) {
                Log.i(TAG, "route pick $chosen is gone, back to automatic")
                chosen = null
            }
            val route = FlashCallAudioRouting.resolve(available, chosen)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applyRouteApi31(am, route)
            } else {
                applyRouteLegacy(am, route)
            }
            _routes.value = FlashCallAudioRoutes(FlashCallAudioRouting.pickerOrder(available), route)
        }.onFailure { Log.w(TAG, "route apply failed", it) }
    }

    /** The outputs this phone can route a call to right now, as [FlashCallAudioRoute]s. */
    private fun availableRoutes(am: AudioManager): Set<FlashCallAudioRoute> {
        val devices: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        val routes = devices.mapNotNullTo(mutableSetOf()) { routeOf(it.type) }
        // Below API 31 a Bluetooth headset is only usable when SCO is available off-call.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !hasLegacyBluetoothHeadset(am)) {
            routes.remove(FlashCallAudioRoute.BLUETOOTH)
        }
        return routes
    }

    private fun routeOf(type: Int): FlashCallAudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> FlashCallAudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> FlashCallAudioRoute.SPEAKER
        in BLUETOOTH_TYPES -> FlashCallAudioRoute.BLUETOOTH
        in WIRED_TYPES -> FlashCallAudioRoute.WIRED
        else -> null
    }

    /**
     * Selecting a communication device is enough here — the platform brings up the Bluetooth
     * SCO link itself. Falling back to [AudioManager.clearCommunicationDevice] rather than
     * pinning the earpiece keeps the platform's own preference intact.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyRouteApi31(am: AudioManager, route: FlashCallAudioRoute?) {
        val devices = am.availableCommunicationDevices
        val wanted = when (route) {
            FlashCallAudioRoute.SPEAKER -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            FlashCallAudioRoute.BLUETOOTH -> firstOfTypes(devices, BLUETOOTH_PRIORITY)
            FlashCallAudioRoute.WIRED -> firstOfTypes(devices, WIRED_PRIORITY)
            // Pinned only on the user's explicit pick; otherwise the platform's own earpiece default stays.
            FlashCallAudioRoute.EARPIECE ->
                if (chosen == FlashCallAudioRoute.EARPIECE) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } else null
            null -> null
        }
        if (wanted != null) am.setCommunicationDevice(wanted) else am.clearCommunicationDevice()
        Log.i(TAG, "route=$route chosen=$chosen device=${wanted?.type ?: "platform"}")
    }

    private fun firstOfTypes(devices: List<AudioDeviceInfo>, types: List<Int>): AudioDeviceInfo? =
        types.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }

    /**
     * Below API 31 the speakerphone flag and the SCO link are the only levers, and they are not
     * mutually exclusive — leaving SCO up while forcing the speaker gives a call that plays out
     * of both. Hence the explicit stop on the speaker branch.
     */
    @Suppress("DEPRECATION")
    private fun applyRouteLegacy(am: AudioManager, route: FlashCallAudioRoute?) {
        when (route) {
            FlashCallAudioRoute.SPEAKER -> {
                stopSco(am)
                am.isSpeakerphoneOn = true
            }
            FlashCallAudioRoute.BLUETOOTH -> {
                am.isSpeakerphoneOn = false
                startSco(am)
            }
            // Wired headsets are taken by the platform itself once the speaker is off.
            FlashCallAudioRoute.WIRED, FlashCallAudioRoute.EARPIECE, null -> {
                stopSco(am)
                am.isSpeakerphoneOn = false
            }
        }
        Log.i(TAG, "route=$route chosen=$chosen sco=$scoStarted")
    }

    @Suppress("DEPRECATION")
    private fun hasLegacyBluetoothHeadset(am: AudioManager): Boolean =
        am.isBluetoothScoAvailableOffCall &&
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

    @Suppress("DEPRECATION")
    private fun startSco(am: AudioManager) {
        if (scoStarted) return
        scoStarted = true
        runCatching { am.startBluetoothSco() }
            .onFailure { Log.w(TAG, "sco start failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun stopSco(am: AudioManager) {
        if (!scoStarted) return
        scoStarted = false
        runCatching {
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
        }.onFailure { Log.w(TAG, "sco stop failed", it) }
    }

    /**
     * Re-applies the route when devices come and go, so plugging a headset mid-call moves the
     * audio instead of stranding it on the transducer that was chosen at attach time.
     */
    private fun registerDeviceCallback(am: AudioManager) {
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                applyRoute(am)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                applyRoute(am)
            }
        }
        deviceCallback = callback
        runCatching { am.registerAudioDeviceCallback(callback, null) }
            .onFailure { Log.w(TAG, "device callback registration failed", it) }
    }

    private fun unregisterDeviceCallback(am: AudioManager) {
        val callback = deviceCallback ?: return
        deviceCallback = null
        runCatching { am.unregisterAudioDeviceCallback(callback) }
    }

    /**
     * SCO comes up asynchronously: `startBluetoothSco()` only asks. `setBluetoothScoOn(true)`
     * has to wait for the headset to report CONNECTED, otherwise the flag lands on a link that
     * does not exist yet and the call is silent on Bluetooth.
     *
     * Legacy-only, so the Android 14 exported-flag requirement on dynamic receivers cannot
     * apply — and this is a protected system broadcast in any case.
     */
    @Suppress("DEPRECATION")
    private fun registerScoReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || scoReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: return
                if (state != AudioManager.SCO_AUDIO_STATE_CONNECTED || !scoStarted) return
                runCatching {
                    @Suppress("DEPRECATION")
                    audioManager?.isBluetoothScoOn = true
                }.onFailure { Log.w(TAG, "sco enable failed", it) }
            }
        }
        scoReceiver = receiver
        runCatching {
            appContext.registerReceiver(
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            )
        }.onFailure {
            scoReceiver = null
            Log.w(TAG, "sco receiver registration failed", it)
        }
    }

    private fun unregisterScoReceiver() {
        val receiver = scoReceiver ?: return
        scoReceiver = null
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun requestFocus(am: AudioManager) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // EXCLUSIVE, not TRANSIENT_MAY_DUCK: a ducked music stream still shares the output
            // mixer with the call, and its buffer is the one that sets the playout latency.
            val request = AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { /* Focus loss does not end a Flash call. */ }
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            val listener = AudioManager.OnAudioFocusChangeListener { }
            legacyFocusListener = listener
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                listener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }
    }

    private fun abandonFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyFocusListener?.let { am.abandonAudioFocus(it) }
            legacyFocusListener = null
        }
    }

    private companion object {
        const val TAG = "FlashCallAudio"

        /**
         * What the earpiece loses to when the speaker is off. Hearing aids sit with the headsets
         * because a user wearing them wants call audio there, not on the earpiece they cannot use.
         */
        val BLUETOOTH_PRIORITY = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        val WIRED_PRIORITY = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )
        val BLUETOOTH_TYPES = BLUETOOTH_PRIORITY.toSet()
        val WIRED_TYPES = WIRED_PRIORITY.toSet()
    }
}
