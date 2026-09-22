package com.transfer.flash.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Lifecycle of the device-pairing consent flow (UI-032). */
enum class FlashPairingPhase {
    Idle,
    RequestReceived,
    AwaitingPeerConfirmation,
    Paired,
    Declined,
    Expired,
}

/**
 * Demo-side snapshot of an incoming pairing request (UI-032).
 * Engine hooks will replace this once the pairing handshake lands engine-side.
 */
data class FlashPairingRequestUi(
    val peerName: String,
    val peerInitials: String,
    /** 6-digit comparison code shown on BOTH devices (numeric-comparison pattern). */
    val numericCode: String,
    val transport: FlashNetworkTransport,
    val expiresInSeconds: Int = 30,
)

/** Pure pairing-flow logic — unit-testable without instrumentation ([FlashPairingLogicTest]). */
object FlashPairingMath {

    const val CODE_DIGITS = 6

    /** Formats a raw code into two 3-digit groups ("123456" → "123 456"); digits-only, padded. */
    fun formatCode(rawCode: String): String {
        val digits = rawCode.filter(Char::isDigit).take(CODE_DIGITS).padEnd(CODE_DIGITS, '0')
        return "${digits.take(3)} ${digits.takeLast(3)}"
    }

    /** Accept/Decline actions exist only while a fresh request awaits the local decision. */
    fun canConfirm(phase: FlashPairingPhase): Boolean =
        phase == FlashPairingPhase.RequestReceived

    /**
     * Evaluates one countdown tick. Pass the seconds remaining AFTER the tick;
     * at or below zero the flow transitions to [FlashPairingPhase.Expired].
     */
    fun tickCountdown(secondsLeft: Int): FlashPairingPhase =
        if (secondsLeft <= 0) FlashPairingPhase.Expired else FlashPairingPhase.RequestReceived

    /** Short status chip copy per phase. */
    fun phaseStatusLabel(phase: FlashPairingPhase): String = when (phase) {
        FlashPairingPhase.Idle -> "Not pairing"
        FlashPairingPhase.RequestReceived -> "Pairing request"
        FlashPairingPhase.AwaitingPeerConfirmation -> "Waiting for peer"
        FlashPairingPhase.Paired -> "Paired"
        FlashPairingPhase.Declined -> "Declined"
        FlashPairingPhase.Expired -> "Request expired"
    }

    /** Up to two initials: first letter of the first two words; "?" when nameless. */
    fun initialsFor(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].first().toString() + words[1].first()).uppercase()
        }
    }

    /** Human label for the transport glyph row; null hides the row. */
    fun transportLabel(transport: FlashNetworkTransport): String? = when (transport) {
        FlashNetworkTransport.Lan -> "LAN"
        FlashNetworkTransport.WifiDirect -> "Wi-Fi Direct"
        FlashNetworkTransport.Relay -> "Relay"
        FlashNetworkTransport.Unknown -> null
    }
}

/**
 * UI-032 Device pairing consent flow — rendered IN-SCREEN (not a Dialog window) as a
 * centered card overlay over a dimmed scrim, following the message-focus-overlay
 * precedent: scrim taps and hardware back dismiss on first contact.
 *
 * Numeric-comparison pattern: the 6-digit code must match on both devices before either
 * side trusts the link. Decline/back/scrim always exit safely with no side effects.
 */
