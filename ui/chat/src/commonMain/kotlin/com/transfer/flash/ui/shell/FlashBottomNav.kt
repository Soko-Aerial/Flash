package com.transfer.flash.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.theme.FlashColors
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashElevation
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashMotion
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlin.math.sqrt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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

/** Signature click/selection animation style for a bottom-navigation destination. */
enum class FlashNavIconMotionStyle {
    ChatPop,
    TransferNudge,
    HubElevate,
    NearbySonar,
    SettingsGear,
    SubtlePop,
}

/** Render-layer transform targets for a bottom-nav icon, applied strictly inside `graphicsLayer`. */
data class FlashNavIconTransform(
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
)

/**
 * Pure keyframe specification for one destination's click/selection animation.
 *
 * - [startSnap]: optional frame-0 tactile impulse (e.g. subtle press-in); `null` skips snapping.
 * - [peak]: primary directional kick / pulse (`null` runs straight to [settle], e.g. Settings gear turn).
 * - [counter]: optional counter-settle beat before returning to rest.
 * - [settle]: final resting transform (for Settings, holds the cumulative gear angle).
 * - [preserveRotationOnStart]: when `true`, [startSnap] leaves current in-flight rotation intact so
 *   rapid re-clicks on Settings accelerate smoothly toward the next +90° target without jumping.
 */
data class FlashNavIconMotionSpec(
    val style: FlashNavIconMotionStyle,
    val startSnap: FlashNavIconTransform? = null,
    val peak: FlashNavIconTransform? = null,
    val counter: FlashNavIconTransform? = null,
    val settle: FlashNavIconTransform,
    val preserveRotationOnStart: Boolean = false,
)

/** Pure helpers backing the bottom-nav visuals so they stay JVM-testable. */
object FlashBottomNavMath {

    /** Peak horizontal growth of the travelling chip, as a fraction of its resting width. */
    const val MAX_STRETCH_BOOST = 0.30f

    /** Degrees the Settings gear turns per click/selection (8-tooth symmetric gear: 90° = 2 teeth). */
    const val SETTINGS_GEAR_STEP_DEG = 90f

    /** Maps each destination to its tailored signature icon motion style. */
    fun iconMotionStyle(destination: FlashDestination): FlashNavIconMotionStyle = when (destination) {
        FlashDestination.ChatList -> FlashNavIconMotionStyle.ChatPop
        FlashDestination.Transfers -> FlashNavIconMotionStyle.TransferNudge
        FlashDestination.Hub -> FlashNavIconMotionStyle.HubElevate
        FlashDestination.NearbyDevices -> FlashNavIconMotionStyle.NearbySonar
        FlashDestination.Settings -> FlashNavIconMotionStyle.SettingsGear
        FlashDestination.Conversation -> FlashNavIconMotionStyle.SubtlePop
    }

    /** Cumulative gear angle (degrees) after [turnCount] clicks/selections. */
    fun settingsGearAngleDeg(turnCount: Int): Float =
        turnCount.coerceAtLeast(0) * SETTINGS_GEAR_STEP_DEG

    /**
     * Idle resting transform for [destination]. Under [reduceMotion], every destination rests at
     * exact identity (`scale = 1f, rotation = 0f, offset = 0dp`).
     */
    fun iconRestingTransform(
        destination: FlashDestination,
        settingsTurnCount: Int = 0,
        reduceMotion: Boolean = false,
    ): FlashNavIconTransform {
        if (reduceMotion) return FlashNavIconTransform()
        return when (destination) {
            FlashDestination.Settings -> FlashNavIconTransform(
                rotationDeg = settingsGearAngleDeg(settingsTurnCount),
            )
            else -> FlashNavIconTransform()
        }
    }

