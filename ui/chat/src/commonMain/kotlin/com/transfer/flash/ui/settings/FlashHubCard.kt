@file:OptIn(FlashInternalApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.transfer.flash.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.enterprise.HubStatus
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import kotlinx.coroutines.delay

/** A clock for relative texts ("Trying again in 20 seconds"). Ticks every second, and only while [active]. */
@Composable
internal fun rememberHubNow(active: Boolean): State<Long> = produceState(initialValue = SystemTimeSource.nowMs(), active) {
    value = SystemTimeSource.nowMs()
    while (active) {
        delay(1_000L)
        value = SystemTimeSource.nowMs()
    }
}

/** The live dot + one-line status for the organisation hub. Tap or long-press opens the connection details. */
@Composable
internal fun FlashHubStatusRow(status: HubStatus, nowMs: Long, onOpenDetails: () -> Unit) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val dotColor by animateColorAsState(
        when (FlashHubStatusMath.dot(status)) {
            FlashHubDot.GOOD -> colors.statusOnline
            FlashHubDot.PROGRESS -> colors.accentPrimary
            FlashHubDot.WARNING -> colors.statusTransfer
            FlashHubDot.BAD -> colors.textError
            FlashHubDot.NEUTRAL -> colors.statusOffline
        },
        label = "hubDot",
    )
    val text = FlashHubStatusMath.statusText(status, nowMs)
    val title = status.hubName.ifBlank { "Organisation hub" }
    Row(
        Modifier
            .fillMaxWidth()
            .flashPressScale(interactionSource)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = "Show connection details",
                onLongClickLabel = "Show connection details",
                onClick = onOpenDetails,
                onLongClick = onOpenDetails,
            )
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $text" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(icon = FlashIcons.Relay, tint = colors.textSecondary, size = FlashDimensions.iconMd, contentDescription = null)
        Spacer(Modifier.width(FlashSpacing.space12))
        Column(Modifier.weight(1f)) {
            FlashText(text = title, style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
            FlashText(text = text, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary)
        }
        Spacer(Modifier.width(FlashSpacing.space8))
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
    }
}

/** A short banner for a state that needs the user (revoked, expiring, offline ...). */
@Composable
internal fun FlashHubBannerCard(
    banner: FlashHubBanner,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    val severe = banner.kind != com.transfer.flash.core.enterprise.HubLinkState.OFFLINE
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurfaceSubtle)
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = false) { },
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(
            text = banner.title,
            style = FlashTheme.typography.bodyEmphasis,
            color = if (severe) colors.textError else colors.textPrimary,
        )
        FlashText(text = banner.body, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
        if (banner.primary != FlashHubBannerAction.NONE) {
            Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                Box(
                    Modifier
                        .clip(FlashShapes.chip)
                        .background(colors.accentPrimary.copy(alpha = 0.15f))
                        .clickable(onClickLabel = banner.primaryLabel, onClick = onPrimary)
                        .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                ) {
                    FlashText(text = banner.primaryLabel, style = FlashTheme.typography.metadataEmphasis, color = colors.accentPrimary)
                }
                if (banner.dismissible) {
                    Box(
                        Modifier
                            .clip(FlashShapes.chip)
                            .clickable(onClickLabel = "Dismiss", onClick = onDismiss)
                            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                    ) {
                        FlashText(text = "Dismiss", style = FlashTheme.typography.metadataEmphasis, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** The long-press sheet: everything technical about the link, in plain labels. */
@Composable
internal fun FlashHubDetailDialog(status: HubStatus, nowMs: Long, onClose: () -> Unit, sealing: FlashHubSealingUi? = null) {
    val colors = FlashTheme.colors
    FlashConfirmHost(
        onDismiss = onClose,
        containerColor = colors.backgroundSurface,
        title = { FlashText(text = "Connection details", style = FlashTheme.typography.headingMedium, color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                for (line in FlashHubStatusMath.detailLines(status, nowMs, sealing)) {
                    Column {
                        FlashText(text = line.label, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary)
                        FlashText(text = line.value, style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = colors.accentPrimary) } },
        dismissButton = {},
    )
}

/** Shown before readable content may travel through the hub: the user must understand the hub can read it. */
@Composable
internal fun FlashHubContentDisclosureDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = FlashTheme.colors
    FlashConfirmHost(
        onDismiss = onCancel,
        containerColor = colors.backgroundSurface,
        title = { FlashText(text = "Let the hub read these messages?", style = FlashTheme.typography.headingMedium, color = colors.textPrimary) },
        text = { FlashText(text = FlashHubStatusMath.CONTENT_VIA_HUB_DISCLOSURE, style = FlashTheme.typography.bodyDefault, color = colors.textSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Allow", color = colors.accentPrimary) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Not now", color = colors.textSecondary) } },
    )
}

/** Offer after enrolling again with the same organisation: link the groups that were linked before. */
@Composable
internal fun FlashHubRelinkOffer(count: Int, onRestore: () -> Unit, onForget: () -> Unit) {
    val colors = FlashTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurfaceSubtle)
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(text = FlashHubStatusMath.relinkOfferText(count), style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            Box(
                Modifier
                    .clip(FlashShapes.chip)
                    .background(colors.accentPrimary.copy(alpha = 0.15f))
                    .clickable(onClickLabel = "Restore groups", onClick = onRestore)
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
            ) {
                FlashText(text = "Restore", style = FlashTheme.typography.metadataEmphasis, color = colors.accentPrimary)
            }
            Box(
                Modifier
                    .clip(FlashShapes.chip)
                    .clickable(onClickLabel = "Not now", onClick = onForget)
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
            ) {
                FlashText(text = "No thanks", style = FlashTheme.typography.metadataEmphasis, color = colors.textSecondary)
            }
        }
    }
}
