package com.transfer.flash.ui.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.calling.model.FlashCallHealthWarning
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale

/**
 * Group video health banner (UI-050d, G6; `docs/ui/calling-ui.md`): the core's warning in plain
 * words with one action, **Show fewer**, or, while receiving is capped by the user, a pill to
 * undo it. A dismissed kind stays dismissed for the rest of the call.
 *
 * The CPU warning also offers **Send smaller** (ADR-053) until the user's "Send smaller video in
 * groups" setting is on: [onSendSmallerVideo] turns that setting on (the host saves it, so it is
 * in Settings from then on). With two actions the banner takes two lines, words above actions.
 */
@Composable
internal fun FlashCallHealthBanner(
    state: FlashCallUiState,
    onShowFewerVideos: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onSendSmallerVideo: () -> Unit = {},
) {
    var dismissed by remember(state.callId) { mutableStateOf(emptySet<FlashCallHealthWarning>()) }
    // Hides the action at once; the setting reaches the state on the core's next tick.
    var smallerTapped by remember(state.callId) { mutableStateOf(false) }
    val warning = state.healthWarning?.takeIf { it !in dismissed }
    when {
        warning != null && healthWarningOffersSmallerVideo(warning, state.smallerVideoForMany || smallerTapped) ->
            Column(modifier = modifier.bannerSurface()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WarningText(healthWarningText(warning, offersSmallerVideo = true), Modifier.weight(1f))
                    BannerClose { dismissed = dismissed + warning }
                }
                Row(modifier = Modifier.align(Alignment.End)) {
                    if (healthWarningOffersShowFewer(warning)) {
                        BannerAction(text = "Show fewer", label = "Show fewer videos") { onShowFewerVideos(true) }
                    }
                    BannerAction(text = "Send 360p", label = "Send my video at 360p in group calls") {
                        smallerTapped = true
                        onSendSmallerVideo()
                    }
                }
            }
        warning != null -> Row(
            modifier = modifier.bannerSurface(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WarningText(healthWarningText(warning), Modifier.weight(1f))
            if (healthWarningOffersShowFewer(warning)) {
                BannerAction(text = "Show fewer", label = "Show fewer videos") { onShowFewerVideos(true) }
            }
            BannerClose { dismissed = dismissed + warning }
        }
        state.showingFewerVideos && state.healthWarning != FlashCallHealthWarning.HOT -> Row(
            modifier = modifier.bannerSurface(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Showing one video",
                style = FlashTheme.typography.metadataDefault,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            BannerAction(text = "Show all", label = "Show all videos") { onShowFewerVideos(false) }
        }
    }
}

@Composable
private fun WarningText(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = FlashTheme.typography.metadataDefault,
        color = Color.White,
        maxLines = 3,
        modifier = modifier
            .padding(vertical = FlashSpacing.space8)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun Modifier.bannerSurface(): Modifier = this
    .widthIn(max = BANNER_MAX_WIDTH)
    .fillMaxWidth()
    .padding(horizontal = FlashSpacing.space16)
    .clip(RoundedCornerShape(FlashShapes.radius12))
    .background(Color.Black.copy(alpha = 0.6f))
    .padding(start = FlashSpacing.space12)

@Composable
private fun BannerAction(text: String, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .widthIn(min = ACTION_WIDTH)
            .flashPressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.accentPrimary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = FlashSpacing.space8),
        )
    }
}

@Composable
private fun BannerClose(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(FlashDimensions.minTouchTarget)
            .flashPressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "Dismiss warning" },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = FlashIcons.Close,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            size = FlashDimensions.iconSm,
        )
    }
}

/**
 * The banner's words for [warning] (UI-050d); [offersSmallerVideo] when the banner also offers
 * **Send smaller** (ADR-053).
 */
internal fun healthWarningText(warning: FlashCallHealthWarning, offersSmallerVideo: Boolean = false): String = when (warning) {
    FlashCallHealthWarning.WARM -> "Your phone is warming up. Showing fewer videos saves battery."
    FlashCallHealthWarning.HOT -> "Your phone is hot. Showing one video until it cools down."
    FlashCallHealthWarning.CPU -> if (offersSmallerVideo) {
        "This call is keeping the processor busy. Showing fewer videos, or sending yours at 360p, helps."
    } else {
        "This call is keeping the processor busy. Showing fewer videos helps."
    }
    FlashCallHealthWarning.SOFTWARE_DECODE ->
        "Videos are being decoded without hardware help. Showing fewer videos saves battery."
}

/** Whether the banner offers **Show fewer**: not when hot, where Flash already shows one video. */
internal fun healthWarningOffersShowFewer(warning: FlashCallHealthWarning): Boolean =
    warning != FlashCallHealthWarning.HOT

/**
 * Whether the banner offers **Send smaller** (ADR-053): only for the CPU warning, whose main cost
 * is encoding this device's video once per watcher, and only while the setting is still off.
 */
internal fun healthWarningOffersSmallerVideo(warning: FlashCallHealthWarning, alreadyOn: Boolean): Boolean =
    warning == FlashCallHealthWarning.CPU && !alreadyOn

private val BANNER_MAX_WIDTH = 480.dp
private val ACTION_WIDTH = 96.dp
