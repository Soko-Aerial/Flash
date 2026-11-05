@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.radio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import com.transfer.flash.ui.hub.FlashHubChip
import com.transfer.flash.ui.hub.FlashHubSwitch
import com.transfer.flash.ui.hub.FlashHubTextAction
import com.transfer.flash.ui.hub.HubShapes
import com.transfer.flash.ui.hub.hubClickable
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Words of the radio voice panel. Plain sentences; no protocol terms. */
public object FlashRadioVoiceCopy {
    public const val TITLE: String = "Radio audio"
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

    /** Keying warning and licence reminder in one sentence: the two facts an operator must know before talking. */
    public const val STATION_ID_NOTE: String =
        "Talking keys the transmitter and goes out as clear audio. Say your callsign as your licence requires."
    public const val A11Y_LISTEN_SWITCH: String = "Listen to the radio switch"

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
 * The radio audio panel (redesign v3). On a phone it is the dock above the shell's bottom bar, so hold-to-talk
 * sits under the thumb; on a wide window the same panel sits under the station plate. Either way it is one
 * surface: listen switch, level, the push-to-talk control, and the one sentence a keyed transmitter needs.
 *
 * [linkLive] is not decoration: a PTT is never offered while the radio link is down. The control stays in place
 * (so the page does not reflow) but is disabled and says why inside itself.
 */
@Composable
public fun FlashRadioAudioPanel(
    voice: RadioVoiceUiState,
    linkLive: Boolean,
    onListenChange: (Boolean) -> Unit,
    onTalkDown: () -> Unit,
    onTalkUp: () -> Unit,
    onAllowMicrophone: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    /** True in the phone dock: the state line and the talk control stack instead of sharing a row. */
    stacked: Boolean = false,
) {
    val colors = FlashTheme.colors
    val listening = voice.phase != RadioVoicePhase.OFF && voice.phase != RadioVoicePhase.FAILED
    val talking = voice.phase == RadioVoicePhase.TRANSMITTING
    val canTalk = FlashRadioVoiceCopy.canTalk(voice.phase) && linkLive
    val level = when {
        talking -> voice.txLevel
        voice.phase == RadioVoicePhase.RECEIVING -> voice.rxLevel
        listening -> voice.rxLevel
        else -> 0f
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HubShapes.card)
            .background(colors.backgroundSurfaceStrong)
            .padding(
                horizontal = if (dense) FlashSpacing.space16 else FlashSpacing.space20,
                vertical = if (dense) FlashSpacing.space12 else FlashSpacing.space16,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            FlashText(
                text = FlashRadioCopy.SECTION_RADIO,
                modifier = Modifier.weight(1f),
                style = FlashTheme.typography.bodyEmphasis.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.textPrimary,
            )
            Box(
                modifier = Modifier
                    .size(FlashDimensions.minTouchTarget)
                    .hubClickable { onListenChange(!listening) }
                    .semantics {
                        contentDescription = FlashRadioCopy.A11Y_LISTEN
                        stateDescription = if (listening) FlashRadioCopy.VALUE_ON else FlashRadioCopy.VALUE_OFF
                    },
                contentAlignment = Alignment.Center,
            ) {
                FlashHubSwitch(on = listening)
            }
        }

        if (!listening) {
            Spacer(modifier = Modifier.height(FlashSpacing.space4))
            FlashText(
                text = FlashRadioCopy.AUDIO_OFF_NOTE,
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary,
            )
        } else {
            Spacer(modifier = Modifier.height(if (dense) FlashSpacing.space4 else FlashSpacing.space8))
            if (stacked) {
                // Phone dock: the state line, then a full-width hold-to-talk under the thumb.
                Column(modifier = Modifier.fillMaxWidth()) {
                    RadioAudioStateRow(voice = voice, linkLive = linkLive, level = level, listening = listening)
                    Spacer(modifier = Modifier.height(FlashSpacing.space8))
                    RadioPushToTalk(
                        enabled = canTalk,
                        talking = talking,
                        needsLink = !linkLive,
                        onDown = onTalkDown,
                        onUp = onTalkUp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                // Wide window: the same row the design shows - state on the left, the talk control on the right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    RadioAudioStateRow(
                        voice = voice,
                        linkLive = linkLive,
                        level = level,
                        listening = listening,
                        modifier = Modifier.weight(1f),
                    )
                    RadioPushToTalk(
                        enabled = canTalk,
                        talking = talking,
                        needsLink = !linkLive,
                        onDown = onTalkDown,
                        onUp = onTalkUp,
                        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        tall = true,
                    )
                }
            }
        }

        voice.message?.takeIf { it.isNotBlank() }?.let { message ->
            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            FlashText(
                text = message,
                style = FlashTheme.typography.metadataDefault,
                color = if (voice.phase == RadioVoicePhase.FAILED) colors.textError else colors.textSecondary,
            )
        }

        if (voice.needsMicrophonePermission) {
            Spacer(modifier = Modifier.height(FlashSpacing.space4))
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

        Spacer(modifier = Modifier.height(if (dense) FlashSpacing.space4 else FlashSpacing.space8))
        FlashText(
            text = FlashRadioVoiceCopy.STATION_ID_NOTE,
            style = FlashTheme.typography.metadataDefault.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = colors.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The state line of the audio panel: what the radio is doing, the level, and the on-air chip. Shared by the
 * dock (its own line) and the wide layout (left half of the row).
 */
@Composable
private fun RadioAudioStateRow(
    voice: RadioVoiceUiState,
    linkLive: Boolean,
    level: Float,
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val talking = voice.phase == RadioVoicePhase.TRANSMITTING
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashText(
            text = when {
                talking -> FlashRadioCopy.VALUE_TALKING
                !linkLive -> FlashRadioCopy.AUDIO_NO_LINK
                voice.phase == RadioVoicePhase.RECEIVING -> FlashRadioCopy.VALUE_RECEIVING
                else -> FlashRadioCopy.AUDIO_LISTENING
            },
            style = FlashTheme.typography.metadataDefault,
            color = when {
                talking -> colors.accentSecondary
                !linkLive -> colors.textError
                else -> colors.textSecondary
            },
        )
        RadioLevelBars(level = if (linkLive) level else 0f, active = listening && linkLive)
        if (talking) {
            FlashHubChip(text = FlashRadioCopy.CHIP_ON_AIR)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * The push-to-talk control. Resting it is an outline, holding it fills with the accent, and a live transmission
 * turns it warm with the word "Talking…" — the one control on the page whose appearance changes *while* it is
 * being used, because "did I key the transmitter?" must be answerable at a glance.
 */
@Composable
private fun RadioPushToTalk(
    enabled: Boolean,
    talking: Boolean,
    needsLink: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && !motion.reduceMotion) 0.985f else 1f,
        animationSpec = motion.tweenFastSpec(),
        label = "radioPttPress",
    )

    val background: Color = when {
        talking -> colors.accentSecondary
        enabled -> colors.accentPrimary
        else -> colors.backgroundSurfaceSubtle
    }
    val foreground: Color = when {
        talking -> colors.backgroundApp
        enabled -> colors.textOnAccent
        else -> colors.textTertiary
    }

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = if (tall) 64.dp else 56.dp)
            .clip(HubShapes.control)
            .background(background)
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                val activation = event.key == Key.Spacebar || event.key == Key.Enter
                if (!activation) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (!pressed) {
                            pressed = true
                            down()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (pressed) {
                            pressed = false
                            up()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled = enabled)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            down()
                            tryAwaitRelease()
                            pressed = false
                            up()
                        },
                    )
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = FlashRadioCopy.A11Y_TALK
                stateDescription = if (talking) FlashRadioCopy.VALUE_TALKING else FlashRadioCopy.VALUE_READY
            }
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8, Alignment.CenterHorizontally),
    ) {
        FlashIcon(
            icon = FlashIcons.Microphone,
            contentDescription = null,
            tint = foreground,
            size = FlashDimensions.iconSm,
        )
        FlashText(
            text = when {
                talking -> FlashRadioVoiceCopy.HOLD_TO_TALK_ACTIVE
                needsLink -> FlashRadioCopy.AUDIO_NEEDS_LINK
                else -> FlashRadioVoiceCopy.HOLD_TO_TALK
            },
            style = FlashTheme.typography.bodyEmphasis,
            color = foreground,
            textAlign = TextAlign.Center,
        )
    }
}

/** Five bars of receive/transmit level, drawn rather than a progress track so a quiet link still reads as alive. */
@Composable
private fun RadioLevelBars(level: Float, active: Boolean) {
    val colors = FlashTheme.colors
    val heights = remember { listOf(4.dp, 7.dp, 10.dp, 12.dp, 8.dp) }
    val filled = if (!active) {
        0
    } else {
        ceil(level.coerceIn(0f, 1f) * heights.size).toInt().coerceIn(1, heights.size)
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        heights.forEachIndexed { index, height ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < filled) colors.accentPrimary else colors.borderStrong),
            )
        }
    }
}
