package com.transfer.flash.core.network.sweep

import android.content.Context
import android.net.ConnectivityManager
import com.transfer.flash.core.network.util.LanRouteChooser
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's [HostProbe]: a TCP connect on the route the dial will use, closed again without a byte sent.
 *
 * The socket is bound exactly as `WsTransferClient` binds a dial ([LanRouteChooser]): to the Wi-Fi/Ethernet
 * network that is on-link for the host, to nothing for this device's own hotspot, so a hotspot host's sweep
 * of its clients does not leave through the router network (ERROR-035). A probe that leaves by a different
 * route than the dial would find hosts the dial cannot reach.
 *
 * The route is chosen once per probe. That costs a few `ConnectivityManager` reads per host, which is
 * negligible at most 254 hosts a sweep and one sweep per network per 10 minutes at most.
 *
 * @param context nullable for pure-JVM tests, as in `WsTransferClient`: null binds no network.
 */
public class TcpHostProbe(context: Context?) : HostProbe {
    private val routes = LanRouteChooser(
        context?.applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager,
    )

    override suspend fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        val socket = try {
            routes.choose(host).network?.socketFactory?.createSocket() ?: Socket()
        } catch (_: IOException) {
            return@withContext false
        }
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
