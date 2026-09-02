package com.transfer.flash.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState

/**
 * Makes the phone ring for a Flash call (C7): the user's ringtone plus vibration while an invite
 * is RINGING, and the supervisory ringback tone while an outgoing call is DIALING.
 *
 * ## Why the app rings instead of the notification
 * The incoming-call notification was expected to ring and never did. A [android.app.NotificationChannel]
 * sound plays **once** — looping needs `FLAG_INSISTENT`, which only the system dialer may set — and a
 * channel's sound is immutable after creation, so the mistake cannot be corrected in place either.
 * Owning the ring here also means it stops the instant the call is answered rather than running to
 * the end of the audio file, and it lets the ringer mode decide sound-vs-vibrate the way a dialer
 * would. The call channel is therefore deliberately silent (see [FlashCallService]).
 *
 * ## How it coexists with [FlashCallAudioRouter]
 * The ringtone plays on [AudioAttributes.USAGE_NOTIFICATION_RINGTONE] (the ring stream: system ring
 * volume, DND-suppressed) and holds only TRANSIENT focus, which is why the call overlay leaves the
 * router detached while RINGING — exclusive voice-communication focus would silence the ring. When
 * the user answers, the router's EXCLUSIVE request lands as an `AUDIOFOCUS_LOSS` here and the ring
 * stops immediately, before the CONNECTING state tick even arrives.
 *
 * Voice and video ring identically: [onCallState] keys off the call state alone and never reads
 * [FlashCallUiState.video].
 *
 * Every platform call is best-effort — a phone with no vibrator, a deleted ringtone or an OEM that
 * refuses a `ToneGenerator` must still get the call, just more quietly.
 */
class FlashCallRinger(context: Context) {

    private enum class Ring { NONE, INCOMING, OUTGOING }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val vibrator: Vibrator? by lazy { resolveVibrator() }

    /** Guards the whole player/tone/vibrator/focus set; reentrant, so callbacks may re-enter. */
    private val lock = Any()

    private var ring = Ring.NONE
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var vibrating = false

    /**
     * In practice this fires once per call, when [FlashCallAudioRouter] takes EXCLUSIVE
     * voice-communication focus on the answer path. Stopping on the focus edge rather than waiting
     * for the CONNECTING state tick is what keeps the ringtone from overlapping the first moment of
     * call audio. `CAN_DUCK` is deliberately not handled — a ring that ducks is still a ring.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            Log.i(TAG, "ring lost audio focus (change=$change) — stopping")
            stop()
        }
    }

    /**
     * The only entry point the wiring needs: hand it every
     * [com.transfer.flash.core.calling.CallCoordinator.activeCall] emission and the ringer follows
     * the call state machine.
     *
     * CONNECTING is the accept edge on both sides — the callee's ringtone and the caller's ringback
     * both have to be gone before media starts — so everything except RINGING/DIALING stops.
     */
    fun onCallState(state: FlashCallUiState?) {
        when (state?.state) {
            FlashCallState.RINGING -> startIncoming()
            FlashCallState.DIALING -> startOutgoing()
            FlashCallState.CONNECTING,
            FlashCallState.ACTIVE,
            FlashCallState.ENDED,
            null,
            -> stop()
        }
    }

    /**
     * Incoming invite: loop the user's ringtone and vibrate, honouring the ringer mode the way a
     * dialer does — silent stays silent, vibrate-only vibrates, and vibration in normal mode is the
     * user's "vibrate for calls" preference. Idempotent.
     */
    fun startIncoming() {
        synchronized(lock) {
            if (ring == Ring.INCOMING) return
            stopLocked()
            ring = Ring.INCOMING
            val mode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
            if (mode == AudioManager.RINGER_MODE_SILENT) {
                Log.i(TAG, "ringer mode SILENT — incoming call stays quiet")
                return
            }
            if (mode == AudioManager.RINGER_MODE_VIBRATE || vibrateWhenRinging()) {
                startVibrationLocked()
            }
            if (mode != AudioManager.RINGER_MODE_VIBRATE) {
                requestRingFocusLocked()
                startRingtoneLocked()
            }
        }
    }