@Composable
fun FlashPairingDialog(
    request: FlashPairingRequestUi?,
    phase: FlashPairingPhase,
    secondsLeft: Int,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = request != null && phase != FlashPairingPhase.Idle
    FlashBackHandler(enabled = visible, onBack = onDismiss)
    if (!visible) return

    val colors = FlashTheme.colors
    val safeRequest = requireNotNull(request)
    val initials = safeRequest.peerInitials.ifBlank { FlashPairingMath.initialsFor(safeRequest.peerName) }

    // Dimmed backdrop — tapping it cancels/dismisses on first contact.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(FlashSpacing.space20),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 460.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* swallow clicks inside the pairing card */ },
                )
                .clip(RoundedCornerShape(FlashShapes.radius24))
                .background(colors.backgroundSurface)
                .border(
                    FlashDimensions.borderHairline,
                    colors.borderSubtle,
                    RoundedCornerShape(FlashShapes.radius24),
                )
                .padding(FlashSpacing.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PeerIdentityBlock(request = safeRequest, initials = initials)

            if (phase == FlashPairingPhase.RequestReceived || phase == FlashPairingPhase.AwaitingPeerConfirmation) {
                VerificationCodeBlock(code = FlashPairingMath.formatCode(safeRequest.numericCode))

                FlashCountdownBar(
                    fraction = secondsLeft.toFloat() / safeRequest.expiresInSeconds.coerceAtLeast(1),
                    modifier = Modifier.padding(top = FlashSpacing.space24),
                )

                val seconds = secondsLeft.coerceAtLeast(0)
                FlashText(
                    text = "Expires in ${seconds}s",
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .padding(top = FlashSpacing.space8)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "$seconds seconds remaining"
                        },
                )

                when (phase) {
                    FlashPairingPhase.RequestReceived -> ActionButtonsRow(
                        onAccept = onAccept,
                        onDecline = onDecline,
                    )
                    FlashPairingPhase.AwaitingPeerConfirmation -> {
                        FlashPulsingDot(modifier = Modifier.padding(top = FlashSpacing.space24))
                        FlashText(
                            text = "Waiting for ${safeRequest.peerName} to confirm…",
                            style = FlashTheme.typography.captionDefault,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = FlashSpacing.space12),
                        )
                    }
                    else -> Unit
                }
            } else {
                TerminalPhaseBlock(
                    phase = phase,
                    peerName = safeRequest.peerName,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/** Seeded avatar + name + "wants to pair" + transport line, announced as one sentence. */
@Composable
private fun PeerIdentityBlock(request: FlashPairingRequestUi, initials: String) {
    val colors = FlashTheme.colors
    val label = FlashPairingMath.transportLabel(request.transport)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(request.peerName)
                    append(" wants to pair")
                    label?.let { append(" over ").append(it) }
                    append(". Verify the code matches on both devices.")
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlashAvatar(
            initials = initials,
            seed = request.peerName,
            size = FlashDimensions.avatarXl,
        )
        FlashText(
            text = request.peerName,
            style = FlashTheme.typography.headingMedium,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.padding(top = FlashSpacing.space16),
        )
        FlashText(
            text = "wants to pair",
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = FlashSpacing.space4),
        )
        if (label != null) {
            Row(
                modifier = Modifier.padding(top = FlashSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                FlashIcon(
                    icon = when (request.transport) {
                        FlashNetworkTransport.Lan -> FlashIcons.Wifi
                        FlashNetworkTransport.WifiDirect -> FlashIcons.WifiDirect
                        else -> FlashIcons.Relay
                    },
                    contentDescription = null,
                    size = FlashDimensions.iconSm,
                    tint = colors.textTertiary,
                )
                FlashText(
                    text = "via $label",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

/** Large tabular verification code as two 3-digit tiles, announced as one comparison task. */
@Composable
private fun VerificationCodeBlock(code: String) {
    val colors = FlashTheme.colors
    val groups = code.split(" ")
    val codeStyle = FlashTheme.typography.numericEmphasis.copy(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = 2.sp,
        fontFamily = FontFamily.Monospace,
    )

    Row(
        modifier = Modifier
            .padding(top = FlashSpacing.space24)
            .semantics(mergeDescendants = true) {
                contentDescription = "Verification code $code. Confirm it matches the other device."
            },
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        groups.forEach { group ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FlashShapes.radius16))
                    .background(colors.backgroundSurfaceSubtle)
                    .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(FlashShapes.radius16))
                    .padding(horizontal = FlashSpacing.space20, vertical = FlashSpacing.space12),
            ) {
                FlashText(
                    text = group,
                    style = codeStyle,
                    color = colors.accentPrimary,
                )
            }
        }
    }
}

/** Canvas-drawn linear countdown: hairline track + accentPrimary progress. */
@Composable
private fun FlashCountdownBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Canvas(modifier = modifier.fillMaxWidth().height(FlashSpacing.space4)) {
        val r = size.height / 2f
        drawRoundRect(
            color = colors.backgroundSurfaceStrong,
            cornerRadius = CornerRadius(r, r),
        )
        drawRoundRect(
            color = colors.accentPrimary,
            size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
            cornerRadius = CornerRadius(r, r),
        )
    }
}

/** Accept (accent pill) + Decline (neutral pill), both Role.Button with explicit labels. */
@Composable
private fun ActionButtonsRow(onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FlashSpacing.space24),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashPairingPillButton(
            label = "Decline",
            description = "Decline pairing request",
            container = FlashTheme.colors.backgroundSurfaceStrong,
            contentColor = FlashTheme.colors.textPrimary,
            onClick = onDecline,
            modifier = Modifier.weight(1f),
        )
        FlashPairingPillButton(
            label = "Accept",
            description = "Accept pairing request",
            container = FlashTheme.colors.accentPrimary,
            contentColor = FlashTheme.colors.textOnAccent,
            onClick = onAccept,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FlashPairingPillButton(
    label: String,
    description: String,
    container: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(RoundedCornerShape(FlashShapes.radiusFull))
            .background(container)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.bodyEmphasis,
            color = contentColor,
        )
    }
}

