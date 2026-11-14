package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashRadioPolicy
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
        FlashPeerPresence.Online -> "Connected"
        FlashPeerPresence.Reachable -> "Online"
        // ADR-228: no session, but a text still goes out over the radio.
        FlashPeerPresence.RadioOnly -> "Radio only"
        FlashPeerPresence.Typing -> "Typing…"
        FlashPeerPresence.Connecting -> "Connecting…"
        FlashPeerPresence.Offline -> "Offline"
    }

    fun isReachable(presence: FlashPeerPresence): Boolean =
        presence == FlashPeerPresence.Online || presence == FlashPeerPresence.Typing

    fun transportLabel(transport: FlashNetworkTransport, presence: FlashPeerPresence): String {
        // ADR-228: no session to call "connected", but the text still goes out, so "Not connected" would be
        // a lie in the other direction.
        if (transport == FlashNetworkTransport.Radio) return "Radio"
        if (presence == FlashPeerPresence.Reachable) {
            return when (transport) {
                FlashNetworkTransport.Lan -> "Local network (connects when you send)"
                FlashNetworkTransport.WifiDirect -> "Wi-Fi Direct (connects when you send)"
                FlashNetworkTransport.Relay -> "Relay (connects when you send)"
                FlashNetworkTransport.Radio -> "Radio"
                FlashNetworkTransport.Unknown -> "Connects when you send"
            }
        }
        if (!isReachable(presence)) return "Not connected"
        return when (transport) {
            FlashNetworkTransport.Lan -> "Local network (Wi-Fi)"
            FlashNetworkTransport.WifiDirect -> "Wi-Fi Direct"
            FlashNetworkTransport.Relay -> "Relay"
            FlashNetworkTransport.Radio -> "Radio"
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
    onOpenSharedContent: (() -> Unit)? = null,
    radioPolicy: FlashRadioPolicy = FlashRadioPolicy.AUTO,
    onSetRadioPolicy: (FlashRadioPolicy) -> Unit = {},
    isVerified: Boolean = true,
    onVerifyTrust: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    var confirmUnpair by remember { mutableStateOf(false) }
    val presenceLabel = remember(header.presence) {
        FlashPeerDetailsMath.presenceLabel(header.presence)
    }
    val reachable = remember(header.presence) {
        FlashPeerDetailsMath.isReachable(header.presence)
    }
    val radioAutomatic = radioPolicy == FlashRadioPolicy.AUTO

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
                .verticalScroll(rememberScrollState())
                .padding(
                    start = FlashSpacing.space20,
                    end = FlashSpacing.space20,
                    bottom = FlashSpacing.space32,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(FlashDimensions.minTouchTarget)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Close"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    FlashIcon(
                        icon = FlashIcons.Close,
                        contentDescription = null,
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
                // UI-030b: solid = Connected, ring = Online (seen, no session).
                FlashPresenceDot(
                    presence = header.presence,
                    size = 16.dp,
                    halo = 3.dp,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
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
                    icon = if (isTrusted && isVerified) FlashIcons.Verified else FlashIcons.Device,
                    label = "Trust",
                    value = when {
                        isTrusted && isVerified -> "Paired device"
                        isTrusted -> "Needs verification"
                        else -> "Not paired"
                    },
                )
                if (isTrusted && !isVerified) {
                    DetailDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FlashDimensions.minTouchTarget)
                            .clip(FlashShapes.bubbleGrouped)
                            .then(
                                if (onVerifyTrust != null) {
                                    Modifier.clickable {
                                        onDismiss()
                                        onVerifyTrust()
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .semantics(mergeDescendants = true) {
                                if (onVerifyTrust != null) role = Role.Button
                                contentDescription = "Verify pairing. Compare a 6-digit code on both devices to finish securing this connection."
                            }
                            .padding(vertical = FlashSpacing.space12),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Verified,
                            contentDescription = null,
                            size = FlashDimensions.iconSm,
                            tint = colors.accentFill,
                        )
                        Column(Modifier.weight(1f).padding(start = FlashSpacing.space12)) {
                            FlashText(
                                text = "Verify pairing",
                                style = FlashTheme.typography.bodyDefault,
                                color = colors.textPrimary,
                            )
                            FlashText(
                                text = "Compare a 6-digit code on both devices to confirm this pairing.",
                                style = FlashTheme.typography.metadataDefault,
                                color = colors.textSecondary,
                            )
                        }
                        if (onVerifyTrust != null) {
                            Spacer(Modifier.width(FlashSpacing.space8))
                            FlashText(
                                text = "Verify",
                                style = FlashTheme.typography.captionEmphasis,
                                color = colors.accentFill,
                            )
                        }
                    }
                }
                DetailDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clip(FlashShapes.bubbleGrouped)
                        .clickable { onSetRadioPolicy(if (radioAutomatic) FlashRadioPolicy.NEVER else FlashRadioPolicy.AUTO) }
                        .semantics(mergeDescendants = true) {
                            role = Role.Switch
                            stateDescription = if (radioAutomatic) "Automatic" else "Never"
                            contentDescription = "Radio fallback: ${if (radioAutomatic) "Automatic" else "Never"}. Send short texts over radio when Wi-Fi isn't available."
                        }
                        .padding(vertical = FlashSpacing.space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashIcon(
                        icon = FlashIcons.Bluetooth,
                        contentDescription = null,
                        size = FlashDimensions.iconSm,
                        tint = colors.textTertiary,
                    )
                    Column(Modifier.weight(1f).padding(start = FlashSpacing.space12)) {
                        FlashText(
                            text = "Radio fallback",
                            style = FlashTheme.typography.bodyDefault,
                            color = colors.textPrimary,
                        )
                        FlashText(
                            text = "Send short texts over radio when Wi-Fi isn't available.",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(FlashSpacing.space8))
                    FlashText(
                        text = if (radioAutomatic) "Automatic" else "Never",
                        style = FlashTheme.typography.bodyDefault.copy(fontWeight = FontWeight.Bold),
                        color = if (radioAutomatic) colors.accentFill else colors.textSecondary,
                    )
                }
                if (onOpenSharedContent != null) {
                    DetailDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FlashDimensions.minTouchTarget)
                            .clip(FlashShapes.bubbleGrouped)
                            .clickable {
                                onDismiss()
                                onOpenSharedContent()
                            }
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                contentDescription = "Shared media, files, and links"
                            }
                            .padding(vertical = FlashSpacing.space12),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Gallery,
                            contentDescription = null,
                            size = FlashDimensions.iconSm,
                            tint = colors.textTertiary,
                        )
                        FlashText(
                            text = "Shared Media & Files",
                            modifier = Modifier.weight(1f),
                            style = FlashTheme.typography.bodyDefault,
                            color = colors.textPrimary,
                            maxLines = 1,
                        )
                        FlashIcon(
                            icon = FlashIcons.Forward,
                            contentDescription = null,
                            size = FlashDimensions.iconSm,
                            tint = colors.textSecondary,
                        )
                    }
                }
            }

            if (isTrusted && onRevokeTrust != null) {
                RevokeTrustButton(
                    onClick = { confirmUnpair = true },
                    modifier = Modifier.padding(top = FlashSpacing.space24),
                )
            }
        }
    }

    if (confirmUnpair && onRevokeTrust != null) {
        FlashConfirmHost(
            onDismiss = { confirmUnpair = false },
            containerColor = colors.backgroundSurface,
            title = {
                FlashText(
                    text = "Unpair ${header.title}?",
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
            },
            text = {
                FlashText(
                    text = "You won't be able to message or send files to ${header.title} until you pair again with a 6-digit code.",
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clip(FlashShapes.bubbleGrouped)
                        .clickable {
                            confirmUnpair = false
                            onRevokeTrust()
                            onDismiss()
                        }
                        .semantics { role = Role.Button }
                        .padding(horizontal = FlashSpacing.space12),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = "Unpair",
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.textError,
                    )
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clip(FlashShapes.bubbleGrouped)
                        .clickable { confirmUnpair = false }
                        .semantics { role = Role.Button }
                        .padding(horizontal = FlashSpacing.space12),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = "Cancel",
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.textSecondary,
                    )
                }
            },
        )
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
            style = FlashTheme.typography.captionDefault.copy(fontWeight = FontWeight.SemiBold),
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
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.button)
            .border(FlashDimensions.borderHairline, colors.textError, FlashShapes.button)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = FlashSpacing.space12),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = "Unpair device",
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
    FlashNetworkTransport.Radio -> FlashIcons.Bluetooth
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
