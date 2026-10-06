package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallHealthWarning
import com.transfer.flash.core.common.perf.FlashThermalStatus

/**
 * Group video health (G6, `docs/calling/GROUP-VIDEO-PLAN.md` §4.5): turns heat, CPU load and
 * software decoding into a warning for the call screen and into the inputs of
 * [GroupVideoLimits.of]. Pure and single-threaded; the session feeds it once per stats sample,
 * under its video lock.
 *
 * - Thermal MODERATE: warn ([FlashCallHealthWarning.WARM]); the user may choose "Show fewer".
 * - Thermal SEVERE or worse: receive one video, turn new watchers down (`thermal`), and say so
 *   ([FlashCallHealthWarning.HOT]). Automatic; it lifts when the device cools.
 * - Own-process CPU at or above [cpuThresholdPercent] of all cores for [cpuSustainMs]: warn
 *   ([FlashCallHealthWarning.CPU]). The threshold is provisional until G0 measures it.
 * - Software video decoding while receiving two or more videos: warn once per call
 *   ([FlashCallHealthWarning.SOFTWARE_DECODE]); the session decides what counts.
 *
 * Heat at MODERATE or sustained CPU also marks the device as struggling, which makes a LOW
 * device ask for 360p (owner decision Q4). "Show fewer" caps receiving at one video until the
 * user turns it off.
 */
internal class CallHealthMonitor(
    private val cpuThresholdPercent: Double = DEFAULT_CPU_THRESHOLD_PERCENT,
    private val cpuSustainMs: Long = 30_000L,
) {
    /** What the video limits and the call screen should do now. */
    data class Verdict(
        val warning: FlashCallHealthWarning? = null,
        val struggling: Boolean = false,
        val receiveCap: Int? = null,
        val acceptNew: Boolean = true,
        /** Receiving is capped at one video (by the user, or automatically when hot). */
        val showingFewer: Boolean = false,
    )

    /** The user's "Show fewer" choice; it lasts until turned off or the call ends. */
    var showFewer: Boolean = false

    /** The user's data saver (ADR-067): receive no video at all; it lasts until turned off or the call ends. */
    var dataSaver: Boolean = false

    private var thermal = FlashThermalStatus.NONE
    private var cpuHighSince: Long? = null
    private var cpuHigh = false
    private var softwareDecodeSeen = false

    /**
     * One stats sample. [cpuPercent] is this process's share of all cores since the previous
     * sample (null when the platform can't tell); [softwareDecode] is true when the session saw
     * software decoding while receiving two or more videos.
     */
    fun update(nowMs: Long, thermal: FlashThermalStatus, cpuPercent: Double?, softwareDecode: Boolean): Verdict {
        this.thermal = thermal
        if (cpuPercent != null && cpuPercent >= cpuThresholdPercent) {
            val since = cpuHighSince ?: nowMs.also { cpuHighSince = it }
            cpuHigh = nowMs - since >= cpuSustainMs
        } else if (cpuPercent != null) {
            cpuHighSince = null
            cpuHigh = false
        }
        if (softwareDecode) softwareDecodeSeen = true
        return verdict()
    }

    /** The verdict for the latest inputs (also after [showFewer] changes). */
    fun verdict(): Verdict {
        if (thermal >= FlashThermalStatus.SEVERE) {
            return Verdict(
                warning = FlashCallHealthWarning.HOT,
                struggling = true,
                receiveCap = 1,
                acceptNew = false,
                showingFewer = true,
            )
        }
        val struggling = thermal >= FlashThermalStatus.MODERATE || cpuHigh
        if (dataSaver) {
            // No video to receive: nothing to warn about either, the tiles are not decoding anything.
            return Verdict(struggling = struggling, receiveCap = 0)
        }
        if (showFewer) {
            return Verdict(struggling = struggling, receiveCap = 1, showingFewer = true)
        }
        val warning = when {
            thermal == FlashThermalStatus.MODERATE -> FlashCallHealthWarning.WARM
            cpuHigh -> FlashCallHealthWarning.CPU
            softwareDecodeSeen -> FlashCallHealthWarning.SOFTWARE_DECODE
            else -> null
        }
        return Verdict(warning = warning, struggling = struggling)
    }

    companion object {
        /** Provisional (G0 replaces it): the call process using 40 % of every core for 30 s. */
        const val DEFAULT_CPU_THRESHOLD_PERCENT = 40.0

        /**
         * Whether a WebRTC `decoderImplementation` names a software decoder: libvpx / FFmpeg /
         * libaom / dav1d (the WebRTC built-ins) or Android's own software codecs (`c2.android.*`,
         * `OMX.google.*`). Hardware decoders carry the vendor's name (`c2.qti.*`, `OMX.MTK.*`, …).
         */
        fun isSoftwareDecoder(implementation: String?): Boolean {
            if (implementation.isNullOrBlank()) return false
            val name = implementation.lowercase()
            return SOFTWARE_MARKERS.any { it in name }
        }

        private val SOFTWARE_MARKERS = listOf("libvpx", "ffmpeg", "libaom", "dav1d", "c2.android.", "omx.google.")
    }
}

/**
 * This process's CPU time in nanoseconds, or null when the platform can't tell (G6). Android:
 * `Process.getElapsedCpuTime`; desktop: the JVM's `OperatingSystemMXBean.processCpuTime`.
 */
internal expect fun processCpuTimeNanos(): Long?

/** How many cores the process may run on, for turning CPU time into a share of the device. */
internal expect fun availableCores(): Int
