@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.tcp

import android.content.Context
import com.transfer.flash.core.common.annotation.FlashInternalApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class LanConnectionProbe(
    context: Context?,
    private val localDeviceId: String,
    private val localFriendlyName: String,
    private val logger: com.transfer.flash.core.network.tcp.LanSessionLogger = com.transfer.flash.core.network.tcp.LanSessionLogger.ANDROID,
) {
    /** Null on JVM test environments; [findLanNetwork] then yields the default socket. */
    private val connectivityManager =
        context?.applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    public suspend fun probe(hostAddress: String, port: Int, peerDeviceId: String? = null): Result<LanProbeHello> =
        probeEndpoint(hostAddress, port, peerDeviceId)

    public suspend fun probeEndpoint(hostAddress: String, port: Int, peerDeviceId: String? = null): Result<LanProbeHello> = withContext(Dispatchers.IO) {
        connectSession(hostAddress, port, peerDeviceId) { _, _ -> }.map { session ->
            session.close("Probe complete")
            session.peerInfo
        }.onSuccess {
            logger.log(LanSessionLogger.INFO, TAG, "LAN probe connected address=$hostAddress:$port", null)
        }
    }

    public suspend fun connectSession(
        hostAddress: String,
        port: Int,
        peerDeviceId: String? = null,
        onDisconnected: (LanProbeHello, String) -> Unit,
    ): Result<LanSession> = withContext(Dispatchers.IO) {
        runCatching {
            val network = findLanNetwork()
            logger.log(
                LanSessionLogger.INFO,
                TAG,
                "LAN probe connecting address=$hostAddress:$port " +
                    "deviceId=${peerDeviceId ?: "unknown"} network=${network?.networkHandle ?: "default"}",
                null,
            )
            val socket = createSocket(network)
            socket.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer.println(LanProbeMessages.hello(PROTOCOL_VERSION, localDeviceId, localFriendlyName))

            val response = reader.readLine()
                ?: error("No response from $hostAddress:$port")
            val parsed = LanProbeMessages.parseOk(response)
                ?: error("Malformed response from $hostAddress:$port")
            LanSession(
                socket = socket,
                reader = reader,
                writer = writer,
                localDeviceId = localDeviceId,
                localFriendlyName = localFriendlyName,
                peerInfo = parsed,
                onDisconnected = onDisconnected,
                logger = logger,
            ).also { it.start() }
        }.onFailure { error ->
            logger.log(LanSessionLogger.WARN, TAG, "LAN probe failed address=$hostAddress:$port", error)
        }
    }

    public suspend fun notifyDisconnect(hostAddress: String, port: Int, peerDeviceId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val network = findLanNetwork()
            logger.log(
                LanSessionLogger.INFO,
                TAG,
                "LAN disconnect notifying address=$hostAddress:$port " +
                    "deviceId=${peerDeviceId ?: "unknown"} network=${network?.networkHandle ?: "default"}",
                null,
            )
            createSocket(network).use { socket ->
                socket.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS

                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(LanProbeMessages.disconnect(PROTOCOL_VERSION, localDeviceId, localFriendlyName))
            }
        }.onFailure { error ->
            logger.log(LanSessionLogger.WARN, TAG, "LAN disconnect notify failed address=$hostAddress:$port", error)
        }
    }

    private fun createSocket(network: Network?): Socket {
        return network?.socketFactory?.createSocket() ?: Socket()
    }

    private fun findLanNetwork(): Network? {
        if (connectivityManager == null) return null
        return connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
    }

    public companion object {
        public const val TAG: String = "LAN"
        public const val PROTOCOL_VERSION: Int = 1
        public const val CONNECT_TIMEOUT_MS: Int = 4_000
        public const val READ_TIMEOUT_MS: Int = 4_000
    }
}
