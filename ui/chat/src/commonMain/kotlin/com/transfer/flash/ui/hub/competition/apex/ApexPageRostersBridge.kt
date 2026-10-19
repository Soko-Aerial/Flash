package com.transfer.flash.ui.hub.competition.apex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * APEX ZENITH — Page 5: Verified Group Rosters & Relay Bridge.
 *
 * Implements:
 * - Enterprise-managed group rosters with cryptographic statements (HubGroupRosters, GroupRosterStatement)
 * - Hub Relay Port and Chat Bridge telemetry (HubChatBridge, HubRelayPort)
 * - Live packet trace telemetry and bridge diagnostics
 */
@Composable
internal fun ApexPageRostersBridge(
    deck: ApexHubDeck,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    var expandedGroupId by remember { mutableStateOf<String?>("roster-crypto") }
    var pingRunning by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
    ) {
        // --- 1. Relay Port & Bridge Telemetry Header ---
        ApexCard(
            tone = if (deck.linkState == ApexLinkState.RELAY_FALLBACK) ApexTone.WARNING else ApexTone.STANDBY,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Relay,
                            tint = colors.accentPrimary,
                            size = FlashDimensions.iconMd,
                        )
                        FlashText(
                            text = "Hub Relay Bridge (Port ${deck.relay.relayPort})",
                            style = FlashTheme.typography.headingMedium,
                            color = colors.textPrimary,
                        )
                    }
                    Spacer(Modifier.height(FlashSpacing.space4))
                    FlashText(
                        text = "Encrypted peer routing & zero-knowledge roster packet delivery",
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }

                ApexBadge(
                    text = if (deck.relay.activeConnections > 0) "PORT ACTIVE" else "IDLE",
                    tone = if (deck.relay.activeConnections > 0) ApexTone.ONLINE else ApexTone.MUTED,
                )
            }

            Spacer(Modifier.height(FlashSpacing.space12))
            ApexDivider()
            Spacer(Modifier.height(FlashSpacing.space12))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                ApexTile(
                    label = "Active Sockets",
                    value = "${deck.relay.activeConnections}",
                    caption = "TLS 1.3 streams",
                    tone = ApexTone.STANDBY,
                    modifier = Modifier.weight(1f),
                )
                ApexTile(
                    label = "Bridge Outbox",
                    value = "${deck.relay.outboxQueued} pkts",
                    caption = if (deck.relay.outboxQueued == 0) "Clear" else "Buffering",
                    tone = if (deck.relay.outboxQueued == 0) ApexTone.ONLINE else ApexTone.WARNING,
                    modifier = Modifier.weight(1f),
                )
                ApexTile(
                    label = "Relay Latency",
                    value = "${deck.relay.latencyMs} ms",
                    caption = "Round-trip time",
                    tone = if (deck.relay.latencyMs in 1..80) ApexTone.ONLINE else ApexTone.WARNING,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // --- 2. Verified Group Rosters Section ---
        ApexCard {
            ApexSectionHeader(
                title = "Enterprise Group Rosters (${deck.groupRosters.size})",
                actionText = "Sync Rosters",
                onAction = {},
            )
            Spacer(Modifier.height(FlashSpacing.space8))

            if (deck.groupRosters.isEmpty()) {
                ApexEmptyState(
                    title = "No Group Rosters",
                    description = "No enterprise group rosters available in this scenario.",
                    icon = FlashIcons.Group,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    deck.groupRosters.forEach { roster ->
                        val isExpanded = expandedGroupId == roster.groupId
                        ApexRosterCard(
                            roster = roster,
                            isExpanded = isExpanded,
                            onClick = {
                                expandedGroupId = if (isExpanded) null else roster.groupId
                            },
                        )
                    }
                }
            }
        }

        // --- 3. Live Relay Packet Traces Table ---
        if (deck.packetTraces.isNotEmpty()) {
            ApexCard {
                ApexSectionHeader(
                    title = "Recent Relay Packet Traces",
                    actionText = "Ping Bridge",
                    onAction = { pingRunning = !pingRunning },
                )
                Spacer(Modifier.height(FlashSpacing.space8))

                Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                    deck.packetTraces.forEach { trace ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(ApexDims.tileRadius))
                                .background(colors.backgroundSurfaceStrong.copy(alpha = 0.5f))
                                .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(ApexDims.tileRadius))
                                .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                                ) {
                                    FlashText(
                                        text = trace.traceId,
                                        style = apexMono(FlashTheme.typography.captionEmphasis),
                                        color = colors.textPrimary,
                                    )
                                    ApexBadge(
                                        text = trace.payloadType,
                                        tone = ApexTone.STANDBY,
                                    )
                                }
                                FlashText(
                                    text = "To: ${trace.recipientDeviceId} • ${apexFormatBytes(trace.sizeBytes)} • ${trace.latencyMs}ms",
                                    style = FlashTheme.typography.metadataDefault,
                                    color = colors.textSecondary,
                                )
                            }

                            ApexBadge(
                                text = trace.status,
                                tone = when (trace.status) {
                                    "DELIVERED" -> ApexTone.ONLINE
                                    "IN_FLIGHT" -> ApexTone.BUSY
                                    else -> ApexTone.WARNING
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApexRosterCard(
    roster: ApexGroupRosterItem,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ApexDims.tileRadius))
            .background(colors.backgroundSurfaceStrong.copy(alpha = 0.4f))
            .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(ApexDims.tileRadius))
            .flashPressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                ) {
                    FlashText(
                        text = roster.title,
                        style = FlashTheme.typography.bodyEmphasis,
                        color = colors.textPrimary,
                    )
                    if (roster.rosterAdminLock) {
                        ApexBadge(text = "LOCKED", tone = ApexTone.SPECIAL)
                    }
                }
                Spacer(Modifier.height(FlashSpacing.space2))
                FlashText(
                    text = "${roster.departmentName} • ${roster.memberCount} members (${roster.onlineCount} online)",
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textSecondary,
                )
            }

            ApexBadge(
                text = if (roster.isJoined) "JOINED" else if (roster.isPending) "PENDING" else "OPEN",
                tone = if (roster.isJoined) ApexTone.ONLINE else if (roster.isPending) ApexTone.WARNING else ApexTone.STANDBY,
            )
        }

        // Expanded Roster Cryptographic Statement
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FlashSpacing.space12),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                ApexDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashText(
                        text = "Roster Statement v${roster.rosterVersion}",
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.accentPrimary,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Verified,
                            tint = colors.textSuccess,
                            size = 14.dp,
                        )
                        FlashText(
                            text = "Cryptographically Verified",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textSuccess,
                        )
                    }
                }
                FlashText(
                    text = "Membership is signed by Department Head and verified through Hub Group Roster authority (ADR-138). End-to-end chat packets are routed via Relay Port with zero plaintext storage on server.",
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
