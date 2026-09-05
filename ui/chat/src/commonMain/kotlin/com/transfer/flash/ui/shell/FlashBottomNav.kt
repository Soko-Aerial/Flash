package com.transfer.flash.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashElevation
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashMotion
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.FlashTypography
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * UI-046 custom animated bottom navigation (docs/ui/bottom-nav.md).
 *
 * v2 "hanging bar": a detached capsule that floats above content (margins on all sides,
 * soft shadow, hairline ring) instead of a docked edge-to-edge bar. Tab changes slide a
 * Pulse-tinted chip between tabs with a squash-and-stretch travel deformation, the arriving
 * icon pops, and label weight/tint crossfade. Re-selecting the active tab fires a pulse ring.
 *
 * No Material `NavigationBar` and no new dependencies; every animation is driven through
 * [FlashTheme.motion] so reduce-motion collapses the whole bar to instant snaps.
 */
data class FlashBottomNavItem(
    val destination: FlashDestination,
    val icon: FlashIconSpec,
    val label: String,
    val badgeCount: Int? = null,
)

/** Geometry hosts need to reserve space for the hanging bar. */
object FlashBottomNavDefaults {

    /** Height of the floating capsule itself. */
    val barHeight: Dp = 60.dp

    /** Gap between the capsule and the screen edges. */
    val horizontalMargin: Dp = FlashSpacing.space16
    val bottomMargin: Dp = FlashSpacing.space12
    val topMargin: Dp = FlashSpacing.space8

    /**
     * Vertical space a host must keep free below its content so the hanging bar never covers
     * it. Excludes the system navigation-bar inset, which the bar adds itself.
     */
    val contentInset: Dp = barHeight + bottomMargin + topMargin
}

/** Pure helpers backing the bottom-nav visuals so they stay JVM-testable. */
object FlashBottomNavMath {

    /** Peak horizontal growth of the travelling chip, as a fraction of its resting width. */
    const val MAX_STRETCH_BOOST = 0.30f

    /** Left edge (px) of the indicator chip under tab [index], clamped inside bar bounds. */
    fun indicatorStartPx(
        barWidthPx: Float,
        itemCount: Int,
        index: Int,
        indicatorWidthPx: Float,
    ): Float {
        require(index in 0 until itemCount) { "index out of tab range" }
        return indicatorStartPxAt(barWidthPx, itemCount, index.toFloat(), indicatorWidthPx)
    }

    /**
     * Same as [indicatorStartPx] for a fractional tab [position] — the animated in-between
     * state while the chip slides from one tab to the next.
     */
    fun indicatorStartPxAt(
        barWidthPx: Float,
        itemCount: Int,
        position: Float,
        indicatorWidthPx: Float,
    ): Float {
        require(itemCount > 0) { "itemCount must be positive" }
        val tabWidth = barWidthPx / itemCount
        val maxStart = (barWidthPx - indicatorWidthPx).coerceAtLeast(0f)
        val clamped = position.coerceIn(0f, (itemCount - 1).toFloat())
        return (clamped * tabWidth + (tabWidth - indicatorWidthPx) / 2f).coerceIn(0f, maxStart)
    }

    /**
     * Horizontal scale of the chip for a 0..1 [travel] pulse (0 at rest, 1 mid-flight):
     * the chip stretches along its direction of motion like a liquid drop.
     */
    fun indicatorStretch(travel: Float): Float =
        1f + travel.coerceIn(0f, 1f) * MAX_STRETCH_BOOST

    /**
     * Vertical scale pairing with [indicatorStretch]. Square-root (not full 1/x) area
     * conservation so the chip thins believably without collapsing into a line.
     */
    fun indicatorSquash(stretch: Float): Float = 1f / sqrt(stretch.coerceAtLeast(0.01f))

    /** Travel amplitude for a jump of [tabDistance] tabs: neighbours ease, long hops snap harder. */
    fun travelAmplitude(tabDistance: Int): Float =
        (abs(tabDistance).toFloat() / 3f).coerceIn(0.45f, 1f)

