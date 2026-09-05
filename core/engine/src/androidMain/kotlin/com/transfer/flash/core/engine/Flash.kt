@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine

import android.content.Context
import android.util.Log
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import com.transfer.flash.core.discovery.nsd.BuildNsdApiLevel
import com.transfer.flash.core.discovery.nsd.NsdTransport
import com.transfer.flash.core.engine.store.KeystorePassphraseProvider
import com.transfer.flash.core.engine.store.RoomTransferStore
import com.transfer.flash.core.messaging.RealFlashChatRepository
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.datachannel.DataChannelClient
import com.transfer.flash.core.network.datachannel.DataChannelServer
import com.transfer.flash.core.network.ws.WsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.persistence.db.FlashDatabaseOpener
import com.transfer.flash.core.persistence.db.FlashMigrations
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.security.identity.AndroidPreferencesIdentityStore
import com.transfer.flash.core.security.trust.AndroidPreferencesTrustStore
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.IncrementalSha256
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.RejectReason
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.policy.FileRandomAccessSinkHandle
import com.transfer.flash.core.transfer.policy.RandomAccessChunkSink
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Consumer-facing knobs for [Flash.create]. Every field has a sensible default, so
 * `Flash.create(context)` yields a fully working engine.
 */
public data class FlashConfig(
    /**
     * Friendly name advertised to peers. When null, the persisted device identity's name is used
     * (falling back to "Flash Device" on first run).
     */
    val displayName: String? = null,
    /**
     * When true, outbound/inbound chunk progress is persisted so a transfer interrupted by a
     * process restart resumes instead of restarting. When false the transfer repository runs with
     * no [com.transfer.flash.core.transfer.store.TransferStore] (still fully functional in-session).
     * The encrypted chat/settings database is opened regardless — chats and settings require it.
     */
    val enableResume: Boolean = true,
    /**
     * Inbound-offer gate. When false (default, mirrors the app), every inbound file arrives as an
     * OFFER that the consumer must accept via [com.transfer.flash.core.transfer.FlashTransferRepository.acceptIncoming];
     * senders park until then. When true, inbound transfers are accepted automatically and senders
     * stream immediately — the zero-friction path for the README quick-start.
     */
    val autoAcceptIncoming: Boolean = false,
    /**
     * Directory for received files. When null, `<externalFilesDir>/FlashReceived` is used.
     */
    val receivedFilesDir: File? = null,
)

/**
 * One-call entry point that assembles a fully-wired [FlashEngine] (ADR-010 / Phase 5 Task 5.1).
 *
 * ```kotlin
 * val engine = Flash.create(context, FlashConfig(autoAcceptIncoming = true))
 * // …observe engine.discovery.discoveredEndpoints, then:
 * engine.transfers.sendFile(peerDevice, uri, "photo.jpg", sizeBytes)
 * engine.close() // in onDestroy / ViewModel.onCleared
 * ```
 *
 * All subsystems share one [CoroutineScope]; [FlashEngine.close] cancels it and releases the
 * NSD/Wi-Fi/data-channel/DB resources. Advanced users may instead hand-assemble the concrete
 * `Default*`/`Real*` impls and [DefaultFlashEngine] directly.
 *
 * Construction opens the encrypted database synchronously, so call this off the main thread. The
 * network server, NSD advertising/browsing, data-channel server, and proactive auto-connect start
 * asynchronously on the shared scope right after this returns.
 */
public object Flash {

    /** Builds and starts a fully-wired engine. See [Flash] for the lifecycle contract. */
    public fun create(context: Context, config: FlashConfig = FlashConfig()): FlashEngine {
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Wiring(appContext, config, scope).build()
    }
}

private const val TAG = "FlashEngine"
private const val MSG_PREFIX = "FLASH_MSG"
private const val RECEIPT_PREFIX = "FLASH_RCPT"
private const val READ_PREFIX = "FLASH_READ"
private const val REACT_PREFIX = "FLASH_REACT"
private const val TYPING_PREFIX = "FLASH_TYPING"
private const val XFER_PREFIX = "FLASH_XFER"
private const val AUTO_CONNECT_SWEEP_MS = 5_000L

