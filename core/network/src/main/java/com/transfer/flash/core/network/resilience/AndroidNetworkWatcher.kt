package com.transfer.flash.core.network.resilience

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlin.concurrent.Volatile

/**
 * Platform half of instant-reconnect (plan C4.2 upgrade 2): fires [onAvailable]
 * the moment ANY Wi-Fi/Ethernet network comes up, so the reconnect engine can
 * attempt immediately instead of waiting out the backoff delay after a Wi-Fi
 * drop/rejoin.
 *
 * Kept behind a tiny callback so JVM tests fake it entirely; the Android
 * surface here is deliberately minimal (register/unregister, idempotent).
 * Requires ACCESS_NETWORK_STATE — already declared app-wide.
 */
internal class AndroidNetworkWatcher(
    context: Context,
    private val onAvailable: () -> Unit,
    private val onLost: () -> Unit = {},
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onAvailable()
        }

        override fun onLost(network: Network) {
            onLost()
        }
    }

    /** Idempotent. Requests LAN-capable networks only (Wi-Fi/Ethernet). */
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
    }
}
