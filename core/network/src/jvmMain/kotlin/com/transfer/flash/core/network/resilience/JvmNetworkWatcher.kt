@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.resilience

import com.transfer.flash.core.common.logging.FlashLog
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Snapshot of a network interface used for change detection and unit testing.
 */
public data class NetworkInterfaceSnapshot(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val addresses: List<String>,
)

/**
 * Desktop (JVM) equivalent of [AndroidNetworkWatcher].
 *
 * Monitors local network interfaces periodically to detect Wi-Fi / Ethernet connection changes,
 * IP reassignments, or interface additions/removals, invoking callbacks so that the desktop
 * engine can rediscovery and reconnect immediately without waiting for timeouts or manual user retries.
 */
public class JvmNetworkWatcher(
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val onAvailable: () -> Unit = {},
    private val onLost: () -> Unit = {},
    private val onLinkChanged: () -> Unit = {},
    private val networkInterfaceProvider: () -> List<NetworkInterfaceSnapshot> = ::defaultNetworkInterfaceProvider,
) {
    private var job: Job? = null
    private var previousFingerprint: String? = null

    @Synchronized
    public fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                pollOnce()
                delay(pollIntervalMs)
            }
        }
    }

    @Synchronized
    public fun stop() {
        job?.cancel()
        job = null
        previousFingerprint = null
    }

    /**
     * Executes a single poll sweep over the network interfaces.
     * Exposed for deterministic unit testing.
     */
    public fun pollOnce() {
        val interfaces = runCatching { networkInterfaceProvider() }.getOrDefault(emptyList())
        val active = interfaces.filter { it.isUp && !it.isLoopback && it.addresses.isNotEmpty() }
        val currentFingerprint = active.sortedBy { it.name }.joinToString(";") { iface ->
            "${iface.name}:${iface.addresses.sorted().joinToString(",")}"
        }

        val prev = previousFingerprint
        if (prev == null) {
            // First observation; record baseline without false-firing.
            previousFingerprint = currentFingerprint
            return
        }

        if (prev != currentFingerprint) {
            FlashLog.i(TAG, "Network change detected: '$prev' -> '$currentFingerprint'")
            previousFingerprint = currentFingerprint
            val wasEmpty = prev.isEmpty()
            val isNowEmpty = currentFingerprint.isEmpty()
            when {
                isNowEmpty -> {
                    runCatching { onLost() }
                }
                wasEmpty -> {
                    runCatching { onAvailable() }
                }
                else -> {
                    runCatching { onLinkChanged() }
                }
            }
        }
    }

    public companion object {
        private const val TAG: String = "JvmNetworkWatcher"
        public const val DEFAULT_POLL_INTERVAL_MS: Long = 2_500L

        public fun defaultNetworkInterfaceProvider(): List<NetworkInterfaceSnapshot> {
            return runCatching {
                NetworkInterface.getNetworkInterfaces()?.asSequence()?.map { iface ->
                    NetworkInterfaceSnapshot(
                        name = iface.name,
                        isUp = runCatching { iface.isUp }.getOrDefault(false),
                        isLoopback = runCatching { iface.isLoopback }.getOrDefault(false),
                        addresses = runCatching {
                            iface.inetAddresses.asSequence()
                                .filter { !it.isLoopbackAddress }
                                .map { it.hostAddress }
                                .toList()
                        }.getOrDefault(emptyList()),
                    )
                }?.toList() ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }
}
