package com.transfer.flash.core.common.perf

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Reads a [FlashDeviceProfile] off the Android platform.
 *
 * Everything here is best-effort and cheap: a handful of system-service getters plus one codec-list
 * walk. There is deliberately no benchmark — first launch is the worst possible moment to spend CPU
 * measuring CPU, and a warm-up run on a cold device measures the cold device, not the device.
 *
 * No permission is required. `ActivityManager`, `PackageManager` and `Resources` are unguarded;
 * `WifiManager.is5GHzBandSupported` is a radio *capability* query rather than a scan or a
 * connection query, so it carries none of the location gating that `getConnectionInfo` does.
 *
 * Every read is wrapped: a device whose OEM has broken one of these getters gets
 * [FlashDeviceProfile.UNKNOWN_INT] or null for that fact, and [FlashPerformanceClassifier] ignores
 * unknowns rather than counting them against the device.
 */
public object AndroidDeviceProfile {

    /** Reads the profile. Safe to call on any thread; touches no disk and blocks on nothing. */
    public fun read(context: Context): FlashDeviceProfile {
        val app = context.applicationContext
        val activityManager = runCatching {
            app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
        return FlashDeviceProfile(
            totalRamMb = totalRamMb(activityManager),
            isLowRamDevice = runCatching { activityManager?.isLowRamDevice }.getOrNull() ?: false,
            apiLevel = Build.VERSION.SDK_INT,
            cpuCores = runCatching { Runtime.getRuntime().availableProcessors() }
                .getOrDefault(FlashDeviceProfile.UNKNOWN_INT),
            screenPixels = screenPixels(app),
            isWatch = runCatching {
                app.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
            }.getOrNull() ?: false,
            supports5GHz = supports5GHz(app),
            hasHardwareVideoEncoder = hasHardwareVideoEncoder(),
        )
    }

    private fun totalRamMb(activityManager: ActivityManager?): Int {
        activityManager ?: return FlashDeviceProfile.UNKNOWN_INT
        return runCatching {
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            (info.totalMem / BYTES_PER_MB).toInt()
        }.getOrDefault(FlashDeviceProfile.UNKNOWN_INT)
    }

    /**
     * Display area in physical pixels.
     *
     * `displayMetrics` describes the app's own window, which under split-screen is smaller than
     * the panel. That only ever makes a device look *more* constrained than it is, and the
     * classifier's display gate is a floor far below any real phone, so the imprecision cannot
     * promote a device — only fail to promote one that was split-screened at launch.
     */
    private fun screenPixels(context: Context): Int = runCatching {
        val metrics = context.resources.displayMetrics
        val pixels = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        if (pixels <= 0L) FlashDeviceProfile.UNKNOWN_INT else pixels.toInt()
    }.getOrDefault(FlashDeviceProfile.UNKNOWN_INT)

    private fun supports5GHz(context: Context): Boolean? = runCatching {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.is5GHzBandSupported
    }.getOrNull()

    /**
     * Whether the platform advertises any non-software video encoder.
     *
     * From API 29 the codec info says so outright. Below that the only signal is the codec name:
     * Google's own software implementations are `OMX.google.*` (Android 8-ish and earlier) and
     * `c2.android.*` (Codec2, from Android 10), and every vendor hardware codec is named
     * something else. That heuristic is the standard one and it is *conservative in the right
     * direction* — a device with a hardware encoder under a name we do not recognise is reported
     * as having one, and a false "no encoder" only ever contributes one concern rather than a
     * demotion on its own.
     */
    private fun hasHardwareVideoEncoder(): Boolean? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            if (!info.isEncoder) return@any false
            if (info.supportedTypes.none { it.startsWith(VIDEO_MIME_PREFIX, ignoreCase = true) }) {
                return@any false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                SOFTWARE_CODEC_PREFIXES.none { info.name.startsWith(it, ignoreCase = true) }
            }
        }
    }.getOrNull()

    private const val BYTES_PER_MB = 1024L * 1024L

    private const val VIDEO_MIME_PREFIX = "video/"

    private val SOFTWARE_CODEC_PREFIXES = listOf("OMX.google.", "c2.android.", "OMX.SEC.sw.")
}
