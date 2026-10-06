package com.transfer.flash.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * Timing of the Ink launch splash (UI-056, ADR-080), in seconds from its first frame.
 *
 * A port of the "Ink" option the owner picked from the 2026-10-06 demo: the pen writes the name,
 * then draws the bolt above it, fills it and sends out one ripple. The whole intro is about 3.5 s
 * and always plays to [INTRO_END]; after that the splash leaves as soon as the engine is ready.
 */
object FlashInkSplashTimeline {
    const val WRITE_START: Float = 0.3f
    const val WRITE_DURATION: Float = 1.9f
    /** The pen is lifted for this long between two strokes. */
    const val PEN_LIFT: Float = 0.08f
    const val WRITE_END: Float = WRITE_START + WRITE_DURATION
    const val BOLT_START: Float = WRITE_END + 0.2f
    const val BOLT_DURATION: Float = 0.5f
    const val BOLT_END: Float = BOLT_START + BOLT_DURATION
    /** The fill starts a little before the outline closes, so the two overlap. */
    const val FILL_START: Float = BOLT_END - 0.15f
    const val FILL_DURATION: Float = 0.4f
    const val FILL_END: Float = FILL_START + FILL_DURATION
    const val RIPPLE_DURATION: Float = 0.9f
    const val NIB_FADE: Float = 0.35f

    /** The intro is over: the name is written, the bolt is drawn and filled, the ripple is fading. */
    const val INTRO_END: Float = FILL_END + 0.3f

    /** With reduced motion the finished frame is shown, still, for this long. */
    const val REDUCED_MOTION_HOLD: Float = 0.8f

    /** The ink boils: four noise patterns, eight changes a second, like the demo's turbulence seeds. */
    const val WOBBLE_FPS: Float = 8f
    const val WOBBLE_PATTERNS: Int = 4

    /**
     * The splash never stays longer than this unless its own intro is still playing. Same ceiling as
     * the old splash (ERROR-034): a stalled boot must not trap the user behind it.
     */
    const val CEILING_MILLIS: Long = 6_000L

    fun introEndMillis(reduceMotion: Boolean): Long =
        ((if (reduceMotion) REDUCED_MOTION_HOLD else INTRO_END) * 1000f).toLong()

    /**
     * When the splash may leave, in milliseconds from its start: at the end of its intro if the
     * engine has settled (ready, or failed to start), otherwise at the ceiling, and never before the
     * intro ends — the owner's rule is that the animation plays to the end.
     */
    fun finishAtMillis(reduceMotion: Boolean, engineSettled: Boolean): Long {
        val introEnd = introEndMillis(reduceMotion)
        return if (engineSettled) introEnd else max(introEnd, CEILING_MILLIS)
    }

    /** The pen's easing: mostly linear, a little slower at both ends of a stroke. */
    internal fun ease(k: Float): Float = when {
        k <= 0f -> 0f
        k >= 1f -> 1f
        else -> 0.45f * k + 0.55f * (0.5f - 0.5f * cos(PI.toFloat() * k))
    }
}

/**
 * When each stroke of the name is written: one after the other, at one pen speed, with a
 * [FlashInkSplashTimeline.PEN_LIFT] between strokes, so the whole word takes [duration] seconds.
 * Pure, so the schedule is unit-tested without drawing anything.
 */
internal class InkWriterSchedule(
    lengths: FloatArray,
    start: Float = FlashInkSplashTimeline.WRITE_START,
    duration: Float = FlashInkSplashTimeline.WRITE_DURATION,
    lift: Float = FlashInkSplashTimeline.PEN_LIFT,
) {
    val starts: FloatArray = FloatArray(lengths.size)
    val durations: FloatArray = FloatArray(lengths.size)

    /** When the last stroke is finished. */
    val end: Float

    init {
        val total = lengths.sum()
        val speed = total / max(0.05f, duration - lift * (lengths.size - 1))
        var t = start
        for (i in lengths.indices) {
            starts[i] = t
            durations[i] = max(0.04f, lengths[i] / speed)
            t += durations[i] + lift
        }
        end = if (lengths.isEmpty()) start else t - lift
    }

    /** How much of stroke [index] is on the page at [time], eased, 0..1. */
    fun progress(index: Int, time: Float): Float =
        FlashInkSplashTimeline.ease((time - starts[index]) / durations[index])
}