/**
 * Softly breathing dot shown while the peer decides; static under reduce-motion.
 *
 * The alpha is kept as a **[State] and read in the draw phase** (EXP-013). Unwrapped to a `Float` it
 * was a composition read, so this dot recomposed on every frame for as long as a pairing request was
 * outstanding — up to the whole countdown, on the device where pairing is already the slowest moment.
 *
 * `drawBehind { drawCircle(color.copy(alpha = …)) }` rather than `clip(CircleShape).background(…)`
 * behind a `graphicsLayer { alpha = … }`: for a square box the inscribed circle is the same pixels,
 * but a `graphicsLayer` with `alpha < 1` under the default `CompositingStrategy.Auto` marks the layer
 * as overlapping and can have the platform allocate an offscreen buffer for it. Modulating the alpha
 * into the one draw call needs no buffer at all.
 */
@Composable
private fun FlashPulsingDot(modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val alpha: State<Float> = if (motion.reduceMotion) {
        remember { mutableFloatStateOf(1f) }
    } else {
        val transition = rememberInfiniteTransition(label = "pairingPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = motion.slowMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pairingPulseAlpha",
        )
    }
    val dotColor = colors.accentPrimary
    Box(
        modifier = modifier
            .size(FlashSpacing.space12)
            .drawBehind { drawCircle(color = dotColor.copy(alpha = alpha.value)) },
    )
}

/** Paired / Declined / Expired outcomes with severity-distinct visuals and retry hints. */
@Composable
private fun TerminalPhaseBlock(
    phase: FlashPairingPhase,
    peerName: String,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    val (icon, iconTint, medallionFill, headline, body) = when (phase) {
        FlashPairingPhase.Paired -> PairQuadruple(
            icon = FlashIcons.Verified,
            iconTint = colors.textSuccess,
            medallionFill = colors.statusOnline.copy(alpha = 0.14f),
            headline = "Paired",
            body = "You're securely connected to $peerName. This window closes automatically.",
        )
        FlashPairingPhase.Declined -> PairQuadruple(
            icon = FlashIcons.Failed,
            iconTint = colors.textError,
            medallionFill = colors.textError.copy(alpha = 0.12f),
            headline = "Request declined",
            body = "$peerName declined the pairing request. You can try again from Nearby Devices.",
        )
        else -> PairQuadruple(
            icon = FlashIcons.Clock,
            iconTint = colors.textSecondary,
            medallionFill = colors.backgroundSurfaceStrong,
            headline = FlashPairingMath.phaseStatusLabel(FlashPairingPhase.Expired),
            body = "The pairing window timed out before both devices confirmed. Try pairing again from Nearby Devices.",
        )
    }

    Box(
        modifier = Modifier
            .padding(top = FlashSpacing.space24)
            .size(FlashDimensions.avatarXl)
            .clip(CircleShape)
            .background(medallionFill),
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(icon = icon, contentDescription = null, size = FlashDimensions.iconLg, tint = iconTint)
    }
    FlashText(
        text = headline,
        style = FlashTheme.typography.headingMedium,
        color = colors.textPrimary,
        modifier = Modifier.padding(top = FlashSpacing.space16),
    )
    FlashText(
        text = body,
        style = FlashTheme.typography.captionDefault,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = FlashSpacing.space8),
    )

    if (phase != FlashPairingPhase.Paired) {
        FlashPairingPillButton(
            label = "Close",
            description = "Close pairing dialog",
            container = colors.backgroundSurfaceStrong,
            contentColor = colors.textPrimary,
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FlashSpacing.space24),
        )
    }
}

