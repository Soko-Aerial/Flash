package com.transfer.flash.core.common.perf

/**
 * The handful of device facts [FlashPerformanceClassifier] is allowed to look at.
 *
 * Deliberately a plain record with no platform types, for two reasons. It keeps the
 * classification rules in `commonMain` where they are trivially unit-testable — the alternative
 * is a policy that can only be exercised on a phone, which is how tiering rules rot. And it
 * makes the *inputs* explicit: nothing here needs a permission the app does not already hold, and
 * nothing here is a benchmark. A device is classified from what it is, not from a warm-up run,
 * because a synthetic benchmark at first launch competes with everything else first launch does.
 *
 * ## Reading these values on Android
 *
 * `AndroidDeviceProfile.read(context)` fills this in. Every field is best-effort: a value the
 * platform will not give up is passed through as [UNKNOWN_INT] / null rather than guessed, and
 * the classifier is written to ignore unknowns instead of treating them as zero.
 *
 * @param totalRamMb total physical RAM in MiB as the platform reports it, or [UNKNOWN_INT].
 *   Note this is materially **lower than the marketed figure** — the kernel's own reservations
 *   are excluded, so a "2 GB" handset reports ~1800 and an "8 GB" phone ~7400. The thresholds in
 *   [FlashPerformanceClassifier] are calibrated against the reported number, not the sticker.
 * @param isLowRamDevice the platform's own verdict (`ActivityManager.isLowRamDevice`, i.e.
 *   `ro.config.low_ram`). Authoritative when true and uninformative when false: OEMs set it on
 *   Android Go builds and frequently not on other constrained hardware.
 * @param apiLevel `Build.VERSION.SDK_INT`.
 * @param cpuCores `Runtime.availableProcessors()`, or [UNKNOWN_INT]. This is cores the process may
 *   currently use, which on a throttled device can read low for reasons unrelated to the
 *   hardware — treated as a weak signal accordingly.
 * @param screenPixels width x height of the display in physical pixels, or [UNKNOWN_INT]. A
 *   strong signal at the bottom of the range: a panel too small to show a 540p frame is
 *   conclusive about what class of device this is.
 * @param isWatch `PackageManager.FEATURE_WATCH`.
 * @param supports5GHz whether the Wi-Fi radio can use the 5 GHz band. A device-generation proxy,
 *   not a statement about the network currently joined. Null when it could not be determined.
 * @param hasHardwareVideoEncoder whether the platform advertises a non-software video encoder.
 *   Null when the codec list could not be read.
 */
public data class FlashDeviceProfile(
    public val totalRamMb: Int = UNKNOWN_INT,
    public val isLowRamDevice: Boolean = false,
    public val apiLevel: Int = UNKNOWN_INT,
    public val cpuCores: Int = UNKNOWN_INT,
    public val screenPixels: Int = UNKNOWN_INT,
    public val isWatch: Boolean = false,
    public val supports5GHz: Boolean? = null,
    public val hasHardwareVideoEncoder: Boolean? = null,
) {
    public companion object {
        /** Sentinel for a numeric fact the platform would not report. Never treated as small. */
        public const val UNKNOWN_INT: Int = -1
    }
}
