package com.transfer.flash.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * UI-039 semantic haptic vocabulary for the whole app.
 *
 * Components must never call [androidx.compose.ui.platform.LocalHapticFeedback] directly;
 * they obtain [rememberFlashHaptics] and express *intent*:
 *
 * | [FlashHaptic] | Intent | Underlying `HapticFeedbackType` |
 * |---|---|---|
 * | [Tick] | Light taps, toggles, selection, steppers | `TextHandleMove` |
 * | [Confirm] | Action confirmed by gesture (long-press menu, swipe threshold, retry) | `LongPress` |
 * | [Warn] | Attention without failure (threshold armed, near-limit states) | `LongPress` |
 * | [Reject] | Destructive / negative outcomes (discard recording, send failure) | `LongPress` |
 *
 * **Honest API limits:** current stable Compose exposes only `TextHandleMove` and
 * `LongPress` on [androidx.compose.ui.hapticfeedback.HapticFeedback]; richer SDK 27+
 * amplitude primitives (`VibrationEffect.Composition`, predefined effects) require the
 * `VIBRATE` permission and a View/`Vibrator` path that Compose's common API does not
 * expose. Warn/Reject therefore intentionally share the LongPress primitive today — they
 * are distinct *semantics* so a future VibrationEffect upgrade is a one-file change here.
 */
enum class FlashHaptic {
    Tick,
    Confirm,
    Warn,
    Reject,
}

/**
 * Pure decision logic for haptics (unit-tested in `FlashFeedbackLogicTest`) —
 * no Compose or Android types so policy changes are testable on the JVM.
 */
object FlashHapticPolicy {

    /**
     * Whether haptics should play at all.
     *
     * Deliberately **independent of [reduceMotion]**: platform reduce-motion settings
     * (Android "Remove animations", WCAG prefers-reduced-motion) target vestibular
     * screen motion, not vibration, and haptics are an important non-visual feedback
     * channel. The parameter is kept so this single function remains the one switch
     * point if product research ever reverses that stance.
     *
     * Note Compose's `LocalHapticFeedback` already honors the system touch-feedback
     * setting (`HAPTIC_FEEDBACK_ENABLED`) via `View.performHapticFeedback`; the explicit
     * [systemHapticsEnabled] flag carries Flash's own Settings → Haptics preference
     * (UI-049) on top of it, and lets policy tests model the gate explicitly.
     */
    fun enabled(reduceMotion: Boolean, systemHapticsEnabled: Boolean = true): Boolean {
        return systemHapticsEnabled
    }
}

/**
 * User-level haptics preference (Settings → Haptics, UI-049). Defaults to true so previews and
 * tests keep feedback on; [FlashTheme] provides the real value from the settings model.
 */
internal val LocalFlashHapticsEnabled = compositionLocalOf { true }

/**
 * UI-039 single choke point for all chat haptics.
 *
 * Returns a stable lambda so call sites read e.g. `haptic(FlashHaptic.Confirm)` without
 * re-resolving composition locals per event. Gated by [FlashHapticPolicy] (reduce-motion
 * aware by contract, currently pass-through — see KDoc) so a future VibrationEffect /
 * amplitude-primitive upgrade touches only this file.
 */
@Composable
fun rememberFlashHaptics(): (FlashHaptic) -> Unit {
    val hapticFeedback = LocalHapticFeedback.current
    val reduceMotion = FlashTheme.motion.reduceMotion
    val userEnabled = LocalFlashHapticsEnabled.current
    return remember(hapticFeedback, reduceMotion, userEnabled) {
        { haptic ->
            if (FlashHapticPolicy.enabled(
                    reduceMotion = reduceMotion,
                    systemHapticsEnabled = userEnabled,
                )
            ) {
                hapticFeedback.performHapticFeedback(
                    when (haptic) {
                        FlashHaptic.Tick -> HapticFeedbackType.TextHandleMove
                        FlashHaptic.Confirm,
                        FlashHaptic.Warn,
                        FlashHaptic.Reject,
                        -> HapticFeedbackType.LongPress
                    },
                )
            }
        }
    }
}
