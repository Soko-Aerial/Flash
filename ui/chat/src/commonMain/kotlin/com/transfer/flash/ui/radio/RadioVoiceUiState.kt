package com.transfer.flash.ui.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the radio's voice (audio) link is. Plain types only: `:ui:chat` does not depend on `:core:radio`. */
public enum class RadioVoicePhase { OFF, CONNECTING, LISTENING, RECEIVING, TRANSMITTING, FAILED }

/**
 * UI state of the radio voice feature (listen to the radio, hold to talk through it).
 *
 * @property available the host can do radio voice at all (Android first; the desktop host reports false).
 * @property rxLevel 0..1 loudness of what the radio is receiving.
 * @property txLevel 0..1 loudness of the microphone while transmitting.
 * @property message a short sentence for people (why it failed, why talking was refused), or null.
 * @property needsMicrophonePermission the last attempt to talk was stopped by the missing microphone permission.
 */
public data class RadioVoiceUiState(
    val available: Boolean = false,
    val phase: RadioVoicePhase = RadioVoicePhase.OFF,
    val rxLevel: Float = 0f,
    val txLevel: Float = 0f,
    val message: String? = null,
    val needsMicrophonePermission: Boolean = false,
)

/** The state a controller without voice support reports. */
internal val NoRadioVoice: StateFlow<RadioVoiceUiState> = MutableStateFlow(RadioVoiceUiState())
