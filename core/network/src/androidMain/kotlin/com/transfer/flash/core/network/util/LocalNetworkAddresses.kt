package com.transfer.flash.core.network.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

public class LocalNetworkAddresses(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    public fun ipv4Addresses(): List<String> {
        val fromNetworks = connectivityManager.allNetworks
            .filter { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }
            .flatMap { network ->
                connectivityManager.getLinkProperties(network)
                    ?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.map { it.hostAddress.orEmpty() }
                    .orEmpty()
            }
            .filter { address ->
                address.isNotBlank() &&
                    !address.startsWith("127.") &&
                    !address.startsWith("169.254.")
            }
            .distinct()
            .sorted()
        if (fromNetworks.isNotEmpty()) return fromNetworks
        // Hotspot host case: the tethering interface is not reported as a WIFI/ETHERNET
        // transport network, so fall back to enumerating network interfaces directly.
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { address ->
                    address.isNotBlank() &&
                        !address.startsWith("127.") &&
                        !address.startsWith("169.254.")
                }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }
}
