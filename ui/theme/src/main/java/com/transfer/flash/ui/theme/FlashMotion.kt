package com.transfer.flash.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
            // `sizeTransform = null` is the load-bearing part, not the two `None`s. `togetherWith`
            // hands `ContentTransform` its default `SizeTransform()`, which animates the *container*
            // between the two children's sizes on its own spring — so the nine `AnimatedContent`
            // sites on this spec still played a size animation under reduce-motion even with both
            // fades switched off. Visible wherever the swapped labels differ in width: a chat-list
            // row preview, a transfer's status text, the conversation header. Null makes the
            // container jump, and drops the per-instance size `Transition` with it.
            return ContentTransform(
                targetContentEnter = EnterTransition.None,
                initialContentExit = ExitTransition.None,
                sizeTransform = null,
            )
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

    /**
     * Micro tap feedback scale spec (UI-041 send / reaction).
     *
     * Returns [FiniteAnimationSpec] rather than `SpringSpec` because reduce-motion has to be able to
     * answer `snap()`, and a spring cannot express "no animation" — its duration is emergent, so
     * there is no zero to set. That is the same reason [messagePlacementSpec] is shaped this way.
     *
     * This used to hand back a live spring unconditionally, which is why two call sites had grown
     * their own `if (motion.reduceMotion) snap() else motion.springSnappySpec()` — `FlashBottomNav`'s
     * indicator and `Modifier.flashPressScale`. The other 27 call sites had no such guard, so on a LOW
     * or MEDIUM tier every press, reaction pop, chip and sheet still ran a spring to settle: exactly
     * the animation the tier exists to switch off. Fixed here so a caller cannot forget.
     *
     * At HIGH, `reduceMotion` is false and the returned spring is unchanged from before.
     */
    fun <T> springSnappySpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = SpringSnappyDamping,
                stiffness = SpringSnappyStiffness,
            )
        }

    fun <T> springDefaultSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = SpringDefaultDamping,
                stiffness = SpringDefaultStiffness,
            )
        }

    fun <T> springGentleSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = SpringGentleDamping,
                stiffness = SpringGentleStiffness,
            )
        }

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

        fun isReduceMotionEnabled(context: Context): Boolean {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            if (scale == 0f) {
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val accessibilityManager =
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                if (accessibilityManager != null && accessibilityManager.isReduceMotionEnabledCompat()) {
                    return true
                }
            }
            return false
        }

        private fun AccessibilityManager.isReduceMotionEnabledCompat(): Boolean {
            return runCatching {
                AccessibilityManager::class.java
                    .getMethod("isReduceMotionEnabled")
                    .invoke(this) as Boolean
            }.getOrDefault(false)
        }
    }
}

/**
 * The platform's own answer to "should this device animate?" — a zeroed animator duration scale,
 * or Android 13+'s accessibility reduce-motion toggle.
 *
 * Exposed separately from [rememberFlashMotion] because the app-level verdict is a three-way one
 * (device performance tier, user override, platform), and only the host knows the first two; this
 * supplies the third. Read once per [android.content.Context]: both inputs need a process restart
 * to take effect anyway, and re-reading them per recomposition would put a `Settings.Global` query
 * on the frame path.
 */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { FlashMotion.isReduceMotionEnabled(context) }
}

@Composable
fun rememberFlashMotion(): FlashMotion = rememberFlashMotion(rememberSystemReduceMotion())

/**
 * A [FlashMotion] whose reduce-motion verdict the caller already reached.
 *
 * The way in for hosts that must widen "reduce motion" past what the platform reports: a handset on
 * a low performance tier should not animate however the platform feels about it
 * (`FlashMotionPolicy.resolveReduceMotion` in `:core:common` is where that is decided).
 * [FlashMotion]'s constructor is internal, so this overload is the only entry point from outside
 * this module.
 */
@Composable
fun rememberFlashMotion(reduceMotion: Boolean): FlashMotion =
    remember(reduceMotion) { FlashMotion(reduceMotion = reduceMotion) }

/**
 * The Flash lazy-list item animation: sibling glide on insert/remove, fade on removal.
 *
 * Exists so that no call site has to remember two separate things, the same reason
 * [FlashMotion.springSnappySpec] collapses to `snap()` internally.
 *
 * `Modifier.animateItem` takes *three* specs and each one defaults to a live spring. Every one of
 * the app's lazy lists had grown the form `animateItem(placementSpec = …, fadeOutSpec = …)`, which
 * leaves `fadeInSpec` at that default — so on a LOW or MEDIUM tier each appearing row still faded in
 * on a spring, which is precisely the animation the tier exists to switch off. Zeroing the third
 * spec would not have been enough either: with all three at `snap()` the modifier is still installed,
 * so every visible row keeps an animation node and the list keeps running item-animator bookkeeping
 * for animations that can never play. Under reduce-motion this returns [Modifier] instead and those
 * rows carry no animation node at all.
 *
 * At HIGH this is exactly what the call sites passed before — the two Flash springs, with
 * `fadeInSpec` left on the platform default.
 */
fun LazyItemScope.flashAnimateItem(motion: FlashMotion): Modifier =
    if (motion.reduceMotion) {
        Modifier
    } else {
        Modifier.animateItem(
            placementSpec = motion.messagePlacementSpec(),
            fadeOutSpec = motion.messageFadeOutSpec(),
        )
    }
