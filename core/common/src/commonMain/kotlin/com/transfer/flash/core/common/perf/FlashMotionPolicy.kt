package com.transfer.flash.core.common.perf

/**
 * Resolves the single "render without animation" boolean the UI actually consumes (ERROR-033).
 *
 * Three inputs want a say and they disagree, so the precedence has to be written down somewhere
 * once rather than re-derived at each of the 26 call sites that read `FlashMotion.reduceMotion`:
 *
 * - the **tier**, which knows the device drops frames animating a list;
 * - the user's **motion preference**, which is an accessibility control;
 * - the **platform** accessibility setting (`ANIMATOR_DURATION_SCALE == 0` and friends).
 *
 * ## Why the tier wins
 *
 * [FlashPerformanceMode.reduceMotion] is a floor: a device on [FlashPerformanceMode.LOW] or
 * [FlashPerformanceMode.MEDIUM] does not animate even if the user's motion preference says
 * "allow motion". That looks like the app overriding a user choice, and it would be, except that
 * the tier is *itself* the escape hatch — a user who wants animation on constrained hardware pins
 * the performance mode to [FlashPerformanceMode.HIGH], which is a truthful statement of intent
 * ("spend this device's cores on the UI") rather than a toggle that silently degrades calls too.
 *
 * Making the motion toggle a second, partial escape hatch would give two controls with
 * overlapping authority and no obvious winner, which is how a setting screen becomes a bug
 * report.
 */
public object FlashMotionPolicy {

    /**
     * @param mode the resolved device tier.
     * @param overrideForcesReduce the user's motion preference already reduced to a tri-state:
     *   true = always reduce, false = allow motion, **null = follow the platform**. Kept as a
     *   nullable Boolean rather than a token string so the persisted vocabulary stays in
     *   `:core:persistence` next to the keys it belongs to, and this stays a pure function.
     * @param systemReduceMotion what the platform accessibility setting says.
     */
    public fun resolveReduceMotion(
        mode: FlashPerformanceMode,
        overrideForcesReduce: Boolean?,
        systemReduceMotion: Boolean,
    ): Boolean = mode.reduceMotion || (overrideForcesReduce ?: systemReduceMotion)
}
