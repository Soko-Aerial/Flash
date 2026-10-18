@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashElevation
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale

// =====================================================================================================================
// Surfaces
// =====================================================================================================================

/**
 * The deck's one card surface: layered [FlashColors.backgroundSurface], a hairline border, and — on a toned card only —
 * a soft vertical wash of the tone. The wash is skipped under `FlashTheme.ultraLowUi`, the tier that disables gradient
 * shaders outright, so the card still resolves to a flat surface on the BelFone-class handset.
 */
@Composable
internal fun Modifier.hcCardSurface(
    tone: HcTone = HcTone.NEUTRAL,
    shape: RoundedCornerShape = RoundedCornerShape(HcDims.cardRadius),
    interactive: Boolean = false,
): Modifier {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    val washed = tone != HcTone.NEUTRAL && !FlashTheme.ultraLowUi
    val lifted = interactive && !FlashTheme.minimalChrome
    var m = this
    if (lifted) m = m.shadow(FlashElevation.sheet, shape)
    m = m
        .clip(shape)
        .background(colors.backgroundSurface)
        .border(FlashDimensions.borderHairline, if (tone == HcTone.NEUTRAL) colors.borderSubtle else t.edge, shape)
    if (washed) {
        m = m.background(
            Brush.verticalGradient(listOf(t.soft.copy(alpha = t.soft.alpha * 0.85f), Color.Transparent)),
        )
    }
    return m
}

/** A section card with an optional eyebrow, title, glyph and trailing action. */
@Composable
internal fun HcCard(
    modifier: Modifier = Modifier,
    tone: HcTone = HcTone.NEUTRAL,
    eyebrow: String? = null,
    title: String? = null,
    glyph: HcGlyph? = null,
    action: (@Composable () -> Unit)? = null,
    contentPadding: Dp = FlashSpacing.space16,
    spacedBy: Dp = FlashSpacing.space12,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    Column(
        modifier = modifier.fillMaxWidth().hcCardSurface(tone).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacedBy),
    ) {
        if (eyebrow != null || title != null || action != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                if (glyph != null) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(FlashShapes.radius8)).background(t.soft),
                        contentAlignment = Alignment.Center,
                    ) { HcGlyphIcon(glyph, tint = t.on, size = FlashDimensions.iconSm) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                    if (eyebrow != null) {
                        FlashText(
                            eyebrow.uppercase(),
                            style = FlashTheme.typography.metadataEmphasis,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (title != null) {
                        FlashText(
                            title,
                            modifier = Modifier.semantics { heading() },
                            style = FlashTheme.typography.headingSmall,
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                action?.invoke()
            }
        }
        content()
    }
}

/** Small uppercase section label with a leading rule — used to separate bands inside a scrolling page. */
@Composable
internal fun HcSectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(top = FlashSpacing.space8, bottom = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(
            text.uppercase(),
            modifier = Modifier.semantics { heading() },
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.textSecondary,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(FlashDimensions.borderHairline).background(colors.borderSubtle))
        if (trailing != null) {
            FlashText(trailing, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1)
        }
    }
}

@Composable
internal fun HcDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(FlashDimensions.borderHairline).background(FlashTheme.colors.borderSubtle))
}

// =====================================================================================================================
// Controls
// =====================================================================================================================

