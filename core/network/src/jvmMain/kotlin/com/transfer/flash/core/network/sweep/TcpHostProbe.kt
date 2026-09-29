package com.transfer.flash.core.network.sweep

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The desktop's [HostProbe]: a plain TCP connect, closed again without a byte sent.
 *
 * The operating system's routing table picks the interface, the same as for the dial that follows
 * (the desktop dial binds nothing either). Twin of the Android class of the same name, which binds the
 * socket to the on-link network first; `core:network` declares no expect/actual.
 *
 * A blocking connect cannot be interrupted, so cancelling a sweep waits for probes in flight, each at most
 * its own timeout (300 ms by default).
 */
public class TcpHostProbe : HostProbe {
    override suspend fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
