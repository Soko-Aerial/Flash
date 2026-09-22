package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Pure copy/label logic for the 1:1 peer details sheet (UI-032). Kept free of
 * Compose/Android types so it is unit-testable (see [FlashPeerDetailsLogicTest]).
 */
object FlashPeerDetailsMath {
    fun presenceLabel(presence: FlashPeerPresence): String = when (presence) {
        FlashPeerPresence.Online -> "Online"
        FlashPeerPresence.Typing -> "Typing…"
        FlashPeerPresence.Connecting -> "Connecting…"
        FlashPeerPresence.Offline -> "Offline"
    }

    fun isReachable(presence: FlashPeerPresence): Boolean =
        presence == FlashPeerPresence.Online || presence == FlashPeerPresence.Typing

    fun transportLabel(transport: FlashNetworkTransport, presence: FlashPeerPresence): String {
        if (!isReachable(presence)) return "Not connected"
        return when (transport) {
            FlashNetworkTransport.Lan -> "Local network (Wi-Fi)"
            FlashNetworkTransport.WifiDirect -> "Wi-Fi Direct"
            FlashNetworkTransport.Relay -> "Relay"
            FlashNetworkTransport.Unknown -> "Not connected"
        }
    }

    fun encryptionLabel(isEncrypted: Boolean): String =
        if (isEncrypted) "End-to-end encrypted" else "Not encrypted"
}

/**
 * UI-032 1:1 peer details sheet — opened from the conversation header avatar for
 * non-group chats. Renders the peer's real identity/presence/transport pulled from
 * [FlashChatHeaderUiState]; when the peer is trusted the host (:app) supplies
 * [onRevokeTrust] to drop the pairing. No stock list-item components.
 */
@Composable
fun FlashPeerDetailsSheet(
    header: FlashChatHeaderUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isTrusted: Boolean = false,
    onRevokeTrust: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val presenceLabel = remember(header.presence) {
        FlashPeerDetailsMath.presenceLabel(header.presence)
    }
    val reachable = remember(header.presence) {
        FlashPeerDetailsMath.isReachable(header.presence)
    }

    FlashSheetHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FlashSpacing.space12)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.borderSubtle),
            )
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = FlashSpacing.space20,
                    end = FlashSpacing.space20,
                    bottom = FlashSpacing.space32,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    FlashIcon(
                        icon = FlashIcons.Close,
                        contentDescription = "Close",
                        tint = colors.textSecondary,
                    )
                }
            }
            Box(modifier = Modifier.size(FlashDimensions.avatarXl)) {
                FlashAvatar(
                    initials = header.avatarInitials,
                    seed = header.avatarSeed,
                    size = FlashDimensions.avatarXl,
                )
                if (reachable) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(colors.statusOnline)
                            .border(3.dp, colors.backgroundSurface, CircleShape),
                    )
                }
            }

            FlashText(
                text = header.title,
                modifier = Modifier.padding(top = FlashSpacing.space12),
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            FlashText(
                text = presenceLabel,
                style = FlashTheme.typography.metadataDefault,
                color = if (reachable) colors.textSuccess else colors.textSecondary,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FlashSpacing.space20),
            ) {
                PeerDetailRow(
                    icon = header.transport.detailIconSpec(),
                    label = "Connection",
                    value = FlashPeerDetailsMath.transportLabel(header.transport, header.presence),
                )
                DetailDivider()
                PeerDetailRow(
                    icon = FlashIcons.Encryption,
                    label = "Security",
                    value = FlashPeerDetailsMath.encryptionLabel(header.isEncrypted),
                )
                DetailDivider()
                PeerDetailRow(
                    icon = if (isTrusted) FlashIcons.Verified else FlashIcons.Device,
                    label = "Trust",
                    value = if (isTrusted) "Paired device" else "Not paired",
                )
            }

            if (isTrusted && onRevokeTrust != null) {
                RevokeTrustButton(
                    onClick = {
                        onRevokeTrust()
                        onDismiss()
                    },
                    modifier = Modifier.padding(top = FlashSpacing.space24),
                )
            }
        }
    }
}

@Composable
private fun PeerDetailRow(icon: FlashIconSpec?, label: String, value: String) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: $value"
            }
            .padding(vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        if (icon != null) {
            FlashIcon(
                icon = icon,
                contentDescription = null,
                size = FlashDimensions.iconSm,
                tint = colors.textTertiary,
            )
        }
        FlashText(
            text = label,
            modifier = Modifier.weight(1f),
            style = FlashTheme.typography.bodyDefault,
            color = colors.textSecondary,
            maxLines = 1,
        )
        FlashText(
            text = value,
            style = FlashTheme.typography.bodyDefault.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FlashDimensions.borderHairline)
            .background(FlashTheme.colors.borderSubtle),
    )
}

@Composable
private fun RevokeTrustButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(FlashShapes.chip)
            .border(FlashDimensions.borderHairline, colors.textError, FlashShapes.chip)
            .clickable(onClick = onClick)
            .padding(vertical = FlashSpacing.space12),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = "Unpair this device",
            style = FlashTheme.typography.bodyDefault.copy(fontWeight = FontWeight.Bold),
            color = colors.textError,
            maxLines = 1,
        )
    }
}

private fun FlashNetworkTransport.detailIconSpec(): FlashIconSpec = when (this) {
    FlashNetworkTransport.Lan -> FlashIcons.Wifi
    FlashNetworkTransport.WifiDirect -> FlashIcons.WifiDirect
    FlashNetworkTransport.Relay -> FlashIcons.Relay
    FlashNetworkTransport.Unknown -> FlashIcons.Connection
}

@Preview(name = "Peer details — trusted online", showBackground = true, widthDp = 390)
@Composable
private fun FlashPeerDetailsTrustedPreview() {
    FlashTheme {
        FlashPeerDetailsSheet(
            header = FlashChatHeaderUiState(
                title = "Kali's Pixel",
                avatarInitials = "KP",
                presence = FlashPeerPresence.Online,
                transport = FlashNetworkTransport.Lan,
                isEncrypted = false,
            ),
            onDismiss = {},
            isTrusted = true,
            onRevokeTrust = {},
        )
    }
}

@Preview(name = "Peer details — untrusted offline", showBackground = true, widthDp = 390)
@Composable
private fun FlashPeerDetailsUntrustedPreview() {
    FlashTheme {
        FlashPeerDetailsSheet(
            header = FlashChatHeaderUiState(
                title = "Unknown device",
                avatarInitials = "UD",
                presence = FlashPeerPresence.Offline,
                transport = FlashNetworkTransport.Unknown,
            ),
            onDismiss = {},
        )
    }
}