/** A pill. Text is always present; [glyph] and colour only reinforce it. */
@Composable
internal fun HcChip(
    label: String,
    tone: HcTone = HcTone.NEUTRAL,
    glyph: HcGlyph? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val t = hcTone(tone)
    Row(
        modifier = modifier
            .clip(FlashShapes.navBar)
            .background(t.soft)
            .border(BorderStroke(FlashDimensions.borderHairline, t.edge), FlashShapes.navBar)
            .padding(
                horizontal = if (compact) FlashSpacing.space8 else FlashSpacing.space12,
                vertical = if (compact) FlashSpacing.space2 else FlashSpacing.space4,
            )
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        if (glyph != null) HcGlyphIcon(glyph, tint = t.on, size = 14.dp)
        FlashText(
            label,
            style = FlashTheme.typography.metadataEmphasis,
            color = t.on,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The deck's live indicator: a dot that breathes while [pulsing], with an optional outward ring.
 *
 * The pulse is the only "always on" animation in the deck, so it is what the reduced-motion toggle most visibly stops —
 * exactly the behaviour `FlashMotionPolicy` asks for.
 */
@Composable
internal fun HcPulseDot(
    tone: HcTone,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    diameter: Dp = 10.dp,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    val reduce = FlashTheme.motion.reduceMotion
    // Unconditional, so the composition structure does not change when `pulsing` flips; the value is simply unused.
    val transition = rememberInfiniteTransition(label = "hcPulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "hcPulsePhase",
    )
    val ring = if (pulsing && !reduce) phase else 0f
    Box(modifier.size(diameter * 2f), contentAlignment = Alignment.Center) {
        if (ring > 0f) {
            Canvas(Modifier.size(diameter * 2f)) {
                val max = size.minDimension / 2f
                val r = max * (0.35f + 0.65f * ring)
                drawCircle(color = t.strong.copy(alpha = (1f - ring) * 0.45f), radius = r)
            }
        }
        Box(
            Modifier.size(diameter).clip(CircleShape).background(t.strong)
                .border(FlashDimensions.borderHairline, colors.backgroundApp.copy(alpha = 0.7f), CircleShape),
        )
    }
}

/** Filled button. Two weights, matching the shipped hub's primary / secondary pair. */
@Composable
internal fun HcButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: HcGlyph? = null,
    primary: Boolean = true,
    tone: HcTone = HcTone.ACCENT,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    val interaction = remember { MutableInteractionSource() }
    val bg = when {
        !enabled -> colors.backgroundSurfaceStrong
        primary -> t.strong
        else -> colors.backgroundSurfaceStrong
    }
    val fg = when {
        !enabled -> colors.textTertiary
        primary -> if (tone == HcTone.ACCENT) colors.textOnAccent else colors.backgroundApp
        else -> colors.textPrimary
    }
    Row(
        modifier = modifier
            .flashPressScale(interaction)
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.button)
            .background(bg)
            .then(if (!primary) Modifier.border(FlashDimensions.borderHairline, colors.borderDefault, FlashShapes.button) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8, Alignment.CenterHorizontally),
    ) {
        Spacer(Modifier.width(FlashSpacing.space12))
        if (glyph != null) HcGlyphIcon(glyph, tint = fg)
        FlashText(label, style = FlashTheme.typography.bodyEmphasis, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(FlashSpacing.space12))
    }
}

/** A borderless text action, for inline "Why?" / "Retry" affordances. */
@Composable
internal fun HcTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: HcTone = HcTone.ACCENT,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    Box(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.button)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FlashSpacing.space8)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        FlashText(label, style = FlashTheme.typography.bodyEmphasis, color = if (enabled) t.on else colors.textTertiary, maxLines = 1)
    }
}

/** A 48dp round glyph button with a required description. */
@Composable
internal fun HcIconAction(
    glyph: HcGlyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: HcTone = HcTone.NEUTRAL,
    enabled: Boolean = true,
) {
    val t = hcTone(tone)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .flashPressScale(interaction)
            .size(FlashDimensions.minTouchTarget)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { HcGlyphIcon(glyph, tint = if (enabled) t.on else FlashTheme.colors.textTertiary) }
}

/** The deck's switch. Whole-row target; this only draws. */
@Composable
internal fun HcSwitch(on: Boolean, onToggle: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val offset by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = motion.springSnappySpec(),
        label = "hcSwitchThumb",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.chip)
            .clickable(role = Role.Switch) { onToggle(!on) }
            .padding(horizontal = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(label, Modifier.weight(1f), style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp)
                .clip(CircleShape)
                .background(if (on) colors.accentPrimary else colors.backgroundSurfaceStrong)
                .border(FlashDimensions.borderHairline, if (on) colors.accentPrimary else colors.borderStrong, CircleShape)
                .semantics { contentDescription = label; selected = on },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .graphicsLayer { translationX = offset * (48.dp.toPx() - 28.dp.toPx()) }
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (on) colors.textOnAccent else colors.textTertiary),
            )
        }
    }
}

// =====================================================================================================================
// Data display
// =====================================================================================================================