    /**
     * Pure click/selection keyframe specification for [destination].
     *
     * - [hopDirection]: tab-index delta sign on switch (`-1`, `0`, `+1`); `0` on re-select.
     * - [isRtl]: mirrors lateral offsets and directional tilts.
     * - [settingsTurnCount]: cumulative turn count AFTER incrementing for this trigger.
     * - [reduceMotion]: collapses the animation to resting identity with no impulse or overshoot.
     */
    fun iconMotionSpec(
        destination: FlashDestination,
        hopDirection: Int = 0,
        isRtl: Boolean = false,
        settingsTurnCount: Int = 1,
        reduceMotion: Boolean = false,
    ): FlashNavIconMotionSpec {
        val style = iconMotionStyle(destination)
        if (reduceMotion) {
            return FlashNavIconMotionSpec(
                style = style,
                startSnap = null,
                peak = null,
                counter = null,
                settle = FlashNavIconTransform(),
                preserveRotationOnStart = false,
            )
        }
        val rtlSign = if (isRtl) -1f else 1f
        return when (destination) {
            FlashDestination.Settings -> {
                val targetAngle = settingsGearAngleDeg(settingsTurnCount)
                FlashNavIconMotionSpec(
                    style = style,
                    startSnap = FlashNavIconTransform(scale = 0.88f),
                    peak = null,
                    counter = null,
                    settle = FlashNavIconTransform(
                        scale = 1f,
                        rotationDeg = targetAngle,
                    ),
                    preserveRotationOnStart = true,
                )
            }
            FlashDestination.ChatList -> {
                val tiltDir = when {
                    hopDirection > 0 -> -1f
                    hopDirection < 0 -> 1f
                    else -> -1f
                } * rtlSign
                FlashNavIconMotionSpec(
                    style = style,
                    startSnap = FlashNavIconTransform(
                        scale = 0.86f,
                        rotationDeg = 4f * tiltDir,
                        offsetYDp = 1f,
                    ),
                    peak = FlashNavIconTransform(
                        scale = 1.12f,
                        rotationDeg = -7f * tiltDir,
                        offsetYDp = -2.5f,
                    ),
                    counter = FlashNavIconTransform(
                        scale = 0.97f,
                        rotationDeg = 2.5f * tiltDir,
                        offsetYDp = 0.5f,
                    ),
                    settle = FlashNavIconTransform(),
                )
            }
            FlashDestination.Transfers -> {
                FlashNavIconMotionSpec(
                    style = style,
                    startSnap = FlashNavIconTransform(scale = 0.90f),
                    peak = FlashNavIconTransform(
                        scale = 1.08f,
                        offsetXDp = 2.2f * rtlSign,
                        offsetYDp = -2.5f,
                    ),
                    counter = FlashNavIconTransform(
                        scale = 0.97f,
                        offsetXDp = -1.6f * rtlSign,
                        offsetYDp = 1.5f,
                    ),
                    settle = FlashNavIconTransform(),
                )
            }
            FlashDestination.Hub -> {
                FlashNavIconMotionSpec(
                    style = style,
                    startSnap = FlashNavIconTransform(
                        scale = 0.90f,
                        offsetYDp = 1.5f,
                    ),
                    peak = FlashNavIconTransform(
                        scale = 1.07f,
                        offsetYDp = -3.0f,
                    ),
                    counter = null,
                    settle = FlashNavIconTransform(),
                )
            }
            FlashDestination.NearbyDevices -> {
                FlashNavIconMotionSpec(
                    style = style,
                    startSnap = FlashNavIconTransform(scale = 0.88f),
                    peak = FlashNavIconTransform(
                        scale = 1.10f,
                        rotationDeg = -9f * rtlSign,
                        offsetYDp = -1.0f,
                    ),
                    counter = FlashNavIconTransform(
                        scale = 1.02f,
                        rotationDeg = 5f * rtlSign,
                        offsetYDp = 0f,
                    ),
                    settle = FlashNavIconTransform(),
                )
            }
            FlashDestination.Conversation -> {
                FlashNavIconMotionSpec(
                    style = style,
                    startSnap = FlashNavIconTransform(scale = 0.88f),
                    peak = FlashNavIconTransform(scale = 1.06f),
                    counter = null,
                    settle = FlashNavIconTransform(),
                )
            }
        }
    }

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

    /** Single-line constraints on capsule tab labels so large font scales ellipsize instead of clipping. */
    const val LabelMaxLines = 1
    const val LabelSoftWrap = false

    fun formatBadgeCount(count: Int): String = if (count > 9) "9+" else count.toString()