/** Small holder so the terminal-state mapping stays a single expression. */
private data class PairQuadruple(
    val icon: com.transfer.flash.ui.icons.FlashIconSpec,
    val iconTint: Color,
    val medallionFill: Color,
    val headline: String,
    val body: String,
)

fun samplePairingRequest(): FlashPairingRequestUi = FlashPairingRequestUi(
    peerName = "Alex Rivera",
    peerInitials = "AR",
    numericCode = "427913",
    transport = FlashNetworkTransport.WifiDirect,
    expiresInSeconds = 30,
)

@Preview(name = "Pairing — request received", showBackground = true, widthDp = 390, heightDp = 720)
@Composable
private fun FlashPairingRequestPreview() {
    FlashTheme {
        Box(Modifier.fillMaxSize()) {
            FlashPairingDialog(
                request = samplePairingRequest(),
                phase = FlashPairingPhase.RequestReceived,
                secondsLeft = 21,
                onAccept = {},
                onDecline = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(name = "Pairing — awaiting peer", showBackground = true, widthDp = 390, heightDp = 720)
@Composable
private fun FlashPairingAwaitingPreview() {
    FlashTheme {
        Box(Modifier.fillMaxSize()) {
            FlashPairingDialog(
                request = samplePairingRequest(),
                phase = FlashPairingPhase.AwaitingPeerConfirmation,
                secondsLeft = 14,
                onAccept = {},
                onDecline = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(name = "Pairing — paired", showBackground = true, widthDp = 390, heightDp = 720)
@Composable
private fun FlashPairingPairedPreview() {
    FlashTheme {
        Box(Modifier.fillMaxSize()) {
            FlashPairingDialog(
                request = samplePairingRequest(),
                phase = FlashPairingPhase.Paired,
                secondsLeft = 0,
                onAccept = {},
                onDecline = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(name = "Pairing — declined", showBackground = true, widthDp = 390, heightDp = 720)
@Composable
private fun FlashPairingDeclinedPreview() {
    FlashTheme {
        Box(Modifier.fillMaxSize()) {
            FlashPairingDialog(
                request = samplePairingRequest(),
                phase = FlashPairingPhase.Declined,
                secondsLeft = 0,
                onAccept = {},
                onDecline = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(name = "Pairing — expired", showBackground = true, widthDp = 390, heightDp = 720)
@Composable
private fun FlashPairingExpiredPreview() {
    FlashTheme {
        Box(Modifier.fillMaxSize()) {
            FlashPairingDialog(
                request = samplePairingRequest(),
                phase = FlashPairingPhase.Expired,
                secondsLeft = 0,
                onAccept = {},
                onDecline = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(name = "Pairing — dark", showBackground = true, widthDp = 390, heightDp = 720)
@Composable
private fun FlashPairingDarkPreview() {
    FlashTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize()) {
            FlashPairingDialog(
                request = samplePairingRequest(),
                phase = FlashPairingPhase.RequestReceived,
                secondsLeft = 27,
                onAccept = {},
                onDecline = {},
                onDismiss = {},
            )
        }
    }
}