/** A telemetry tile: label, a big numeric value, a unit, an optional delta and an optional sparkline. */
@Composable
internal fun HcStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    caption: String? = null,
    tone: HcTone = HcTone.NEUTRAL,
    glyph: HcGlyph? = null,
    series: List<Float>? = null,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    Column(
        modifier = modifier.hcCardSurface(tone, RoundedCornerShape(HcDims.tileRadius)).padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            if (glyph != null) HcGlyphIcon(glyph, tint = t.on, size = 14.dp)
            FlashText(
                label.uppercase(),
                modifier = Modifier.weight(1f),
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            FlashText(
                value,
                style = FlashTheme.typography.headingLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit != null) {
                FlashText(unit, Modifier.padding(bottom = 3.dp), style = FlashTheme.typography.metadataEmphasis, color = colors.textSecondary)
            }
        }
        if (series != null) {
            HcSparkline(values = series, tone = t.strong, modifier = Modifier.fillMaxWidth().height(28.dp))
        }
        if (caption != null) {
            FlashText(caption, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A responsive tile grid built from rows, so it can sit inside the page's single vertical scroll. */
@Composable
internal fun HcTileGrid(
    columns: Int,
    count: Int,
    modifier: Modifier = Modifier,
    spacing: Dp = FlashSpacing.space12,
    content: @Composable (index: Int) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        var i = 0
        while (i < count) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                val end = minOf(i + columns, count)
                for (j in i until end) {
                    Box(Modifier.weight(1f)) { content(j) }
                }
                // Keep the last row's tiles the same width as a full row's.
                for (j in end until i + columns) {
                    Spacer(Modifier.weight(1f))
                }
            }
            i += columns
        }
    }
}

