// FlashTextFraming/FlashProtocol/FlashLog are @FlashInternalApi — library-internal, opted into here
// exactly as NsdTransport and JmdsTransport do: this is a radio transport, not published API.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.multicast

import com.transfer.flash.core.common.logging.FlashLog
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Desktop (JVM) implementation of [MulticastSocketFactory] (see [MulticastTransport]).
 *
 * The desktop twin of `AndroidMulticastSocketFactory` minus the multicast lock, which is an Android
 * concept: a JVM on Windows, Linux or macOS needs no permission to receive link-local multicast, so
 * [close] has nothing to release.
 *
 * Same two design points as the Android side, for the same reasons:
 * - **one socket per interface** — a wildcard socket joins the group on one interface chosen by the
 *   routing table, which on a laptop with Wi-Fi + Ethernet + Hyper-V switches is routinely the wrong
 *   one, and the transport then hears nobody while reporting itself healthy;
 * - **`SO_REUSEADDR` before bind**, so the per-interface sockets can share the announce port.
 *
 * OS-neutral by the project's rule for `jvmMain`: no path literals, no Windows-only assumptions.
 */
public class JvmMulticastSocketFactory(
    private val interfaces: () -> List<NetworkInterface> = ::multicastCapableInterfaces,
) : MulticastSocketFactory {

    override fun bind(group: String, port: Int): List<MulticastSocketBinding> {
        val groupAddress = InetAddress.getByName(group)
        return interfaces().mapNotNull { networkInterface ->
            runCatching { JvmMulticastBinding(networkInterface, groupAddress, port) }
                .onFailure {
                    FlashLog.w(TAG, "multicast bind failed on ${networkInterface.name}", it)
                }
                .getOrNull()
        }
    }

    private companion object {
        const val TAG = "MulticastTransport"
    }
}

/** One bound socket. Joins [group] on exactly one interface and sends out of it. */
private class JvmMulticastBinding(
    private val networkInterface: NetworkInterface,
    private val group: InetAddress,
    port: Int,
) : MulticastSocketBinding {

    private val buffer = ByteArray(MulticastProtocol.MAX_DATAGRAM_BYTES)
    private val target = InetSocketAddress(group, port)

    override val label: String = networkInterface.name

    /** Where the DR2 beacon also goes: this interface's directed-broadcast address(es), on the announce port. */
    private val broadcastDestinations: List<InetSocketAddress> = directedBroadcastTargets(networkInterface, port)

    override val broadcastTargets: List<String> = broadcastDestinations.map { it.address.hostAddress.orEmpty() }

    @Volatile private var broadcastFailureLogged = false

    private val socket: MulticastSocket = MulticastSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
        this.networkInterface = this@JvmMulticastBinding.networkInterface
        timeToLive = MULTICAST_TTL
        // DR2 sends to the subnet broadcast address from this same socket. Java's default is already true; it is
        // written out because a broadcast send on a socket without SO_BROADCAST fails with "Permission denied".
        broadcast = true
        // Loopback is deliberately NOT touched. `setLoopbackMode` is deprecated for removal, and its
        // boolean is inverted ("true" means "do not deliver my own multicasts to me") — so writing
        // intent here is how you get the opposite of what the comment says. Correctness does not
        // depend on it: if the platform default delivers our own datagram back, the transport
        // filters it by device id, and if it does not, there was nothing to filter.
        joinGroup(target, this@JvmMulticastBinding.networkInterface)
    }

    override fun send(payload: ByteArray): Boolean = runCatching {
        socket.send(DatagramPacket(payload, payload.size, target))
        true
    }.getOrElse { error ->
        FlashLog.w(TAG, "multicast send failed on ${networkInterface.name}", error)
        false
    }

    override fun sendBroadcast(payload: ByteArray): Boolean {
        var sent = false
        for (destination in broadcastDestinations) {
            runCatching {
                socket.send(DatagramPacket(payload, payload.size, destination))
                sent = true
            }.onFailure { error ->
                // Once per binding: a network that forbids broadcast would otherwise log every announcement.
                if (!broadcastFailureLogged) {
                    broadcastFailureLogged = true
                    FlashLog.w(TAG, "broadcast send failed on ${networkInterface.name} to ${destination.address.hostAddress}", error)
                }
            }
        }
        return sent
    }

    override fun receive(timeoutMs: Int): MulticastDatagram? = runCatching {
        socket.soTimeout = timeoutMs
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        MulticastDatagram(
            payload = packet.data.copyOf(packet.length),
            // The datagram's source IS the peer's address: nothing to resolve, so nothing to age.
            sourceAddress = packet.address?.hostAddress.orEmpty(),
            ipv6ScopeId = (packet.address as? Inet6Address)?.scopeId?.takeIf { it != 0 },
        )
    }.getOrElse { error ->
        // Two benign cases, neither of which is a failure:
        //
        //  - a timeout, which is how the loop stays responsive to stop()/rebind;
        //  - the socket being closed underneath a blocked receive, which is exactly what stop() and
        //    restartBrowsing() do. `MulticastSocket.close()` on a thread parked in `receive` raises
        //    `SocketException: Socket closed` (wrapping `AsynchronousCloseException`), and logging
        //    that as a warning printed a full stack trace on every single shutdown — observed
        //    2026-09-14 in the interop harness's exit, where it read like a crash. The loop exits on
        //    its own `browsing` flag, so this only has to stay quiet.
        val shuttingDown = error is SocketTimeoutException || socket.isClosed
        if (!shuttingDown) {
            FlashLog.w(TAG, "multicast receive failed on ${networkInterface.name}", error)
        }
        null
    }

    override fun close() {
        runCatching { socket.leaveGroup(target, networkInterface) }
        runCatching { socket.close() }
    }

    private companion object {
        /** TTL 1: announcements stay on the local link (Flash is LAN-only, ADR-025). */
        const val MULTICAST_TTL = 1
        const val TAG = "MulticastTransport"
    }
}