    /**
     * Background/foreground pair for unread tab badges.
     * Keeps [FlashColors.textError] as the badge fill while choosing a foreground with >= 4.5:1
     * WCAG AA contrast in both light mode (white on error600) and dark mode (dark ink on error400,
     * where white on #F87171 was only 2.77:1).
     */
    fun badgeColors(colors: FlashColors): Pair<Color, Color> {
        val bg = colors.textError
        val candidates = listOf(colors.onAccentFill, Color.White, colors.backgroundApp, Color(0xFF07080A))
        val fg = candidates.firstOrNull { contrastRatio(it, bg) >= 4.5f }
            ?: candidates.maxByOrNull { contrastRatio(it, bg) }
            ?: colors.onAccentFill
        return bg to fg
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }
}

private val ChipHeight = 44.dp
private val ChipGap = FlashSpacing.space8
private val CapsuleInnerPadding = FlashSpacing.space4
private val RingCanvasSize = 56.dp
private val BadgeMinPadding = FlashSpacing.space4
private const val IndicatorAlpha = 0.16f
private const val LabelWeightSelected = 600
private const val LabelWeightIdle = 400

/**
 * The rule behind [rememberAutoNavVisible]: the bar follows how fast the list moves, not how far it has moved.
 * A quick downward flick hides it, a quick upward flick (or reaching the top) brings it back, and a slow reading
 * drift changes nothing, so the bar does not twitch while someone reads.
 */
object FlashNavAutoHideMath {
    /** Downward speed, px per second, at or above which the bar hides. */
    const val HIDE_VELOCITY_PX_PER_S: Float = 600f

    /** Upward speed, px per second, at or above which the bar returns (lower than hiding: showing is the safe side). */
    const val SHOW_VELOCITY_PX_PER_S: Float = 300f

    /**
     * Rough size of one list row in px, used only to turn a change of first visible item into a distance, since the
     * real row heights are not known here. A speed estimate does not need better.
     */
    const val ROW_ESTIMATE_PX: Float = 160f

    /** Signed distance scrolled between two observations (positive = content moved up, i.e. the user scrolled down). */
    fun scrolledPx(previousIndex: Int, previousOffset: Int, index: Int, offset: Int): Float =
        (index - previousIndex) * ROW_ESTIMATE_PX + (offset - previousOffset)

    fun velocityPxPerSec(deltaPx: Float, elapsedMs: Long): Float =
        if (elapsedMs <= 0L) 0f else deltaPx * 1000f / elapsedMs

    /** The new visibility. [atTop] (first row fully in view) always shows the bar. */
    fun visibleAfter(current: Boolean, deltaPx: Float, elapsedMs: Long, atTop: Boolean): Boolean {
        if (atTop) return true
        val v = velocityPxPerSec(deltaPx, elapsedMs)
        return when {
            v >= HIDE_VELOCITY_PX_PER_S -> false
            v <= -SHOW_VELOCITY_PX_PER_S -> true
            else -> current
        }
    }
}

/**
 * Observes a [LazyListState] and derives whether navigation chrome should remain visible.
 * Hides on a fast downward scroll to maximize content reading area on smaller screens, and restores on a fast upward
 * scroll or when the list is back at the top ([FlashNavAutoHideMath]).
 */
@Composable
fun rememberAutoNavVisible(
    listState: LazyListState?,
): State<Boolean> {
    val isVisible = remember { mutableStateOf(true) }
    if (listState == null) {
        // A tab with no list to watch (the Hub scrolls inside its own panes) must never inherit a hidden bar from the tab before it.
        LaunchedEffect(Unit) { isVisible.value = true }
        return isVisible
    }

    LaunchedEffect(listState) {
        isVisible.value = true
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        var previousMark = TimeSource.Monotonic.markNow()
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val elapsedMs = previousMark.elapsedNow().inWholeMilliseconds
            val delta = FlashNavAutoHideMath.scrolledPx(previousIndex, previousOffset, index, offset)
            isVisible.value = FlashNavAutoHideMath.visibleAfter(
                current = isVisible.value,
                deltaPx = delta,
                elapsedMs = elapsedMs,
                atTop = index == 0 && offset <= 0,
            )
            previousIndex = index
            previousOffset = offset
            previousMark = TimeSource.Monotonic.markNow()
        }
    }
    return isVisible
}

