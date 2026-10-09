package com.transfer.flash.core.calling

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import kotlinx.coroutines.CancellationException

/*
 * Screen sharing in calls (ADR-102, docs/calling/SCREEN-SHARE-DESIGN.md).
 *
 * Everything in this file is pure: the share ladder, the local share state, and the rule that picks the one
 * presenter. The sessions carry out the effects (capture, replaceTrack, tuning) and tests drive these classes
 * without a native stack.
 */

/** What a presenter can share. */
public enum class ShareSourceKind { SCREEN, WINDOW }

/** One thing the presenter can pick in the share dialog. [id] is the platform capturer's own id. */
public data class ShareSource(
    public val id: Long,
    public val title: String,
    public val kind: ShareSourceKind,
)

/** Why a share ended. Everything except [USER] and [CALL_ENDED] is shown to the presenter as a notice. */
public enum class ShareStopReason {
    /** The presenter pressed Stop. */
    USER,

    /** Another participant started presenting (they took over). */
    TAKEN_OVER,

    /**
     * The shared window was closed or the screen went away. NOT produced today (design decision D8: the watchdog looks
     * at the first frame only, so a window closed mid-share shows a frozen picture until the presenter stops; ERROR-137).
     */
    SOURCE_LOST,

    /**
     * The capturer delivered no picture within [ShareLadder.FIRST_FRAME_TIMEOUT_MS] of starting. Only the first frame
     * is watched (D8); a capturer that goes silent later is not detected.
     */
    NO_FRAMES,

    /** The capturer could not be opened or the track could not be put on the call. */
    FAILED,

    /** The call ended; nothing to tell the presenter. */
    CALL_ENDED,
}

/** A one-line message about the share, shown once and then cleared (the same idea as [com.transfer.flash.core.calling.model.FlashCallNotice]). */
public enum class FlashShareNotice {
    /** Another participant started presenting, so this device stopped. */
    TAKEN_OVER,

    /** The shared window or screen is gone (not raised today, see [ShareStopReason.SOURCE_LOST]). */
    SOURCE_LOST,

    /** The share was stopped because the capturer showed nothing within the first-frame timeout. */
    NO_FRAMES,

    /** The share could not start. */
    FAILED,

    /** The share was lowered to a smaller picture because the computer is busy or the link is slow. */
    LOWERED,

    /** More people want to watch than this device sends the share to; the extra ones are told. */
    WATCHER_CAP,

    /** Someone else is presenting and the user must confirm taking over. Not an error; drives the confirm sheet. */
    SOMEONE_PRESENTING,
}

/** What the presenter chose: a normal share, or a smaller, lighter one ("Share at lower quality"). */
public enum class ShareQuality { STANDARD, LOWER }

/**
 * One rung of the share ladder: the tallest picture, the frame rate and the bitrate window of a share.
 *
 * Text needs resolution, not frame rate, so the rungs trade fps first and height last. The numbers are first
 * guesses, not measurements (`SHARE-04` in the test backlog replaces them); ADR-098's 540p/360p camera caps are NOT
 * reused because 360p text is unreadable.
 */
internal data class ShareProfile(
    val level: Int,
    val maxHeight: Int,
    val fps: Int,
    val maxBitrateKbps: Int,
    val minBitrateKbps: Int,
)

internal object ShareLadder {
    /** Rungs from best to lightest. */
    val RUNGS: List<ShareProfile> = listOf(
        ShareProfile(level = 0, maxHeight = 1080, fps = 10, maxBitrateKbps = 2_500, minBitrateKbps = 400),
        ShareProfile(level = 1, maxHeight = 720, fps = 8, maxBitrateKbps = 1_500, minBitrateKbps = 300),
        ShareProfile(level = 2, maxHeight = 720, fps = 5, maxBitrateKbps = 900, minBitrateKbps = 200),
        ShareProfile(level = 3, maxHeight = 540, fps = 5, maxBitrateKbps = 600, minBitrateKbps = 150),
    )

    /** From this many watchers the share starts one rung lower: each is one more software encode of the same screen. */
    const val CROWD_WATCHERS = 3

    /** The picture a 4K display is captured at: larger is converted and scaled for nothing, the ladder never sends it. */
    const val CAPTURE_MAX_WIDTH = 2560
    const val CAPTURE_MAX_HEIGHT = 1440

    /** The capturer's own frame rate; the encoder's cap is the rung's [ShareProfile.fps], never above this. */
    const val CAPTURE_FPS = 10

    /** A share that has delivered no frame this long after starting is stopped (a dead capturer shows black for ever). */
    const val FIRST_FRAME_TIMEOUT_MS = 5_000L

