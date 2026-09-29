package com.transfer.flash.core.network.sweep

/**
 * One TCP reachability check, the only network operation a sweep performs itself.
 *
 * It opens a connection and closes it again without sending a byte. Everything else (TLS, HELLO,
 * identity) is the normal dial, which the planner starts for each hit. Platform twins:
 * `TcpHostProbe` in `androidMain` (binds the socket to the on-link network, like the dial does) and in
 * `jvmMain`.
 */
public fun interface HostProbe {
    /**
     * True when a TCP connect to [host]:[port] succeeds within [timeoutMs]. A refused, unreachable or
     * timed-out connect is `false`, not an exception. Only cancellation may throw.
     */
    public suspend fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean
}
