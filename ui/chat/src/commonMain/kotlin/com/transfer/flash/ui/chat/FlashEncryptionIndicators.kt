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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import com.transfer.flash.ui.shims.rememberFlashClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
 * UI-031 — Trust state of the conversation's encryption surface.
 *
 * [EncryptedTrusted] — channel encrypted AND peer security codes marked verified.
 * [EncryptedUnverified] — channel encrypted, verification not yet performed
 * (a condition, not a fault — follows error-states.md severity language: never red).
 * [None] — no encryption guarantee yet; badge renders nothing.
 */
enum class FlashEncryptionBadgeState {
    EncryptedTrusted,
    EncryptedUnverified,
    None,
}

/**
 * Pure decision + copy logic for UI-031 encryption indicators.
 * Unit-tested without instrumentation (see [FlashEncryptionLogicTest]).
 */
object FlashEncryptionMath {

    /**
     * Map engine booleans to badge trust state.
     * No encryption always wins (None); otherwise verified distinguishes Trusted.
     */
    fun badgeState(isEncrypted: Boolean, isVerified: Boolean): FlashEncryptionBadgeState = when {
        !isEncrypted -> FlashEncryptionBadgeState.None
        isVerified -> FlashEncryptionBadgeState.EncryptedTrusted
        else -> FlashEncryptionBadgeState.EncryptedUnverified
    }

    /** Short jargon-free chip label. Empty for [FlashEncryptionBadgeState.None]. */
    fun badgeLabel(state: FlashEncryptionBadgeState): String = when (state) {
        FlashEncryptionBadgeState.EncryptedTrusted -> "Encrypted"
        FlashEncryptionBadgeState.EncryptedUnverified -> "Unverified"
        FlashEncryptionBadgeState.None -> ""
    }

    /**
     * Plain-language explainer lines for [FlashEncryptionSheet]: what encryption protects
     * in Flash P2P (local network, no cloud), plus verification status context.
     * Non-blank guard applied — callers can render every returned line safely.
     */
    fun sheetExplainerLines(state: FlashEncryptionBadgeState): List<String> {
        val lines = when (state) {
            FlashEncryptionBadgeState.EncryptedTrusted -> listOf(
                "Messages in this chat are encrypted before they leave your device.",
                "Flash sends them directly between devices on your local network — there are no cloud servers in between.",
                "You have verified this device's security codes, so you know who is on the other end.",
            )
            FlashEncryptionBadgeState.EncryptedUnverified -> listOf(
                "Messages in this chat are encrypted before they leave your device.",
                "Flash sends them directly between devices on your local network — there are no cloud servers in between.",
                "You haven't verified this device's security codes yet. Verifying confirms nobody is intercepting traffic on the network.",
            )
            FlashEncryptionBadgeState.None -> listOf(
                "This conversation isn't encrypted yet.",
                "Messages will be protected once a direct connection is established.",
            )
        }
        return lines.mapNotNull { it.trim().takeIf(String::isNotBlank) }
    }

    /** Title copy for the sheet, per trust state. */
    fun sheetTitle(state: FlashEncryptionBadgeState): String = when (state) {
        FlashEncryptionBadgeState.EncryptedTrusted -> "Encrypted & verified"
        FlashEncryptionBadgeState.EncryptedUnverified -> "Encrypted"
        FlashEncryptionBadgeState.None -> "Encryption"
    }

    /**
     * Verification entry points shown in the sheet until engine support lands.
     * Rendered disabled-with-explanation (error-states.md: explained beats hidden).
     */
    val verificationEntries: List<VerificationEntry> = listOf(
        VerificationEntry(
            title = "Verify security codes",
            explanation = "Compare short codes with your peer to confirm the connection is private.",
            icon = FlashIcons.Check,
        ),
        VerificationEntry(
            title = "View device fingerprint",
            explanation = "Inspect this device's unique identity on the local network.",
            icon = FlashIcons.Device,
        ),
    )