    /** What a receiver asks a presenter for: the tallest rung, or the 720p rung when the receiver itself is struggling. */
    const val ASK_HEIGHT = 1080
    const val ASK_HEIGHT_LOWER = 720

    /**
     * How many devices one presenter sends its screen to at once. Each is a software VP8 encode on the presenter, so
     * it follows the tier like the camera does; a watcher beyond it is turned down ("busy") and told.
     */
    fun watcherCap(tier: FlashPerformanceMode): Int = when (tier) {
        FlashPerformanceMode.HIGH -> 4
        FlashPerformanceMode.MEDIUM -> 3
        FlashPerformanceMode.LOW -> 2
    }

    /**
     * The rung for a share with [watchers] watchers.
     *
     * - [quality] [ShareQuality.LOWER] (the presenter's own choice) skips the two best rungs.
     * - a crowd of [CROWD_WATCHERS] or more starts one rung lower.
     * - [struggling] (the computer cannot keep up: quality limited by CPU, or hot) steps one more rung down.
     */
    fun profile(watchers: Int, quality: ShareQuality, struggling: Boolean): ShareProfile {
        var level = 0
        if (watchers >= CROWD_WATCHERS) level += 1
        if (quality == ShareQuality.LOWER) level += 2
        if (struggling) level += 1
        return RUNGS[level.coerceIn(0, RUNGS.lastIndex)]
    }

    /**
     * `scaleResolutionDownBy` that brings a [sourceHeight]-tall capture to at most [targetHeight]. 1.0 when the
     * source is not taller (never upscale text). An unknown source size (no frame yet) is also 1.0: the next
     * session's re-tune corrects it once the first frame says how big it is.
     */
    fun scaleDownBy(sourceHeight: Int, targetHeight: Int): Double {
        if (sourceHeight <= 0 || targetHeight <= 0 || sourceHeight <= targetHeight) return 1.0
        return sourceHeight.toDouble() / targetHeight
    }

    /** The sender tuning for [profile] on a capture [sourceHeight] tall. */
    fun tuning(profile: ShareProfile, sourceHeight: Int, demoteForVoice: Boolean): VideoSendTuning = VideoSendTuning(
        maxBitrateBps = profile.maxBitrateKbps * 1_000,
        minBitrateBps = profile.minBitrateKbps * 1_000,
        maxFramerate = minOf(profile.fps, CAPTURE_FPS).toDouble(),
        scaleResolutionDownBy = scaleDownBy(sourceHeight, profile.maxHeight),
        demoteForVoice = demoteForVoice,
        // Text: shed frame rate before resolution, the opposite of a face (Android honours it; desktop logs the gap).
        maintainFramerate = false,
        maintainResolution = true,
    )
}

/**
 * The local presenter's phase. Pure: the session decides when each event happens.
 *
 * ```
 * IDLE --begin--> STARTING --ready--> SHARING --end--> STOPPING --finished--> IDLE
 *                    |  \--fail--> IDLE                    ^
 *                    \--end (cancelled while opening)------/
 * ```
 */
internal class ShareStateMachine {
    enum class Phase { IDLE, STARTING, SHARING, STOPPING }

    var phase: Phase = Phase.IDLE
        private set

    /** Title of the thing being shared, for the indicator. Null when idle. */
    var sourceTitle: String? = null
        private set

    /** When the share started on the arbiter's counter; 0 when idle. */
    var startedAt: Long = 0L
        private set

    var quality: ShareQuality = ShareQuality.STANDARD
        private set

    /** True from the moment the user asked to share until the capturer is gone again. */
    val busy: Boolean get() = phase != Phase.IDLE

    /** The share is on the call (or about to be): the camera track must not be sent. */
    val sharing: Boolean get() = phase == Phase.STARTING || phase == Phase.SHARING

    /** IDLE -> STARTING. False when a share is already running or still closing. */
    fun begin(title: String, startedAt: Long, quality: ShareQuality): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.STARTING
        sourceTitle = title
        this.startedAt = startedAt
        this.quality = quality
        return true
    }

    /** STARTING -> SHARING. False when it was cancelled meanwhile. */
    fun ready(): Boolean {
        if (phase != Phase.STARTING) return false
        phase = Phase.SHARING
        return true
    }

    /** STARTING -> STOPPING (the capturer may be half open and must be closed). */
    fun fail(): Boolean {
        if (phase != Phase.STARTING) return false
        phase = Phase.STOPPING
        return true
    }

    /** STARTING or SHARING -> STOPPING. False when there was nothing to stop (a second Stop is a no-op). */
    fun end(): Boolean {
        if (phase != Phase.STARTING && phase != Phase.SHARING) return false
        phase = Phase.STOPPING
        return true
    }

    /** STOPPING -> IDLE, once the capturer has been closed. */
    fun finished() {
        if (phase != Phase.STOPPING) return
        phase = Phase.IDLE
        sourceTitle = null
        startedAt = 0L
        quality = ShareQuality.STANDARD
    }

    fun setQuality(next: ShareQuality) {
        quality = next
    }
}

