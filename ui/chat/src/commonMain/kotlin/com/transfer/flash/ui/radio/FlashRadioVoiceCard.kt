@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.hub.FlashHubSwitch
import com.transfer.flash.ui.hub.FlashHubTextAction
import com.transfer.flash.ui.hub.HubShapes
import com.transfer.flash.ui.hub.hubClickable
import com.transfer.flash.ui.hub.hubRuleBelow
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Words of the radio voice card. Plain sentences; no protocol terms. */
public object FlashRadioVoiceCopy {
    public const val TITLE: String = "Radio voice"
    public const val SUBTITLE: String = "Hear what the radio receives, and talk through it from this phone."
    public const val LISTEN_LABEL: String = "Listen to the radio"
    public const val PHASE_OFF: String = "Off."
    public const val PHASE_CONNECTING: String = "Connecting to the radio's audio…"
    public const val PHASE_LISTENING: String = "Listening. Quiet right now."
    public const val PHASE_RECEIVING: String = "Receiving: you can hear the radio."
    public const val PHASE_TRANSMITTING: String = "Transmitting: the radio is sending your voice."
    public const val PHASE_FAILED: String = "The audio link is not working."
    public const val HOLD_TO_TALK: String = "Hold to talk"
    public const val HOLD_TO_TALK_ACTIVE: String = "Talking…"
    public const val NEEDS_MICROPHONE: String = "Flash needs the microphone to talk through the radio."
    public const val ALLOW_MICROPHONE: String = "Allow microphone"

    /** Voice goes out as ordinary analog audio, readable by anyone with a receiver; the operator must identify. */
    public const val STATION_ID_NOTE: String =
        "Voice goes out on the air as ordinary audio, not encrypted. Say your callsign as your licence requires."
    public const val A11Y_LISTEN_SWITCH: String = "Listen to the radio switch"
    public const val A11Y_TALK_BUTTON: String = "Hold to talk through the radio"

    public fun phaseText(phase: RadioVoicePhase): String = when (phase) {
        RadioVoicePhase.OFF -> PHASE_OFF
        RadioVoicePhase.CONNECTING -> PHASE_CONNECTING
        RadioVoicePhase.LISTENING -> PHASE_LISTENING
        RadioVoicePhase.RECEIVING -> PHASE_RECEIVING
        RadioVoicePhase.TRANSMITTING -> PHASE_TRANSMITTING
        RadioVoicePhase.FAILED -> PHASE_FAILED
    }

    /** Talking needs a live audio link and a quiet radio. */
    public fun canTalk(phase: RadioVoicePhase): Boolean =
        phase == RadioVoicePhase.LISTENING || phase == RadioVoicePhase.TRANSMITTING
}

/**
 * Radio voice as a page section (redesign v2): the section label, one switch row, the phase sentence, the level bar
 * and the hold-to-talk surface. It used to be a card of its own, which pushed "Find my radio" below the fold.
 */
@Composable
public fun FlashRadioVoiceSection(
    voice: RadioVoiceUiState,
    onListenChange: (Boolean) -> Unit,
    onTalkDown: () -> Unit,
    onTalkUp: () -> Unit,
    onAllowMicrophone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val listening = voice.phase != RadioVoicePhase.OFF && voice.phase != RadioVoicePhase.FAILED
    val canTalk = FlashRadioVoiceCopy.canTalk(voice.phase)
    val talking = voice.phase == RadioVoicePhase.TRANSMITTING
    val down by rememberUpdatedState(onTalkDown)
    val up by rememberUpdatedState(onTalkUp)

    Column(modifier = modifier.fillMaxWidth()) {
        FlashRadioSectionHeader(FlashRadioVoiceCopy.TITLE)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .hubRuleBelow()
                .padding(vertical = FlashSpacing.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
            ) {
                FlashText(
                    text = FlashRadioVoiceCopy.LISTEN_LABEL,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                )
                FlashText(
                    text = FlashRadioVoiceCopy.phaseText(voice.phase),
                    style = FlashTheme.typography.metadataDefault,
                    color = if (voice.phase == RadioVoicePhase.FAILED) colors.textError else colors.textSecondary,
                )
            }
            Box(
                modifier = Modifier
                    .size(FlashDimensions.minTouchTarget)
                    .hubClickable { onListenChange(!listening) }
                    .semantics {
                        contentDescription = FlashRadioVoiceCopy.A11Y_LISTEN_SWITCH
                        stateDescription = if (listening) FlashRadioCopy.VALUE_ON else FlashRadioCopy.VALUE_OFF
                    },
                contentAlignment = Alignment.Center,
            ) {
                FlashHubSwitch(on = listening)
            }
        }
        if (voice.message != null) {
            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            FlashText(
                text = voice.message,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
        if (listening) {
            Spacer(modifier = Modifier.height(FlashSpacing.space12))
            LevelBar(level = if (talking) voice.txLevel else voice.rxLevel)
            Spacer(modifier = Modifier.height(FlashSpacing.space12))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HubShapes.control)
                    .background(
                        when {
                            talking -> colors.accentPrimary
                            canTalk -> colors.accentPrimary.copy(alpha = 0.12f)
                            else -> colors.backgroundSurfaceStrong
                        },
                    )
                    .border(
                        width = FlashDimensions.borderHairline,
                        color = if (canTalk) colors.accentPrimary.copy(alpha = 0.5f) else colors.borderSubtle,
                        shape = HubShapes.control,
                    )
                    .pointerInput(canTalk) {
                        if (canTalk) {
                            detectTapGestures(onPress = {
                                down()
                                tryAwaitRelease()
                                up()
                            })
                        }
                    }
                    .padding(vertical = FlashSpacing.space16)
                    .semantics {
                        role = Role.Button
                        contentDescription = FlashRadioVoiceCopy.A11Y_TALK_BUTTON
                    },
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = if (talking) FlashRadioVoiceCopy.HOLD_TO_TALK_ACTIVE else FlashRadioVoiceCopy.HOLD_TO_TALK,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = when {
                        talking -> colors.textOnAccent
                        canTalk -> colors.accentPrimary
                        else -> colors.textTertiary
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (voice.needsMicrophonePermission) {
            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = FlashDimensions.minTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                FlashText(
                    text = FlashRadioVoiceCopy.NEEDS_MICROPHONE,
                    modifier = Modifier.weight(1f),
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                )
                FlashHubTextAction(
                    label = FlashRadioVoiceCopy.ALLOW_MICROPHONE,
                    onClick = onAllowMicrophone,
                )
            }
        }
        Spacer(modifier = Modifier.height(FlashSpacing.space12))
        FlashText(
            text = FlashRadioVoiceCopy.STATION_ID_NOTE,
            modifier = Modifier.padding(bottom = FlashSpacing.space4),
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun LevelBar(level: Float) {
    val colors = FlashTheme.colors
    val shape = HubShapes.capsule
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(colors.backgroundSurfaceStrong),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(shape)
                .background(colors.accentPrimary),
        )
    }
}