    /** One disabled placeholder row inside [FlashEncryptionSheet]. */
    data class VerificationEntry(
        val title: String,
        val explanation: String,
        val icon: FlashIconSpec,
    )
}

/**
 * UI-031 — Compact encryption trust badge (icon + short label) for placement near the
 * chat header or composer. Tapping opens [FlashEncryptionSheet] via [onClick].
 *
 * [FlashEncryptionBadgeState.None] renders nothing — absence is the honest signal when
 * no encryption guarantee exists yet.
 */
@Composable
fun FlashEncryptionBadge(
    state: FlashEncryptionBadgeState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    if (state == FlashEncryptionBadgeState.None) return

    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val label = FlashEncryptionMath.badgeLabel(state)
    // Subtle positive cue for verified; neutral for unverified. Never red —
    // unverified is a condition, not a fault (error-states.md).
    val iconTint = if (state == FlashEncryptionBadgeState.EncryptedTrusted) {
        colors.accentPrimary
    } else {
        colors.textSecondary
    }
    val description = "$label. Tap for details"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        modifier = modifier
            .clip(FlashShapes.chip)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
    ) {
        FlashIcon(
            icon = FlashIcons.Encryption,
            contentDescription = null,
            tint = iconTint,
            size = FlashDimensions.iconSm,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(modifier = Modifier.width(2.dp))
        FlashText(
            text = label,
            style = typography.metadataDefault,
            color = colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * UI-031 — Bottom-sheet trust surface opened from [FlashEncryptionBadge].
 * Plain-language explanation of what encryption protects in Flash P2P, plus
 * verification entry-point placeholder rows rendered disabled-with-explanation
 * until engine pairing/verification support lands (UI-032).
 *
 * Container styling matches [FlashAttachmentSheet]: radius24 top corners, manual drag
 * handle, navigationBars insets. All visible content stays Flash-owned (tokens only).
 */
@Composable
fun FlashEncryptionSheet(
    state: FlashEncryptionBadgeState,
    onDismiss: () -> Unit,
    onVerifySecurityCodes: (() -> Unit)? = null,
    onViewFingerprint: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val explainerLines = rememberExplainerLines(state)

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
                    top = FlashSpacing.space8,
                    bottom = FlashSpacing.space32,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlashText(
                    text = FlashEncryptionMath.sheetTitle(state),
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

            Spacer(modifier = Modifier.height(FlashSpacing.space12))

            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                explainerLines.forEach { line ->
                    FlashText(
                        text = line,
                        style = typography.bodyDefault,
                        color = colors.textSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space20))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FlashDimensions.borderHairline)
                    .background(colors.borderSubtle),
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space16))

            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                FlashEncryptionMath.verificationEntries.forEach { entry ->
                    val action = when (entry.title) {
                        "Verify security codes" -> onVerifySecurityCodes
                        "View device fingerprint" -> onViewFingerprint
                        else -> null
                    }
                    if (action != null) {
                        FlashVerificationActionRow(
                            entry = entry,
                            state = state,
                            onClick = action,
                        )
                    } else {
                        FlashVerificationPlaceholderRow(entry = entry)
                    }
                }
            }
        }
    }
}

