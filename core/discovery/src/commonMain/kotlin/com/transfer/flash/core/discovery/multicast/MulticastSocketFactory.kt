package com.transfer.flash.core.discovery.multicast

/**
 * One received datagram, with the address it came from.
 *
 * The source address IS the peer's address, and that is the load-bearing difference from the
 * DNS-SD transports: there is no SRV/A record to resolve, so there is no stale-address state and no
 * window in which a peer resolves to somewhere it no longer is. `ipv6ScopeId` carries the interface
 * index a link-local IPv6 source arrived on, without which that address cannot be dialed at all.
 */
public data class MulticastDatagram(
    val payload: ByteArray,
    val sourceAddress: String,
    val ipv6ScopeId: Int? = null,
) {
    // ByteArray compares by identity in a generated data-class equals, so both are written out:
    // content-based equality is the only one that means anything for a datagram. (Tests compare
    // these to pin what a fake socket hands the transport.)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MulticastDatagram) return false
        return sourceAddress == other.sourceAddress &&
            ipv6ScopeId == other.ipv6ScopeId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + sourceAddress.hashCode()
        return 31 * result + (ipv6ScopeId ?: 0)
    }
}

/**
 * One bound UDP multicast socket on one network interface (see [MulticastTransport]).
 *
 * Deliberately shaped like `androidMain`'s `NsdManagerBridge` and `jvmMain`'s `JmdsBridge`: the
 * transport talks only to this interface, so `MulticastTransport` is pure `commonMain` logic that
 * unit-tests against a fake with no network at all.
 *
 * **Blocking by contract.** [receive] blocks up to its timeout, and the caller owns the dispatcher
 * (the transport runs its receive loop off the main thread). Making these suspending would buy
 * nothing — the underlying platform calls are blocking — and would make the transport untestable on
 * a single-threaded test dispatcher.
 *
 * Not thread-safe: [send] and [receive] may be called from different coroutines on the same socket
 * (the announce loop and the receive loop), which real datagram sockets permit, but [close] must not
 * race a `send` on the same binding from two threads.
 */
public interface MulticastSocketBinding {

    /** Interface this socket is bound to, for logs and for the endpoint's `serviceName`. */
    public val label: String

    /**
     * Sends one datagram to the multicast group.
     *
     * @return false when the send failed. A failed send is not fatal: one interface going away must
     *   not take discovery down on the others, so the caller logs and continues.
     */
    public fun send(payload: ByteArray): Boolean

    /**
     * Blocks until a datagram arrives or [timeoutMs] elapses.
     *
     * @return the datagram and the address it came FROM, or null on timeout, close, or a transient
     *   receive error. A transient error must NOT be surfaced as termination: on Windows an ICMP
     *   error from a previous send surfaces on the next receive of the same socket, and treating
     *   that as "socket dead" makes discovery flap.
     */
    public fun receive(timeoutMs: Int): MulticastDatagram?

    /** Releases the socket. Idempotent; after it, [send] and [receive] must not be called. */
    public fun close()
}

/**
 * Binds the sockets [MulticastTransport] announces on and listens to.
 *
 * An interface rather than an `expect fun` because the Android implementation needs a `Context`
 * (for the `WifiManager` multicast lock) and the desktop one does not — the composition root that
 * has the `Context` constructs the right factory, exactly as it does for `NsdTransport` and
 * `JmdsTransport`.
 */
public interface MulticastSocketFactory {

    /**
     * Binds one socket per usable interface and joins [group] on [port].
     *
     * **One socket per interface, never one wildcard socket.** A single unbound multicast socket
     * joins the group on exactly one interface, chosen by the OS routing table — on a laptop with
     * Wi-Fi + Ethernet + Hyper-V switches that is routinely the wrong one, and the transport then
     * silently hears nobody. This is the same lesson `RealJmdsBridge` learned for mDNS.
     *
     * @return the bindings that succeeded; empty when no interface could be used, which the
     *   transport reports as a failure rather than pretending to browse.
     */
    public fun bind(group: String, port: Int): List<MulticastSocketBinding>

    /**
     * Releases process-wide resources held for the bindings — on Android that is the `WifiManager`
     * multicast lock, which is chipset-wide and must not outlive a stopped transport.
     *
     * Called whenever the transport drops its sockets, including the rebind inside
     * `restartBrowsing`. Default no-op: a platform with nothing to release (the desktop's plain
     * `MulticastSocket`s) needs no code for this.
     */
    public fun close() {}
}
