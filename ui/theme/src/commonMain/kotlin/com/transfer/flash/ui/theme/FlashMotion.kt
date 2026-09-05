package com.transfer.flash.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset

/**
 * Flash motion design tokens (UI-037).
 *
 * All chat animations should reference [FlashTheme.motion] — no ad-hoc durations in components.
 */
class FlashMotion internal constructor(
    val reduceMotion: Boolean,
) {
    val fastMillis: Int
        get() = if (reduceMotion) 0 else FastMillis

    val normalMillis: Int
        get() = if (reduceMotion) 0 else NormalMillis

    val slowMillis: Int
        get() = if (reduceMotion) 0 else SlowMillis

    val emphasisMillis: Int
        get() = if (reduceMotion) 0 else EmphasisMillis

    /** Header status, metadata, small state swaps. */
    fun statusCrossfade(): ContentTransform {
        if (reduceMotion) {
            return EnterTransition.None togetherWith ExitTransition.None
        }
        return fadeIn(tween(fastMillis, easing = Decelerate)) togetherWith
            fadeOut(tween(fastMillis, easing = Accelerate))
    }

    /** New message tail insert (UI-005 / UI-006). */
    fun messageEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(normalMillis, easing = Decelerate)) +
            slideInVertically(tween(normalMillis, easing = Decelerate)) { fullHeight -> fullHeight / 4 } +
            scaleIn(
                initialScale = 0.96f,
                animationSpec = spring(
                    dampingRatio = SpringSnappyDamping,
                    stiffness = SpringSnappyStiffness,
                ),
            )
    }

    /** Message remove / send failure rollback. */
    fun messageExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(fastMillis, easing = Accelerate))
    }

    /** UI-006: sibling glide while messages are inserted/removed; snap under reduce-motion. */
    fun messagePlacementSpec(): FiniteAnimationSpec<IntOffset> =
        if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = SpringDefaultDamping,
                stiffness = SpringDefaultStiffness,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            )
        }

    /** UI-006: list-item removal fade; zero-duration under reduce-motion. */
    fun messageFadeOutSpec(): FiniteAnimationSpec<Float> =
        tween(fastMillis, easing = Accelerate)

    /**
     * UI-006: one-shot 0→1 progress driving the Flash message entrance (alpha, quarter-height
     * rise, 4% scale) for a freshly appended tail message — the [messageEnter] channels at its
     * exact duration/easing. Historical messages and reduce-motion report 1f immediately.
     * The start value is fixed at item birth; later `animate` changes must not reset it.
     */
    @Composable
    fun rememberMessageEnterProgress(animate: Boolean): State<Float> {
        val progress = remember { Animatable(if (animate && !reduceMotion) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (progress.value != 1f) {
                progress.animateTo(1f, tween(normalMillis, easing = Decelerate))
            }
        }
        return progress.asState()
    }

    /**
     * Conversation ↔ list navigation (UI-033). Alias of the forward-push pair, kept for
     * callers that do not care about direction.
     */
    fun screenEnter(): EnterTransition = screenPushEnter()

    fun screenExit(): ExitTransition = screenPushExit()

    /** Push (a screen opening on top): incoming rides in from the trailing edge. */
    fun screenPushEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(slowMillis, easing = Decelerate)) +
            slideInHorizontally(tween(slowMillis, easing = Standard)) { w -> (w * ScreenSlide).toInt() }
    }

    fun screenPushExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(slowMillis, easing = Accelerate)) +
            slideOutHorizontally(tween(slowMillis, easing = Accelerate)) { w -> (-w * ScreenSlide).toInt() }
    }

    /** Pop (a screen closing): the mirror image of [screenPushEnter] / [screenPushExit]. */
    fun screenPopEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(slowMillis, easing = Decelerate)) +
            slideInHorizontally(tween(slowMillis, easing = Standard)) { w -> (-w * ScreenSlide).toInt() }
    }

    fun screenPopExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(slowMillis, easing = Accelerate)) +
            slideOutHorizontally(tween(slowMillis, easing = Accelerate)) { w -> (w * ScreenSlide).toInt() }
    }

    /**
     * UI-046 lateral tab hop: a short slide (no page-push depth) so switching tabs reads like a
     * pager rather than opening a new screen. [towardEnd] follows the sign of the tab-index delta.
     */
    fun tabEnter(towardEnd: Boolean): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        val sign = if (towardEnd) 1f else -1f
        return fadeIn(tween(normalMillis, easing = Decelerate)) +
            slideInHorizontally(tween(normalMillis, easing = Standard)) { w -> (w * TabSlide * sign).toInt() }
    }

    fun tabExit(towardEnd: Boolean): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        val sign = if (towardEnd) -1f else 1f
        return fadeOut(tween(fastMillis, easing = Accelerate)) +
            slideOutHorizontally(tween(normalMillis, easing = Accelerate)) { w -> (w * TabSlide * sign).toInt() }
    }

    /** Full-window overlay (Dev Console) rising from the bottom edge. */
    fun sheetEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(fastMillis, easing = Decelerate)) +
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = SpringDefaultDamping,
                    stiffness = SpringDefaultStiffness,
                    visibilityThreshold = IntOffset.VisibilityThreshold,
                ),
                initialOffsetY = { fullHeight -> fullHeight },
            )
    }

    fun sheetExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(fastMillis, easing = Accelerate)) +
            slideOutVertically(tween(normalMillis, easing = Accelerate)) { fullHeight -> fullHeight }
    }


    /**
     * UI-046: hanging shell bar (bottom nav) entering/leaving as tab roots and pushed
     * screens swap. Drops out downward so it reads as chrome sliding off the page.
     */
    fun shellBarEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(normalMillis, easing = Decelerate)) +
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = SpringSnappyDamping,
                    stiffness = SpringSnappyStiffness,
                ),
                initialOffsetY = { fullHeight -> fullHeight },
            )
    }

    fun shellBarExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(fastMillis, easing = Accelerate)) +
            slideOutVertically(tween(normalMillis, easing = Accelerate)) { fullHeight -> fullHeight }
    }

    /**
     * UI-046: unread badge on a bottom-nav tab. Pops from the icon corner instead of blinking
     * into existence, so a count arriving while you look elsewhere still registers.
     */
    fun badgePopEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(fastMillis, easing = Decelerate)) +
            scaleIn(
                initialScale = BadgePopStartScale,
                animationSpec = spring(
                    dampingRatio = SpringSnappyDamping,
                    stiffness = SpringSnappyStiffness,
                ),
            )
    }

    fun badgePopExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(fastMillis, easing = Accelerate)) +
            scaleOut(
                targetScale = BadgePopStartScale,
                animationSpec = tween(fastMillis, easing = Accelerate),
            )
    }

    /** Multi-line composer height growth (UI-011). */
    fun composerExpandEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(normalMillis, easing = Decelerate)) +
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = SpringGentleDamping,
                    stiffness = SpringGentleStiffness,
                ),
                initialOffsetY = { fullHeight -> fullHeight / 8 },
            )
    }

    /** Reply quote strip reveal (UI-010). */
    fun replyExpandEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(normalMillis, easing = Decelerate)) +
            slideInVertically(tween(normalMillis, easing = Standard)) { fullHeight -> fullHeight / 6 }
    }

    /** Full-screen media open (UI-018). */
    fun mediaOpenEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(slowMillis, easing = Decelerate)) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(slowMillis, easing = Decelerate),
            )
    }

    fun mediaOpenExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(fastMillis, easing = Accelerate)) +
            scaleOut(
                targetScale = 0.96f,
                animationSpec = tween(fastMillis, easing = Accelerate),
            )
    }

    /** Micro tap feedback scale spec (UI-041 send / reaction). */
    fun <T> springSnappySpec(): SpringSpec<T> = spring(
        dampingRatio = SpringSnappyDamping,
        stiffness = SpringSnappyStiffness,
    )

    fun <T> springDefaultSpec(): SpringSpec<T> = spring(
        dampingRatio = SpringDefaultDamping,
        stiffness = SpringDefaultStiffness,
    )

    fun <T> springGentleSpec(): SpringSpec<T> = spring(
        dampingRatio = SpringGentleDamping,
        stiffness = SpringGentleStiffness,
    )

    fun <T> tweenFastSpec(): androidx.compose.animation.core.TweenSpec<T> =
        tween(fastMillis, easing = Standard)

    fun <T> tweenNormalSpec(): androidx.compose.animation.core.TweenSpec<T> =
        tween(normalMillis, easing = Standard)

    /**
     * 0 → 1 entrance progress for the [index]-th item of a page that just came on screen, used
     * as alpha + a small rise inside `graphicsLayer { }` so the stagger costs a render pass and
     * not a recomposition. The delay is capped at [MaxStaggerSteps] steps so long lists do not
     * cascade for seconds; reduce-motion reports 1f immediately.
     *
     * [key] restarts the stagger — pass the page/state identity, not the item, so re-entering a
     * tab replays it while a scroll does not.
     */
    @Composable
    fun rememberStaggerProgress(index: Int, key: Any): State<Float> {
        val progress = remember(key) { Animatable(if (reduceMotion) 1f else 0f) }
        LaunchedEffect(key) {
            if (progress.value == 1f) return@LaunchedEffect
            val step = index.coerceIn(0, MaxStaggerSteps)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = normalMillis,
                    delayMillis = if (reduceMotion) 0 else step * StaggerStepMillis,
                    easing = Decelerate,
                ),
            )
        }
        return progress.asState()
    }

    companion object {
        const val FastMillis = 120
        const val NormalMillis = 200
        const val SlowMillis = 320
        const val EmphasisMillis = 400

        /** Per-item delay of [rememberStaggerProgress], and how many items still get one. */
        const val StaggerStepMillis = 24
        const val MaxStaggerSteps = 6

        /** Screen-push travel as a fraction of window width; tab hops are shorter. */
        const val ScreenSlide = 0.3f
        const val TabSlide = 0.1f

        /** Scale a nav badge pops out of (and collapses back into). */
        const val BadgePopStartScale = 0.5f

        const val SpringSnappyDamping = 0.85f
        const val SpringSnappyStiffness = 600f
        const val SpringDefaultDamping = 0.90f
        const val SpringDefaultStiffness = 400f
        const val SpringGentleDamping = 1.00f
        const val SpringGentleStiffness = 280f

        val Decelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
        val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
        val Accelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    }
}

/**
 * Whether the platform is asking UI to stop moving.
 *
 * Android reads `ANIMATOR_DURATION_SCALE` plus the SDK 33 `AccessibilityManager` flag — see
 * `FlashMotion.Companion.isReduceMotionEnabled` in `FlashMotion.android.kt`, which is the
 * pre-KMP body verbatim. Desktop has no equivalent query and reports `false`.
 *
 * `@Composable` because the Android side needs `LocalContext`; the pre-KMP
 * [rememberFlashMotion] read it directly and cached the result with `remember(context)`, and the
 * `actual` keeps that cache so the query still runs once per context rather than per recomposition.
 */
@Composable
internal expect fun isReduceMotionOnPlatform(): Boolean

@Composable
fun rememberFlashMotion(): FlashMotion {
    val reduceMotion = isReduceMotionOnPlatform()
    return remember(reduceMotion) { FlashMotion(reduceMotion = reduceMotion) }
}
