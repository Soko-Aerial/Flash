package com.transfer.flash.ui.theme

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberFlashSounds(): (FlashSound) -> Unit {
    val context = LocalContext.current.applicationContext
    return remember { { sound -> FlashSoundPlayer.play(context, sound) } }
}

/**
 * Internal AudioTrack backend. One MODE_STATIC track per event, created lazily on first
 * enabled play and reused via stop/reloadStaticData/play. USAGE_ASSISTANCE_SONIFICATION +
 * CONTENT_TYPE_SONIFICATION route tones to the system volume group and let the OS mute them
 * under Zen modes that disallow system sounds (belt-and-braces with [FlashSoundPolicy]).
 *
 * Moved here from `FlashSounds.kt` unchanged. It is the only thing in the sound stack that
 * touches `android.media`, and it is where the real `AudioManager.RINGER_MODE_NORMAL` /
 * `NotificationManager.INTERRUPTION_FILTER_ALL` constants are still read from the platform —
 * `commonMain`'s [FlashSoundPolicy] mirrors their *values*, this reads the device's actual state.
 */
internal object FlashSoundPlayer {

    private val lock = Any()
    private var appContextRef: Context? = null
    private val tracks = HashMap<FlashSound, AudioTrack>()

    fun play(context: Context, sound: FlashSound) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val allowed = FlashSoundPolicy.shouldPlay(
            soundsEnabled = FlashSoundSettings.soundsEnabled,
            ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL,
            interruptionFilter = notificationManager?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        if (!allowed) return

        synchronized(lock) {
            appContextRef = context.applicationContext
            try {
                val track = tracks.getOrPut(sound) { createTrack(sound) }
                track.stop()
                track.reloadStaticData()
                track.play()
            } catch (_: IllegalStateException) {
                releaseLocked(sound)
            } catch (_: IllegalArgumentException) {
                releaseLocked(sound)
            }
        }
    }

    private fun createTrack(sound: FlashSound): AudioTrack {
        val pcm = FlashSoundSynth.render(sound)
        val bytes = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(FlashSoundSynth.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    private fun releaseLocked(sound: FlashSound) {
        tracks.remove(sound)?.release()
        if (tracks.isEmpty()) appContextRef = null
    }
}
