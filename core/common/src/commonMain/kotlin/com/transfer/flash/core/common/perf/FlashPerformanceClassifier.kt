package com.transfer.flash.core.common.perf

/**
 * Which [FlashPerformanceMode] a device gets when nobody has pinned one, plus why.
 *
 * The [reason] is not decoration. A tier changes video resolution, packet rate, animation and
 * reconnect timing all at once, so the first question any field report raises is "which tier was
 * that device on, and what put it there". Carrying the answer in the verdict means the log line
 * at boot is self-explaining and nobody has to re-derive it from `Build.MODEL`.
 */
public data class FlashPerformanceVerdict(
    public val mode: FlashPerformanceMode,
    public val reason: String,
)

/**
 * Resolves a [FlashDeviceProfile] to a tier (ERROR-033) — the "detect automatically on first
 * run" half of the performance-mode feature.
 *
 * ## Shape of the rules
 *
 * Two stages, in this order:
 *
 * 1. **Hard gates to [FlashPerformanceMode.LOW].** Any single one of these is conclusive: a
 *    wearable, the platform's own low-RAM flag, RAM or a display below what a modern phone has,
 *    an OS too old, or a CPU too narrow. These are the facts that make the *default* stack fail
 *    rather than merely strain.
 * 2. **Concern counting for [FlashPerformanceMode.MEDIUM].** Everything else is a weak signal, so
 *    no single one demotes: it takes [MEDIUM_CONCERN_THRESHOLD] of them. This asymmetry is
 *    deliberate and is the one judgement call worth stating outright — MEDIUM disables animation,
 *    so misclassifying a device that was working perfectly well is a visible regression for its
 *    user. A conservative demotion rule costs a bit of headroom on a borderline device; an
 *    aggressive one strips the UI on hardware that never needed it.
 *
 * ## Calibration
 *
 * Anchored on the three devices in the field report. A BelFone SCP810 (2 GB, Android 8.1,
 * 480x640) is caught twice over by the RAM and display gates, which is intentional redundancy —
 * some OEM builds under-report `totalMem`. A Pixel 7 trips nothing. An Infinix in the 4 GB /
 * API 31 / 8-core / 5 GHz shape raises exactly one concern (RAM) and so stays on
 * [FlashPerformanceMode.HIGH], matching the report that it "worked fine".
 *
 * Unknown facts never count against a device. [FlashDeviceProfile.UNKNOWN_INT] is not small.
 */
public object FlashPerformanceClassifier {

    /**
     * Reported RAM (MiB) below which the default stack is not viable. ~2.5 GiB: a "2 GB" handset
     * reports well under this, a "3 GB" one just over — see [FlashDeviceProfile.totalRamMb] for
     * why the sticker figure and the reported figure differ.
     */
    public const val LOW_RAM_MB: Int = 2_560

    /** Reported RAM (MiB) below which memory pressure counts as one concern. */
    public const val MEDIUM_RAM_MB: Int = 6_144

    /**
     * Oldest API level that still gets the default stack. 26 (Oreo) is the line where the
     * platform gained the scheduling and codec behaviour the defaults assume.
     */
    public const val LOW_API_LEVEL: Int = 26

    /** API level below which OS age counts as one concern. 29 (Q). */
    public const val MEDIUM_API_LEVEL: Int = 29

    /** A display smaller than this many pixels cannot be a device the defaults were sized for. */
    public const val LOW_SCREEN_PIXELS: Int = 500_000

    /** Two usable cores cannot run capture, encode, crypto and a UI at once. */
    public const val LOW_CPU_CORES: Int = 2

    /** Core count at or below which CPU width counts as one concern. */
    public const val MEDIUM_CPU_CORES: Int = 4

    /** How many weak signals it takes to demote to [FlashPerformanceMode.MEDIUM]. */
    public const val MEDIUM_CONCERN_THRESHOLD: Int = 2

    /** Classifies [profile], with the deciding evidence in [FlashPerformanceVerdict.reason]. */
    public fun classify(profile: FlashDeviceProfile): FlashPerformanceVerdict {
        gate(profile)?.let { return FlashPerformanceVerdict(FlashPerformanceMode.LOW, it) }
        val concerns = concerns(profile)
        return if (concerns.size >= MEDIUM_CONCERN_THRESHOLD) {
            FlashPerformanceVerdict(FlashPerformanceMode.MEDIUM, concerns.joinToString(", "))
        } else {
            val detail = if (concerns.isEmpty()) "no concerns" else concerns.joinToString(", ")
            FlashPerformanceVerdict(FlashPerformanceMode.HIGH, detail)
        }
    }

    /** The first conclusive reason to force [FlashPerformanceMode.LOW], or null if there is none. */
    private fun gate(profile: FlashDeviceProfile): String? = when {
        profile.isWatch -> "wearable"
        profile.isLowRamDevice -> "platform low-RAM device"
        profile.totalRamMb.known() && profile.totalRamMb < LOW_RAM_MB ->
            "RAM ${profile.totalRamMb}MB < $LOW_RAM_MB"
        profile.apiLevel.known() && profile.apiLevel < LOW_API_LEVEL ->
            "API ${profile.apiLevel} < $LOW_API_LEVEL"
        profile.screenPixels.known() && profile.screenPixels < LOW_SCREEN_PIXELS ->
            "display ${profile.screenPixels}px < $LOW_SCREEN_PIXELS"
        profile.cpuCores.known() && profile.cpuCores <= LOW_CPU_CORES ->
            "${profile.cpuCores} CPU cores"
        else -> null
    }

    /** Weak signals, none of which demotes on its own. */
    private fun concerns(profile: FlashDeviceProfile): List<String> = buildList {
        if (profile.totalRamMb.known() && profile.totalRamMb < MEDIUM_RAM_MB) {
            add("RAM ${profile.totalRamMb}MB")
        }
        if (profile.apiLevel.known() && profile.apiLevel < MEDIUM_API_LEVEL) {
            add("API ${profile.apiLevel}")
        }
        if (profile.cpuCores.known() && profile.cpuCores <= MEDIUM_CPU_CORES) {
            add("${profile.cpuCores} cores")
        }
        if (profile.supports5GHz == false) add("2.4GHz-only radio")
        if (profile.hasHardwareVideoEncoder == false) add("no hardware video encoder")
    }

    private fun Int.known(): Boolean = this > 0
}
