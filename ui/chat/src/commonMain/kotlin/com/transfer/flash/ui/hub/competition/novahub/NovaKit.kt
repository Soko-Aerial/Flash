package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). The shared visual kit for the five pages.
 *
 * Every component obeys the house rules the rest of the repo obeys:
 * - Typography only through [FlashText] and [FlashTheme.typography] tokens (no `material3.Text`);
 * - Colour only through `FlashTheme.colors` semantics, alpha-blended with [NovaTone.wash];
 * - Motion only through `FlashTheme.motion` specs, with reduce-motion flat equivalents;
 * - Ornament (rings, shimmer, spark fills, tape sheen) only when [novaDecorAllowed] says the budget
 *   allows; low tiers draw the flat equivalent, never nothing;
 * - Press feedback = a shared interaction source + `flashPressScale`, never a bare `clickable`;
 * - 48 dp minimum touch targets; never colour alone — every state carries an icon and a sentence.
 *
 * The kit is design-only: it reads nothing from repositories, engines or the host platform beyond
 * Compose and the Flash theme, which is what keeps the whole package preview-safe and merge-free.
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.avatar.flashAvatarColorsFor
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import kotlinx.coroutines.delay

/** Cap for inner content columns on wide panes; mirrors the role of the hub screen's content cap. */
internal val NovaContentMaxWidth: Dp = 720.dp

/** Map of the demo telemetry icon ids to the Flash icon set (icons are verified in FlashIcons). */
internal fun novaTelemetryIcon(id: Int): FlashIconSpec = when (id) {
    1 -> FlashIcons.Chat
    2 -> FlashIcons.Bolt
    3 -> FlashIcons.Group
    else -> FlashIcons.Connection
}

/** Short label the session kind tile carries (transport tag, not an invented word). */
internal fun novaKindLabel(kind: NovaSessionKind): String = when (kind) {
    NovaSessionKind.WS -> "WS"
    NovaSessionKind.REST -> "REST"
    NovaSessionKind.PEER -> "P2P"
}

// --- Selection / press --------------------------------------------------------------------------------

/**
 * House-standard press handling for tappable Nova rows and cards: a shared interaction source feeds
 * `flashPressScale`, and `indication = null` so no Material ripple draws underneath it. Passing a
 * null callback leaves the modifier untouched (the row just is not interactive).
 */
@Composable
internal fun Modifier.novaClick(onClick: (() -> Unit)?, enabled: Boolean = true): Modifier {
    if (onClick == null || !enabled) return this
    val interaction = remember { MutableInteractionSource() }
    return this
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
        .flashPressScale(interaction)
}

// --- Surfaces -----------------------------------------------------------------------------------------

/**
 * The Nova card: Flash surface layering (surface + hairline border), 16dp radius, content padding.
 * Callers who want a tappable card pass `Modifier.novaClick { … }` + a 48 dp min height themselves.
 */
@Composable
internal fun NovaCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = NovaCardShape,
    contentPadding: Dp = FlashSpacing.space16,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FlashTheme.colors
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.backgroundSurface, shape)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, shape)
            .padding(contentPadding),
    ) {
        content()
    }
}

/** Hairline row separator for lists sitting inside a card. */
@Composable
internal fun NovaDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FlashDimensions.borderHairline)
            .background(FlashTheme.colors.borderSubtle),
    )
}

// --- Text & headers ------------------------------------------------------------------------------------

/**
 * Section label: small caps-style emphasis line with an optional right-aligned trailing note.
 * Marked as a semantics heading for screen readers.
 */
@Composable
internal fun NovaSectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(
            text = title,
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.let {
            FlashText(it, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary)
        }
    }
}

