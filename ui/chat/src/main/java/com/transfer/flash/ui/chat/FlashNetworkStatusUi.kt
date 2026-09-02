package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * UI-030 — Conversation-level P2P connection health.
 * Derived from transport + peer presence + discovery peer count; see [FlashNetworkStatusMath.resolveHealth].
 */
enum class FlashConnectionHealth {
    Connected,
    Connecting,
    Degraded,
    Offline,
}

/**
 * Banner tone for a non-Connected health. Environmental states are calm (offline is a condition,
 * not a fault — web.dev / error-states.md); only Offline gets gentle attention. Never red.
 */
enum class FlashNetworkBannerSeverity { Calm, Attention }

/**
 * Pure decision + copy logic for UI-030 network status surfaces.
 * Unit-tested without instrumentation (see [FlashNetworkStatusLogicTest]).
 */
object FlashNetworkStatusMath {

    /**
     * Resolve conversation connection health.
     * Precedence: no peers → Offline; handshake in flight → Connecting; no transport → Offline;
     * direct transports with online presence → Connected; relay → Degraded;
     * peer gone or none discovered → Offline; anything else (e.g. relay + connecting peer) → Degraded.
     */
    fun resolveHealth(
        transport: FlashNetworkTransport,
        peerPresence: FlashPeerPresence,
        peerCount: Int,
    ): FlashConnectionHealth = when {
        // Nobody reachable at all — always offline regardless of transport.
        peerCount == 0 -> FlashConnectionHealth.Offline
        // Connecting outranks "no transport" (ERROR-031). A peer whose session just dropped has no
        // transport to name yet — that is exactly the reconnect window, not idle searching — so
        // testing Unknown first made the banner say "Searching for devices…" underneath a header
        // already showing "Connecting…".
        peerPresence == FlashPeerPresence.Connecting -> FlashConnectionHealth.Connecting
        transport == FlashNetworkTransport.Unknown -> FlashConnectionHealth.Offline
        (transport == FlashNetworkTransport.Lan || transport == FlashNetworkTransport.WifiDirect) &&
            peerPresence == FlashPeerPresence.Online -> FlashConnectionHealth.Connected
        // An unreachable peer is offline regardless of any relay path still being up.
        peerPresence == FlashPeerPresence.Offline -> FlashConnectionHealth.Offline
        transport == FlashNetworkTransport.Relay -> FlashConnectionHealth.Degraded
        else -> FlashConnectionHealth.Degraded
    }

    /** Short transport label used by the always-visible [FlashTransportBadge]. */
    fun transportLabel(transport: FlashNetworkTransport): String = when (transport) {
        FlashNetworkTransport.Lan -> "LAN"
        FlashNetworkTransport.WifiDirect -> "Wi-Fi Direct"
        FlashNetworkTransport.Relay -> "Relay"
        FlashNetworkTransport.Unknown -> "No link"
    }

    /**
     * Human label for the banner/badge. Direct transports qualify the Connected state;
     * offline reads as active searching (P2P peers come and go — not an error).
     */
    fun healthLabel(
        health: FlashConnectionHealth,
        transport: FlashNetworkTransport = FlashNetworkTransport.Unknown,
    ): String = when (health) {
        FlashConnectionHealth.Connected -> when (transport) {
            FlashNetworkTransport.Lan -> "Connected · LAN"
            FlashNetworkTransport.WifiDirect -> "Connected · Wi-Fi Direct"
            else -> "Connected"
        }
        FlashConnectionHealth.Degraded -> if (transport == FlashNetworkTransport.Relay) "Relayed" else "Degraded connection"
        FlashConnectionHealth.Connecting -> "Connecting…"
        FlashConnectionHealth.Offline -> "Searching for devices…"
    }

    /** Only Offline blocks sending; Degraded/Connecting stay non-blocking per research. */
    fun isBlockingState(health: FlashConnectionHealth): Boolean = health == FlashConnectionHealth.Offline

    /** Calm for environmental conditions, Attention only when the user truly cannot send. */
    fun bannerSeverity(health: FlashConnectionHealth): FlashNetworkBannerSeverity = when (health) {
        FlashConnectionHealth.Degraded, FlashConnectionHealth.Connecting -> FlashNetworkBannerSeverity.Calm
        FlashConnectionHealth.Offline -> FlashNetworkBannerSeverity.Attention
        FlashConnectionHealth.Connected -> FlashNetworkBannerSeverity.Calm
    }

    /** Transport glyph; Unknown falls back to the generic Device icon rather than hiding the chip. */
    fun transportIconSpec(transport: FlashNetworkTransport): FlashIconSpec? = when (transport) {
        FlashNetworkTransport.Lan -> FlashIcons.Wifi
        FlashNetworkTransport.WifiDirect -> FlashIcons.WifiDirect
        FlashNetworkTransport.Relay -> FlashIcons.Relay
        FlashNetworkTransport.Unknown -> FlashIcons.Device
    }
}

/**
 * UI-030 — Compact connection banner shown under the chat header while the P2P link is
 * not fully Connected. Caller controls visibility (e.g. wraps in `AnimatedVisibility`
 * keyed on `health != Connected`); the banner itself is static — no internal animations.
 *
 * Tone follows error-states.md severity language: Calm (neutral surface, textSecondary)
 * for Connecting/Degraded, Attention (spark tint) for Offline. Never `textError` red.
 */
