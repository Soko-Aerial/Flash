package com.transfer.flash.desktop.radio

import com.transfer.flash.core.radio.audio.JvmRadioPcmInput
import com.transfer.flash.core.radio.audio.JvmRadioPcmOutput
import com.transfer.flash.core.radio.audio.RadioAudioPhase
import com.transfer.flash.core.radio.audio.RadioAudioSession
import com.transfer.flash.core.radio.audio.RadioPcmInput
import com.transfer.flash.core.radio.audio.RadioPcmOutput
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.LinkException
import com.transfer.flash.ui.radio.RadioVoicePhase
import com.transfer.flash.ui.radio.RadioVoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The radio's audio link on the desktop (ADR-225, the same session as Android): opens the vendor audio channel of the
 * chosen radio, plays what the radio receives and sends the microphone while hold-to-talk is pressed. Independent of the
 * KISS serial link. Foreground only: the screen closes it when it is left. [scope] is the controller's UI scope.
 */
internal class DesktopRadioVoice(
    private val scope: CoroutineScope,
    private val openLink: suspend () -> ByteLink?,
    private val newOutput: () -> RadioPcmOutput = { JvmRadioPcmOutput() },
    private val newInput: () -> RadioPcmInput = { JvmRadioPcmInput() },
    private val microphoneOpens: () -> Boolean = { JvmRadioPcmInput().canOpen() },
    private val log: (String) -> Unit = {},
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
        _state.value = RadioVoiceUiState(available = true, phase = RadioVoicePhase.CONNECTING)
        openJob = scope.launch {
            val link = try {
                openLink()
            } catch (e: LinkException) {
                log("radio audio link refused: ${e.message}")
                _state.value = failed(e.message ?: DEFAULT_OPEN_FAILURE)
                return@launch
            } catch (e: Exception) {
                log("radio audio link failed: ${e.message}")
                _state.value = failed(DEFAULT_OPEN_FAILURE)
                return@launch
            }
            if (link == null) {
                _state.value = failed("Choose a radio first.")
                return@launch
            }
            val s = RadioAudioSession(
                link = link,
                output = newOutput(),
                input = newInput(),
                scope = scope,
                log = log,
            )
            session = s
            mirrorJob = launch {
                s.state.collect { a ->
                    _state.update {
                        it.copy(phase = a.phase.toUi(), rxLevel = a.rxLevel, txLevel = a.txLevel, message = a.message)
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
        // Without this the session would report "audio link lost" when the microphone is blocked in Windows privacy settings.
        if (!microphoneOpens()) {
            _state.update { it.copy(message = MICROPHONE_BLOCKED) }
            return
        }
        s.pushToTalkDown()
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
        const val DEFAULT_OPEN_FAILURE =
            "Could not open the radio's audio channel. Check that the radio is on, in range and connected to this computer."
        const val MICROPHONE_BLOCKED =
            "The microphone could not be opened. In Windows Settings > Privacy & security > Microphone, allow desktop apps to use it."
    }
}
