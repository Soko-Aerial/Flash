package com.transfer.flash.core.discovery.jmdns

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Desktop mDNS seam, deliberately shaped like `androidMain`'s `NsdManagerBridge` (Phase 08): the
 * transport talks only to this interface and to the neutral DTOs below, never to
 * `javax.jmdns.*`. That is what makes [JmdnsTransport] unit-testable on a CI box with no
 * multicast — a fake bridge records calls and replays callbacks.
 *
 * `public` for the same reason `NsdManagerBridge` is: the two radio bridges are siblings and
 * their ABIs stay symmetric, so a downstream consumer can supply its own implementation.
 *
 * Not thread-safe on its own. [JmdnsTransport] serialises every call through its own lock;
 * JmDNS's own callback threads only ever enter through [JmdnsBrowseEvents].
 */
public interface JmdnsBridge {

    /** Binds one mDNS responder per usable interface address. Throws when none can be bound. */
    public fun open()

    /** Registers (or re-registers) this device's service record on every bound responder. */
    public fun register(request: JmdnsAdvertiseRequest)

    /** Withdraws every record this bridge registered. Idempotent. */
    public fun unregisterAll()

    /** Subscribes to [serviceType] on every bound responder. Idempotent per type. */
    public fun startBrowse(serviceType: String, events: JmdnsBrowseEvents)

    /** Unsubscribes from [serviceType]. Idempotent. */
    public fun stopBrowse(serviceType: String)

    /**
     * Asks the responders to resolve one instance. BLOCKING — JmDNS performs the query on the
     * calling thread — so callers must not invoke it from a JmDNS callback thread.
     */
    public fun requestServiceInfo(serviceType: String, serviceName: String)

    /** Closes every responder and drops all listeners. Idempotent. */
    public fun close()
}

/** What to advertise. Mirrors `NsdManagerBridge`'s `AdvertiseRequest`. */
public data class JmdnsAdvertiseRequest(
    val serviceType: String,
    val serviceName: String,
    val port: Int,
    val attributes: Map<String, String>,
)

/**
 * A resolved peer instance, stripped of every JmDNS type.
 *
 * @param hostAddress an IPv4 literal when the peer published one. Flash dials
 *   `hostAddress:port` with a plain socket, and an IPv6 link-local literal carries a scope id
 *   (`fe80::1%eth0`) that such a dial cannot use — so IPv4 is preferred and IPv6 is only a
 *   fallback. Null when the record resolved with no address at all.
 */
public data class JmdnsResolvedService(
    val hostAddress: String?,
    val port: Int,
    val serviceName: String,
    val attributes: Map<String, String>,
)

/**
 * Browse callbacks, in JmDNS's three-stage shape. `onServiceAdded` carries no address or TXT
 * data — JmDNS announces the instance first and resolves it afterwards — so a consumer must
 * treat it purely as "ask for details now".
 */
public interface JmdnsBrowseEvents {
    public fun onServiceAdded(serviceType: String, serviceName: String)
    public fun onServiceRemoved(serviceName: String)
    public fun onServiceResolved(service: JmdnsResolvedService)
}

/**
 * Production bridge over JmDNS 3.5.12.
 *
 * **One responder per interface address, chosen explicitly.** `InetAddress.getLocalHost()` — and
 * equally `JmDNS.create()` with no argument, which resolves the local host internally — returns
 * ONE arbitrary adapter on a multi-homed host. On a laptop with Wi-Fi + Ethernet + a VPN or
 * Hyper-V virtual switch that is routinely the wrong one, and the responder then answers on a
 * network no peer is on. Phase 14 calls this out explicitly, so this bridge enumerates
 * [NetworkInterface] itself and binds every usable address.
 *
 * Consequences of binding several responders, all of them intended:
 * - the service is advertised on every network the host is attached to, matching Android NSD;
 * - one peer can be announced once per responder. Duplicate sightings dedup in
 *   `EndpointDirectory` to `Diff.Unchanged`, which the transport publishes as
 *   `FlashTransportEvent.Presence` — never a second `Found`.
 *
 * OS-neutral (CONVENTIONS 2026-09-03 amendment): no path literals, no `%USERPROFILE%`, no
 * reverse-DNS lookup. The mDNS hostname is derived from the bound address, so a host with a
 * broken DNS suffix cannot stall `open()`.
 */
