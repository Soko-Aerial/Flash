package com.transfer.flash.ui.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale

/**
 * The participant strip of the compact group video layout (UI-050c, G5; `docs/ui/calling-ui.md`):
 * everyone as an avatar chip (speaking ring, mute badge, first name). The chip of the person in the
 * main tile sits on a pill; a tap pins that person's video, a tap on the pinned one unpins it.
 *
 * Avatars only: a compact device receives one video (G4), so live thumbnails would need decoders
 * it does not have.
 */
@Composable
internal fun FlashGroupVideoStrip(
    state: FlashCallUiState,
    onVideoFocus: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val main = groupVideoMainPeer(state)
    val people = state.participants.filter { it.state != FlashCallParticipantState.LEFT }
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = FlashSpacing.space16),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        people.forEach { participant ->
            FlashGroupVideoChip(
                participant = participant,
                shown = participant.peerId == main,
                pinned = participant.peerId == state.videoFocusPeerId,
                onClick = { onVideoFocus(nextVideoFocus(state, participant.peerId)) },
                dataSaver = state.dataSaver,
                showingFewer = state.showingFewerVideos,
            )
        }
    }
}

@Composable
private fun FlashGroupVideoChip(
    participant: FlashCallParticipantUi,
    shown: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    dataSaver: Boolean,
    showingFewer: Boolean,
) {
    val colors = FlashTheme.colors
    val interaction = remember { MutableInteractionSource() }
    // ERROR-105: the chip is avatar-only, so the reason a tap shows no video is spoken (the main tile prints it).
    val status = participantStatusLabel(participant, dataSaver, showingFewer, isMain = shown)
    val description = buildString {
        append(participant.name)
        append(if (shown) ", shown" else ", not shown")
        if (status != null) append(", ").append(status)
        if (pinned) append(", pinned")
    }
    Column(
        modifier = Modifier
            .width(CHIP_WIDTH)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(if (shown) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .flashPressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = videoFocusClickLabel(participant.name, pinned),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description }
            .padding(vertical = FlashSpacing.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(FlashDimensions.minTouchTarget), contentAlignment = Alignment.Center) {
            FlashAvatar(
                initials = participant.name.take(2),
                seed = participant.peerId,
                size = FlashDimensions.avatarMd,
                modifier = if (participant.isSpeaking) {
                    Modifier.border(2.dp, colors.statusOnline, CircleShape)
                } else {
                    Modifier
                },
            )
            if (participant.handRaised) {
                FlashPeerBadges(
                    micMuted = false,
                    cameraOff = false,
                    handRaised = true,
                    onDark = true,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            if (participant.isMuted) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(BADGE_SIZE)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashIcon(icon = FlashIcons.MicOff, contentDescription = null, tint = Color.White, size = BADGE_ICON)
                }
            }
        }
        Spacer(Modifier.height(FlashSpacing.space4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pinned) {
                FlashIcon(icon = FlashIcons.Pin, contentDescription = null, tint = Color.White, size = BADGE_ICON)
            }
            Text(
                text = participant.name.substringBefore(' '),
                style = FlashTheme.typography.metadataDefault,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val CHIP_WIDTH = 64.dp
private val BADGE_SIZE = 20.dp
private val BADGE_ICON = 14.dp