/**
 * Who is presenting, when more than one device says it is (ADR-102: one presenter at a time).
 *
 * Every participant states `ss=1` with `sst` (its start counter). The presenter is the claim with the largest
 * ([startedAt], id): the latest start wins, which is both the take-over rule (a deliberate start is later than the
 * one it replaces) and the tie-break for two starts at the same moment. Every device sees the same claims, so every
 * device picks the same winner, and the loser stops itself.
 *
 * The counter is not a clock comparison: [nextStart] is at least one more than the largest start ever seen, so a
 * take-over always beats the share it replaces, even when the two devices' clocks disagree by minutes. An older
 * client states no share at all, so it is never a claim.
 *
 * A remote claim is bounded by [claimBound]: the receiver's clock plus [CLOCK_SKEW_MS], but never below "one more than
 * the largest start seen". The second term is what keeps the rule honest: a member that states an absurd start can
 * lift the bound only to the clock plus the skew, and the very next deliberate start (largest seen + 1) is still
 * within the bound of every receiver, so it sorts after the absurd claim and the id tie-break never decides. (A fixed
 * ceiling instead would make the absurd claim and the honest take-over tie at the ceiling, and the higher id would
 * keep the role for the rest of the call: review S3, ERROR-139.) Whoever takes over last still wins; there is no
 * authority model, so any member can take the screen from another by starting a share, exactly as intended.
 */
internal class ShareArbiter(private val localId: String, private val nowMs: () -> Long) {
    private val claims = HashMap<String, Long>()
    private var highestSeen = 0L

    /** The counter value for a start that happens now (wall clock [nowMs], raised above every start seen). */
    fun nextStart(nowMs: Long): Long = minOf(maxOf(nowMs.coerceAtLeast(0L), highestSeen + 1), MAX_START)

    /** The largest start a remote claim may state right now (see the class comment). */
    private fun claimBound(): Long =
        minOf(maxOf(nowMs().coerceIn(0L, MAX_START) + CLOCK_SKEW_MS, highestSeen + 1), MAX_START)

    /** Records the local device's own claim (or clears it with null). Bounded like a remote one, so every device agrees. */
    fun setLocal(startedAt: Long?) = set(localId, startedAt?.coerceIn(0L, claimBound()))

    /** Folds a status from [peerId] in. A missing `sst` on an `ss=1` is a claim of 0 (still ordered by id). */
    fun onStatus(peerId: String, sharing: Boolean?, startedAt: Long?) {
        if (peerId == localId) return
        when (sharing) {
            true -> set(peerId, (startedAt ?: 0L).coerceIn(0L, claimBound()))
            false -> claims.remove(peerId)
            null -> Unit
        }
    }

    fun onPeerLeft(peerId: String) {
        if (peerId != localId) claims.remove(peerId)
    }

    /** The current presenter's device id (possibly this device), or null. */
    val presenter: String?
        get() = claims.entries.maxWithOrNull(compareBy<Map.Entry<String, Long>>({ it.value }, { it.key }))?.key

    /** The presenter, when it is another device. */
    val remotePresenter: String? get() = presenter?.takeIf { it != localId }

    /** The local device claims to present but another claim is later: it must stop. */
    fun localMustYield(): Boolean = localId in claims && presenter != localId

    fun reset() {
        claims.clear()
    }

    private fun set(id: String, startedAt: Long?) {
        if (startedAt == null) {
            claims.remove(id)
        } else {
            claims[id] = startedAt
            highestSeen = maxOf(highestSeen, startedAt)
        }
    }

