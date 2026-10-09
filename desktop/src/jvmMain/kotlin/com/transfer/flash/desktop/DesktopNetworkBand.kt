@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.perf.FlashNetworkBand
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The desktop's [FlashNetworkBand] (G2), cached. Windows only; anything else reports UNKNOWN.
 *
 * - **Ethernet** when an up, non-virtual wired adapter holds a private IPv4 address (Java names
 *   Windows Ethernet adapters `ethN`; virtual switches, VPNs and a phone's USB tethering, which are
 *   also `ethN`, are excluded by their display names). A PC wired to one network and on Wi-Fi to
 *   another reports Ethernet; the peers are usually on the wired one.
 * - Otherwise the Wi-Fi band from `netsh wlan show interfaces`. Its field names are localised, so
 *   only the "`<n> GHz`" value is read (Windows 11 prints a Band line; older builds may not, and
 *   then the band is UNKNOWN).
 *
 * [current] never blocks: it returns the cached value and refreshes in the background when the
 * value is older than [maxAgeMs]. [start] primes it.
 */
internal class DesktopNetworkBand(
    private val scope: CoroutineScope,
    private val maxAgeMs: Long = 15_000L,
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
) {
    @Volatile
    private var cached: FlashNetworkBand = FlashNetworkBand.UNKNOWN

    @Volatile
    private var readAtMs: Long = Long.MIN_VALUE

    private val refreshing = AtomicBoolean(false)

    fun start() = refreshAsync()

    fun current(): FlashNetworkBand {
        if (System.currentTimeMillis() - readAtMs > maxAgeMs) refreshAsync()
        return cached
    }

    private fun refreshAsync() {
        if (!isWindows || !refreshing.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val band = read()
                if (band != cached) FlashLog.i("CALL", "desktop network band=${band.label}")
                cached = band
                readAtMs = System.currentTimeMillis()
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun read(): FlashNetworkBand {
        if (hasWiredAdapter()) return FlashNetworkBand.ETHERNET
        return runCatching { parseNetshBand(netshInterfaces()) }.getOrNull() ?: FlashNetworkBand.UNKNOWN
    }

    private fun hasWiredAdapter(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { nic ->
            nic.isUp && !nic.isLoopback && !nic.isVirtual &&
                isWiredAdapter(nic.name, nic.displayName.orEmpty()) &&
                nic.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress }
        }
    }.getOrDefault(false)

    private fun netshInterfaces(): String {
        val process = ProcessBuilder("netsh", "wlan", "show", "interfaces").redirectErrorStream(true).start()
        // R-20: the output used to be read to the end BEFORE waitFor(3 s), so a hung netsh blocked this thread for ever and
        // `refreshing` never cleared. Read on a daemon thread and bound the wait instead; a timeout kills the process, which
        // closes its pipe and ends the reader.
        val captured = StringBuilder()
        val reader = Thread({
            // ASCII is all the parser needs ("GHz" and digits), whatever the console code page is.
            runCatching { process.inputStream.bufferedReader(Charsets.ISO_8859_1).forEachLine { synchronized(captured) { captured.appendLine(it) } } }
        }, "flash-netsh-reader").apply { isDaemon = true; start() }
        if (!process.waitFor(NETSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        reader.join(1_000)
        return synchronized(captured) { captured.toString() }
    }

    companion object {
        private const val NETSH_TIMEOUT_SECONDS = 3L
        private val GHZ = Regex("""(\d+(?:[.,]\d+)?)\s*GHz""", RegexOption.IGNORE_CASE)

        private val NOT_WIRED = listOf(
            "virtual", "vethernet", "hyper-v", "vmware", "virtualbox", "vpn", "tap-", "wireguard",
            "tailscale", "zerotier", "remote ndis", "rndis", "bluetooth", "loopback", "wsl", "wi-fi",
            "wireless", "wlan", "802.11",
        )

        /** The band in `netsh wlan show interfaces` output, or null when it names none. */
        fun parseNetshBand(output: String): FlashNetworkBand? {
            val value = GHZ.find(output)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: return null
            return when {
                value < 3.0 -> FlashNetworkBand.WIFI_2_4GHZ
                value < 5.9 -> FlashNetworkBand.WIFI_5GHZ
                value < 7.2 -> FlashNetworkBand.WIFI_6GHZ
                else -> null
            }
        }

        /** A physical wired adapter, by Java's Windows interface name and the driver's display name. */
        fun isWiredAdapter(name: String, displayName: String): Boolean {
            if (!name.startsWith("eth")) return false
            val display = displayName.lowercase()
            return NOT_WIRED.none { it in display }
        }
    }
}