/** One icon + one metadata line: the "detail row" used through every page. */
@Composable
internal fun NovaMetaLine(
    text: String,
    modifier: Modifier = Modifier,
    icon: FlashIconSpec? = null,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        icon?.let { FlashIcon(it, size = 14.dp, tint = colors.textTertiary) }
        FlashText(
            text = text,
            style = FlashTheme.typography.metadataDefault,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Status atoms --------------------------------------------------------------------------------------

/**
 * The Nova status dot. A solid disc in the tone's strong colour, optionally wrapped in a slow
 * breathing halo when the ornament budget allows it. Reduce-motion and low tiers draw just the
 * disc — same information, zero motion cost.
 */
@Composable
internal fun NovaDot(
    tone: NovaTone,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    breathe: Boolean = false,
) {
    val wash = tone.wash()
    val decor = novaDecorAllowed()
    val sweep = rememberNovaSweep()
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2f
        if (decor && breathe) {
            val p = sweep.value
            drawCircle(
                color = wash.strong,
                radius = r * (1.35f + p * 1.4f),
                alpha = 0.30f * (1f - p),
            )
        }
        drawCircle(color = wash.strong, radius = r)
    }
}

/**
 * The Nova pill: state = icon + words, never colour alone. Wash fill keyed off the tone.
 */
@Composable
internal fun NovaPill(
    text: String,
    tone: NovaTone,
    modifier: Modifier = Modifier,
    icon: FlashIconSpec? = tone.icon(),
    withIcon: Boolean = true,
) {
    val wash = tone.wash()
    Row(
        modifier = modifier
            .clip(NovaPillShape)
            .background(wash.fill)
            .border(FlashDimensions.borderHairline, wash.edge, NovaPillShape)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        if (withIcon && icon != null) {
            FlashIcon(icon, size = 13.dp, tint = wash.strong)
        }
        FlashText(
            text = text,
            style = FlashTheme.typography.metadataEmphasis,
            color = wash.text,
            maxLines = 1,
        )
    }
}

/** The strip shown on every page under the header: full-width, live-region polite state summary. */
@Composable
internal fun NovaStatusStrip(
    strip: NovaStrip,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
) {
    val wash = strip.tone.wash()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(NovaTileShape)
            .background(wash.fill)
            .border(FlashDimensions.borderHairline, wash.edge, NovaTileShape)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashIcon(strip.tone.icon(), size = 15.dp, tint = wash.strong)
        FlashText(
            text = strip.text,
            style = FlashTheme.typography.captionDefault,
            color = FlashTheme.colors.textPrimary,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        strip.actionLabel?.let { label ->
            FlashText(
                text = label,
                style = FlashTheme.typography.captionEmphasis,
                color = wash.strong,
                modifier = Modifier
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .novaClick(onAction)
                    .padding(horizontal = FlashSpacing.space4),
            )
        }
    }
}

// --- Identity tiles ------------------------------------------------------------------------------------

/**
 * Initials tile for people and departments, coloured from the same seeded avatar palette the chat
 * list uses, so a directory row reads as native next to a conversation.
 */
@Composable
internal fun NovaMonogramTile(
    initials: String,
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
) {
    val typography = FlashTheme.typography
    val pair = flashAvatarColorsFor(seed)
    Box(
        modifier = modifier.size(size).clip(shape).background(pair.first),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = initials.take(2).uppercase(),
            style = typography.metadataEmphasis,
            color = pair.second,
        )
    }
}

/**
 * A wash-filled tile carrying a Flash icon: the standard "what kind of thing is this" badge on
 * cards, sessions, chain rows and stats.
 */
@Composable
internal fun NovaIconTile(
    icon: FlashIconSpec,
    tone: NovaTone,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = NovaTileShape,
    iconSize: Dp = 18.dp,
) {
    val wash = tone.wash()
    Box(
        modifier = modifier.size(size).clip(shape).background(wash.fill),
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(icon, size = iconSize, tint = wash.strong)
    }
}

/** Short transport/section tag tile (ws, rest, p2p) in the same visual family. */
@Composable
internal fun NovaKindTile(text: String, tone: NovaTone, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val wash = tone.wash()
    Box(
        modifier = modifier.size(size).clip(NovaTileShape).background(wash.fill),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = text,
            style = FlashTheme.typography.metadataEmphasis,
            color = wash.strong,
            maxLines = 1,
        )
    }
}

