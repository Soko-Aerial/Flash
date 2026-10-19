package com.transfer.flash.ui.hub.competition.apex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale

/**
 * APEX ZENITH — Page 1: Hub Command Center (Overview & Live Telemetry).
 *
 * Implements:
 * - Hero status header visualizing HubStatus, HubSession, and RelaySessionState
 * - Cryptographic trust and sealing status (HubSealingKeyManager, DirectoryHubSealing, HubSealingPins)
 * - Realtime telemetry metric tiles (Latency, Outbox Queue, Relay Port, Connections)
 * - Actionable system notices and alert banners (HubNotice, HubTabModels)
 */
@Composable
internal fun ApexPageCommandCenter(
    deck: ApexHubDeck,
    modifier: Modifier = Modifier,
    onNavigatePage: (ApexPage) -> Unit = {},
    onTriggerSync: () -> Unit = {},
) {
    val colors = FlashTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
    ) {
        // --- 1. Hero Organization & Telemetry Header ---
        ApexCard(
            tone = if (deck.linkState == ApexLinkState.CONNECTED) ApexTone.SEALED else null,
            contentPadding = FlashSpacing.space20,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    ) {
                        ApexStatusDot(
                            tone = when (deck.linkState) {
                                ApexLinkState.CONNECTED -> ApexTone.ONLINE
                                ApexLinkState.AUTHENTICATING -> ApexTone.BUSY
                                ApexLinkState.RELAY_FALLBACK -> ApexTone.WARNING
                                ApexLinkState.RECONNECTING -> ApexTone.WARNING
                                ApexLinkState.OFFLINE -> ApexTone.MUTED
                                ApexLinkState.REVOKED -> ApexTone.DANGER
                            },
                        )
                        FlashText(
                            text = deck.org.name,
                            style = FlashTheme.typography.headingLarge,
                            color = colors.textPrimary,
                        )
                    }
                    Spacer(Modifier.height(FlashSpacing.space4))
                    FlashText(
                        text = deck.org.host,
                        style = apexMono(FlashTheme.typography.captionDefault),
                        color = colors.textSecondary,
                    )
                }

                ApexBadge(
                    text = deck.linkState.label,
                    tone = when (deck.linkState) {
                        ApexLinkState.CONNECTED -> ApexTone.ONLINE
                        ApexLinkState.AUTHENTICATING -> ApexTone.BUSY
                        ApexLinkState.RELAY_FALLBACK -> ApexTone.WARNING
                        ApexLinkState.RECONNECTING -> ApexTone.WARNING
                        ApexLinkState.OFFLINE -> ApexTone.MUTED
                        ApexLinkState.REVOKED -> ApexTone.DANGER
                    },
                    icon = when (deck.linkState) {
                        ApexLinkState.CONNECTED -> FlashIcons.Verified
                        ApexLinkState.AUTHENTICATING -> FlashIcons.Clock
                        ApexLinkState.RELAY_FALLBACK -> FlashIcons.Relay
                        ApexLinkState.RECONNECTING -> FlashIcons.Retry
                        ApexLinkState.OFFLINE -> FlashIcons.Connection
                        ApexLinkState.REVOKED -> FlashIcons.Failed
                    },
                )
            }

            Spacer(Modifier.height(FlashSpacing.space16))
            ApexDivider()
            Spacer(Modifier.height(FlashSpacing.space12))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    FlashText(
                        text = "ROLE & MEMBERSHIP",
                        style = FlashTheme.typography.metadataEmphasis,
                        color = colors.textTertiary,
                    )
                    Spacer(Modifier.height(FlashSpacing.space2))
                    FlashText(
                        text = deck.org.myRole,
                        style = FlashTheme.typography.bodyEmphasis,
                        color = colors.textPrimary,
                    )
                }

                ApexSecondaryButton(
                    text = "Sync Now",
                    icon = FlashIcons.Retry,
                    tone = ApexTone.STANDBY,
                    onClick = onTriggerSync,
                )
            }
        }

        // --- 2. Actionable System Banners (HubNotice) ---
        if (deck.notices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                deck.notices.forEach { notice ->
                    val toneColors = apexTone(notice.tone)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(ApexDims.cardRadius))
                            .background(toneColors.wash)
                            .border(FlashDimensions.borderHairline, toneColors.border, RoundedCornerShape(ApexDims.cardRadius))
                            .padding(FlashSpacing.space16),
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(toneColors.indicator.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                FlashIcon(
                                    icon = if (notice.tone == ApexTone.DANGER) FlashIcons.Failed else FlashIcons.Encryption,
                                    tint = toneColors.indicator,
                                    size = FlashDimensions.iconSm,
                                    contentDescription = null,
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                FlashText(
                                    text = notice.title,
                                    style = FlashTheme.typography.bodyEmphasis,
                                    color = toneColors.text,
                                )
                                Spacer(Modifier.height(FlashSpacing.space2))
                                FlashText(
                                    text = notice.message,
                                    style = FlashTheme.typography.captionDefault,
                                    color = colors.textSecondary,
                                )
                                if (notice.actionLabel != null) {
                                    Spacer(Modifier.height(FlashSpacing.space8))
                                    ApexSecondaryButton(
                                        text = notice.actionLabel,
                                        tone = notice.tone,
                                        onClick = {
                                            if (notice.actionLabel.contains("Cert")) onNavigatePage(ApexPage.VAULT)
                                            else onTriggerSync()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 3. Cryptographic Trust & Sealing Status Dashboard ---
        ApexCard {
            ApexSectionHeader(
                title = "Cryptographic Sealing Engine",
                actionText = "View Vault",
                onAction = { onNavigatePage(ApexPage.VAULT) },
            )
            Spacer(Modifier.height(FlashSpacing.space8))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                ApexTile(
                    label = "Sealing State",
                    value = if (deck.sealing.isSealed) "Active & Sealed" else "Degraded",
                    caption = deck.sealing.cipherSuite,
                    tone = if (deck.sealing.isSealed) ApexTone.SEALED else ApexTone.WARNING,
                    icon = FlashIcons.Encryption,
                    modifier = Modifier.weight(1f),
                )
                ApexTile(
                    label = "Trust Pins",
                    value = "${deck.sealing.verifiedPinCount}/${deck.sealing.activePinsCount} Verified",
                    caption = "Hub Key Pinning Valid",
                    tone = if (deck.sealing.verifiedPinCount > 0) ApexTone.ONLINE else ApexTone.MUTED,
                    icon = FlashIcons.Pin,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(FlashSpacing.space12))
            ApexCodeBox(
                label = "Hub Sealing Public Key Fingerprint",
                code = deck.sealing.hubFingerprint,
            )
        }

        // --- 4. Live Relay Telemetry Grid (RelaySessionState) ---
        ApexCard {
            ApexSectionHeader(title = "Relay Network Telemetry (Port ${deck.relay.relayPort})")
            Spacer(Modifier.height(FlashSpacing.space8))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                ApexTile(
                    label = "Latency",
                    value = if (deck.relay.latencyMs > 0) "${deck.relay.latencyMs} ms" else "---",
                    caption = if (deck.relay.latencyMs < 50) "Ultra-low" else "High jitter",
                    tone = if (deck.relay.latencyMs in 1..80) ApexTone.ONLINE else if (deck.relay.latencyMs > 80) ApexTone.WARNING else ApexTone.MUTED,
                    icon = FlashIcons.Clock,
                    modifier = Modifier.weight(1f),
                )
                ApexTile(
                    label = "Outbox Queue",
                    value = "${deck.relay.outboxQueued} pkts",
                    caption = if (deck.relay.outboxQueued == 0) "Drain clear" else "Buffering",
                    tone = if (deck.relay.outboxQueued == 0) ApexTone.STANDBY else ApexTone.WARNING,
                    icon = FlashIcons.Upload,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(FlashSpacing.space8))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                ApexTile(
                    label = "Sockets",
                    value = "${deck.relay.activeConnections} active",
                    caption = "${deck.relay.throughputKbps} kbps link",
                    tone = if (deck.relay.activeConnections > 0) ApexTone.STANDBY else ApexTone.MUTED,
                    icon = FlashIcons.Relay,
                    modifier = Modifier.weight(1f),
                )
                ApexTile(
                    label = "Packet Health",
                    value = "${deck.relay.socketHealthPct}%",
                    caption = "${deck.relay.droppedPackets} dropped",
                    tone = if (deck.relay.socketHealthPct >= 90) ApexTone.ONLINE else ApexTone.DANGER,
                    icon = FlashIcons.Verified,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // --- 5. Quick Navigation & Snapshot Watermark ---
        ApexCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    FlashText(
                        text = "DIRECTORY SNAPSHOT",
                        style = FlashTheme.typography.metadataEmphasis,
                        color = colors.textTertiary,
                    )
                    Spacer(Modifier.height(FlashSpacing.space2))
                    FlashText(
                        text = "Sequence #${deck.syncWatermark.sequenceNumber} • ${deck.syncWatermark.status}",
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.textPrimary,
                    )
                }

                ApexSecondaryButton(
                    text = "Open Directory",
                    icon = FlashIcons.Device,
                    tone = ApexTone.STANDBY,
                    onClick = { onNavigatePage(ApexPage.DIRECTORY) },
                )
            }
        }
    }
}