    internal companion object {
        /** Year 2100 in ms: a hard ceiling that only guards `highestSeen + 1` against overflow; [claimBound] is the real limit. */
        const val MAX_START = 4_102_444_800_000L

        /** How far ahead of the receiver's own clock a claim may be before it is cut back (one day: far more than any real clock error). */
        const val CLOCK_SKEW_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * Whether the presenting computer cannot keep up, from a stream of samples: [enterAfter] strained samples in a row
 * make it "struggling" (the ladder steps one rung down), [leaveAfter] clean samples in a row end it. Slower to leave
 * than to enter, so the picture does not flap between two rungs. Pure.
 */
internal class ShareStrain(private val enterAfter: Int = 3, private val leaveAfter: Int = 12) {
    var struggling: Boolean = false
        private set
    private var strained = 0
    private var clean = 0

    /** Returns true when [struggling] changed. */
    fun onSample(isStrained: Boolean): Boolean {
        if (isStrained) {
            clean = 0
            strained++
            if (!struggling && strained >= enterAfter) {
                struggling = true
                return true
            }
        } else {
            strained = 0
            clean++
            if (struggling && clean >= leaveAfter) {
                struggling = false
                return true
            }
        }
        return false
    }

    fun reset() {
        struggling = false
        strained = 0
        clean = 0
    }
}

/**
 * The capture a session presents with, and the decisions about it that need no native stack: the ladder rung to use,
 * and the watchdog. One per session. The session calls [open] and [close] on the media thread.
 */
internal class ScreenShareRun(
    private val provider: ScreenCaptureProvider,
    private val nowMs: () -> Long,
) {
    val machine = ShareStateMachine()
    val strain = ShareStrain()

    @Volatile
    var handle: ScreenCaptureHandle? = null
        private set
    private var openedAtMs = 0L
    private var tunedHeight = 0

    enum class Check { OK, RETUNE, NO_FRAMES }

    /** Opens [source] at the ladder's capture size. Throws when the capturer cannot start. */
    suspend fun open(source: ShareSource): ScreenCaptureHandle {
        val opened = provider.open(source, ShareLadder.CAPTURE_FPS, ShareLadder.CAPTURE_MAX_WIDTH, ShareLadder.CAPTURE_MAX_HEIGHT)
        handle = opened
        openedAtMs = nowMs()
        tunedHeight = 0
        return opened
    }

    /** Closes the capture; idempotent. The track must already be off every sender. */
    fun close() {
        val h = handle ?: return
        handle = null
        runCatching { h.close() }
    }

    /**
     * The ERROR-123 / ADR-102 D10 order for every way a capture ends: [offSenders] first (the screen leaves every
     * sender), then the capture stops and disposes. A failing [offSenders] never keeps the capture open: a connection
     * that is already gone has nothing to take it off. [offSenders] runs even when no capture is open (a half-failed
     * start may have put a track on a sender); [close] is idempotent.
     */
    suspend fun closeAfter(offSenders: suspend () -> Unit) {
        try {
            offSenders()
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (_: Throwable) {
            // A sender that cannot be emptied (closed connection) is not a reason to leak the capture.
        }
        close()
    }

    /** The rung for [watchers] watchers under the chosen quality and the computer's strain. */
    fun profile(watchers: Int): ShareProfile = ShareLadder.profile(watchers, machine.quality, strain.struggling)

    /**
     * The tuning for one connection that carries the share: the rung for [watchers] watchers, lowered to [askedHeight]
     * when that watcher asked for less (the bitrate follows the picture's area), then scaled and possibly paused by the
     * connection's own voice-priority [concession] (voice still comes first while presenting).
     */
    fun legTuning(
        watchers: Int,
        askedHeight: Int?,
        active: Boolean,
        concession: VideoConcession,
        demoteForVoice: Boolean,
    ): VideoSendTuning {
        val rung = profile(watchers)
        val capped = if (askedHeight != null && askedHeight in 1 until rung.maxHeight) {
            val area = (askedHeight.toDouble() / rung.maxHeight).let { it * it }
            rung.copy(
                maxHeight = askedHeight,
                maxBitrateKbps = (rung.maxBitrateKbps * area).toInt().coerceAtLeast(rung.minBitrateKbps),
            )
        } else {
            rung
        }
        val base = ShareLadder.tuning(capped, handle?.height ?: 0, demoteForVoice)
        return VideoSendTuning(
            maxBitrateBps = (base.maxBitrateBps * concession.bitrateScale).toInt(),
            minBitrateBps = if (concession.holdsBitrateFloor) base.minBitrateBps else null,
            maxFramerate = base.maxFramerate,
            scaleResolutionDownBy = maxOf(base.scaleResolutionDownBy ?: 1.0, concession.scaleResolutionDownBy),
            active = active && concession.videoActive,
            demoteForVoice = base.demoteForVoice,
            maintainResolution = true,
        )
    }

    /**
     * One watchdog look: [Check.NO_FRAMES] when nothing arrived within the first-frame timeout (the share is dead),
     * [Check.RETUNE] when the captured height became known or changed (the scale factor must follow it).
     */
    fun check(): Check {
        val h = handle ?: return Check.OK
        if (h.frameCount == 0L) {
            return if (nowMs() - openedAtMs >= ShareLadder.FIRST_FRAME_TIMEOUT_MS) Check.NO_FRAMES else Check.OK
        }
        val height = h.height
        if (height > 0 && height != tunedHeight) {
            tunedHeight = height
            return Check.RETUNE
        }
        return Check.OK
    }
}