// --- Buttons & chips -----------------------------------------------------------------------------------

/**
 * Tinted pill action (banner actions, empty states). Not the page-primary CTA — [NovaPrimaryButton].
 */
@Composable
internal fun NovaActionChip(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tone: NovaTone = NovaTone.INFO,
    enabled: Boolean = true,
) {
    val wash = tone.wash()
    Row(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(NovaPillShape)
            .background(wash.fill)
            .border(FlashDimensions.borderHairline, wash.edge, NovaPillShape)
            .novaClick(onClick, enabled)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.bodyEmphasis,
            color = wash.strong,
        )
    }
}

/** The page-primary CTA: Flash accent fill with on-accent text, 48 dp min target. */
@Composable
internal fun NovaPrimaryButton(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(NovaPillShape)
            .background(colors.accentPrimary)
            .novaClick(onClick, enabled)
            .padding(horizontal = FlashSpacing.space20, vertical = FlashSpacing.space12)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.bodyEmphasis,
            color = colors.textOnAccent,
        )
    }
}

/**
 * A 48 dp square icon button (dismiss rows, close sheet). [label] names it for accessibility —
 * the FlashIcon wrapper falls back to the icon spec's own description, so the label is passed
 * explicitly here to keep the call-site's wording.
 */
@Composable
internal fun NovaIconButton(
    icon: FlashIconSpec,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tint: Color? = null,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .size(FlashDimensions.minTouchTarget)
            .clip(NovaPillShape)
            .novaClick(onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(icon, size = 20.dp, tint = tint ?: colors.textSecondary, contentDescription = null)
    }
}

/** Horizontally scrollable selection chip for page filters (All / Company / Department …). */
@Composable
internal fun NovaFilterChip(
    label: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    count: Int? = null,
    tone: NovaTone = NovaTone.INFO,
) {
    val colors = FlashTheme.colors
    val wash = tone.wash()
    Row(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(NovaPillShape)
            .background(if (selected) wash.fill else colors.backgroundSurfaceSubtle)
            .border(
                FlashDimensions.borderHairline,
                if (selected) wash.edge else colors.borderDefault,
                NovaPillShape,
            )
            .novaClick(onClick)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.captionEmphasis,
            color = if (selected) wash.strong else colors.textSecondary,
        )
        count?.let {
            FlashText(
                text = it.toString(),
                style = FlashTheme.typography.numericDefault,
                color = if (selected) wash.strong else colors.textTertiary,
            )
        }
    }
}

/**
 * Fingerprint / masked-token display row with copy. The demo pastes exactly what is shown — a
 * masked token copies the masked string, never a secret, mirroring Flash's "never key material"
 * display rule.
 */
@Composable
internal fun NovaCopyRow(
    value: String,
    modifier: Modifier = Modifier,
    icon: FlashIconSpec? = null,
    copyLabel: String = "Copy",
) {
    val colors = FlashTheme.colors
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(NovaTileShape)
            .background(colors.backgroundSurfaceSubtle)
            .novaClick(onClick = { clipboard.setText(AnnotatedString(value)) })
            .heightIn(min = FlashDimensions.minTouchTarget)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        icon?.let { FlashIcon(it, size = 16.dp, tint = colors.textTertiary) }
        FlashText(
            text = value,
            style = FlashTheme.typography.numericEmphasis,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FlashText(
            text = copyLabel,
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.textLink,
        )
    }
}

// --- Progress & meters ---------------------------------------------------------------------------------

/**
 * Linear meter for leases, sync and session progress. [flowing] adds a slow highlight sweep when
 * the budget allows it (in-flight meters only); reduce-motion and low tiers draw the static bar.
 */
