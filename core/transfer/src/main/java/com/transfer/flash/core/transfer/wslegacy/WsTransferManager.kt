@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.transfer.wslegacy

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import com.transfer.flash.core.network.util.LocalNetworkAddresses
import com.transfer.flash.core.network.ws.WebSocketCodec
import com.transfer.flash.core.network.ws.WsConnection
import com.transfer.flash.core.network.ws.WsTransferClient
import com.transfer.flash.core.network.ws.WsTransferServer
import com.transfer.flash.core.security.trust.AndroidPreferencesTrustStore
import com.transfer.flash.core.transfer.protocol.WsTransferMessages
import com.transfer.flash.core.transfer.model.WsDiscoveredDevice
import com.transfer.flash.core.transfer.model.WsPeer
import com.transfer.flash.core.transfer.model.WsTransferDirection
import com.transfer.flash.core.transfer.model.WsTransferItem
import com.transfer.flash.core.transfer.model.WsTransferStatus
import com.transfer.flash.core.transfer.model.WsTransferUiState
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * LEGACY relocation (C5.1): pre-chunked whole-file 64KiB-frame engine retained as
 * fallback/reference; superseded by transfer.chunked pipelines (C5.3+) — scheduled for
 * deletion after parity.
 *
 * Experimental WebSocket transfer orchestrator (side track, not the main protocol).
 *
 * Multi-peer model: every device runs a [WsTransferServer] and can open any number
 * of outbound [WsTransferClient] connections, so three devices can fully mesh —
 * each connects to the other two. Peers are keyed by the device ID exchanged in the
 * post-upgrade `FLASH_WS_HELLO`; if a pair ends up with one connection in each
 * direction, the outbound connection is preferred for sending and the inbound one
 * is kept as a fallback that gets promoted when the primary drops.
 *
 * File transfer: `FLASH_FILE_START` (text), raw bytes as 64 KiB binary frames in
 * order, `FLASH_FILE_END` (text), then the receiver answers `FLASH_FILE_ACK`.
 * One active transfer per connection keeps the simple ordered-stream design valid.
 *
 * Identity seam (C7): device identity is constructor-injected (`localDeviceId`,
 * `localFriendlyName`) instead of read from the `:app` `AppIdentity`; the future
 * `:core:engine` / `:app` wiring passes the current identity at construction.
 */
