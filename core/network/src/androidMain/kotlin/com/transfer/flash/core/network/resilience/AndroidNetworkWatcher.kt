@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.resilience

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.net.LinkChangeTracker
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

/**
 * Platform half of instant-reconnect (plan C4.2 upgrade 2): fires [onAvailable]
 * the moment ANY Wi-Fi/Ethernet network comes up, so the reconnect engine can
 * attempt immediately instead of waiting out the backoff delay after a Wi-Fi
 * drop/rejoin.
 *
 * ERROR-033 added [onLinkChanged], which covers the case [onAvailable] structurally cannot: an
 * AP-to-AP roam on a mesh SSID keeps the same [Network] object, so neither `onAvailable` nor
 * `onLost` ever fires even though the radio spent seconds reassociating. See [LinkChangeTracker]
 * for why the signal is a hint rather than a fact, and why the response to it must be cheap.
 *
 * ## Why the transport filter stays narrow
 *
 * ERROR-035 asked whether this should widen to every transport so a hotspot coming up while Wi-Fi
 * stays connected is seen. It should not, for two separate reasons. A SoftAP interface is not a
 * [Network] at all — the platform hands out no `Network` object for `ap0`, which is why
 * `LocalNetworkAddresses` has to enumerate interfaces directly — so widening would not see the
 * hotspot either. And what it *would* newly see is cellular, whose bandwidth estimate moves
 * constantly on a walking device; every one of those reports would cost a LAN redial sweep and a
 * foreground-service promotion retry for an event that cannot carry LAN traffic. The hotspot
 * transition is picked up on the discovery side instead (`NsdManagerBridge.linkFingerprint`), and
 * the peers it uncovers reach this side through the auto-connect sweep.
 *
 * Kept behind a tiny callback so JVM tests fake it entirely; the Android
 * surface here is deliberately minimal (register/unregister, idempotent).
 * Requires ACCESS_NETWORK_STATE — already declared app-wide.
 */
internal class AndroidNetworkWatcher(
    context: Context,
    private val onAvailable: () -> Unit,
    private val onLost: () -> Unit = {},
    /**
     * Invoked when the shape of the current link changes in a way consistent with a reassociation.
     * Rate-limited by [LinkChangeTracker]; may still be a false positive, so the handler must be
     * non-destructive (probe, do not reap). Delivered on the ConnectivityManager callback thread.
     */
    private val onLinkChanged: () -> Unit = {},
    nowMs: () -> Long = System::currentTimeMillis,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var registered = false

    private val linkChanges = LinkChangeTracker(nowMs)

    /**
     * Capabilities/link-properties fingerprint halves **per network**, keyed by
     * [Network.networkHandle].
     *
     * Two maps rather than two strings, because the request below matches more than one network
     * routinely — Wi-Fi plus Ethernet on a set-top device, or a single SSID published as two
     * `Network`s. With one shared pair of strings, alternating reports from two networks made the
     * combined fingerprint flip on every callback, so the tracker rendered a roam verdict every
     * time its rate limit expired and the mesh paid a probe round per peer per 5 s, forever, on a
     * link that had never moved. Keyed per network, a report only changes the fingerprint when that
     * network's own shape changed.
     *
     * Concurrent because the two callbacks are documented to arrive on one thread but the maps are
     * also cleared from [start]/[stop] on the caller's thread.
     */
    private val capabilitiesByNetwork = ConcurrentHashMap<Long, String>()

    private val linkByNetwork = ConcurrentHashMap<Long, String>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A genuinely new network re-seeds the tracker: its first capabilities/link report is
            // an initial association, not a roam, and must not fire a probe round.
            linkChanges.reset()
            capabilitiesByNetwork.clear()
            linkByNetwork.clear()
            onAvailable()
        }

        override fun onLost(network: Network) {
            linkChanges.reset()
            capabilitiesByNetwork.remove(network.networkHandle)
            linkByNetwork.remove(network.networkHandle)
            onLost()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            capabilitiesByNetwork[network.networkHandle] = fingerprint(capabilities)
            offerFingerprint()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            linkByNetwork[network.networkHandle] = fingerprint(linkProperties)
            offerFingerprint()
        }
    }

    private fun offerFingerprint() {
        if (linkChanges.onFingerprint(combinedFingerprint())) {
            runCatching { onLinkChanged() }
        }
    }

    /**
     * One canonical string over every network we are tracking. Sorted by handle so the order the
     * platform happens to deliver reports in cannot register as a change.
     */
    private fun combinedFingerprint(): String {
        val handles = (capabilitiesByNetwork.keys + linkByNetwork.keys).sorted()
        return handles.joinToString(separator = ";") { handle ->
            "$handle=${capabilitiesByNetwork[handle].orEmpty()}|${linkByNetwork[handle].orEmpty()}"
        }
    }

    /**
     * The permission-free part of the capabilities report that moves across a reassociation.
     *
     * Bandwidth estimates are bucketed to 1 Mbit/s: the platform re-reports them continuously on a
     * marginal link, and an unbucketed value would make every report a "change" and defeat the
     * tracker's rate limit from below. A roam between APs at different rates crosses a bucket;
     * ordinary jitter does not.
     *
     * The BSSID is included where the platform offers it (API 29+ via [NetworkCapabilities]) and is
     * the one unambiguous roam signal. It is redacted to a fixed placeholder from API 31 without
     * location permission, which is harmless — a constant contributes nothing and costs nothing.
     */
    private fun fingerprint(capabilities: NetworkCapabilities): String {
        val down = capabilities.linkDownstreamBandwidthKbps / BANDWIDTH_BUCKET_KBPS
        val up = capabilities.linkUpstreamBandwidthKbps / BANDWIDTH_BUCKET_KBPS
        val bssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { (capabilities.transportInfo as? WifiInfo)?.bssid }.getOrNull().orEmpty()
        } else {
            ""
        }
        return "$down/$up/$bssid"
    }

    /**
     * Addresses, routes, DNS and interface name. Sorted, because neither the platform nor the
     * kernel promises a stable order and an order flip is not a roam.
     */
    private fun fingerprint(linkProperties: LinkProperties): String = runCatching {
        val addresses = linkProperties.linkAddresses.map { it.toString() }.sorted()
        val routes = linkProperties.routes.map { it.toString() }.sorted()
        val dns = linkProperties.dnsServers.map { it.hostAddress.orEmpty() }.sorted()
        "${linkProperties.interfaceName}/$addresses/$routes/$dns"
    }.getOrDefault("")

    /** Idempotent. Requests LAN-capable networks only (Wi-Fi/Ethernet); see the class KDoc. */
    @Synchronized
    fun start() {
        if (registered || connectivityManager == null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            registered = true
        }
    }

    /** Idempotent. */
    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        registered = false
        linkChanges.reset()
        capabilitiesByNetwork.clear()
        linkByNetwork.clear()
    }

    private companion object {
        const val BANDWIDTH_BUCKET_KBPS = 1_000
    }
}