@Composable
internal fun NovaMeter(
    progress: Float,
    modifier: Modifier = Modifier,
    tone: NovaTone = NovaTone.INFO,
    height: Dp = 6.dp,
    flowing: Boolean = false,
) {
    val wash = tone.wash()
    val colors = FlashTheme.colors
    val decor = novaDecorAllowed()
    val sweep = rememberNovaSweep()
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val w = this.size.width
        val h = this.size.height
        val r = CornerRadius(h / 2f)
        drawRoundRect(color = colors.backgroundSurfaceStrong, cornerRadius = r)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0.001f) {
            drawRoundRect(
                color = wash.strong,
                topLeft = Offset.Zero,
                size = Size(w * p.coerceAtLeast(0.02f), h),
                cornerRadius = r,
            )
            if (decor && flowing) {
                val x = ((sweep.value * 1.7f) % 1f) * (w * p)
                drawRoundRect(
                    color = wash.soft,
                    topLeft = Offset(x, 0f),
                    size = Size((w * p * 0.22f).coerceAtLeast(12f), h),
                    cornerRadius = r,
                )
            }
        }
    }
}

/**
 * Skeleton placeholder line for data the world has not collected yet (enrolling, syncing).
 * Under reduce-motion the sweep band freezes mid-line: still rendered, just not animated.
 */
@Composable
internal fun NovaSkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 12.dp,
) {
    val colors = FlashTheme.colors
    val shimmer = rememberNovaShimmer()
    Canvas(modifier = modifier.fillMaxWidth(widthFraction).height(height).clip(NovaHairShape)) {
        drawRect(color = colors.borderSubtle)
        val band = size.width * 0.38f
        val x = size.width * shimmer.value.coerceIn(-0.35f, 1.35f) - band / 2f
        drawRect(
            color = colors.backgroundSurfaceStrong,
            topLeft = Offset(x, 0f),
            size = Size(band, this.size.height),
        )
    }
}

// --- Data graphics -------------------------------------------------------------------------------------

/**
 * Telemetry spark history: one stroked polyline with a soft wash fill beneath when the budget
 * allows. The stroke itself is a single static draw — it never animates (a "live" line that draws
 * during a low-UI pass is exactly what ultraLowUi exists to prevent).
 */
@Composable
internal fun NovaSpark(
    values: List<Float>,
    tone: NovaTone,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
) {
    val wash = tone.wash()
    val decor = novaDecorAllowed()
    Canvas(modifier = modifier) {
        if (values.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        val inset = 3f
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1).toFloat()
            val y = size.height - inset - v.coerceIn(0f, 1f) * (size.height - inset * 2f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (decor) {
            val fill = Path()
            fill.addPath(path)
            fill.lineTo(size.width, size.height)
            fill.lineTo(0f, size.height)
            fill.close()
            drawPath(fill, wash.soft, style = Fill)
        }
        drawPath(
            path,
            wash.strong,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// --- States --------------------------------------------------------------------------------------------

/**
 * Empty / offline / not-enrolled panel: wash icon tile, headline and one plain sentence, optional
 * action. Houses the "what you can still do" honesty line at its call sites.
 */
@Composable
internal fun NovaEmptyPanel(
    icon: FlashIconSpec,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tone: NovaTone = NovaTone.NEUTRAL,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space24, horizontal = FlashSpacing.space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        NovaIconTile(icon = icon, tone = tone, size = 56.dp, iconSize = 24.dp, shape = CircleShape)
        FlashText(
            text = title,
            style = FlashTheme.typography.headingSmall,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        FlashText(
            text = body,
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(FlashSpacing.space4))
            NovaActionChip(label = actionLabel, onClick = onAction, tone = tone)
        }
    }
}

/** A whole-second tick for live "connected/opened for…" lines. Stops with the composable. */
@Composable
internal fun rememberNovaTick(periodMillis: Long = 1_000L): Int =
    produceState(initialValue = 0, periodMillis) {
        while (true) {
            delay(periodMillis)
            value += 1
        }
    }.value