@Composable
fun FlashBottomNav(
    items: List<FlashBottomNavItem>,
    selectedTab: FlashDestination,
    onTabSelected: (FlashDestination) -> Unit,
    modifier: Modifier = Modifier,
    onTabReselected: (FlashDestination) -> Unit = {},
    visible: Boolean = true,
) {
    if (items.isEmpty()) return
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    // The bar hangs over the page, and its drop-shadow is what sells that (UI-046). It is also the
    // one piece of chrome in the shell that costs a blur every frame the page scrolls underneath,
    // so a low-tier device gets the hairline border alone (ERROR-033).
    val elevation = if (FlashTheme.minimalChrome) FlashElevation.none else FlashElevation.floating
    val haptics = rememberFlashHaptics()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val selectedIndex = items.indexOfFirst { it.destination == selectedTab }.coerceAtLeast(0)

    val position = animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = motion.springSnappySpec(),
        label = "flashNavIndicatorPosition",
    )
    val travel = rememberTravelPulse(selectedIndex, motion)

    val navVisibleFraction = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (motion.reduceMotion) androidx.compose.animation.core.snap() else motion.springSnappySpec(),
        label = "flashBottomNavVisibility",
    )

    var previousIndex by remember { mutableIntStateOf(selectedIndex) }
    val hopDirection = remember(selectedIndex) {
        val delta = selectedIndex - previousIndex
        previousIndex = selectedIndex
        if (delta > 0) 1 else if (delta < 0) -1 else 0
    }

    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val extraBarHeight = ((fontScale - 1f) * 22f).dp
    val effectiveBarHeight = FlashBottomNavDefaults.barHeight + extraBarHeight
    val effectiveChipHeight = ChipHeight + extraBarHeight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val hideDistance = (effectiveBarHeight + FlashBottomNavDefaults.bottomMargin + 32.dp).toPx()
                translationY = (1f - navVisibleFraction.value) * hideDistance
                alpha = navVisibleFraction.value.coerceIn(0f, 1f)
            }
            .navigationBarsPadding()
            .padding(
                start = FlashBottomNavDefaults.horizontalMargin,
                end = FlashBottomNavDefaults.horizontalMargin,
                top = FlashBottomNavDefaults.topMargin,
                bottom = FlashBottomNavDefaults.bottomMargin,
            ),
    ) {
        Layout(
            content = {
                Box(
                    Modifier
                        .clip(FlashShapes.navBar)
                        .background(colors.accentPrimary.copy(alpha = IndicatorAlpha)),
                )

                Row(Modifier.fillMaxWidth()) {
                    items.forEachIndexed { index, item ->
                        key(item.destination) {
                            FlashBottomNavItemCell(
                                item = item,
                                tabIndex = index,
                                selectedIndex = selectedIndex,
                                isSelected = index == selectedIndex,
                                hopDirection = if (index == selectedIndex) hopDirection else 0,
                                isRtl = isRtl,
                                barHeight = effectiveBarHeight,
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
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(effectiveBarHeight)
                .shadow(elevation, FlashShapes.navBar, clip = false)
                .clip(FlashShapes.navBar)
                .background(colors.backgroundSurface)
                .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.navBar)
                .padding(horizontal = CapsuleInnerPadding)
                .selectableGroup(),
        ) { measurables, constraints ->
            val innerWidth = constraints.maxWidth
            val itemCount = items.size
            val tabWidthPx = if (itemCount > 0) innerWidth.toFloat() / itemCount else 0f
            val chipGapPx = ChipGap.toPx()
            val chipWidthPx = (tabWidthPx - chipGapPx).coerceAtLeast(0f)
            val chipHeightPx = effectiveChipHeight.roundToPx().coerceAtMost(constraints.maxHeight)

            val chipPlaceable = measurables[0].measure(
                Constraints.fixed(
                    width = chipWidthPx.toInt(),
                    height = chipHeightPx,
                )
            )
            val rowPlaceable = measurables[1].measure(constraints)

            layout(innerWidth, constraints.maxHeight) {
                val chipY = (constraints.maxHeight - chipPlaceable.height) / 2
                chipPlaceable.placeRelativeWithLayer(0, chipY) {
                    val start = FlashBottomNavMath.indicatorStartPxAt(
                        barWidthPx = innerWidth.toFloat(),
                        itemCount = itemCount,
                        position = position.value,
                        indicatorWidthPx = chipWidthPx,
                    )
                    translationX = if (isRtl) -start else start
                    val stretch = FlashBottomNavMath.indicatorStretch(travel.value)
                    scaleX = stretch
                    scaleY = FlashBottomNavMath.indicatorSquash(stretch)
                }
                rowPlaceable.placeRelative(0, 0)
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
    tabIndex: Int,
    selectedIndex: Int,
    isSelected: Boolean,
    hopDirection: Int = 0,
    isRtl: Boolean = false,
    barHeight: Dp = FlashBottomNavDefaults.barHeight,
    onClick: () -> Unit,
    onReselect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val typography = FlashTheme.typography

    var reselectTrigger by remember { mutableIntStateOf(0) }
    val ringProgress = remember { Animatable(1f) }
    LaunchedEffect(reselectTrigger) {
        if (reselectTrigger > 0 && !motion.reduceMotion) {
            ringProgress.snapTo(0f)
            ringProgress.animateTo(1f, tween(motion.emphasisMillis, easing = FlashMotion.Decelerate))
        }
    }

    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    val offsetXDp = remember { Animatable(0f) }
    val offsetYDp = remember { Animatable(0f) }

    var settingsTurnCount by remember { mutableIntStateOf(0) }
    var animTrigger by remember { mutableIntStateOf(0) }
    var effectiveHopDirection by remember { mutableIntStateOf(0) }
    var tappedWhileUnselected by remember { mutableStateOf(false) }
    var initializedSelection by remember { mutableStateOf(false) }

    LaunchedEffect(motion.reduceMotion) {
        if (motion.reduceMotion) {
            settingsTurnCount = 0
            tappedWhileUnselected = false
            ringProgress.snapTo(1f)
            scale.snapTo(1f)
            rotation.snapTo(0f)
            offsetXDp.snapTo(0f)
            offsetYDp.snapTo(0f)
        }
    }

    LaunchedEffect(isSelected) {
        if (!initializedSelection) {
            initializedSelection = true
            return@LaunchedEffect
        }
        if (isSelected) {
            if (tappedWhileUnselected) {
                tappedWhileUnselected = false
            } else if (!motion.reduceMotion) {
                effectiveHopDirection = hopDirection
                if (item.destination == FlashDestination.Settings) {
                    settingsTurnCount++
                }
                animTrigger++
            }
        } else {
            tappedWhileUnselected = false
            val rest = FlashBottomNavMath.iconRestingTransform(
                destination = item.destination,
                settingsTurnCount = settingsTurnCount,
                reduceMotion = motion.reduceMotion,
            )
            scale.snapTo(rest.scale)
            rotation.snapTo(rest.rotationDeg)
            offsetXDp.snapTo(rest.offsetXDp)
            offsetYDp.snapTo(rest.offsetYDp)
        }
    }

    LaunchedEffect(animTrigger) {
        if (animTrigger == 0) return@LaunchedEffect
        if (motion.reduceMotion) {
            settingsTurnCount = 0
            tappedWhileUnselected = false
            scale.snapTo(1f)
            rotation.snapTo(0f)
            offsetXDp.snapTo(0f)
            offsetYDp.snapTo(0f)
            return@LaunchedEffect
        }
        val spec = FlashBottomNavMath.iconMotionSpec(
            destination = item.destination,
            hopDirection = effectiveHopDirection,
            isRtl = isRtl,
            settingsTurnCount = settingsTurnCount,
            reduceMotion = motion.reduceMotion,
        )
        spec.startSnap?.let { snap ->
            scale.snapTo(snap.scale)
            if (!spec.preserveRotationOnStart) {
                rotation.snapTo(snap.rotationDeg)
            }
            offsetXDp.snapTo(snap.offsetXDp)
            offsetYDp.snapTo(snap.offsetYDp)
        }
        val peakDurationMs = if (spec.style == FlashNavIconMotionStyle.HubElevate) {
            motion.fastMillis
        } else {
            (motion.fastMillis / 2).coerceAtLeast(1)
        }
        val counterDurationMs = (motion.fastMillis / 2).coerceAtLeast(1)
        val settleSpec = if (spec.style == FlashNavIconMotionStyle.HubElevate) {
            motion.springDefaultSpec<Float>()
        } else {
            motion.springSnappySpec<Float>()
        }
        coroutineScope {
            launch {
                animateIconChannel(
                    scale, spec.peak?.scale, spec.counter?.scale, spec.settle.scale,
                    peakDurationMs, counterDurationMs, settleSpec,
                )
            }
            launch {
                animateIconChannel(
                    rotation, spec.peak?.rotationDeg, spec.counter?.rotationDeg, spec.settle.rotationDeg,
                    peakDurationMs, counterDurationMs, settleSpec,
                )
            }
            launch {
                animateIconChannel(
                    offsetXDp, spec.peak?.offsetXDp, spec.counter?.offsetXDp, spec.settle.offsetXDp,
                    peakDurationMs, counterDurationMs, settleSpec,
                )
            }
            launch {
                animateIconChannel(
                    offsetYDp, spec.peak?.offsetYDp, spec.counter?.offsetYDp, spec.settle.offsetYDp,
                    peakDurationMs, counterDurationMs, settleSpec,
                )
            }
        }
        tappedWhileUnselected = false
    }

    val tintState = animateColorAsState(
        targetValue = if (isSelected) colors.accentPrimary else colors.textSecondary,
        animationSpec = motion.tweenFastSpec(),
        label = "flashNavItemTint",
    )
    val tint by tintState
    val labelColorProducer = remember(tintState) { ColorProducer { tintState.value } }
    val labelWeight by animateIntAsState(
        targetValue = if (isSelected) LabelWeightSelected else LabelWeightIdle,
        animationSpec = motion.tweenFastSpec(),
        label = "flashNavItemWeight",
    )

    Box(
        modifier = modifier
            .height(barHeight)
            .pointerHoverIcon(PointerIcon.Hand)
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = {
                    if (isSelected) {
                        if (!motion.reduceMotion) {
                            reselectTrigger++
                            effectiveHopDirection = 0
                            if (item.destination == FlashDestination.Settings) {
                                settingsTurnCount++
                            }
                            animTrigger++
                        }
                        onReselect()
                    } else {
                        if (!motion.reduceMotion) {
                            tappedWhileUnselected = true
                            effectiveHopDirection = (tabIndex - selectedIndex).coerceIn(-1, 1)
                            if (item.destination == FlashDestination.Settings) {
                                settingsTurnCount++
                            }
                            animTrigger++
                        }
                        onClick()
                    }
                },
            )
            .semantics {
                contentDescription = if (item.badgeCount != null && item.badgeCount > 0) {
                    "${item.label} tab, ${item.badgeCount} unread"
                } else {
                    "${item.label} tab"
                }
            },
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
                    modifier = Modifier
                        .clearAndSetSemantics {}
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            rotationZ = rotation.value
                            translationX = offsetXDp.value.dp.toPx()
                            translationY = offsetYDp.value.dp.toPx()
                        },
                )
                Badge(
                    count = item.badgeCount,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            BasicText(
                text = item.label,
                modifier = Modifier.padding(horizontal = FlashSpacing.space2),
                style = typography.captionDefault.copy(
                    fontWeight = FontWeight(labelWeight),
                    textAlign = TextAlign.Center,
                ),
                maxLines = FlashBottomNavMath.LabelMaxLines,
                softWrap = FlashBottomNavMath.LabelSoftWrap,
                overflow = TextOverflow.Ellipsis,
                color = labelColorProducer,
            )
        }
    }
}

private suspend fun animateIconChannel(
    animatable: Animatable<Float, AnimationVector1D>,
    peakValue: Float?,
    counterValue: Float?,
    settleValue: Float,
    peakDurationMs: Int,
    counterDurationMs: Int,
    settleSpec: FiniteAnimationSpec<Float>,
) {
    if (peakValue != null) {
        animatable.animateTo(peakValue, tween(peakDurationMs, easing = FlashMotion.Decelerate))
    }
    if (counterValue != null) {
        animatable.animateTo(counterValue, tween(counterDurationMs, easing = FlashMotion.Standard))
    }
    if (peakValue != null || counterValue != null || animatable.value != settleValue) {
        animatable.animateTo(settleValue, settleSpec)
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
    val (badgeBg, badgeFg) = FlashBottomNavMath.badgeColors(colors)
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
                .background(badgeBg)
                .padding(horizontal = BadgeMinPadding)
                .clearAndSetSemantics {},
        ) {
            FlashText(
                text = FlashBottomNavMath.formatBadgeCount(lastCount),
                style = FlashTheme.typography.numericEmphasis,
                color = badgeFg,
                maxLines = 1,
            )
        }
    }
}

