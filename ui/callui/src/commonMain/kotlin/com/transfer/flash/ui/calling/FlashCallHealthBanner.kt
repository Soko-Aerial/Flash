package com.transfer.flash.ui.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 */
@Composable
internal fun FlashCallHealthBanner(
    state: FlashCallUiState,
    onShowFewerVideos: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dismissed by remember(state.callId) { mutableStateOf(emptySet<FlashCallHealthWarning>()) }
    val warning = state.healthWarning?.takeIf { it !in dismissed }
    when {
        warning != null -> Row(
            modifier = modifier.bannerSurface(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = healthWarningText(warning),
                style = FlashTheme.typography.metadataDefault,
                color = Color.White,
                maxLines = 3,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = FlashSpacing.space8)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
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
            .size(height = FlashDimensions.minTouchTarget, width = ACTION_WIDTH)
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

/** The banner's words for [warning] (UI-050d). */
internal fun healthWarningText(warning: FlashCallHealthWarning): String = when (warning) {
    FlashCallHealthWarning.WARM -> "Your phone is warming up. Showing fewer videos saves battery."
    FlashCallHealthWarning.HOT -> "Your phone is hot. Showing one video until it cools down."
    FlashCallHealthWarning.CPU -> "This call is keeping the processor busy. Showing fewer videos helps."
    FlashCallHealthWarning.SOFTWARE_DECODE ->
        "Videos are being decoded without hardware help. Showing fewer videos saves battery."
}

/** Whether the banner offers **Show fewer**: not when hot, where Flash already shows one video. */
internal fun healthWarningOffersShowFewer(warning: FlashCallHealthWarning): Boolean =
    warning != FlashCallHealthWarning.HOT

private val BANNER_MAX_WIDTH = 480.dp
private val ACTION_WIDTH = 96.dp
