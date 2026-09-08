package com.transfer.flash.core.discovery.nsd

import android.os.Build

/**
 * Isolates [Build.VERSION.SDK_INT] so unit tests can fake API levels and drive every
 * NSD strategy branch (plan C3.4) on the JVM without Robolectric.
 */
public interface NsdApiLevel {
    public val sdkInt: Int

    /** Production implementation reading the real OS value. */
    public fun isAtLeast(level: Int): Boolean = sdkInt >= level
}

/** Reads [Build.VERSION.SDK_INT]. Never construct in JVM tests — use a fake [NsdApiLevel]. */
public object BuildNsdApiLevel : NsdApiLevel {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT
}

/**
 * Verified NSD API thresholds (research 2026-08-22, plan C3.0/C3.4).
 *
 * Sources:
 * - https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager
 *   (`registerServiceInfoCallback(NsdServiceInfo, Executor, ServiceInfoCallback)` —
 *   "Added in API level 34"; legacy `resolveService(NsdServiceInfo, ResolveListener)` —
 *   "Deprecated in API level 34")
 * - https://developer.android.com/sdk/api_diff/33/changes/android.net.nsd.NsdManager
 *   (both `discoverServices(..., Network, | NetworkRequest, Executor, Listener)`
 *   overloads ADDED in API 33 — not 34)
 * - https://developer.android.com/reference/kotlin/android/net/nsd/NsdServiceInfo
 *   (`getNetwork()`/`setNetwork()` — "Added in API level 33")
 * - https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock +
 *   NsdManager "Wi-Fi Multicast Lock" section (lock required before T-extensions 7;
 *   system-managed multicast reception for foreground apps from T-ext 7 onward).
 *
 * NOTE on T-extensions: the precise runtime gate for auto-multicast is
 * `SdkExtensions.getExtensionVersion(VERSION_T) >= 7`, which is satisfied on all
 * Android 14+ devices. We approximate it with [SDK_SERVICE_INFO_CALLBACK] (34),
 * accepting that an Android 13 device WITHOUT the T-ext 7 mainline update keeps
 * taking the lock unnecessarily (safe direction: extra lock costs battery, absence
 * of the lock silently breaks mDNS reception).
 */
public object NsdApiThresholds {

    /**
     * `registerServiceInfoCallback` + deprecation of blocking-style `resolveService`.
     * At this level we switch to continuous monitoring (C3.4 upper branch).
     */
    public const val SDK_SERVICE_INFO_CALLBACK: Int = 34

    /**
     * `discoverServices(serviceType, protocolType, NetworkRequest, Executor, listener)`
     * tracks network changes automatically: services lost when their network drops,
     * re-found when a matching network rejoins (proper Found/Lost across Wi-Fi drops).
     * Requires ACCESS_NETWORK_STATE. Below this level use the legacy PROTOCOL_DNS_SD call.
     */
    public const val SDK_NETWORK_REQUEST_DISCOVERY: Int = 33

    /** `NsdServiceInfo.getNetwork()`/`setNetwork(Network)` availability. */
    public const val SDK_SERVICE_INFO_NETWORK_FIELD: Int = 33

    /**
     * Historic threshold above which (34+) the Wi-Fi multicast lock used to be skipped, on the
     * theory that T-extensions 7+ manage foreground multicast reception in-framework.
     *
     * NO LONGER USED to gate the lock: framework-managed multicast only applies to FOREGROUND
     * apps, and Flash browses from a backgrounded connectedDevice FGS (screen off), where mDNS
     * is not delivered without the explicit lock. [NsdTransport] now acquires the lock on ALL
     * API levels. Retained as documentation of the researched threshold.
     */
    public const val SDK_MULTICAST_LOCK_NOT_NEEDED: Int = 34
}