@Composable
fun FlashConnectionBanner(
    health: FlashConnectionHealth,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val severity = FlashNetworkStatusMath.bannerSeverity(health)

    val background = when (severity) {
        FlashNetworkBannerSeverity.Calm -> colors.backgroundSurfaceSubtle
        FlashNetworkBannerSeverity.Attention -> colors.accentSecondary.copy(alpha = 0.15f)
    }
    val contentColor = when (severity) {
        FlashNetworkBannerSeverity.Calm -> colors.textSecondary
        FlashNetworkBannerSeverity.Attention -> colors.textPrimary
    }
    val leadingIcon = when (severity) {
        FlashNetworkBannerSeverity.Calm -> FlashIcons.Connection
        FlashNetworkBannerSeverity.Attention -> FlashIcons.Device
    }
    val label = FlashNetworkStatusMath.healthLabel(health)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .background(background)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                contentDescription = label
            },
    ) {
        FlashIcon(
            icon = leadingIcon,
            contentDescription = null,
            tint = contentColor,
            size = FlashDimensions.iconSm,
        )
        FlashText(
            text = label,
            style = typography.metadataDefault,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )

        if (FlashNetworkStatusMath.isBlockingState(health)) {
            Box(
                modifier = Modifier
                    .clip(FlashShapes.chip)
                    .background(colors.accentPrimary)
                    .defaultMinSize(minHeight = 32.dp)
                    .clickable(onClick = onRetry)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = "Retry connection"
                    }
                    .padding(horizontal = FlashSpacing.space12),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Retry",
                    style = typography.metadataEmphasis,
                    color = colors.textOnAccent,
                )
            }
        }
    }
}

/**
 * UI-030 — Small always-visible transport chip (LAN / Wi-Fi Direct / Relay / No link).
 * Sits in the header status area or anywhere transport context helps; purely informative.
 */
@Composable
fun FlashTransportBadge(
    transport: FlashNetworkTransport,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val label = FlashNetworkStatusMath.transportLabel(transport)
    val description = "Connection: $label"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        modifier = modifier
            .clip(FlashShapes.chip)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.chip)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
    ) {
        FlashIcon(
            icon = FlashNetworkStatusMath.transportIconSpec(transport) ?: FlashIcons.Device,
            contentDescription = null,
            tint = colors.textSecondary,
            size = FlashDimensions.iconSm,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(modifier = Modifier.width(2.dp))
        FlashText(
            text = label,
            style = typography.metadataDefault,
            color = colors.textSecondary,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Banner — connected LAN", showBackground = true, widthDp = 390)
@Composable
private fun FlashConnectionBannerConnectedLanPreview() {
    FlashTheme {
        ColumnPreviewHost {
            FlashConnectionBanner(
                health = FlashNetworkStatusMath.resolveHealth(
                    FlashNetworkTransport.Lan,
                    FlashPeerPresence.Online,
                    peerCount = 1,
                ),
            )
        }
    }
}

@Preview(name = "Banner — relayed (degraded)", showBackground = true, widthDp = 390)
@Composable
private fun FlashConnectionBannerRelayedPreview() {
    FlashTheme {
        ColumnPreviewHost {
            FlashConnectionBanner(
                health = FlashNetworkStatusMath.resolveHealth(
                    FlashNetworkTransport.Relay,
                    FlashPeerPresence.Online,
                    peerCount = 1,
                ),
            )
        }
    }
}

@Preview(name = "Banner — connecting", showBackground = true, widthDp = 390)
@Composable
private fun FlashConnectionBannerConnectingPreview() {
    FlashTheme {
        ColumnPreviewHost {
            FlashConnectionBanner(
                health = FlashNetworkStatusMath.resolveHealth(
                    FlashNetworkTransport.WifiDirect,
                    FlashPeerPresence.Connecting,
                    peerCount = 1,
                ),
            )
        }
    }
}

@Preview(name = "Banner — offline with retry", showBackground = true, widthDp = 390)
@Composable
private fun FlashConnectionBannerOfflineRetryPreview() {
    FlashTheme {
        ColumnPreviewHost {
            FlashConnectionBanner(
                health = FlashNetworkStatusMath.resolveHealth(
                    FlashNetworkTransport.Unknown,
                    FlashPeerPresence.Offline,
                    peerCount = 0,
                ),
                onRetry = {},
            )
        }
    }
}

@Preview(name = "Banner — dark offline with retry", showBackground = true, widthDp = 390)
@Composable
private fun FlashConnectionBannerDarkOfflinePreview() {
    FlashTheme(darkTheme = true) {
        ColumnPreviewHost {
            FlashConnectionBanner(
                health = FlashConnectionHealth.Offline,
                onRetry = {},
            )
        }
    }
}

@Preview(name = "Badges — all transports", showBackground = true, widthDp = 390)
@Composable
private fun FlashTransportBadgeAllPreview() {
    FlashTheme {
        ColumnPreviewHost {
            Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                FlashTransportBadge(FlashNetworkTransport.Lan)
                FlashTransportBadge(FlashNetworkTransport.WifiDirect)
            }
            Spacer(modifier = Modifier.size(FlashSpacing.space8))
            Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                FlashTransportBadge(FlashNetworkTransport.Relay)
                FlashTransportBadge(FlashNetworkTransport.Unknown)
            }
        }
    }
}

/** Neutral preview scaffolding so banner strips read against the app surface. */
@Composable
private fun ColumnPreviewHost(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent),
    ) {
        content()
    }
}