public class RealJmdnsBridge(
    private val addresses: () -> List<InetAddress> = ::multicastCapableAddresses,
    private val logWarn: (String, Throwable?) -> Unit = { _, _ -> },
) : JmdnsBridge {

    private val responders = mutableListOf<JmDNS>()
    private val listeners = mutableMapOf<String, ServiceListener>()

    override fun open() {
        if (responders.isNotEmpty()) return
        val candidates = runCatching(addresses).getOrElse {
            logWarn("network interface enumeration failed", it)
            emptyList()
        }
        for (address in candidates) {
            runCatching { JmDNS.create(address, mdnsHostnameFor(address)) }
                .onSuccess { responders += it }
                .onFailure { logWarn("JmDNS bind failed on ${address.hostAddress}", it) }
        }
        if (responders.isEmpty()) {
            // Every explicit bind failed, or the host exposes no multicast-capable IPv4 address.
            // One unbound attempt so a plain single-NIC machine still works rather than reporting
            // the radio dead.
            runCatching { JmDNS.create() }
                .onSuccess { responders += it }
                .onFailure { logWarn("unbound JmDNS fallback failed", it) }
        }
        if (responders.isEmpty()) throw IOException("no usable mDNS interface on this host")
    }

    override fun register(request: JmdnsAdvertiseRequest) {
        for (responder in responders) {
            // A ServiceInfo remembers the JmDNS that registered it, so handing the SAME object to
            // a second responder throws IllegalStateException. Build one per responder.
            val info = ServiceInfo.create(
                request.serviceType,
                request.serviceName,
                request.port,
                /* weight = */ 0,
                /* priority = */ 0,
                request.attributes,
            )
            runCatching { responder.registerService(info) }
                .onFailure { logWarn("registerService failed on ${responder.hostName}", it) }
        }
    }

    override fun unregisterAll() {
        responders.forEach { responder ->
            runCatching { responder.unregisterAllServices() }
                .onFailure { logWarn("unregisterAllServices failed", it) }
        }
    }

    override fun startBrowse(serviceType: String, events: JmdnsBrowseEvents) {
        if (listeners.containsKey(serviceType)) return
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                events.onServiceAdded(event.type, event.name)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                events.onServiceRemoved(event.name)
            }

            override fun serviceResolved(event: ServiceEvent) {
                events.onServiceResolved(event.info.toNeutral())
            }
        }
        listeners[serviceType] = listener
        responders.forEach { it.addServiceListener(serviceType, listener) }
    }

    override fun stopBrowse(serviceType: String) {
        val listener = listeners.remove(serviceType) ?: return
        responders.forEach { runCatching { it.removeServiceListener(serviceType, listener) } }
    }

    override fun requestServiceInfo(serviceType: String, serviceName: String) {
        responders.forEach { responder ->
            // `persistent = true` keeps the resolution subscribed, so a peer that later changes
            // address re-fires serviceResolved instead of going stale until the next browse.
            runCatching { responder.requestServiceInfo(serviceType, serviceName, true) }
                .onFailure { logWarn("requestServiceInfo($serviceName) failed", it) }
        }
    }

    override fun close() {
        listeners.keys.toList().forEach { stopBrowse(it) }
        responders.forEach { runCatching { it.close() } }
        responders.clear()
    }
}

/**
 * Copies a [ServiceInfo] into [JmdnsResolvedService].
 *
 * JmDNS exposes TXT data as `getPropertyNames(): Enumeration<String>` + `getPropertyString(key)`.
 * PHASE-14's sample code reads `info.txtMap`, which does not exist on this class — verified with
 * `javap` against `jmdns-3.5.12.jar`.
 *
 * `internal` rather than `private` so `JmdnsBridgeAttributeTest` can drive it directly. That test
 * is the only coverage this function has: it cannot be reached from a same-host multicast test,
 * because JmDNS ignores records whose hostname matches its own
 * ([multicastCapableAddresses] + [mdnsHostnameFor] give two bridges on one machine the same
 * hostname, so they never see each other).
 */
internal fun ServiceInfo.toNeutral(): JmdnsResolvedService {
    val attributes = LinkedHashMap<String, String>()
    val keys = propertyNames
    while (keys.hasMoreElements()) {
        val key = keys.nextElement() ?: continue
        getPropertyString(key)?.let { attributes[key] = it }
    }
    return JmdnsResolvedService(
        hostAddress = inet4Addresses.firstOrNull()?.hostAddress
            ?: hostAddresses.firstOrNull(),
        port = port,
        serviceName = name,
        attributes = attributes,
    )
}

/**
 * Every IPv4 address on an interface that is up, not loopback and multicast-capable.
 *
 * IPv4 only, and link-local (169.254/16 APIPA) excluded: mDNS needs a routable address peers can
 * dial back on, and JmDNS binds one socket per address, so adding unusable ones only produces
 * responders that never hear anything. Sequences are guarded per interface because
 * `isUp`/`supportsMulticast` throw `SocketException` on adapters that disappear mid-enumeration
 * (common with VPN and virtual switches).
 */
internal fun multicastCapableAddresses(): List<InetAddress> =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { nic ->
            runCatching { nic.isUp && !nic.isLoopback && nic.supportsMulticast() }
                .getOrDefault(false)
        }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filterNot { it.isLinkLocalAddress || it.isAnyLocalAddress }
        .distinct()
        .toList()

/**
 * mDNS hostname for a bound address, derived from the address itself.
 *
 * Deliberately not `InetAddress.getHostName()`: that performs a reverse lookup which can block
 * for seconds on a host with an unreachable DNS server, and Phase 14 forbids depending on
 * host-name resolution for adapter selection. The derived form is unique per address, so several
 * responders on one machine cannot collide.
 */
private fun mdnsHostnameFor(address: InetAddress): String =
    "flash-" + address.hostAddress.replace('.', '-').replace(':', '-')