    /**
     * Outgoing invite: the supervisory ringback tone (425 Hz, 1 s on / 4 s off, repeating) on the
     * call stream, so it follows the route [FlashCallAudioRouter] picked — earpiece by default,
     * speaker when the user asked for it. Unconditional: ringback is call audio, and a silenced
     * ringer says nothing about whether *you* may hear your own outgoing call. Idempotent.
     */
    fun startOutgoing() {
        synchronized(lock) {
            if (ring == Ring.OUTGOING) return
            stopLocked()
            ring = Ring.OUTGOING
            tone = runCatching {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).apply {
                    startTone(ToneGenerator.TONE_SUP_RINGTONE)
                }
            }.onFailure { Log.w(TAG, "ringback unavailable", it) }.getOrNull()
        }
    }

    /** Silences everything. Idempotent, safe from any thread, safe when nothing is ringing. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        ring = Ring.NONE
        player?.let { active ->
            runCatching { if (active.isPlaying) active.stop() }
            runCatching { active.release() }
        }
        player = null
        tone?.let { active ->
            runCatching { active.stopTone() }
            runCatching { active.release() }
        }
        tone = null
        if (vibrating) {
            vibrating = false
            runCatching { vibrator?.cancel() }
        }
        abandonFocusLocked()
    }

    private fun startRingtoneLocked() {
        val configured = defaultRingtoneUri()
        if (configured == null) {
            Log.i(TAG, "default ringtone is None — vibrate only")
            return
        }
        // A ringtone the user picked from their own files can be unreadable to us (revoked grant,
        // deleted file, unmounted volume). Falling back to any valid ringtone beats a silent call.
        player = openLoopingPlayer(configured)
            ?: openLoopingPlayer(runCatching { RingtoneManager.getValidRingtoneUri(appContext) }.getOrNull())
        if (player == null) Log.w(TAG, "no playable ringtone — vibrate only")
    }

    private fun openLoopingPlayer(uri: Uri?): MediaPlayer? {
        if (uri == null) return null
        val media = MediaPlayer()
        return runCatching {
            media.setAudioAttributes(ringtoneAttributes())
            media.setDataSource(appContext, uri)
            media.isLooping = true
            media.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "ringtone playback error what=$what extra=$extra")
                true
            }
            media.prepare()
            media.start()
            media
        }.onFailure { error ->
            Log.w(TAG, "ringtone $uri failed to play", error)
            runCatching { media.release() }
        }.getOrNull()
    }

    /**
     * `null` means the user's default ringtone is "None" — a deliberate choice, honoured rather
     * than overridden with something loud. A *failure to read* the setting is our problem, not the
     * user's preference, so that case falls back to the symbolic default URI.
     */
    private fun defaultRingtoneUri(): Uri? =
        runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)
        }.getOrElse { Settings.System.DEFAULT_RINGTONE_URI }

    /** Ring stream: system ring volume, and suppressed by Do Not Disturb without extra work. */
    private fun ringtoneAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Settings' "Vibrate for calls". Read through the raw key so this compiles identically on every
     * platform level; defaults to off, because with an audible ringer vibration is an opt-in.
     */
    private fun vibrateWhenRinging(): Boolean =
        runCatching {
            Settings.System.getInt(appContext.contentResolver, SETTING_VIBRATE_WHEN_RINGING, 0) != 0
        }.getOrDefault(false)

    private fun startVibrationLocked() {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // repeat = 0: restart the waveform from its first entry, i.e. buzz until cancelled.
                val effect = VibrationEffect.createWaveform(VIBRATE_PATTERN, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // VibrationAttributes superseded AudioAttributes for vibration in API 33.
                    // USAGE_RINGTONE is what keeps the buzz alive under a DND/priority profile that
                    // allows calls, the same classification the platform dialer's ring uses.
                    device.vibrate(
                        effect,
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    device.vibrate(effect, ringtoneAttributes())
                }
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(VIBRATE_PATTERN, 0)
            }
            vibrating = true
        }.onFailure { Log.w(TAG, "vibration failed", it) }
    }

    private fun resolveVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /**
     * TRANSIENT, not EXCLUSIVE: music should pause for a ring, and unlike the in-call router this
     * is not trying to own the output mixer — it hands over to [FlashCallAudioRouter] on answer.
     */
    private fun requestRingFocusLocked() {
        val manager = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(ringtoneAttributes())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                legacyFocusListener = focusListener
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
        }.onFailure { Log.w(TAG, "ring focus request failed", it) }
    }

    /**
     * Releasing focus is what lets whatever was playing before the call resume. Cleared
     * unconditionally so a failed request (or an OEM that never granted it) still leaves no state
     * behind for the next call to trip over.
     */
    private fun abandonFocusLocked() {
        val manager = audioManager
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { manager?.abandonAudioFocusRequest(it) }
            } else {
                legacyFocusListener?.let {
                    @Suppress("DEPRECATION")
                    manager?.abandonAudioFocus(it)
                }
            }
        }.onFailure { Log.w(TAG, "abandoning ring focus failed", it) }
        focusRequest = null
        legacyFocusListener = null
    }

    private companion object {
        const val TAG = "FlashCallRing"

        /** Off 0 ms, buzz 1 s, pause 1 s — the familiar incoming-call cadence, repeated. */
        val VIBRATE_PATTERN = longArrayOf(0L, 1_000L, 1_000L)

        /** [ToneGenerator] volume is 0..100; 80 matches the platform's own ringback loudness. */
        const val RINGBACK_VOLUME = 80

        /**
         * `Settings.System.VIBRATE_WHEN_RINGING` — public as a value but `@hide` as a constant on
         * some levels, so the key is spelled out rather than referenced.
         */
        const val SETTING_VIBRATE_WHEN_RINGING = "vibrate_when_ringing"
    }
}
