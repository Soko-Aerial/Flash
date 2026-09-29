package com.transfer.flash.core.calling

import android.content.Context
import com.transfer.flash.core.common.logging.FlashLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.webrtc.NetworkChangeDetector
import org.webrtc.NetworkChangeDetector.ConnectionType
import org.webrtc.NetworkChangeDetector.NetworkInformation
import org.webrtc.NetworkMonitor
import org.webrtc.NetworkMonitorAutoDetect

/**
 * WebRTC's Android network detector plus the one interface it cannot see: a local-only link
 * such as this phone's own Wi-Fi hotspot (ERROR-079).
 *
 * ### Why
 *
 * libwebrtc on Android (m125, `sdk/android/src/jni/android_network_monitor.cc`) trusts the
 * Java detector for two things:
 * - **Availability.** `GetInterfaceInfo(if_name)` for a name the detector never reported
 *   answers `available = false`, and `BasicNetworkManager` then marks that interface
 *   *ignored*: no host candidate, no checks from it.
 * - **Binding.** `BindSocketToNetwork` for an unknown address returns `ADDRESS_NOT_FOUND`,
 *   and `PhysicalSocket::Bind` turns anything but SUCCESS / NOT_IMPLEMENTED into a failed
 *   bind.
 *
 * `NetworkMonitorAutoDetect` reports only `ConnectivityManager` networks. A hotspot's SoftAP
 * interface (`ap0`, `swlan0`, `wlan1`, … by OEM) is not one — the platform routes it through
 * its `local_network` table instead — so on the phone *hosting* the hotspot every leg to a
 * client stayed in ICE Checking while chat, which uses plain sockets, worked.
 *
 * ### How
 *
 * The same way libwebrtc already handles a Wi-Fi Direct group (its `WifiDirectManagerDelegate`,
 * off by default): report the interface as a network with handle **0** (`NETWORK_UNSPECIFIED`).
 * Native code then marks it available, and `BindSocketToNetwork` answers NOT_IMPLEMENTED for
 * handle 0, so the socket gets a plain `bind()` — exactly what Flash's own sockets do, and what
 * the kernel routes to the hotspot's clients.
 *
 * Everything else is delegated untouched, so a phone that is *not* hosting anything behaves
 * exactly as before. Found by polling the interface list every [POLL_MS] (the cadence
 * libwebrtc itself uses without a monitor); the detector only exists while WebRTC is
 * monitoring, i.e. while a call's connections are gathering.
 *
 * ### Limitation
 *
 * Handle 0 is one slot in the native maps (keyed by handle), so only **one** local-only
 * interface can be reported at a time. [LocalInterfacePicker] prefers a hotspot over a
 * Wi-Fi Direct group; a phone doing both at once gets calls over the hotspot only.
 */
@OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)
internal class FlashLocalNetworkDetector(
    private val observer: NetworkChangeDetector.Observer,
    context: Context,
) : NetworkChangeDetector {

    private val lock = Any()
    private var reported: NetworkInformation? = null
    private var destroyed = false

    private val inner = NetworkMonitorAutoDetect(
        object : NetworkChangeDetector.Observer() {
            override fun onConnectionTypeChanged(newConnectionType: ConnectionType) =
                observer.onConnectionTypeChanged(newConnectionType)

            override fun onNetworkConnect(networkInfo: NetworkInformation) =
                observer.onNetworkConnect(networkInfo)

            override fun onNetworkDisconnect(networkHandle: Long) =
                observer.onNetworkDisconnect(networkHandle)

            override fun onNetworkPreference(types: List<ConnectionType>, preference: Int) =
                observer.onNetworkPreference(types, preference)

            override fun getFieldTrialsString(): String = observer.fieldTrialsString
        },
        context,
    )

    private val poller: ScheduledExecutorService? =
        if (inner.supportNetworkCallback()) {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "flash-local-net").apply { isDaemon = true }
            }.also { it.scheduleWithFixedDelay(::rescan, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS) }
        } else {
            // No network callbacks means libwebrtc never binds sockets to networks
            // (networkBindingSupported is false), so there is nothing to work around.
            null
        }

    override fun getCurrentConnectionType(): ConnectionType = inner.currentConnectionType

    override fun supportNetworkCallback(): Boolean = inner.supportNetworkCallback()

    override fun getActiveNetworkList(): List<NetworkInformation>? {
        val list = inner.activeNetworkList ?: return null
        if (poller == null) return list
        val local = synchronized(lock) {
            // Called by NetworkMonitor right after creation and on its own refreshes: settle
            // the local interface now so the first gather already sees it.
            reported = pick(list)
            reported
        }
        if (local != null) log("local network in initial list", local)
        return if (local == null) list else list + local
    }

    override fun destroy() {
        synchronized(lock) { destroyed = true }
        poller?.shutdownNow()
        inner.destroy()
    }

    private fun rescan() {
        try {
            val known = inner.activeNetworkList ?: return
            synchronized(lock) {
                if (destroyed) return
                val next = pick(known)
                val prev = reported
                if (sameNetwork(prev, next)) return
                reported = next
                if (prev != null && (next == null || next.name != prev.name)) {
                    FlashLog.i("CALL", "WebRTC local network gone: ${prev.name}")
                    observer.onNetworkDisconnect(LOCAL_HANDLE)
                }
                if (next != null) {
                    log("local network reported", next)
                    observer.onNetworkConnect(next)
                }
            }
        } catch (t: Throwable) {
            // Never let the poll thread die on a transient interface-list failure.
            FlashLog.w("CALL", "WebRTC local network scan failed: ${t.message}")
        }
    }

    private fun pick(known: List<NetworkInformation>): NetworkInformation? {
        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { nif ->
            runCatching {
                LocalInterfacePicker.Candidate(
                    name = nif.name,
                    up = nif.isUp,
                    loopback = nif.isLoopback,
                    pointToPoint = nif.isPointToPoint,
                    addresses = nif.inetAddresses.toList(),
                )
            }.getOrNull()
        }
        val chosen = LocalInterfacePicker.pick(candidates, known.mapTo(HashSet()) { it.name })
            ?: return null
        return NetworkInformation(
            chosen.name,
            ConnectionType.CONNECTION_WIFI,
            ConnectionType.CONNECTION_NONE,
            LOCAL_HANDLE,
            chosen.addresses.map { NetworkChangeDetector.IPAddress(it.address) }.toTypedArray(),
        )
    }

    private fun sameNetwork(a: NetworkInformation?, b: NetworkInformation?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.name == b.name &&
            a.ipAddresses.map { it.address.toList() }.toSet() ==
            b.ipAddresses.map { it.address.toList() }.toSet()
    }

    private fun log(what: String, info: NetworkInformation) {
        val v4 = info.ipAddresses
            .mapNotNull { runCatching { InetAddress.getByAddress(it.address) }.getOrNull() }
            .filterIsInstance<Inet4Address>()
            .joinToString { it.hostAddress.orEmpty() }
        FlashLog.i("CALL", "WebRTC $what: ${info.name} [$v4] handle=$LOCAL_HANDLE")
    }

    internal companion object {
        /** `NETWORK_UNSPECIFIED`: native code answers NOT_IMPLEMENTED → a plain `bind()`. */
        const val LOCAL_HANDLE: Long = 0L

        /** Same cadence as libwebrtc's own polling when it has no monitor. */
        const val POLL_MS: Long = 2_000L

        /**
         * Installs this detector as WebRTC's. Must run before the first PeerConnection
         * starts network monitoring (NetworkMonitor asserts that); safe to call once at
         * engine setup. Returns false when it could not be installed.
         */
        fun install(): Boolean = try {
            NetworkMonitor.getInstance().setNetworkChangeDetectorFactory { observer, context ->
                FlashLocalNetworkDetector(observer, context)
            }
            true
        } catch (t: Throwable) {
            // AssertionError if monitoring already started; calls then keep the stock detector.
            FlashLog.w("CALL", "local network detector not installed: ${t.message}")
            false
        }
    }
}

/**
 * Chooses the local-only interface [FlashLocalNetworkDetector] reports. Pure, for tests.
 */
internal object LocalInterfacePicker {

    internal class Candidate(
        val name: String,
        val up: Boolean,
        val loopback: Boolean,
        val pointToPoint: Boolean,
        val addresses: List<InetAddress>,
    )

    /**
     * Cellular, VPN, 464xlat and kernel pseudo-interfaces. None of them is a local link a
     * peer could sit on, and cellular/VPN ones are reported by the stock detector when live.
     */
    private val EXCLUDED_PREFIXES = listOf(
        "rmnet", "ccmni", "seth", "v4-", "clat", "tun", "ppp", "ipsec", "dummy", "ifb",
        "sit", "ip6", "gre", "lo", "radio", "umts", "epdg", "r_rmnet",
    )

    /**
     * The interface to report, or null. Eligible: up, not loopback / point-to-point, not one
     * the stock detector already reports ([known]), not an excluded prefix, and holding a
     * private IPv4 address (every Android hotspot, Wi-Fi Direct group and USB tether hands
     * out one). A hotspot is preferred over a Wi-Fi Direct group (`p2p*`); ties go by name
     * so the choice is stable between polls.
     */
    fun pick(candidates: List<Candidate>, known: Set<String>): Candidate? =
        candidates
            .filter { c ->
                c.up && !c.loopback && !c.pointToPoint &&
                    c.name !in known &&
                    EXCLUDED_PREFIXES.none { c.name.startsWith(it) } &&
                    c.addresses.any { it is Inet4Address && it.isSiteLocalAddress }
            }
            .sortedWith(compareBy<Candidate> { it.name.startsWith("p2p") }.thenBy { it.name })
            .firstOrNull()
}