internal class WsTransferManager(
    context: Context,
    localDeviceId: String,
    localFriendlyName: String,
) : WsConnection.Listener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val localNetworkAddresses = LocalNetworkAddresses(appContext)
    private val deviceId: String = localDeviceId
    private val friendlyName: String = localFriendlyName

    private val lock = Any()
    private val connections = mutableListOf<WsConnection>()
    private val outboundConnections = mutableSetOf<WsConnection>()
    private val helloByConnection = mutableMapOf<WsConnection, WsTransferMessages.Hello>()
    private val primaryByPeerId = mutableMapOf<String, WsConnection>()
    private val outboundByPeerId = mutableMapOf<String, WsConnection>()
    private val peerNames = mutableMapOf<String, String>()
    private val receivingByConnection = mutableMapOf<WsConnection, ReceivingTransfer>()
    private val sendingConnections = mutableSetOf<WsConnection>()

    private val server = WsTransferServer(
        connectionListener = this,
        onConnection = ::registerInboundConnection,
    )
    private val client = WsTransferClient(appContext, connectionListener = this)
    private val pairingStore = WsPairingStore(AndroidPreferencesTrustStore(appContext))
    private val discovery = WsDiscovery(
        context = appContext,
        localDeviceId = deviceId,
        friendlyName = friendlyName,
        onDeviceFound = ::onDeviceDiscovered,
        onDeviceLost = ::onDiscoveredLost,
        onStatusChanged = ::onDiscoveryStatus,
    )

    private val discoveredById = mutableMapOf<String, LegacyDiscoveredDevice>()
    private val connectingDeviceIds = mutableSetOf<String>()
    private val autoConnectAttempts = mutableMapOf<String, Long>()

    private val _state = MutableStateFlow(
        WsTransferUiState(
            localDeviceId = deviceId,
            friendlyName = friendlyName,
        )
    )
    val state: StateFlow<WsTransferUiState> = _state

    init {
        // Discovery stays on for the lifetime of the manager so newly joined devices
        // just appear; the server auto-starts so this device is immediately connectable.
        loadExistingReceivedFiles()
        scope.launch(Dispatchers.IO) {
            startServer()
            discovery.startDiscovery()
        }
    }

    fun startServer() {
        if (server.isRunning) return
        runCatching { server.start() }
            .onSuccess { port ->
                discovery.startAdvertising(port)
                _state.update {
                    it.copy(
                        isServerRunning = true,
                        listenPort = port,
                        localAddresses = localNetworkAddresses.ipv4Addresses(),
                        status = "Listening on port $port — visible to nearby Flash devices",
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(status = "Server start failed: ${error.message ?: error::class.java.simpleName}")
                }
            }
    }

    fun stopServer() {
        server.stop()
        discovery.stopAdvertising()
        _state.update {
            it.copy(
                isServerRunning = false,
                listenPort = 0,
                localAddresses = emptyList(),
                status = "Server stopped (discovery stays on; existing connections stay open)",
            )
        }
    }

    fun updateHostInput(value: String) {
        _state.update { it.copy(hostInput = value.trim()) }
    }

    fun updatePortInput(value: String) {
        _state.update { it.copy(portInput = value.filter(Char::isDigit).take(5)) }
    }

    fun connectToPeer() {
        val current = _state.value
        val host = current.hostInput.trim()
        val port = current.portInput.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65_535) {
            _state.update { it.copy(lastConnectResult = "Enter a valid IP address and port.") }
            return
        }
        scope.launch {
            _state.update { it.copy(isConnecting = true, lastConnectResult = "Connecting to $host:$port...") }
            runCatching { client.connect(host, port) }
                .onSuccess { connection ->
                    registerConnection(connection, outbound = true)
                    _state.update { it.copy(lastConnectResult = "WebSocket open to $host:$port — pairing...") }
                }
                .onFailure { error ->
                    Log.w(TAG, "WS connect failed address=$host:$port", error)
                    _state.update {
                        it.copy(lastConnectResult = "Connection failed: ${error.message ?: error::class.java.simpleName}")
                    }
                }
            _state.update { it.copy(isConnecting = false) }
        }
    }

    fun disconnectPeer(deviceId: String) {
        val toClose = synchronized(lock) {
            helloByConnection.entries
                .filter { it.value.deviceId == deviceId }
                .map { it.key }
        }
        toClose.forEach { it.close("Disconnected") }
    }

    /** Sends [uri] to one peer ([targetPeerId]) or to every connected peer when null. */
    fun sendFile(uri: Uri, targetPeerId: String?) {
        val targets = synchronized(lock) {
            if (targetPeerId == null) {
                primaryByPeerId.values.toList()
            } else {
                primaryByPeerId[targetPeerId]?.let(::listOf).orEmpty()
            }
        }
        if (targets.isEmpty()) {
            _state.update { it.copy(status = "No connected peer to send to") }
            return
        }
        scope.launch(Dispatchers.IO) {
            targets.forEach { connection -> sendFileTo(connection, uri) }
        }
    }

    /** Connects to a discovered device by ID (manual tap from the discovery list). */
    fun connectToDiscoveredPeer(deviceId: String) {
        val device = synchronized(lock) { discoveredById[deviceId] } ?: return
        val added = synchronized(lock) { connectingDeviceIds.add(deviceId) }
        if (!added) return
        refreshDiscovered()
        connectToDevice(device)
    }

    fun close() {
        discovery.stopAll()
        server.stop()
        synchronized(lock) { connections.toList() }.forEach { it.close("Manager closed") }
    }

    // region connection registration

    private fun registerInboundConnection(connection: WsConnection) {
        registerConnection(connection, outbound = false)
    }

    private fun registerConnection(connection: WsConnection, outbound: Boolean) {
        synchronized(lock) {
            connections += connection
            if (outbound) outboundConnections += connection
        }
        connection.start()
        // Async: this method can run on any thread (connect continuation),
        // and StrictMode forbids socket writes on the main thread.
        connection.sendTextAsync(WsTransferMessages.hello(deviceId, friendlyName))
    }

    private fun registerPeerHello(connection: WsConnection, hello: WsTransferMessages.Hello) {
        if (hello.deviceId == deviceId) {
            connection.close("Connected to self")
            _state.update { it.copy(lastConnectResult = "That address is this device — connect to another phone.") }
            return
        }
        val outbound = synchronized(lock) {
            helloByConnection[connection] = hello
            peerNames[hello.deviceId] = hello.friendlyName
            val isOutbound = connection in outboundConnections
            if (isOutbound) outboundByPeerId[hello.deviceId] = connection
            if (primaryByPeerId[hello.deviceId] == null || isOutbound) {
                primaryByPeerId[hello.deviceId] = connection
            }
            isOutbound
        }
        // Pairing is auto-accepted on a successful hello exchange; both sides persist it,
        // so a rediscovered peer is recognized and can reconnect immediately next time.
        pairingStore.markPaired(hello.deviceId, hello.friendlyName)
        refreshPeers()
        refreshDiscovered()
        Log.i(TAG, "WS paired peerId=${hello.deviceId} name=${hello.friendlyName} outbound=$outbound")
        _state.update {
            it.copy(
                status = "Paired with ${hello.friendlyName} (${it.peers.size} peer${if (it.peers.size == 1) "" else "s"})",
                lastConnectResult = if (outbound) "Paired with ${hello.friendlyName}" else it.lastConnectResult,
            )
        }
    }

    // endregion

    // region discovery + auto-reconnect

    private fun onDeviceDiscovered(device: LegacyDiscoveredDevice) {
        synchronized(lock) { discoveredById[device.deviceId] = device }
        refreshDiscovered()
        maybeAutoConnect(device)
    }

    private fun onDiscoveredLost(serviceName: String) {
        synchronized(lock) {
            val entry = discoveredById.entries.firstOrNull { it.value.serviceName == serviceName }
            if (entry != null) discoveredById.remove(entry.key)
        }
        refreshDiscovered()
    }

    private fun onDiscoveryStatus(status: String) {
        Log.i(TAG, "WS discovery status: $status")
    }

    /** Already-paired devices reconnect automatically as soon as their server reappears. */
    private fun maybeAutoConnect(device: LegacyDiscoveredDevice) {
        if (!pairingStore.isPaired(device.deviceId)) return
        val shouldConnect = synchronized(lock) {
            val alreadyConnected = primaryByPeerId.containsKey(device.deviceId)
            val alreadyConnecting = device.deviceId in connectingDeviceIds
            val lastAttempt = autoConnectAttempts[device.deviceId] ?: 0L
            val suppressed = SystemClock.uptimeMillis() - lastAttempt < AUTO_CONNECT_SUPPRESS_MS
            if (!alreadyConnected && !alreadyConnecting && !suppressed) {
                connectingDeviceIds += device.deviceId
                autoConnectAttempts[device.deviceId] = SystemClock.uptimeMillis()
                true
            } else {
                false
            }
        }
        if (shouldConnect) {
            Log.i(TAG, "WS auto-connecting to paired device ${device.deviceId}")
            refreshDiscovered()
            connectToDevice(device)
        }
    }

    private fun connectToDevice(device: LegacyDiscoveredDevice) {
        scope.launch {
            runCatching { client.connect(device.hostAddress, device.port) }
                .onSuccess { connection ->
                    registerConnection(connection, outbound = true)
                    _state.update {
                        it.copy(lastConnectResult = "WebSocket open to ${device.friendlyName} — pairing...")
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "WS connect failed device=${device.deviceId}", error)
                    _state.update {
                        it.copy(
                            lastConnectResult = "Connection to ${device.friendlyName} failed: " +
                                (error.message ?: error::class.java.simpleName),
                        )
                    }
                }
            synchronized(lock) { connectingDeviceIds -= device.deviceId }
            refreshDiscovered()
        }
    }

    private fun refreshDiscovered() {
        val list = synchronized(lock) {
            discoveredById.values.map { device ->
                WsDiscoveredDevice(
                    deviceId = device.deviceId,
                    friendlyName = device.friendlyName,
                    address = "${device.hostAddress}:${device.port}",
                    paired = pairingStore.isPaired(device.deviceId),
                    connected = primaryByPeerId.containsKey(device.deviceId),
                    connecting = device.deviceId in connectingDeviceIds,
                )
            }.sortedBy { it.friendlyName.lowercase() }
        }
        _state.update { it.copy(discovered = list) }
    }

    // endregion

    // region WsConnection.Listener

    override fun onTextMessage(connection: WsConnection, text: String) {
        WsTransferMessages.parseHello(text)?.let { hello ->
            registerPeerHello(connection, hello)
            return
        }
        WsTransferMessages.parseFileStart(text)?.let { start ->
            beginReceiving(connection, start)
            return
        }
        WsTransferMessages.parseFileEnd(text)?.let { end ->
            finishReceiving(connection, end)
            return
        }
        WsTransferMessages.parseFileAck(text)?.let { ack ->
            handleAck(ack)
            return
        }
        Log.w(TAG, "WS ignored unknown message remote=${connection.remoteLabel}")
    }

    override fun onBinaryMessage(connection: WsConnection, data: ByteArray) {
        val receiving = synchronized(lock) { receivingByConnection[connection] }
        if (receiving == null) {
            Log.w(TAG, "WS dropped ${data.size} bytes with no active transfer remote=${connection.remoteLabel}")
            return
        }
        try {
            receiving.output.write(data)
            receiving.received += data.size
            updateTransferProgress(receiving.transferId, receiving.received)
        } catch (error: IOException) {
            failReceiving(connection, "Write failed: ${error.message}")
        }
    }

    override fun onConnectionClosed(connection: WsConnection, reason: String) {
        val hello = synchronized(lock) {
            connections -= connection
            outboundConnections -= connection
            sendingConnections -= connection
            val removedHello = helloByConnection.remove(connection)
            receivingByConnection.remove(connection)?.let { receiving ->
                receiving.closeQuietly()
                markTransferFailed(receiving.transferId, "Connection lost mid-transfer")
            }
            if (removedHello != null) {
                if (primaryByPeerId[removedHello.deviceId] == connection) {
                    primaryByPeerId.remove(removedHello.deviceId)
                    val replacement = helloByConnection.entries
                        .firstOrNull { it.key in connections && it.value.deviceId == removedHello.deviceId }
                    if (replacement != null) {
                        primaryByPeerId[removedHello.deviceId] = replacement.key
                    }
                }
                if (outboundByPeerId[removedHello.deviceId] == connection) {
                    outboundByPeerId.remove(removedHello.deviceId)
                }
                if (helloByConnection.values.none { it.deviceId == removedHello.deviceId }) {
                    peerNames.remove(removedHello.deviceId)
                }
            }
            removedHello
        }
        if (hello != null) {
            failSendingTransfersFor(hello.friendlyName)
        }
        refreshPeers()
        refreshDiscovered()
        if (hello != null) {
            _state.update { it.copy(status = "${hello.friendlyName} disconnected") }
        }
    }

    // endregion

    // region sending

    private fun sendFileTo(connection: WsConnection, uri: Uri) {
        val peerLabel = synchronized(lock) {
            helloByConnection[connection]?.friendlyName ?: connection.remoteLabel
        }
        val alreadySending = synchronized(lock) { !sendingConnections.add(connection) }
        if (alreadySending) {
            _state.update { it.copy(status = "Wait for the current transfer to $peerLabel to finish") }
            return
        }
        var transferId: String? = null
        try {
            val resolver = appContext.contentResolver
            val fileName = queryDisplayName(uri) ?: "flash-file"
            val fileSize = queryFileSize(uri)
            transferId = UUID.randomUUID().toString().take(8)
            upsertTransfer(
                WsTransferItem(
                    id = transferId,
                    peerName = peerLabel,
                    fileName = fileName,
                    direction = WsTransferDirection.SENDING,
                    bytesDone = 0,
                    bytesTotal = fileSize,
                    status = WsTransferStatus.ACTIVE,
                )
            )
            if (!connection.sendText(WsTransferMessages.fileStart(transferId, fileName, fileSize))) {
                throw IOException("Connection closed")
            }
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open selected file")
            input.use { stream ->
                val buffer = ByteArray(CHUNK_BYTES)
                var sent = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    if (!connection.sendBinary(chunk)) throw IOException("Connection lost while sending")
                    sent += read
                    updateTransferProgress(transferId, sent)
                }
                if (!connection.sendText(WsTransferMessages.fileEnd(transferId, sent))) {
                    throw IOException("Connection closed")
                }
                updateTransferDetail(transferId, "Sent $sent bytes — waiting for confirmation")
            }
        } catch (error: Exception) {
            Log.w(TAG, "WS send failed remote=$peerLabel", error)
            transferId?.let { id ->
                markTransferFailed(id, error.message ?: error::class.java.simpleName)
            }
        } finally {
            synchronized(lock) { sendingConnections -= connection }
        }
    }

    private fun handleAck(ack: WsTransferMessages.FileAck) {
        val transfer = _state.value.transfers.firstOrNull { it.id == ack.transferId } ?: return
        if (ack.ok) {
            upsertTransfer(
                transfer.copy(
                    bytesDone = ack.bytesReceived,
                    status = WsTransferStatus.COMPLETED,
                    detail = "Peer confirmed ${ack.bytesReceived} bytes",
                )
            )
        } else {
            markTransferFailed(ack.transferId, "Peer reported a failed receive")
        }
    }

    private fun failSendingTransfersFor(peerLabel: String) {
        _state.update { current ->
            current.copy(
                transfers = current.transfers.map { transfer ->
                    if (transfer.direction == WsTransferDirection.SENDING &&
                        transfer.peerName == peerLabel &&
                        transfer.status == WsTransferStatus.ACTIVE
                    ) {
                        transfer.copy(status = WsTransferStatus.FAILED, detail = "Connection lost mid-transfer")
                    } else {
                        transfer
                    }
                }
            )
        }
    }

    // endregion

    // region receiving

    private fun beginReceiving(connection: WsConnection, start: WsTransferMessages.FileStart) {
        val peerLabel = synchronized(lock) {
            helloByConnection[connection]?.friendlyName ?: connection.remoteLabel
        }
        val existing = synchronized(lock) { receivingByConnection[connection] }
        if (existing != null) {
            failReceiving(connection, "Interrupted by a new transfer")
        }
        try {
            val file = uniqueReceivedFile(start.fileName)
            val receiving = ReceivingTransfer(
                transferId = start.transferId,
                file = file,
                output = BufferedOutputStream(FileOutputStream(file)),
                expectedBytes = start.fileSize,
            )
            synchronized(lock) { receivingByConnection[connection] = receiving }
            upsertTransfer(
                WsTransferItem(
                    id = start.transferId,
                    peerName = peerLabel,
                    fileName = file.name,
                    direction = WsTransferDirection.RECEIVING,
                    bytesDone = 0,
                    bytesTotal = start.fileSize,
                    status = WsTransferStatus.ACTIVE,
                    filePath = file.absolutePath,
                )
            )
        } catch (error: IOException) {
            Log.w(TAG, "WS receive setup failed remote=$peerLabel", error)
            connection.sendText(WsTransferMessages.fileAck(start.transferId, 0, ok = false))
        }
    }

    private fun finishReceiving(connection: WsConnection, end: WsTransferMessages.FileEnd) {
        val receiving = synchronized(lock) { receivingByConnection.remove(connection) } ?: return
        receiving.closeQuietly()
        val expected = if (receiving.expectedBytes >= 0) receiving.expectedBytes else end.bytesSent
        val ok = receiving.received == expected
        connection.sendText(WsTransferMessages.fileAck(end.transferId, receiving.received, ok))
        if (ok) {
            upsertTransferById(end.transferId) { transfer ->
                transfer.copy(
                    bytesDone = receiving.received,
                    status = WsTransferStatus.COMPLETED,
                    detail = "Saved to ws-received/${receiving.file.name}",
                    filePath = receiving.file.absolutePath,
                )
            }
        } else {
            markTransferFailed(end.transferId, "Size mismatch: got ${receiving.received} of $expected bytes")
        }
    }

    private fun failReceiving(connection: WsConnection, detail: String) {
        val receiving = synchronized(lock) { receivingByConnection.remove(connection) } ?: return
        receiving.closeQuietly()
        markTransferFailed(receiving.transferId, detail)
    }

    private fun loadExistingReceivedFiles() {
        val directory = File(appContext.filesDir, RECEIVED_DIR_NAME)
        if (!directory.exists()) return
        val files = directory.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified()}
            ?: return
        val initialItems = files.map { file ->
            WsTransferItem(
                id = "saved-${file.name.hashCode()}",
                peerName = "Local Storage",
                fileName = file.name,
                direction = WsTransferDirection.RECEIVING,
                bytesDone = file.length(),
                bytesTotal = file.length(),
                status = WsTransferStatus.COMPLETED,
                detail = "Saved in ws-received/${file.name}",
                filePath = file.absolutePath,
            )
        }
        if (initialItems.isNotEmpty()) {
            _state.update { it.copy(transfers = initialItems) }
        }
    }

    private fun uniqueReceivedFile(displayName: String): File {
        val directory = File(appContext.filesDir, RECEIVED_DIR_NAME).apply { mkdirs() }
        val safeName = displayName.replace('/', '_').replace('\\', '_').ifBlank { "flash-file" }
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        var candidate = File(directory, safeName)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($counter)$extension")
            counter++
        }
        return candidate
    }

    // endregion

    // region state helpers

    private fun refreshPeers() {
        val peers = synchronized(lock) {
            primaryByPeerId.entries.map { (deviceId, connection) ->
                WsPeer(
                    deviceId = deviceId,
                    friendlyName = peerNames[deviceId] ?: "Flash peer",
                    address = connection.remoteLabel,
                    outbound = connection in outboundConnections,
                )
            }.sortedBy { it.friendlyName.lowercase() }
        }
        _state.update { it.copy(peers = peers) }
    }

    private fun upsertTransfer(item: WsTransferItem) {
        _state.update { current ->
            val next = (listOf(item) + current.transfers.filterNot { it.id == item.id })
                .take(MAX_TRANSFER_ITEMS)
            current.copy(transfers = next)
        }
    }

    private fun upsertTransferById(id: String, transform: (WsTransferItem) -> WsTransferItem) {
        _state.update { current ->
            current.copy(
                transfers = current.transfers.map { transfer ->
                    if (transfer.id == id) transform(transfer) else transfer
                }
            )
        }
    }

    private fun updateTransferProgress(id: String, bytesDone: Long) {
        upsertTransferById(id) { transfer ->
            if (transfer.status == WsTransferStatus.ACTIVE &&
                bytesDone - transfer.bytesDone >= PROGRESS_STEP_BYTES
            ) {
                transfer.copy(bytesDone = bytesDone)
            } else {
                transfer
            }
        }
    }

    private fun updateTransferDetail(id: String, detail: String) {
        upsertTransferById(id) { transfer ->
            if (transfer.status == WsTransferStatus.ACTIVE) transfer.copy(detail = detail) else transfer
        }
    }

    private fun markTransferFailed(id: String, detail: String) {
        upsertTransferById(id) { transfer ->
            if (transfer.status == WsTransferStatus.ACTIVE) {
                transfer.copy(status = WsTransferStatus.FAILED, detail = detail)
            } else {
                transfer
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    private fun queryFileSize(uri: Uri): Long {
        return appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
        } ?: -1L
    }

    // endregion

    private class ReceivingTransfer(
        val transferId: String,
        val file: File,
        val output: BufferedOutputStream,
        val expectedBytes: Long,
    ) {
        var received: Long = 0

        fun closeQuietly() {
            runCatching { output.close() }
        }
    }

    companion object {
        private const val TAG = "WS"
        private const val CHUNK_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024
        private const val MAX_TRANSFER_ITEMS = 50
        private const val RECEIVED_DIR_NAME = "ws-received"
        private const val AUTO_CONNECT_SUPPRESS_MS = 60_000L
    }
}