/**
 * Faithful port of the app's `DiscoveryEngineHolder` wiring, minus the app-only pieces (pairing UI
 * glue, foreground service, Dev Console payload). Assembles the six [FlashEngine] subsystems on one
 * shared [scope] and returns a [DefaultFlashEngine] whose `close()` tears everything down.
 */
private class Wiring(
    private val appContext: Context,
    private val config: FlashConfig,
    private val scope: CoroutineScope,
) {
    // Receive-side shared state (see DiscoveryEngineHolder for the rationale of each map).
    private val openHandles = ConcurrentHashMap<String, RandomAccessSinkHandle>()
    private val incomingMeta = ConcurrentHashMap<String, ChunkFrame.FileStart>()
    private val receivedPaths = ConcurrentHashMap<String, String>()
    private val incomingByPeer = ConcurrentHashMap<String, MutableSet<String>>()
    private val dataPortCache = ConcurrentHashMap<String, Int>()
    private val pausedIntakeIds = MutableStateFlow<Set<String>>(emptySet())

    @Volatile private var dataPort: Int = 0
    @Volatile private var transferRef: RealFlashTransferRepository? = null
    @Volatile private var acceptOffer: ((String) -> Unit)? = null
    private var onPeerConnectionClosed: ((String) -> Unit)? = null

    fun build(): FlashEngine {
        val stored = AndroidPreferencesIdentityStore(appContext).getIdentity()
        val identity = FlashAdvertisedIdentity(
            deviceId = FlashDeviceId(stored.deviceId.value.ifBlank { UUID.randomUUID().toString() }),
            friendlyName = config.displayName?.ifBlank { null }
                ?: stored.friendlyName.ifBlank { "Flash Device" },
            deviceModel = android.os.Build.MODEL ?: "unknown",
            protocolVersion = 2,
        )
        val localId = identity.deviceId.value

        val engine = CompositeDiscovery(
            transports = listOf(
                NsdTransport(
                    context = appContext,
                    apiLevel = BuildNsdApiLevel,
                    directory = StandardEndpointDirectory(),
                    sweep = { _ -> emptyList() },
                ),
            ),
        )
        val networkImpl = WsFlashNetwork(
            context = appContext,
            localDeviceId = localId,
            localFriendlyName = identity.friendlyName,
        )

        val db = FlashDatabaseOpener.openEncrypted(
            appContext,
            KeystorePassphraseProvider(appContext),
            *FlashMigrations.ALL,
        )
        val trustStore = AndroidPreferencesTrustStore(appContext)
        val settings = FlashSettingsDataStore(
            produceFile = { File(appContext.filesDir, "flash_settings.preferences_pb") },
            scope = scope,
        )
        val receivedDir = (config.receivedFilesDir
            ?: File(appContext.getExternalFilesDir(null), "FlashReceived")).apply { mkdirs() }

        val receivePipeline = ReceivePipeline(
            sink = { _, _ -> Log.w(TAG, "Legacy shared sink invoked — expected per-transfer sinkFactory") },
            sinkFactory = { start ->
                val safeName = sanitizePathComponent(start.fileName.ifBlank { "received.bin" })
                val safeId = sanitizePathComponent(start.transferId)
                val dest = File(File(receivedDir, safeId), safeName)
                dest.parentFile?.mkdirs()
                receivedPaths[start.transferId] = dest.absolutePath
                val handle = FileRandomAccessSinkHandle(dest, start.totalBytes)
                openHandles[start.transferId] = handle
                RandomAccessChunkSink(handle, start.chunkSize)
            },
            emitSessionStarted = true,
            // Always defer the sink until an accept resolves it (#5); autoAcceptIncoming just
            // automates that accept immediately (see the SessionStarted handler).
            requireAcceptance = true,
            resumeIndexesProvider = { start ->
                transferRef?.receiverDoneIndexes(start.transferId) ?: emptyList()
            },
        )

        val dcServer = DataChannelServer(
            localDeviceId = localId,
            listener = object : DataChannelServer.Listener {
                override fun onFrame(senderDeviceId: String, channelId: Int, payload: ByteArray, reply: (ByteArray) -> Boolean) {
                    transferRef?.let { handleInboundBinary(it, receivePipeline, "dc:$channelId", senderDeviceId, payload, reply) }
                }
                override fun onConnectionClosed(peerDeviceId: String?, channelId: Int) {
                    peerDeviceId?.let { pid -> onPeerConnectionClosed?.invoke(pid) }
                }
            },
        )

        val transferImpl = RealFlashTransferRepository(
            streamChannelFactory = { channelId, peerDeviceId -> openStreamChannel(channelId, peerDeviceId, networkImpl, localId) },
            fileSourceOpener = { uriString -> openSource(uriString) },
            store = if (config.enableResume) RoomTransferStore(db.transferDao(), db.transferChunkDao()) else null,
            repositoryScope = scope,
            requireReceiverAcceptance = true,
        )
        transferRef = transferImpl
        val chatImpl = RealFlashChatRepository(
            localDeviceId = localId,
            localDisplayName = identity.friendlyName,
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            onlinePeerIds = networkImpl.activeSessions.map { sessions ->
                sessions.keys.mapTo(HashSet()) { it.value }
            },
            peerNameResolver = { id -> trustStore.getTrustedPeers()[FlashDeviceId(id)] },
            attachmentProgress = transferImpl.activeTransfers.map { transfers ->
                transfers.associate { t ->
                    t.id.value to com.transfer.flash.core.messaging.model.FlashAttachmentProgress(
                        progress = if (t.bytesTotal > 0L) {
                            (t.bytesDone.toFloat() / t.bytesTotal.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        status = when (t.state) {
                            com.transfer.flash.core.transfer.model.FlashTransferState.Completed,
                            com.transfer.flash.core.transfer.model.FlashTransferState.Verifying ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.Downloaded
                            com.transfer.flash.core.transfer.model.FlashTransferState.Failed,
                            com.transfer.flash.core.transfer.model.FlashTransferState.Cancelled ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.Failed
                            com.transfer.flash.core.transfer.model.FlashTransferState.Offered ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.AwaitingAcceptance
                            else ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.Transferring
                        },
                        localPath = t.localPath ?: t.sourceUri,
                        speedMbps = t.speedBytesPerSec / 1_000_000f,
                        etaSeconds = t.etaSeconds.toInt(),
                    )
                }
            },
            transportSink = { targetDeviceId, wireFrame -> sendChatFrame(networkImpl, targetDeviceId, wireFrame) },
        )
        val cleanupInbound: (String, String) -> Unit = { transferId, reason ->
            openHandles.remove(transferId)?.let { handle -> runCatching { handle.close() } }
            incomingMeta.remove(transferId)
            receivedPaths.remove(transferId)
            receivePipeline.cancelSession(transferId)
            pausedIntakeIds.update { it - transferId }
            incomingByPeer.values.forEach { it.remove(transferId) }
            transferImpl.onIncomingFailed(transferId, reason)
        }
        val failInboundForPeer: (String, String) -> Unit = { peerId, reason ->
            incomingByPeer.remove(peerId)?.toList()?.forEach { transferId -> cleanupInbound(transferId, reason) }
        }
        onPeerConnectionClosed = { peerId -> failInboundForPeer(peerId, "data channel closed") }

        val sendXfer: (String, String, String) -> Unit = { peerId, action, transferId ->
            val session = networkImpl.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
            if (session != null) {
                session.connection.sendText(FlashTextFraming.encodeFields(XFER_PREFIX, listOf("action" to action, "transferId" to transferId)))
            } else {
                Log.w(TAG, "Cannot deliver XFER $action: no session for $peerId")
            }
        }

        // Resolve the deferred sink FIRST, surface Transferring + attachment bubble, THEN RESUME the
        // parked sender. autoAcceptIncoming triggers this automatically on the offer (below).
        val acceptOffer: (String) -> Unit = { transferId ->
            val meta = incomingMeta[transferId]
            val pid = transferImpl.activeTransfers.value.find { it.id.value == transferId }?.peerDeviceId
            if (meta != null && receivePipeline.acceptSession(transferId)) {
                transferImpl.onIncomingStarted(transferId, meta.fileId, meta.fileName, meta.totalBytes, pid ?: "", pid, receivedPaths[transferId])
                pid?.let {
                    chatImpl.onInboundAttachment(it, transferId, meta.fileName, guessMimeType(meta.fileName), meta.totalBytes)
                    sendXfer(it, RealFlashTransferRepository.ACTION_RESUME, transferId)
                }
            } else {
                Log.w(TAG, "acceptOffer no-op transferId=$transferId")
            }
        }
        val declineOffer: (String) -> Unit = { transferId ->
            receivePipeline.declineSession(transferId)
            incomingMeta.remove(transferId)
            receivedPaths.remove(transferId)
            pausedIntakeIds.update { it - transferId }
            incomingByPeer.values.forEach { it.remove(transferId) }
        }
        this.acceptOffer = acceptOffer
        scope.launch {
            transferImpl.incomingControl.collect { control ->
                when (control.action) {
                    RealFlashTransferRepository.ACTION_PAUSE -> pausedIntakeIds.update { it + control.transferId }
                    RealFlashTransferRepository.ACTION_RESUME -> pausedIntakeIds.update { it - control.transferId }
                    RealFlashTransferRepository.ACTION_CANCEL -> cleanupInbound(control.transferId, "cancelled by peer")
                    RealFlashTransferRepository.ACTION_ACCEPT -> acceptOffer(control.transferId)
                    RealFlashTransferRepository.ACTION_DECLINE -> declineOffer(control.transferId)
                }
            }
        }
        scope.launch {
            transferImpl.outgoingControl.collect { control ->
                val peerId = control.peerDeviceId ?: return@collect
                sendXfer(peerId, control.action, control.transferId)
            }
        }

        val sessionJobs = ConcurrentHashMap<WsSession, kotlinx.coroutines.Job>()
        scope.launch {
            networkImpl.activeSessions.collect { sessions ->
                sessionJobs.keys.filterNot { it in sessions.values }.forEach { stale ->
                    sessionJobs.remove(stale)?.cancel()
                    failInboundForPeer(stale.peerDeviceId.value, "peer disconnected")
                }
                sessions.values.forEach { session ->
                    if (session is WsSession && !sessionJobs.containsKey(session)) {
                        // Bug 5: a peer session is up (first connect or reconnect) — flush the
                        // durable outbox so messages queued while this peer was offline send now.
                        chatImpl.notifyPeerSessionUp()
                        sessionJobs[session] = scope.launch {
                            launch {
                                session.incomingText.collect { text ->
                                    handleInboundText(chatImpl, transferImpl, session.peerDeviceId.value, text)
                                }
                            }
                            launch {
                                while (true) {
                                    pausedIntakeIds.first { it.isEmpty() }
                                    val data = runCatching { session.awaitBinaryFrame() }.getOrElse { break }
                                    handleInboundBinary(
                                        transferImpl, receivePipeline, session.peer.friendlyName,
                                        session.peerDeviceId.value, data,
                                        { bytes -> session.connection.sendBinary(bytes) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Bring the transport up asynchronously: bind the WS server (its port drives NSD advertising
        // and the data-channel port), start discovery, then proactively dial discovered peers.
        scope.launch {
            val netStart = networkImpl.start(0)
            val serverPort = (netStart as? FlashResult.Success)?.value ?: 0
            if (serverPort <= 0) {
                Log.e(TAG, "Network server failed to start: ${(netStart as? FlashResult.Failure)?.error}")
                return@launch
            }
            DiscoveryRouteBinder.observe(scope, engine.discoveredEndpoints, networkImpl)
            engine.setMode(FlashDiscoveryMode.STANDARD)
            val started = engine.startAll(serverPort, identity)
            if (!started.isSuccess) Log.e(TAG, "Discovery startAll failed: ${(started as? FlashResult.Failure)?.error}")

            dataPort = runCatching { dcServer.start(preferredPort = serverPort + 1) }.getOrElse {
                Log.w(TAG, "DataChannelServer failed to bind: ${it.message}"); 0
            }
            runCatching { transferImpl.preloadReceiverProgress() }

            val gate = com.transfer.flash.core.engine.internal.AutoConnectGate()
            while (isActive) {
                runAutoConnectSweep(engine, networkImpl, localId, gate)
                delay(AUTO_CONNECT_SWEEP_MS)
            }
        }

        return DefaultFlashEngine(
            chats = chatImpl,
            transfers = transferImpl,
            discovery = engine,
            network = networkImpl,
            trustStore = trustStore,
            settings = settings,
            onClose = {
                runCatching { dcServer.stop() }
                kotlinx.coroutines.runBlocking {
                    runCatching { engine.stopAll() }
                    runCatching { networkImpl.stop() }
                }
                runCatching { db.close() }
                scope.cancel()
            },
        )
    }
    private suspend fun handleInboundText(
        chatImpl: RealFlashChatRepository,
        transferImpl: RealFlashTransferRepository,
        peerDeviceId: String,
        text: String,
    ) {
        FlashTextFraming.parseFields(text, MSG_PREFIX)?.let { f ->
            val localId = f["localId"] ?: return
            chatImpl.onInboundWireFrame(
                MessageWireFrame.TextMessage(
                    localId = localId,
                    conversationId = f["conversationId"] ?: "",
                    senderId = f["senderId"] ?: "",
                    senderName = f["senderName"] ?: "Peer",
                    sentAt = f["sentAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                    text = f["text"] ?: "",
                    replyToId = f["replyToId"]?.ifBlank { null },
                    replyToPreview = f["replyToPreview"]?.ifBlank { null },
                ),
            )
            return
        }
        FlashTextFraming.parseFields(text, RECEIPT_PREFIX)?.let { f ->
            chatImpl.onInboundWireFrame(
                MessageWireFrame.DeliveryReceipt(
                    messageId = f["messageId"] ?: return,
                    conversationId = f["conversationId"] ?: "",
                    memberId = f["memberId"] ?: "",
                    deliveredAt = f["deliveredAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                ),
            )
            return
        }
        FlashTextFraming.parseFields(text, READ_PREFIX)?.let { f ->
            chatImpl.onInboundWireFrame(
                MessageWireFrame.ReadReceipt(
                    conversationId = f["conversationId"] ?: "",
                    memberId = f["memberId"] ?: return,
                    upToMessageId = f["upToMessageId"] ?: return,
                    readAt = f["readAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                ),
            )
            return
        }
        FlashTextFraming.parseFields(text, REACT_PREFIX)?.let { f ->
            chatImpl.onInboundWireFrame(
                MessageWireFrame.ReactionFrame(
                    messageId = f["messageId"] ?: return,
                    conversationId = f["conversationId"] ?: "",
                    memberId = f["memberId"] ?: return,
                    emoji = f["emoji"] ?: return,
                    isAdded = f["isAdded"]?.toBooleanStrictOrNull() ?: true,
                ),
            )
            return
        }
        FlashTextFraming.parseFields(text, TYPING_PREFIX)?.let { f ->
            chatImpl.onInboundWireFrame(
                MessageWireFrame.TypingFrame(
                    conversationId = peerDeviceId,
                    memberId = f["memberId"] ?: return,
                    memberName = f["memberName"] ?: "Peer",
                    isTyping = f["isTyping"]?.toBooleanStrictOrNull() ?: false,
                    timestampMs = f["timestampMs"]?.toLongOrNull() ?: System.currentTimeMillis(),
                ),
            )
            return
        }
        FlashTextFraming.parseFields(text, XFER_PREFIX)?.let { f ->
            val action = f["action"] ?: return
            val transferId = f["transferId"] ?: return
            transferImpl.onRemoteTransferControl(transferId, action)
            return
        }
    }
    private fun handleInboundBinary(
        transferImpl: RealFlashTransferRepository,
        receivePipeline: ReceivePipeline,
        peerLabel: String,
        peerDeviceId: String?,
        data: ByteArray,
        reply: (ByteArray) -> Boolean,
    ) {
        // Sender-side ACK/COMPLETE first; if consumed, not a receiver frame.
        if (transferImpl.onInboundFrame(data)) return
        for (event in receivePipeline.onFrame(data)) {
            when (event) {
                is ReceiveEvent.SessionStarted -> {
                    val frame = event.frame
                    incomingMeta[frame.transferId] = frame
                    peerDeviceId?.let { pid ->
                        incomingByPeer.getOrPut(pid) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }.add(frame.transferId)
                    }
                    // A re-offer of a transfer this device already accepted is a RETRY: the previous
                    // attempt's session died with the transport, so the sender's relaunch arrives as
                    // a fresh FILE_START and would park on the acceptance gate with a deferred sink —
                    // chunks dropped, no ACKs, progress frozen. Resolve the sink instead of asking
                    // again. Cancelled stays excluded: a declined offer is never auto-accepted.
                    if (transferImpl.isResumableInboundRetry(frame.transferId)) {
                        receivePipeline.acceptSession(frame.transferId)
                        transferImpl.onIncomingStarted(
                            frame.transferId, frame.fileId, frame.fileName, frame.totalBytes,
                            peerLabel, peerDeviceId, receivedPaths[frame.transferId],
                        )
                        continue
                    }
                    transferImpl.onIncomingOffered(frame.transferId, frame.fileId, frame.fileName, frame.totalBytes, peerLabel, peerDeviceId)
                    if (config.autoAcceptIncoming) acceptOffer?.invoke(frame.transferId)
                }
                is ReceiveEvent.AckBatchReady -> {
                    transferImpl.onIncomingChunkConfirmed(event.frame.transferId, event.frame.indexes)
                    updateIncomingProgress(transferImpl, receivePipeline, event.frame.transferId)
                    reply(ChunkFrame.serialize(event.frame))
                }
                is ReceiveEvent.Completed -> {
                    val transferId = event.frame.transferId
                    openHandles.remove(transferId)?.let { it.flush(); it.close() }
                    incomingByPeer.values.forEach { it.remove(transferId) }
                    val path = receivedPaths.remove(transferId)
                    val expectedHex = incomingMeta.remove(transferId)?.fileSha256Hex
                    val verified = event.frame.verified && verifyWholeFile(path, expectedHex)
                    transferImpl.onIncomingCompleted(transferId, verified, path)
                    reply(ChunkFrame.serialize(event.frame))
                }
                is ReceiveEvent.Rejected -> {
                    if (event.reason != RejectReason.UNEXPECTED_DIRECTION) {
                        Log.w(TAG, "Receiver rejected frame: reason=${event.reason} transferId=${event.transferId} index=${event.index}")
                    }
                }
            }
        }
    }
    private fun openStreamChannel(channelId: Int, peerDeviceId: String?, networkImpl: WsFlashNetwork, localId: String): StreamChannel? {
        val active = networkImpl.activeSessions.value
        val wsSession = (peerDeviceId?.let { active[FlashDeviceId(it)] } ?: active.values.firstOrNull()) as? WsSession

        fun wsFallback(): StreamChannel? {
            if (wsSession == null) return null
            return object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean = wsSession.connection.sendBinary(frameBytes)
            }
        }

        if (dataPort <= 0 || peerDeviceId == null) return wsFallback()
        val endpoint = networkImpl.endpointOf(peerDeviceId) ?: return wsFallback()
        val probeOffset = dataPortCache[peerDeviceId]
        val offsets = listOfNotNull(probeOffset) + (1..20).filter { it != probeOffset }
        for (offset in offsets) {
            val candidate = DataChannelClient.connect(
                host = endpoint.first,
                port = endpoint.second + offset,
                targetDeviceId = localId,
                channelId = channelId,
                onFrame = { bytes -> transferRef?.onInboundFrame(bytes) },
                onClosed = { },
                localDeviceId = localId,
            )
            if (candidate != null) {
                dataPortCache[peerDeviceId] = offset
                return object : StreamChannel {
                    override val id: Int = channelId
                    override suspend fun sendFrame(frameBytes: ByteArray): Boolean = candidate.send(frameBytes)
                }
            }
        }
        return wsFallback()
    }
    private fun sendChatFrame(networkImpl: WsFlashNetwork, targetDeviceId: String, wireFrame: MessageWireFrame): Boolean {
        val session = networkImpl.activeSessions.value[FlashDeviceId(targetDeviceId)] as? WsSession ?: return false
        val frameText = when (wireFrame) {
            is MessageWireFrame.TextMessage -> FlashTextFraming.encodeFields(
                MSG_PREFIX,
                listOf(
                    "localId" to wireFrame.localId,
                    "conversationId" to wireFrame.conversationId,
                    "senderId" to wireFrame.senderId,
                    "senderName" to (wireFrame.senderName ?: "Peer"),
                    "sentAt" to wireFrame.sentAt.toString(),
                    "text" to wireFrame.text,
                    "replyToId" to (wireFrame.replyToId ?: ""),
                    "replyToPreview" to (wireFrame.replyToPreview ?: ""),
                ),
            )
            is MessageWireFrame.DeliveryReceipt -> FlashTextFraming.encodeFields(
                RECEIPT_PREFIX,
                listOf(
                    "messageId" to wireFrame.messageId,
                    "conversationId" to wireFrame.conversationId,
                    "memberId" to wireFrame.memberId,
                    "deliveredAt" to wireFrame.deliveredAt.toString(),
                ),
            )
            is MessageWireFrame.ReadReceipt -> FlashTextFraming.encodeFields(
                READ_PREFIX,
                listOf(
                    "conversationId" to wireFrame.conversationId,
                    "memberId" to wireFrame.memberId,
                    "upToMessageId" to wireFrame.upToMessageId,
                    "readAt" to wireFrame.readAt.toString(),
                ),
            )
            is MessageWireFrame.ReactionFrame -> FlashTextFraming.encodeFields(
                REACT_PREFIX,
                listOf(
                    "messageId" to wireFrame.messageId,
                    "conversationId" to wireFrame.conversationId,
                    "memberId" to wireFrame.memberId,
                    "emoji" to wireFrame.emoji,
                    "isAdded" to wireFrame.isAdded.toString(),
                ),
            )
            is MessageWireFrame.TypingFrame -> FlashTextFraming.encodeFields(
                TYPING_PREFIX,
                listOf(
                    "conversationId" to wireFrame.conversationId,
                    "memberId" to wireFrame.memberId,
                    "memberName" to wireFrame.memberName,
                    "isTyping" to wireFrame.isTyping.toString(),
                    "timestampMs" to wireFrame.timestampMs.toString(),
                ),
            )
        }
        return session.connection.sendText(frameText)
    }
    private fun verifyWholeFile(path: String?, expectedHex: String?): Boolean {
        if (path.isNullOrBlank() || expectedHex.isNullOrBlank() || !Sha256.isValidHex(expectedHex)) return false
        return runCatching {
            val acc = IncrementalSha256()
            File(path).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    acc.update(buffer, 0, read)
                }
            }
            Sha256.hexEqualsConstantTime(acc.digestHex(), Sha256.normalizeHex(expectedHex))
        }.getOrElse { false }
    }

    private fun updateIncomingProgress(transferImpl: RealFlashTransferRepository, receivePipeline: ReceivePipeline, transferId: String) {
        val start = incomingMeta[transferId] ?: return
        val done = receivePipeline.doneIndexes(transferId) ?: return
        var bytes = 0L
        for (index in done) bytes += minOf(start.chunkSize.toLong(), start.totalBytes - index.toLong() * start.chunkSize)
        transferImpl.onIncomingProgress(transferId, bytes)
    }

    private fun sanitizePathComponent(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim('.').ifBlank { "unnamed" }.take(120)

    private fun openSource(uriString: String): InputStream {
        require(uriString.startsWith("content://") || uriString.startsWith("file://")) { "Unsupported source descriptor: $uriString" }
        return appContext.contentResolver.openInputStream(android.net.Uri.parse(uriString))
            ?: throw IOException("Content resolver returned null stream for $uriString")
    }

    private fun runAutoConnectSweep(engine: CompositeDiscovery, networkImpl: WsFlashNetwork, localId: String, gate: com.transfer.flash.core.engine.internal.AutoConnectGate) {
        for (ep in engine.discoveredEndpoints.value) {
            val id = ep.deviceId.value
            if (id == localId) continue
            // ERROR-031: "has a session" must mean a session that is demonstrably carrying traffic.
            // Gating on map presence alone let a session whose socket had died — without its watchdog
            // noticing — suppress the very sweep that would have replaced it, so the peer stayed
            // Online-but-unreachable until the app was force-stopped.
            if (!gate.tryBegin(id, networkImpl.hasLiveSession(id), System.currentTimeMillis())) continue
            scope.launch {
                runCatching { networkImpl.connectManual(ep.hostAddress, ep.port) }
                gate.end(id)
            }
        }
    }

    private fun guessMimeType(fileName: String): String {
        // Locale.ROOT, not getDefault(): a file extension is machine data compared against
        // lowercase ASCII literals below. Under a Turkish locale getDefault() folds 'I' to the
        // dotless 'ı', so "TIFF"/"GIF"/"MIDI"/"JPI" would stop matching. Matches the precedent
        // in core/discovery CompositeDiscovery.kt (uppercase(Locale.ROOT)).
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

