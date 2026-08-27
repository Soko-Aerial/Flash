@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.tcp

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public class LanProbeServer(
    private val deviceId: String,
    private val friendlyName: String,
    private val onPeerProbed: (LanProbeHello, String, LanSession) -> Unit = { _, _, _ -> },
    private val onPeerDisconnected: (LanProbeHello) -> Unit = {},
    private val logger: com.transfer.flash.core.network.tcp.LanSessionLogger = com.transfer.flash.core.network.tcp.LanSessionLogger.ANDROID,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    public val port: Int
        get() = serverSocket?.localPort ?: 0

    public suspend fun start(): Int = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext port

        val socket = createServerSocket().apply {
            reuseAddress = true
            soTimeout = ACCEPT_TIMEOUT_MS
        }
        serverSocket = socket
        running.set(true)
        acceptJob = scope.launch {
            acceptLoop(socket)
        }
        logger.log(LanSessionLogger.INFO, TAG, "LAN probe server listening on port=${socket.localPort}", null)
        socket.localPort
    }

    public fun stop() {
        running.set(false)
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (running.get() && scope.isActive) {
            val client = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: Exception) {
                if (running.get()) {
                    logger.log(LanSessionLogger.WARN, TAG, "LAN probe accept failed", error)
                }
                break
            }

            scope.launch {
                handleClient(client)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val client = socket
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)
            val line = reader.readLine()
            val disconnect = line?.let(LanProbeMessages::parseDisconnect)
            if (disconnect != null) {
                onPeerDisconnected(disconnect)
                logger.log(LanSessionLogger.INFO, TAG, "LAN peer disconnected peerId=${disconnect.deviceId}", null)
                client.close()
                return@withContext
            }

            val hello = line?.let(LanProbeMessages::parseHello)
            if (hello == null) {
                logger.log(LanSessionLogger.WARN, TAG, "LAN probe rejected malformed hello", null)
                client.close()
                return@withContext
            }
            writer.println(LanProbeMessages.ok(PROTOCOL_VERSION, deviceId, friendlyName))
            val session = LanSession(
                socket = client,
                reader = reader,
                writer = writer,
                localDeviceId = deviceId,
                localFriendlyName = friendlyName,
                peerInfo = hello,
                onDisconnected = { peer, _ -> onPeerDisconnected(peer) },
            ).also { it.start() }
            onPeerProbed(hello, client.inetAddress.hostAddress.orEmpty(), session)
            logger.log(LanSessionLogger.INFO, TAG, "LAN probe completed peerId=${hello.deviceId}", null)
        } catch (error: Exception) {
            logger.log(LanSessionLogger.WARN, TAG, "LAN probe client handling failed", error)
            runCatching { client.close() }
        }
    }

    private fun createServerSocket(): ServerSocket {
        return runCatching { ServerSocket(DEFAULT_PORT) }
            .onFailure { error ->
                logger.log(LanSessionLogger.WARN, TAG, "Default LAN probe port unavailable; using dynamic port", error)
            }
            .getOrElse { ServerSocket(0) }
    }

    public companion object {
        public const val TAG: String = "LAN"
        public const val PROTOCOL_VERSION: Int = 1
        public const val DEFAULT_PORT: Int = 45821
        public const val ACCEPT_TIMEOUT_MS: Int = 1_000
        public const val READ_TIMEOUT_MS: Int = 3_000
    }
}
