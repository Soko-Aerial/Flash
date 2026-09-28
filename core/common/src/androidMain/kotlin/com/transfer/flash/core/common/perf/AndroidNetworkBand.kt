package com.transfer.flash.core.common.perf

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Reads this phone's [FlashNetworkBand] (G2): Ethernet, or the band of the Wi-Fi network it is
 * connected to. Anything else is [FlashNetworkBand.UNKNOWN], which includes a phone hosting the
 * hotspot (its default network is cellular, and its own AP band is a system API).
 *
 * The frequency comes from the default network's `WifiInfo` (API 31+, `getTransportInfo`) or
 * `WifiManager.getConnectionInfo()` below that. The `WifiInfo` class documentation names SSID and
 * BSSID as the location-redacted fields; frequency is not among them, so no location permission is
 * requested. A redacted or missing frequency (≤ 0) reads as UNKNOWN. Needs ACCESS_NETWORK_STATE
 * and ACCESS_WIFI_STATE, both normal permissions the app already holds.
 *
 * Cheap (system-service getters only) and safe on any thread; every read is wrapped.
 */
public object AndroidNetworkBand {

    public fun read(context: Context): FlashNetworkBand = runCatching { readUnsafe(context.applicationContext) }
        .getOrDefault(FlashNetworkBand.UNKNOWN)

    private fun readUnsafe(app: Context): FlashNetworkBand {
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return FlashNetworkBand.UNKNOWN
        val network = connectivity.activeNetwork ?: return FlashNetworkBand.UNKNOWN
        val caps = connectivity.getNetworkCapabilities(network) ?: return FlashNetworkBand.UNKNOWN
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return FlashNetworkBand.ETHERNET
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return FlashNetworkBand.UNKNOWN
        val frequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (caps.transportInfo as? WifiInfo)?.frequency
        } else {
            @Suppress("DEPRECATION")
            (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.frequency
        }
        return if (frequency == null || frequency <= 0) {
            FlashNetworkBand.UNKNOWN
        } else {
            FlashNetworkBand.fromWifiFrequencyMhz(frequency)
        }
    }
}