/** Line + soft area sparkline. A flat series draws a mid-height line rather than nothing. */
@Composable
internal fun HcSparkline(
    values: List<Float>,
    tone: Color,
    modifier: Modifier = Modifier,
    fillArea: Boolean = true,
) {
    val normalised = remember(values) { hcNormalise(values) }
    val drawArea = fillArea && !FlashTheme.ultraLowUi
    Canvas(modifier) {
        if (normalised.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val stepX = if (normalised.size == 1) 0f else w / (normalised.size - 1)
        fun y(v: Float) = h * (1f - v.coerceIn(0f, 1f)) * 0.84f + h * 0.08f
        val line = Path()
        normalised.forEachIndexed { i, v ->
            val x = i * stepX
            if (i == 0) line.moveTo(x, y(v)) else line.lineTo(x, y(v))
        }
        if (drawArea) {
            val area = Path().apply {
                addPath(line)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(tone.copy(alpha = 0.26f), tone.copy(alpha = 0f))))
        }
        drawPath(line, tone, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val lastY = y(normalised.last())
        drawCircle(tone, radius = 2.5.dp.toPx(), center = Offset(w, lastY))
    }
}

/** Progress ring with a centre value. Used for lease days, certificate days and roster validity. */
@Composable
internal fun HcRingGauge(
    progress: Float,
    tone: HcTone,
    modifier: Modifier = Modifier,
    diameter: Dp = 88.dp,
    strokeWidth: Dp = 8.dp,
    value: String,
    caption: String? = null,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    val motion = FlashTheme.motion
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (motion.reduceMotion) snap() else tween(motion.emphasisMillis),
        label = "hcRing",
    )
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val full = size.minDimension
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(full - stroke, full - stroke)
            drawArc(
                color = colors.backgroundSurfaceStrong,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = t.strong,
                startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(value, style = FlashTheme.typography.headingMedium, color = colors.textPrimary, maxLines = 1)
            if (caption != null) {
                FlashText(
                    caption.uppercase(),
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A labelled meter. Colour is reinforced by the label and the value, never carrying meaning alone. */
@Composable
internal fun HcMeter(
    label: String,
    fraction: Float,
    valueText: String,
    modifier: Modifier = Modifier,
    tone: HcTone = HcTone.ACCENT,
    glyph: HcGlyph? = null,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    val motion = FlashTheme.motion
    val f by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = if (motion.reduceMotion) snap() else tween(motion.normalMillis),
        label = "hcMeter",
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            if (glyph != null) HcGlyphIcon(glyph, tint = t.on, size = 14.dp)
            FlashText(label, Modifier.weight(1f), style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlashText(valueText, style = FlashTheme.typography.numericEmphasis, color = colors.textPrimary, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(FlashShapes.navBar).background(colors.backgroundSurfaceStrong)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(f)
                    .clip(FlashShapes.navBar)
                    .background(t.strong),
            )
        }
    }
}

/** Label/value row. [mono] renders the value in the monospace face used for fingerprints and serials. */
@Composable
internal fun HcKeyValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    tone: HcTone = HcTone.NEUTRAL,
    glyph: HcGlyph? = null,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        if (glyph != null) HcGlyphIcon(glyph, tint = colors.textTertiary, size = 14.dp)
        FlashText(
            label,
            Modifier.weight(1f),
            style = FlashTheme.typography.metadataDefault,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FlashText(
            value,
            style = if (mono) hcMono(FlashTheme.typography.numericEmphasis) else FlashTheme.typography.captionEmphasis,
            color = if (tone == HcTone.NEUTRAL) colors.textPrimary else t.on,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/** A fingerprint in grouped monospace, so two similar hashes cannot be confused at a glance. */
@Composable
internal fun HcFingerprint(
    raw: String,
    modifier: Modifier = Modifier,
    groups: Int = 4,
    tone: HcTone = HcTone.NEUTRAL,
    glyph: HcGlyph? = HcGlyph.KEY,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    Row(
        modifier = modifier.padding(vertical = FlashSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        if (glyph != null) HcGlyphIcon(glyph, tint = t.on, size = 14.dp)
        FlashText(
            hcFingerprint(raw, groups),
            style = hcMono(FlashTheme.typography.numericEmphasis).copy(letterSpacing = 0.6.sp),
            color = if (tone == HcTone.NEUTRAL) colors.textPrimary else t.on,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Palette avatar with an optional presence dot that appears only when presence is actually known. */
@Composable
internal fun HcAvatar(
    name: String,
    seed: String,
    modifier: Modifier = Modifier,
    diameter: Dp = FlashDimensions.avatarMd,
    online: Boolean? = null,
    ringTone: HcTone? = null,
) {
    val colors = FlashTheme.colors
    val idx = hcPaletteIndex(seed, colors.avatarPaletteBackgrounds.size)
    val bg = colors.avatarPaletteBackgrounds.getOrElse(idx) { colors.avatarPlaceholderBackground }
    val fg = colors.avatarPaletteForegrounds.getOrElse(idx) { colors.avatarPlaceholderText }
    Box(modifier.size(diameter)) {
        Box(
            Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(bg)
                .then(
                    if (ringTone != null) {
                        Modifier.border(2.dp, hcTone(ringTone).strong, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                hcInitials(name),
                style = FlashTheme.typography.captionEmphasis.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                maxLines = 1,
            )
        }
        if (online != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(diameter / 3.4f)
                    .clip(CircleShape)
                    .background(if (online) colors.statusOnline else colors.statusOffline)
                    .border(2.dp, colors.backgroundSurface, CircleShape),
            )
        }
    }
}

// =====================================================================================================================
// Banners, empty states, skeletons
// =====================================================================================================================

/** A system banner. Tone decides the wash and the glyph; the title and body always carry the meaning. */
@Composable
internal fun HcBannerCard(
    banner: HcBanner,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val t = hcTone(banner.tone)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hcCardSurface(banner.tone)
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(FlashShapes.radius8)).background(t.soft),
                contentAlignment = Alignment.Center,
            ) { HcGlyphIcon(banner.glyph, tint = t.on) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(banner.title, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary)
                FlashText(banner.body, style = FlashTheme.typography.captionDefault, color = colors.textSecondary)
            }
        }
        if (banner.action != null || banner.secondaryAction != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                Spacer(Modifier.weight(1f))
                if (banner.secondaryAction != null) {
                    HcTextAction(banner.secondaryAction, onClick = { onSecondaryAction?.invoke() }, tone = HcTone.NEUTRAL)
                }
                if (banner.action != null) {
                    HcTextAction(banner.action, onClick = { onAction?.invoke() })
                }
            }
        }
    }
}

/** A designed empty state. Never blank, always one concrete next step. */
@Composable
internal fun HcEmptyState(
    glyph: HcGlyph,
    headline: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tone: HcTone = HcTone.ACCENT,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space32, vertical = FlashSpacing.space32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(t.soft).border(FlashDimensions.borderHairline, t.edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) { HcGlyphIcon(glyph, tint = t.on, size = FlashDimensions.iconLg) }
        FlashText(
            headline,
            modifier = Modifier.semantics { heading() },
            style = FlashTheme.typography.headingSmall,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            FlashText(body, style = FlashTheme.typography.bodyDefault, color = colors.textSecondary, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            HcButton(actionLabel, onAction, tone = tone)
        }
    }
}

/** Shimmering placeholder rows, hidden from a screen reader except for a single polite "Loading". */
@Composable
internal fun HcSkeleton(modifier: Modifier = Modifier, rows: Int = 6) {
    val colors = FlashTheme.colors
    val reduce = FlashTheme.motion.reduceMotion
    val alpha by if (reduce) {
        remember { androidx.compose.runtime.mutableStateOf(0.5f) }
    } else {
        rememberInfiniteTransition(label = "hcSkeleton").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "hcSkeletonAlpha",
        )
    }
    val tone = colors.backgroundSurfaceStrong.copy(alpha = alpha)
    Column(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Loading"; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        repeat(rows.coerceIn(1, 12)) { index ->
            Row(
                Modifier.fillMaxWidth().height(64.dp).hcCardSurface(shape = RoundedCornerShape(HcDims.tileRadius)).padding(FlashSpacing.space12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(tone))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.5f else 0.38f).height(13.dp).clip(FlashShapes.chip).background(tone))
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.78f else 0.62f).height(11.dp).clip(FlashShapes.chip).background(tone))
                }
            }
        }
    }
}

