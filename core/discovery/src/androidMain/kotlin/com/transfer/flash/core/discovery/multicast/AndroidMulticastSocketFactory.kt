// FlashTextFraming/FlashProtocol/FlashLog are @FlashInternalApi — library-internal, opted into here
// exactly as NsdTransport and JmdsTransport do: this is a radio transport, not published API.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.multicast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Android implementation of [MulticastSocketFactory] (see [MulticastTransport]).
 *
 * Two platform obligations this file exists to satisfy, both of which fail silently when missed:
 *
 * - **The `WifiManager` multicast lock.** Without it the Wi-Fi chipset filters multicast frames in
 *   hardware while the socket still looks perfectly healthy — the app announces into the void and
 *   hears nobody. Acquired on bind and released when the transport tears its sockets down, so a
 *   stopped transport never holds a chipset-wide lock.
 * - **One socket per interface.** A single wildcard socket joins the group on whichever interface
 *   the routing table picks; on a phone with Wi-Fi plus tethering or a VPN that is not necessarily
 *   the one the peer is on, and the failure mode is total silence, not an error.
 *
 * Sockets are bound with `SO_REUSEADDR` set BEFORE the bind (hence the `MulticastSocket(null)`
 * dance): several sockets on one device share the announce port, one per interface, and a
 * constructor-bind would leave no window in which to set the flag.
 */
public class AndroidMulticastSocketFactory(
    context: Context,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val interfaces: () -> List<NetworkInterface> = ::multicastCapableInterfaces,
) : MulticastSocketFactory {

    private val appContext = context.applicationContext
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun bind(group: String, port: Int): List<MulticastSocketBinding> {
        acquireMulticastLockIfNeeded()
        val groupAddress = InetAddress.getByName(group)
        val bound = interfaces().mapNotNull { networkInterface ->
            runCatching { AndroidMulticastBinding(networkInterface, groupAddress, port) }
                .onFailure { Log.w(TAG, "multicast bind failed on ${networkInterface.name}", it) }
                .getOrNull()
        }
        if (bound.isEmpty()) releaseMulticastLock()
        return bound
    }

    /**
     * Mirrors `NsdTransport`'s rule: below API 34 the app must hold an explicit multicast lock, and
     * from 34 the framework manages it. Kept identical on purpose — an unconditional lock is a
     * battery cost with no benefit on modern devices, and `NsdTransportLogicTest` pins the threshold
     * for the NSD path.
     */
    private fun acquireMulticastLockIfNeeded() {
        if (apiLevel >= SDK_MULTICAST_LOCK_NOT_NEEDED) return
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifiManager.createMulticastLock(LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "multicast lock acquisition failed", it) }
    }

    override fun close() {
        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
        }.onFailure { Log.w(TAG, "multicast lock release failed", it) }
        multicastLock = null
    }

    internal companion object {
        internal const val SDK_MULTICAST_LOCK_NOT_NEEDED: Int = 34
        private const val LOCK_TAG = "flash-multicast-transport"
        private const val TAG = "MulticastTransport"
    }
}

/** One bound socket. Joins [group] on exactly one interface and sends out of it. */
private class AndroidMulticastBinding(
    private val networkInterface: NetworkInterface,
    private val group: InetAddress,
    port: Int,
) : MulticastSocketBinding {

    private val buffer = ByteArray(MulticastProtocol.MAX_DATAGRAM_BYTES)
    private val target = InetSocketAddress(group, port)

    override val label: String = networkInterface.name

    private val socket: MulticastSocket = MulticastSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
        networkInterface = this@AndroidMulticastBinding.networkInterface
        timeToLive = MULTICAST_TTL
        // Loopback is deliberately NOT touched — see JvmMulticastBinding for the reasoning: the
        // flag is deprecated for removal and its boolean is inverted, while the transport's
        // identity filter already covers us receiving (or not receiving) our own announcement.
        joinGroup(target, this@AndroidMulticastBinding.networkInterface)
    }

    override fun send(payload: ByteArray): Boolean = runCatching {
        socket.send(DatagramPacket(payload, payload.size, target))
        true
    }.getOrElse { error ->
        Log.w(TAG, "multicast send failed on ${networkInterface.name}", error)
        false
    }

    override fun receive(timeoutMs: Int): MulticastDatagram? = runCatching {
        socket.soTimeout = timeoutMs
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        MulticastDatagram(
            payload = packet.data.copyOf(packet.length),
            // The SOURCE of the datagram is the peer's address — there is no record to resolve and
            // therefore none to go stale.
            sourceAddress = packet.address?.hostAddress.orEmpty(),
            ipv6ScopeId = (packet.address as? Inet6Address)?.scopeId?.takeIf { it != 0 },
        )
    }.getOrElse { error ->
        // Quiet on the two benign cases: a receive timeout, and the socket closed underneath this
        // blocked receive — which is exactly what stop()/restartBrowsing() do, and which would
        // otherwise log a full stack trace on every shutdown. See the JVM twin for the detail.
        val shuttingDown = error is SocketTimeoutException || socket.isClosed
        if (!shuttingDown) {
            Log.w(TAG, "multicast receive failed on ${networkInterface.name}", error)
        }
        null
    }

    override fun close() {
        runCatching { socket.leaveGroup(target, networkInterface) }
        runCatching { socket.close() }
    }

    private companion object {
        /**
         * TTL 1 keeps announcements on the local link. Flash is LAN-only by design (ADR-025), and a
         * higher TTL would advertise the device to networks it has no business appearing on.
         */
        const val MULTICAST_TTL = 1
        const val TAG = "MulticastTransport"
    }
}

/**
 * Interfaces worth joining the group on: up, not loopback, with an IPv4 address. IPv6-only
 * interfaces are skipped because the announce address is an IPv4 group and a link-local source is
 * not dialable without its scope id.
 *
 * Deliberately does NOT pre-filter on `NetworkInterface.supportsMulticast()`, unlike the desktop
 * factory and `JmdnsBridge`'s enumeration. That flag is per-driver and its answer differs by OEM and
 * API level, and the two ways of being wrong are not symmetric: a false negative here means the
 * transport silently hears nobody and announces nothing on a phone whose Wi-Fi is perfectly fine,
 * while the alternative — attempting the join — is authoritative and free, because `joinGroup`
 * throws on an interface that cannot carry multicast and [AndroidMulticastBinding]'s construction is
 * already wrapped per interface by `bind()`. So the attempt IS the test.
 */
private fun multicastCapableInterfaces(): List<NetworkInterface> =
    runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { candidate ->
            runCatching {
                candidate.isUp &&
                    !candidate.isLoopback &&
                    candidate.inetAddresses.toList().any { it is Inet4Address }
            }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())
