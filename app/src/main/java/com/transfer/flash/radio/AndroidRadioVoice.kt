package com.transfer.flash.radio

import android.content.Context
import android.util.Log
import com.transfer.flash.core.radio.audio.AndroidRadioPcmInput
import com.transfer.flash.core.radio.audio.AndroidRadioPcmOutput
import com.transfer.flash.core.radio.audio.RadioAudioPhase
import com.transfer.flash.core.radio.audio.RadioAudioSession
import com.transfer.flash.core.radio.link.AndroidBluetoothCatalog
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.LinkException
import com.transfer.flash.ui.radio.RadioVoicePhase
import com.transfer.flash.ui.radio.RadioVoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The radio's audio link on Android (ADR-225): opens the vendor RFCOMM channel of the remembered radio, plays what
 * the radio receives and sends the microphone while hold-to-talk is pressed. Independent of the KISS serial link.
 * Foreground only: the screen closes it when it is left. [scope] is the main-thread controller scope.
 */
internal class AndroidRadioVoice(
    private val context: Context,
    private val scope: CoroutineScope,
    private val radioAddress: () -> String?,
    private val hasMicrophonePermission: () -> Boolean,
    private val requestMicrophonePermission: () -> Unit,
    private val openLink: suspend (String) -> ByteLink = { address ->
        withContext(Dispatchers.IO) { AndroidBluetoothCatalog(context).openRadioAudioLink(address) }
    },
) {
    private val _state = MutableStateFlow(RadioVoiceUiState(available = true))
    val state: StateFlow<RadioVoiceUiState> = _state.asStateFlow()

    private var session: RadioAudioSession? = null
    private var openJob: Job? = null
    private var mirrorJob: Job? = null

    fun setListening(on: Boolean) {
        if (!on) {
            close()
            _state.value = RadioVoiceUiState(available = true)
            return
        }
        if (openJob?.isActive == true || session != null) return
        val address = radioAddress()
        if (address == null) {
            _state.value = RadioVoiceUiState(available = true, phase = RadioVoicePhase.FAILED, message = "Choose a radio first.")
            return
        }
        _state.value = RadioVoiceUiState(available = true, phase = RadioVoicePhase.CONNECTING)
        openJob = scope.launch {
            val link = try {
                openLink(address)
            } catch (e: LinkException) {
                Log.w(TAG, "radio audio link refused: ${e.message}")
                _state.value = failed(
                    "Could not open the radio's audio channel. Check that the radio is on, in range and paired " +
                        "as a speaker/headset as well.",
                )
                return@launch
            } catch (e: SecurityException) {
                _state.value = failed("Bluetooth permission is needed for the radio's audio.")
                return@launch
            }
            val s = RadioAudioSession(
                link = link,
                output = AndroidRadioPcmOutput(),
                input = AndroidRadioPcmInput(),
                scope = scope,
                log = { Log.i(TAG, it) },
            )
            session = s
            mirrorJob = launch {
                s.state.collect { a ->
                    _state.update {
                        it.copy(
                            phase = a.phase.toUi(),
                            rxLevel = a.rxLevel,
                            txLevel = a.txLevel,
                            message = a.message,
                        )
                    }
                }
            }
            s.start()
        }
    }

    fun pushToTalk(down: Boolean) {
        val s = session ?: return
        if (!down) {
            s.pushToTalkUp()
            return
        }
        if (!hasMicrophonePermission()) {
            _state.update { it.copy(needsMicrophonePermission = true) }
            requestMicrophonePermission()
            return
        }
        _state.update { it.copy(needsMicrophonePermission = false) }
        s.pushToTalkDown()
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        _state.update { it.copy(needsMicrophonePermission = !granted && it.needsMicrophonePermission) }
    }

    fun close() {
        openJob?.cancel(); openJob = null
        mirrorJob?.cancel(); mirrorJob = null
        session?.close(); session = null
    }

    private fun failed(message: String) =
        RadioVoiceUiState(available = true, phase = RadioVoicePhase.FAILED, message = message)

    private fun RadioAudioPhase.toUi(): RadioVoicePhase = when (this) {
        RadioAudioPhase.LISTENING -> RadioVoicePhase.LISTENING
        RadioAudioPhase.RECEIVING -> RadioVoicePhase.RECEIVING
        RadioAudioPhase.TRANSMITTING -> RadioVoicePhase.TRANSMITTING
        RadioAudioPhase.CLOSED -> RadioVoicePhase.OFF
        RadioAudioPhase.FAILED -> RadioVoicePhase.FAILED
    }

    private companion object {
        const val TAG = "RadioVoice"
    }
}