// =====================================================================================================================
// Composition helpers
// =====================================================================================================================

/** A wrap-capable row of chips. */
@Composable
internal fun HcChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) { content() }
}

/** One step of the enrolment rail. */
@Composable
internal fun HcStepRail(steps: List<HcEnrollmentStep>, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            val last = index == steps.lastIndex
            val tone = when (step.state) {
                HcStepState.DONE -> HcTone.SUCCESS
                HcStepState.ACTIVE -> HcTone.ACCENT
                HcStepState.PENDING -> HcTone.MUTED
                HcStepState.FAILED -> HcTone.DANGER
            }
            val t = hcTone(tone)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(34.dp).fillMaxHeight()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(t.soft)
                            .border(FlashDimensions.borderHairline, t.edge, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (step.state) {
                            HcStepState.DONE -> HcGlyphIcon(HcGlyph.CHECK, tint = t.on, size = 14.dp)
                            HcStepState.FAILED -> HcGlyphIcon(HcGlyph.CLOSE, tint = t.on, size = 14.dp)
                            HcStepState.ACTIVE -> HcPulseDot(tone, pulsing = true, diameter = 10.dp)
                            HcStepState.PENDING -> Box(
                                Modifier.size(8.dp).clip(CircleShape).background(colors.borderStrong),
                            )
                        }
                    }
                    if (!last) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 30.dp)
                                .width(FlashDimensions.borderHairline * 2)
                                .fillMaxHeight()
                                .background(colors.borderSubtle),
                        )
                    }
                }
                Column(
                    Modifier.weight(1f).padding(start = FlashSpacing.space8, bottom = FlashSpacing.space16),
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
                ) {
                    FlashText(step.title, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary)
                    FlashText(step.detail, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
                }
            }
        }
    }
}

/** A rotated chevron, so one glyph serves "next" and "down". */
@Composable
internal fun HcChevron(tint: Color, degrees: Float = -90f, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    HcGlyphIcon(HcGlyph.CHEVRON, tint = tint, modifier = modifier.rotate(degrees), size = size)
}

/** A hairline rule that fades at both ends — the deck's "already read" separator. */
@Composable
internal fun HcSoftRule(modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(FlashDimensions.borderHairline)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, colors.borderDefault, Color.Transparent),
                ),
            ),
    )
}

/** A minimal segmented control used by the Showcase's control bar. */
@Composable
internal fun HcSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Row(
        modifier
            .clip(FlashShapes.navBar)
            .background(colors.backgroundSurfaceStrong)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                Modifier
                    .clip(FlashShapes.navBar)
                    .background(if (isSelected) colors.accentPrimary else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
                    .semantics {
                        selected = isSelected
                        contentDescription = label
                    },
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    label,
                    style = if (isSelected) FlashTheme.typography.metadataEmphasis else FlashTheme.typography.metadataDefault,
                    color = if (isSelected) colors.textOnAccent else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