/**
 * Interfaces worth joining: up, multicast-capable, not loopback, with an IPv4 address.
 *
 * Mirror `RealJmdsBridge`'s enumeration rule, including its `supportsMulticast()` filter, which on
 * a desktop earns its place: a laptop carries several virtual adapters (Hyper-V, VirtualBox, Docker)
 * whose sockets would otherwise be bound and announced into nothing. IPv6-only interfaces are
 * skipped because the group is IPv4 and a link-local IPv6 source cannot be dialed without its scope
 * id. Also see the Android factory, which omits the multicast-capable check on purpose.
 */
private fun multicastCapableInterfaces(): List<NetworkInterface> =
    runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { candidate ->
            runCatching {
                candidate.isUp &&
                    candidate.supportsMulticast() &&
                    !candidate.isLoopback &&
                    candidate.inetAddresses.toList().any { it is Inet4Address }
            }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())

/**
 * The directed-broadcast destinations of [networkInterface] on [port] (DR2).
 *
 * Computed from each IPv4 address and its prefix length by [DirectedBroadcast.forIpv4], which refuses /31, /32 and
 * odd ranges. Only when the reported prefix length is impossible (outside 0..32) does it use the address the platform
 * reports as the broadcast address instead; some Android releases have returned bad prefix lengths from
 * `getInterfaceAddresses()`. A sane prefix that the arithmetic refuses stays refused. Point-to-point interfaces
 * (VPN tunnels) have no broadcast domain and yield nothing.
 */
private fun directedBroadcastTargets(networkInterface: NetworkInterface, port: Int): List<InetSocketAddress> =
    runCatching {
        if (networkInterface.isPointToPoint) {
            emptyList()
        } else {
            networkInterface.interfaceAddresses
                .mapNotNull { entry ->
                    val ip = entry.address as? Inet4Address ?: return@mapNotNull null
                    DirectedBroadcast.forIpv4(ip.address, entry.networkPrefixLength.toInt())
                        ?: entry.broadcast?.hostAddress?.takeIf { entry.networkPrefixLength.toInt() !in 0..32 }
                }
                .distinct()
                .map { InetSocketAddress(InetAddress.getByName(it), port) }
        }
    }.getOrDefault(emptyList())
