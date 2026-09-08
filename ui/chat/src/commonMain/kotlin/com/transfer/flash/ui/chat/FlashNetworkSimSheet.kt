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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Pure cycle + copy logic for the UI-044 network-state simulation panel.
 * Engine-independent: only reshuffles [FlashConnectionHealth] values for demos/QA.
 * Unit-tested without instrumentation (see [FlashNetworkSimLogicTest]).
 */
object FlashNetworkSimMath {

    /** All forceable states, in sheet presentation order. */
    val simulatableStates: List<FlashConnectionHealth> = FlashConnectionHealth.entries.toList()

    /** Next state in the [simulatableStates] cycle (wraps from Offline back to Connected). */
    fun nextHealth(current: FlashConnectionHealth): FlashConnectionHealth =
        healthFromIndex(current.ordinal + 1)

    /**
     * State at cyclic [index]; negative and out-of-range indices wrap via floor-mod,
     * so callers can step forward or backward freely.
     */
    fun healthFromIndex(index: Int): FlashConnectionHealth {
        val size = FlashConnectionHealth.entries.size
        // `Math.floorMod` is `java.lang` and does not exist in `commonMain`. Kotlin's
        // `Int.mod(Int)` is the same operation — flooring remainder, result signed like the
        // divisor — from the common stdlib, so the wrap behaviour is unchanged.
        return FlashConnectionHealth.entries[index.mod(size)]
    }

    /** Row/trigger copy for forcing a state ("Force Connected", …). */
    fun simLabel(health: FlashConnectionHealth): String = when (health) {
        FlashConnectionHealth.Connected -> "Force Connected"
        FlashConnectionHealth.Connecting -> "Force Connecting"
        FlashConnectionHealth.Degraded -> "Force Degraded"
        FlashConnectionHealth.Offline -> "Force Offline"
    }
}

/**
 * UI-044 — Effective connection health for demo/testing overrides.
 *
 * Returns [simulated] while an override is active (non-null), otherwise [real].
 * Pass the result into [FlashConnectionBanner] visibility gating exactly as the
 * banner contract in chat-screen.md (UI-030) describes:
 *
 * ```
 * var simulated by remember { mutableStateOf<FlashConnectionHealth?>(null) }
 * val health = rememberSimulatedHealth(realHealth, simulated)
 * AnimatedVisibility(visible = health.value != FlashConnectionHealth.Connected) {
 *     FlashConnectionBanner(health = health.value, onRetry = engine::retryConnection)
 * }
 * ```
 *
 * Clearing [simulated] to null instantly restores engine-reported reality.
 */
@Composable
fun rememberSimulatedHealth(
    real: FlashConnectionHealth,
    simulated: FlashConnectionHealth?,
): State<FlashConnectionHealth> {
    val effective = simulated ?: real
    return remember(effective) { mutableStateOf(effective) }
}

/**
 * UI-044 — Developer/QA simulation panel that forces a conversation-level
 * [FlashConnectionHealth] without touching the transfer/discovery engines.
 * Opens from a hidden debug trigger (e.g. long-press on the transport badge —
 * wiring owned by the lead, see docs/ui/error-states.md UI-044 section).
 *
 * Container styling matches [com.transfer.flash.ui.chat.FlashAttachmentSheet]:
 * radius24 top corners, manual drag handle, navigationBars insets.
 * Not user-facing product surface — must stay out of release entry points.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashNetworkSimSheet(
    currentHealth: FlashConnectionHealth,
    onSelect: (FlashConnectionHealth) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val states = remember { FlashNetworkSimMath.simulatableStates }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.backgroundSurface,
        shape = RoundedCornerShape(
            topStart = FlashShapes.radius24,
            topEnd = FlashShapes.radius24,
        ),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FlashSpacing.space12)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.borderSubtle),
            )
        },
        contentWindowInsets = { WindowInsets.navigationBars },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = FlashSpacing.space20,
                    end = FlashSpacing.space20,
                    top = FlashSpacing.space8,
                    bottom = FlashSpacing.space32,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlashText(
                    text = "Network simulation",
                    style = typography.headingSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
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
                        size = FlashDimensions.iconSm,
                    )
                }
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space8))

            FlashText(
                text = "Demo override — forces the connection banner in this conversation " +
                    "until cleared. Does not change the real connection.",
                style = typography.metadataDefault,
                color = colors.textTertiary,
            )

            Spacer(modifier = Modifier.height(FlashSpacing.space16))

            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                states.forEach { health ->
                    FlashNetworkSimRow(
                        health = health,
                        isSelected = health == currentHealth,
                        onClick = { onSelect(health) },
                    )
                }
            }
        }
    }
}

/** One selectable forced-state row: custom radio dot, label, trailing check when active. */
@Composable
private fun FlashNetworkSimRow(
    health: FlashConnectionHealth,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val label = FlashNetworkSimMath.simLabel(health)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.chip)
            .background(if (isSelected) colors.backgroundSurfaceSubtle else colors.backgroundSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (isSelected) "$label. Active" else label
            },
    ) {
        // Custom radio indicator (drawn, not Material RadioButton).
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (isSelected) colors.accentPrimary else colors.textTertiary,
                    shape = CircleShape,
                )
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accentPrimary else colors.backgroundSurface),
            )
        }

        FlashText(
            text = label,
            style = typography.metadataEmphasis,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )

        if (isSelected) {
            FlashIcon(
                icon = FlashIcons.Check,
                contentDescription = null,
                tint = colors.accentPrimary,
                size = FlashDimensions.iconSm,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Sim sheet — light, offline forced", showBackground = true, widthDp = 390, heightDp = 640)
@Composable
private fun FlashNetworkSimSheetLightPreview() {
    FlashTheme {
        FlashNetworkSimSheet(
            currentHealth = FlashConnectionHealth.Offline,
            onSelect = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Sim sheet — dark, connected default", showBackground = true, widthDp = 390, heightDp = 640)
@Composable
private fun FlashNetworkSimSheetDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashNetworkSimSheet(
            currentHealth = FlashConnectionHealth.Connected,
            onSelect = {},
            onDismiss = {},
        )
    }
}