    fun formatBadgeCount(count: Int): String = if (count > 9) "9+" else count.toString()
}

private val ChipHeight = 44.dp
private val ChipGap = FlashSpacing.space8
private val CapsuleInnerPadding = FlashSpacing.space4
private val RingCanvasSize = 56.dp
private val BadgeMinPadding = FlashSpacing.space4
private const val IndicatorAlpha = 0.16f
private const val IconPopStartScale = 0.82f
private const val LabelWeightSelected = 600
private const val LabelWeightIdle = 400

@Composable
fun FlashBottomNav(
    items: List<FlashBottomNavItem>,
    selectedTab: FlashDestination,
    onTabSelected: (FlashDestination) -> Unit,
    modifier: Modifier = Modifier,
    onTabReselected: (FlashDestination) -> Unit = {},
) {
    if (items.isEmpty()) return
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val selectedIndex = items.indexOfFirst { it.destination == selectedTab }.coerceAtLeast(0)

    val slideSpec: FiniteAnimationSpec<Float> =
        if (motion.reduceMotion) snap() else motion.springSnappySpec()
    val position = animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = slideSpec,
        label = "flashNavIndicatorPosition",
    )
    val travel = rememberTravelPulse(selectedIndex, motion)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = FlashBottomNavDefaults.horizontalMargin,
                end = FlashBottomNavDefaults.horizontalMargin,
                top = FlashBottomNavDefaults.topMargin,
                bottom = FlashBottomNavDefaults.bottomMargin,
            ),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(FlashBottomNavDefaults.barHeight)
                .shadow(FlashElevation.floating, FlashShapes.navBar, clip = false)
                .clip(FlashShapes.navBar)
                .background(colors.backgroundSurface)
                .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.navBar)
                .padding(horizontal = CapsuleInnerPadding)
                .selectableGroup(),
        ) {
            val innerWidth = maxWidth
            val tabWidth = innerWidth / items.size
            val chipWidth = (tabWidth - ChipGap).coerceAtLeast(0.dp)

            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(width = chipWidth, height = ChipHeight)
                    .graphicsLayer {
                        val start = FlashBottomNavMath.indicatorStartPxAt(
                            barWidthPx = innerWidth.toPx(),
                            itemCount = items.size,
                            position = position.value,
                            indicatorWidthPx = chipWidth.toPx(),
                        )
                        translationX = if (isRtl) -start else start
                        val stretch = FlashBottomNavMath.indicatorStretch(travel.value)
                        scaleX = stretch
                        scaleY = FlashBottomNavMath.indicatorSquash(stretch)
                    }
                    .clip(FlashShapes.navBar)
                    .background(colors.accentPrimary.copy(alpha = IndicatorAlpha)),
            )

            Row(Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, item ->
                    key(item.destination) {
                        FlashBottomNavItemCell(
                            item = item,
                            isSelected = index == selectedIndex,
                            onClick = {
                                haptics(FlashHaptic.Tick)
                                onTabSelected(item.destination)
                            },
                            onReselect = {
                                // Reselect is a real gesture (scroll-to-top), so it earns the
                                // same tick the tab switch gets.
                                haptics(FlashHaptic.Tick)
                                onTabReselected(item.destination)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 0 → amplitude → 0 pulse fired on every tab change, driving the chip's squash-and-stretch.
 * Exposed as an [Animatable] state so the deformation can be read inside `graphicsLayer`
 * (render pipeline) instead of recomposing the whole bar every frame.
 */
@Composable
private fun rememberTravelPulse(selectedIndex: Int, motion: FlashMotion): State<Float> {
    val travel = remember { Animatable(0f) }
    var previousIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        val distance = selectedIndex - previousIndex
        previousIndex = selectedIndex
        if (distance == 0 || motion.reduceMotion) {
            travel.snapTo(0f)
            return@LaunchedEffect
        }
        travel.snapTo(0f)
        travel.animateTo(
            targetValue = FlashBottomNavMath.travelAmplitude(distance),
            animationSpec = tween(motion.fastMillis, easing = FlashMotion.Decelerate),
        )
        travel.animateTo(
            targetValue = 0f,
            animationSpec = tween(motion.normalMillis, easing = FlashMotion.Standard),
        )
    }
    return travel.asState()
}

@Composable
private fun FlashBottomNavItemCell(
    item: FlashBottomNavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onReselect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val typography = FlashTypography.default()

    var reselectTrigger by remember { mutableIntStateOf(0) }
    val ringProgress = remember { Animatable(1f) }
    LaunchedEffect(reselectTrigger) {
        if (reselectTrigger > 0 && !motion.reduceMotion) {
            ringProgress.snapTo(0f)
            ringProgress.animateTo(1f, tween(motion.emphasisMillis, easing = FlashMotion.Decelerate))
        }
    }

    val scale = remember { Animatable(1f) }
    LaunchedEffect(isSelected) {
        when {
            isSelected && !motion.reduceMotion -> {
                scale.snapTo(IconPopStartScale)
                scale.animateTo(1f, motion.springSnappySpec())
            }
            !isSelected -> scale.snapTo(1f)
        }
    }

    val tint by animateColorAsState(
        targetValue = if (isSelected) colors.accentPrimary else colors.textSecondary,
        animationSpec = motion.tweenFastSpec(),
        label = "flashNavItemTint",
    )
    val labelWeight by animateIntAsState(
        targetValue = if (isSelected) LabelWeightSelected else LabelWeightIdle,
        animationSpec = motion.tweenFastSpec(),
        label = "flashNavItemWeight",
    )

    Box(
        modifier = modifier
            .height(FlashBottomNavDefaults.barHeight)
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = {
                    if (isSelected) {
                        reselectTrigger++
                        onReselect()
                    } else {
                        onClick()
                    }
                },
            )
            .semantics { contentDescription = "${item.label} tab" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                if (ringProgress.value < 1f) {
                    PulseRing(progress = ringProgress.value)
                }
                FlashIcon(
                    icon = item.icon,
                    tint = tint,
                    size = FlashDimensions.iconMd,
                    contentDescription = null,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    },
                )
                Badge(
                    count = item.badgeCount,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            FlashText(
                text = item.label,
                style = typography.captionDefault.copy(fontWeight = FontWeight(labelWeight)),
                color = tint,
            )
        }
    }
}

@Composable
private fun PulseRing(progress: Float) {
    val accent = FlashTheme.colors.accentPrimary
    Canvas(Modifier.size(RingCanvasSize)) {
        val maxRadius = size.minDimension / 2f
        drawCircle(
            color = accent.copy(alpha = (1f - progress) * 0.35f),
            radius = lerp(maxRadius * 0.45f, maxRadius, progress),
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = FlashDimensions.borderHairline.toPx() * 2f),
        )
    }
}

@Composable
private fun Badge(count: Int?, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    // Kept mounted so a count arriving (or clearing) plays the pop instead of appearing and
    // vanishing between frames. The last non-null count is retained so the exit animation has
    // something to render while it collapses.
    var lastCount by remember { mutableIntStateOf(0) }
    if (count != null && count > 0) {
        lastCount = count
    }
    AnimatedVisibility(
        visible = count != null && count > 0,
        modifier = modifier,
        enter = motion.badgePopEnter(),
        exit = motion.badgePopExit(),
    ) {
        Box(
            Modifier
                .clip(FlashShapes.navBar)
                .background(colors.textError)
                .padding(horizontal = BadgeMinPadding),
        ) {
            FlashText(
                text = FlashBottomNavMath.formatBadgeCount(lastCount),
                style = FlashTypography.default().numericEmphasis,
                color = colors.mediaViewerChromeText,
            )
        }
    }
}