/**
 * Decides once per process whether the launch splash plays, and remembers how far it has got (ADR-080).
 *
 * The owner's rules: play only on a **cold start**, always **play to the end**, and never when the
 * "Launch animation" setting is off. A host keeps one gate for the life of its process (Android: a
 * process-wide object; desktop: the `application { }` scope), so an Activity that is recreated
 * mid-splash resumes it at the right moment, and a window reopened from the tray or a warm return to
 * the app never replays it.
 *
 * [phase] is snapshot state, so composition and the Android splash keep-on-screen condition can both
 * read it.
 */
@Stable
class FlashLaunchSplashGate(private val nowMillis: () -> Long) {

    enum class Phase {
        /** The setting has not been read yet. Android holds its system splash meanwhile. */
        Pending,
        Playing,
        /** Turned off, or a launch that must not wait (answering a call, a push-to-talk press). */
        Skipped,
        Finished,
    }

    var phase: Phase by mutableStateOf(Phase.Pending)
        private set

    private var startedAtMillis = 0L

    /** The first decision wins; later calls (a recreated Activity, a reopened window) change nothing. */
    fun decide(enabled: Boolean) {
        if (phase != Phase.Pending) return
        if (enabled) {
            startedAtMillis = nowMillis()
            phase = Phase.Playing
        } else {
            phase = Phase.Skipped
        }
    }

    /** Skips the splash for this process if nothing was decided yet. */
    fun skip(): Unit = decide(enabled = false)

    /** Milliseconds since the splash started playing; 0 unless [Phase.Playing]. */
    val elapsedMillis: Long
        get() = if (phase == Phase.Playing) (nowMillis() - startedAtMillis).coerceAtLeast(0L) else 0L

    fun finish() {
        if (phase == Phase.Playing) phase = Phase.Finished
    }
}

/**
 * The launch splash over the app, driven by [gate]. Both hosts put it above their shell.
 *
 * - [FlashLaunchSplashGate.Phase.Pending]: only the splash ground, so nothing of the app shows while
 *   the setting is read.
 * - Playing: the Ink splash, which swallows pointer input so a tap cannot reach the hidden app.
 *   It finishes at [FlashInkSplashTimeline.finishAtMillis] and fades out over 250 ms.
 * - Skipped / Finished: nothing.
 *
 * @param engineSettled true once the engine is ready or has failed to start.
 */
@Composable
fun FlashLaunchSplashOverlay(
    gate: FlashLaunchSplashGate,
    engineSettled: Boolean,
    modifier: Modifier = Modifier,
) {
    val phase = gate.phase
    val reduceMotion = FlashTheme.motion.reduceMotion

    LaunchedEffect(phase, engineSettled, reduceMotion) {
        if (phase != FlashLaunchSplashGate.Phase.Playing) return@LaunchedEffect
        val finishAt = FlashInkSplashTimeline.finishAtMillis(reduceMotion, engineSettled)
        delay((finishAt - gate.elapsedMillis).coerceAtLeast(0L))
        gate.finish()
    }

    when (phase) {
        FlashLaunchSplashGate.Phase.Pending ->
            FlashInkSplash(modifier = modifier.fillMaxSize(), running = false)
        FlashLaunchSplashGate.Phase.Playing, FlashLaunchSplashGate.Phase.Finished -> {
            // Remembered once: an Activity recreated mid-splash starts its clock where the last left off.
            val startElapsed = remember { gate.elapsedMillis }
            // A host that comes back after the splash should have ended (the Activity was closed
            // mid-splash, the desktop window was hidden to the tray) shows nothing, not one stray frame;
            // the effect above then finishes the gate.
            val overdue = remember {
                startElapsed >= FlashInkSplashTimeline.finishAtMillis(reduceMotion, engineSettled)
            }
            AnimatedVisibility(
                visible = phase == FlashLaunchSplashGate.Phase.Playing && !overdue,
                enter = EnterTransition.None,
                exit = fadeOut(animationSpec = tween(FADE_OUT_MILLIS)),
            ) {
                FlashInkSplash(modifier = modifier.fillMaxSize(), startElapsedMillis = startElapsed)
            }
        }
        FlashLaunchSplashGate.Phase.Skipped -> Unit
    }
}

private const val FADE_OUT_MILLIS = 250