/** Actionable verification entry point row inside [FlashEncryptionSheet]. */
@Composable
private fun FlashVerificationActionRow(
    entry: FlashEncryptionMath.VerificationEntry,
    state: FlashEncryptionBadgeState,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val isCodesEntry = entry.title == "Verify security codes"
    val isVerified = state == FlashEncryptionBadgeState.EncryptedTrusted

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.chip)
            .background(colors.backgroundSurfaceSubtle)
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "${entry.title}: ${entry.explanation}"
            },
    ) {
        FlashIcon(
            icon = if (isCodesEntry && isVerified) FlashIcons.Verified else entry.icon,
            contentDescription = null,
            tint = if (isCodesEntry && isVerified) colors.accentPrimary else colors.textPrimary,
            size = FlashDimensions.iconMd,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Column(modifier = Modifier.weight(1f)) {
            FlashText(
                text = entry.title,
                style = typography.metadataEmphasis,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            FlashText(
                text = entry.explanation,
                style = typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
        if (isCodesEntry && isVerified) {
            Box(
                modifier = Modifier
                    .clip(FlashShapes.chip)
                    .background(colors.accentPrimary.copy(alpha = 0.15f))
                    .padding(horizontal = FlashSpacing.space8, vertical = 2.dp),
            ) {
                FlashText(
                    text = "Verified",
                    style = typography.captionDefault,
                    color = colors.accentPrimary,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(FlashShapes.chip)
                    .background(colors.backgroundSurfaceStrong)
                    .padding(horizontal = FlashSpacing.space8, vertical = 2.dp),
            ) {
                FlashText(
                    text = if (isCodesEntry) "Verify" else "View",
                    style = typography.captionDefault,
                    color = colors.textPrimary,
                )
            }
        }
    }
}

/** Disabled verification entry point — visible with explanation, not actionable yet. */
@Composable
private fun FlashVerificationPlaceholderRow(entry: FlashEncryptionMath.VerificationEntry) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val description = "${entry.title}. Coming after device pairing support."

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.chip)
            .background(colors.backgroundSurfaceSubtle)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .alpha(0.7f)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
    ) {
        FlashIcon(
            icon = entry.icon,
            contentDescription = null,
            tint = colors.textTertiary,
            size = FlashDimensions.iconMd,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Column(modifier = Modifier.weight(1f)) {
            FlashText(
                text = entry.title,
                style = typography.metadataEmphasis,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            FlashText(
                text = entry.explanation,
                style = typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
        FlashText(
            text = "Soon",
            style = typography.metadataDefault,
            color = colors.textTertiary,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * Bottom-sheet displaying local and peer cryptographic fingerprints on the local network.
 */
@Composable
fun FlashFingerprintSheet(
    peerTitle: String,
    isVerified: Boolean,
    localFingerprint: String?,
    peerFingerprint: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val clipboard = rememberFlashClipboard()
    var copiedMessage by remember { mutableStateOf<String?>(null) }

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
                    top = FlashSpacing.space8,
                    bottom = FlashSpacing.space32,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    FlashText(
                        text = "Device Fingerprint",
                        style = typography.headingSmall,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    FlashText(
                        text = "Unique identity on the local network",
                        style = typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }
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

            Spacer(modifier = Modifier.height(FlashSpacing.space16))

            // Verified Status Chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                modifier = Modifier
                    .clip(FlashShapes.chip)
                    .background(
                        if (isVerified) colors.accentPrimary.copy(alpha = 0.12f)
                        else colors.backgroundSurfaceSubtle
                    )
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
            ) {
                FlashIcon(
                    icon = if (isVerified) FlashIcons.Verified else FlashIcons.Encryption,
                    contentDescription = null,
                    tint = if (isVerified) colors.accentPrimary else colors.textSecondary,
                    size = FlashDimensions.iconSm,
                )
                FlashText(
                    text = if (isVerified) "Security codes verified with $peerTitle" else "Not verified yet with $peerTitle",
                    style = typography.metadataEmphasis,
                    color = if (isVerified) colors.accentPrimary else colors.textSecondary,
                )
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space16))

            // This Device Fingerprint Card
            FlashFingerprintCard(
                label = "This device",
                fingerprint = localFingerprint ?: "Generating identity…",
                onCopy = localFingerprint?.let { fp ->
                    {
                        clipboard.copy(fp)
                        copiedMessage = "This device fingerprint copied"
                    }
                },
            )

            Spacer(modifier = Modifier.height(FlashSpacing.space12))

            // Peer Device Fingerprint Card
            FlashFingerprintCard(
                label = peerTitle,
                fingerprint = peerFingerprint ?: "Available once connected to $peerTitle",
                onCopy = peerFingerprint?.let { fp ->
                    {
                        clipboard.copy(fp)
                        copiedMessage = "$peerTitle fingerprint copied"
                    }
                },
            )

            if (copiedMessage != null) {
                Spacer(modifier = Modifier.height(FlashSpacing.space8))
                FlashText(
                    text = copiedMessage ?: "",
                    style = typography.captionDefault,
                    color = colors.accentPrimary,
                )
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space16))

            FlashText(
                text = "Inspect this device's unique identity on the local network. Compare these fingerprints with the other device to confirm that no third party is intercepting your connection.",
                style = typography.captionDefault,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun FlashFingerprintCard(
    label: String,
    fingerprint: String,
    onCopy: (() -> Unit)?,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FlashShapes.attachment)
            .background(colors.backgroundSurfaceSubtle)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.attachment)
            .padding(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText(
                text = label,
                style = typography.metadataEmphasis,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (onCopy != null) {
                Box(
                    modifier = Modifier
                        .clip(FlashShapes.chip)
                        .background(colors.backgroundSurfaceStrong)
                        .clickable(onClick = onCopy)
                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Copy $label fingerprint"
                        },
                ) {
                    FlashText(
                        text = "Copy",
                        style = typography.captionDefault,
                        color = colors.accentPrimary,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(FlashSpacing.space8))
        FlashText(
            text = fingerprint,
            style = typography.numericDefault.copy(fontFamily = FontFamily.Monospace),
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun rememberExplainerLines(state: FlashEncryptionBadgeState): List<String> =
    androidx.compose.runtime.remember(state) { FlashEncryptionMath.sheetExplainerLines(state) }

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Badge — trusted", showBackground = true, widthDp = 390)
@Composable
private fun FlashEncryptionBadgeTrustedPreview() {
    FlashTheme {
        BadgePreviewHost {
            FlashEncryptionBadge(
                state = FlashEncryptionMath.badgeState(isEncrypted = true, isVerified = true),
                onClick = {},
            )
        }
    }
}

@Preview(name = "Badge — unverified", showBackground = true, widthDp = 390)
@Composable
private fun FlashEncryptionBadgeUnverifiedPreview() {
    FlashTheme {
        BadgePreviewHost {
            FlashEncryptionBadge(
                state = FlashEncryptionMath.badgeState(isEncrypted = true, isVerified = false),
                onClick = {},
            )
        }
    }
}

@Preview(name = "Badges — both states, dark", showBackground = true, widthDp = 390)
@Composable
private fun FlashEncryptionBadgeDarkPreview() {
    FlashTheme(darkTheme = true) {
        BadgePreviewHost {
            Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                FlashEncryptionBadge(
                    state = FlashEncryptionBadgeState.EncryptedTrusted,
                    onClick = {},
                )
                FlashEncryptionBadge(
                    state = FlashEncryptionBadgeState.EncryptedUnverified,
                    onClick = {},
                )
            }
        }
    }
}

@Preview(name = "Sheet — unverified, light", showBackground = true, widthDp = 390, heightDp = 640)
@Composable
private fun FlashEncryptionSheetLightPreview() {
    FlashTheme {
        FlashEncryptionSheet(
            state = FlashEncryptionBadgeState.EncryptedUnverified,
            onDismiss = {},
        )
    }
}

@Preview(name = "Sheet — verified, dark", showBackground = true, widthDp = 390, heightDp = 640)
@Composable
private fun FlashEncryptionSheetDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashEncryptionSheet(
            state = FlashEncryptionBadgeState.EncryptedTrusted,
            onDismiss = {},
        )
    }
}

/** Neutral preview scaffolding so chips read against the app surface. */
@Composable
private fun BadgePreviewHost(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(FlashSpacing.space16)) {
        content()
    }
}
