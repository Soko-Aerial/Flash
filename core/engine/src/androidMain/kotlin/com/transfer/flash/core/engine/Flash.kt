@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine

import com.transfer.flash.core.persistence.db.runInWriteTransaction
import android.content.Context
import android.util.Log
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import com.transfer.flash.core.discovery.nsd.BuildNsdApiLevel
import com.transfer.flash.core.discovery.nsd.NsdTransport
import com.transfer.flash.core.engine.store.EncryptedDatabaseRecovery
import com.transfer.flash.core.engine.store.RoomTransferStore
import com.transfer.flash.core.messaging.RealFlashChatRepository
import com.transfer.flash.core.messaging.protocol.ChatTextFrameCodec
import com.transfer.flash.core.security.crypto.E2eFrameCodec
import com.transfer.flash.core.messaging.protocol.DirectChatFamily
import com.transfer.flash.core.messaging.protocol.DirectMessageActionCodec
import com.transfer.flash.core.messaging.protocol.GroupFrameCodec
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.messaging.protocol.PttAudioFrame
import com.transfer.flash.core.messaging.protocol.PttFrameCodec
import com.transfer.flash.core.messaging.protocol.PttSessionCodec
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.datachannel.DataChannelClient
import com.transfer.flash.core.network.datachannel.DataChannelServer
import com.transfer.flash.core.network.ws.WsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.ptt.PttSessionEngine
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import com.transfer.flash.core.network.tls.TransportSecurityUnavailableException
import com.transfer.flash.core.network.tls.requireTransportSecurity
import com.transfer.flash.core.security.crypto.KeystoreFlashCrypto
import com.transfer.flash.core.security.crypto.SecureBinaryFrameCodec
import com.transfer.flash.core.security.identity.AndroidPreferencesIdentityStore
import com.transfer.flash.core.security.trust.AndroidPreferencesTrustStore
import com.transfer.flash.core.security.trust.FlashTrustStore
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
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
import com.transfer.flash.core.transfer.policy.TransferReconnectResumePolicy
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.source

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

    /**
     * Builds and starts a fully-wired engine. See [Flash] for the lifecycle contract.
     *
     * @throws TransportSecurityUnavailableException when the TLS identity cannot be built after a
     * retry. The engine never starts without TLS; there is no plaintext fallback (audit S3).
     */
    public fun create(context: Context, config: FlashConfig = FlashConfig()): FlashEngine {
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Wiring(appContext, config, scope).build()
    }
}

private const val TAG = "FlashEngine"
private const val CALL_PREFIX = "FLASH_CALL"
private const val XFER_PREFIX = "FLASH_XFER"
private const val AUTO_CONNECT_SWEEP_MS = 5_000L

/**
 * Delay between a session coming up and auto-resume re-offering on it: long enough for two-way-dial
 * glare to have been resolved, negligible next to the outage that failed the transfer.
 */
private const val SETTLE_BEFORE_RESUME_MS = 750L

/**
 * True when [text] is a calling-signaling frame (`FLASH_CALL …`, ADR-025) — the recognition half of
 * the facade's call routing, and the reason a call frame can never fall through into the chat,
 * transfer or PTT parsers whether or not an engine is attached.
 *
 * A prefix test rather than `CallFrameCodec.decode` on purpose: the codec lives in `:core:calling`,
 * which this module depends on with `compileOnly`, so a consumer that never attaches calling has no
 * such class at runtime — and this runs on *every* inbound text frame. Same reason the attached
 * engine is reached through [FlashEngine.onInboundCallText] rather than `FlashEngine.calls`.
 */
internal fun isCallFrameText(text: String): Boolean =
    FlashTextFraming.parseFields(text, CALL_PREFIX) != null

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

    /**
     * Attempt budget for restarting roam-killed sends (ERROR-035). One per engine, so the cap spans
     * session-up edges — a per-edge instance would never run out, which defeats the point.
     */
    private val reconnectResume = TransferReconnectResumePolicy()

    @Volatile private var dataPort: Int = 0
    @Volatile private var transferRef: RealFlashTransferRepository? = null
    @Volatile private var acceptOffer: ((String) -> Unit)? = null
    @Volatile private var sendXfer: ((String, String, String) -> Unit)? = null
    private var onPeerConnectionClosed: ((String) -> Unit)? = null

    /**
     * The facade this wiring backs, set as soon as it is constructed. Inbound PTT and calling
     * frames are routed to [FlashEngine.ptt] / [FlashEngine.calls] through it, so an attached engine
     * has exactly one owner; the session collector below drives the call engine's signaling-recovery
     * window through the same reference.
     */
    @Volatile private var facade: FlashEngine? = null
    @Volatile private var trustStoreRef: FlashTrustStore? = null

    fun build(): FlashEngine {
        val stored = AndroidPreferencesIdentityStore(appContext).getIdentity()
        val identity = FlashAdvertisedIdentity(
            deviceId = FlashDeviceId(stored.deviceId.value.ifBlank { UUID.randomUUID().toString() }),
            friendlyName = config.displayName?.ifBlank { null }
                ?: stored.friendlyName.ifBlank { "Flash Device" },
            deviceModel = android.os.Build.MODEL ?: "unknown",
            protocolVersion = 2,
            // Declares this endpoint's kind so a desktop's Nearby row can say "Phone" rather than
            // guessing from `Build.MODEL`. Rides the existing `caps` field — no wire key is added,
            // and a peer that does not send it shows no badge instead of a wrong one.
            capabilities = setOf(FlashDeviceKind.CAP_MOBILE),
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
        val trustStore = AndroidPreferencesTrustStore(appContext)
        this.trustStoreRef = trustStore
        val crypto = KeystoreFlashCrypto(appContext)
        // Audit S3: TLS is mandatory. No plaintext fallback — failure throws out of Flash.create.
        val tlsOptions = requireTransportSecurity(
            onAttemptFailed = { attempt, error -> Log.w(TAG, "TLS setup attempt $attempt failed: ${error.message}") },
        ) {
            crypto.selfSignedCertificate()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, null)
            val pinVerifier = TofuPinVerifier(
                lookupPin = { peerId -> trustStore.getPin(FlashDeviceId(peerId)) },
                recordPin = { peerId, pin -> trustStore.savePin(FlashDeviceId(peerId), pin) },
            )
            TlsOptions(
                pinVerifier = pinVerifier,
                keyManagers = kmf.keyManagers,
            )
        }

        var boundServerPort = 0
        var networkRestartJob: Job? = null
        val networkImpl = WsFlashNetwork(
            context = appContext,
            localDeviceId = localId,
            localFriendlyName = identity.friendlyName,
            tlsOptions = tlsOptions,
            onUsableNetwork = {
                networkRestartJob?.cancel()
                networkRestartJob = scope.launch {
                    engine.restartDiscovery()
                    if (boundServerPort > 0) {
                        engine.startAdvertising(boundServerPort)
                    }
                }
            },
        )

        // Audit B7: a lost keystore key no longer crash-loops the app on an unopenable DB.
        val db = EncryptedDatabaseRecovery.openRecoveringLostKey(appContext)
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
                val canonicalRoot = receivedDir.canonicalFile
                val dest = File(File(canonicalRoot, safeId), safeName).canonicalFile
                // SENTINEL: Path traversal guard — canonical containment under FlashReceived
                require(dest.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Path traversal escape detected for transferId=${start.transferId}, fileName=${start.fileName}"
                }
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
            // Phase 13B-2: FileSourceOpener.open() now returns okio.Source, so the ContentResolver
            // stream is bridged with okio's `InputStream.source()`. openSource() itself is
            // unchanged — it stays an InputStream producer because that is what ContentResolver
            // hands back.
            fileSourceOpener = { uriString -> openSource(uriString).source() },
            store = if (config.enableResume) RoomTransferStore(db.transferDao(), db.transferChunkDao()) else null,
            repositoryScope = scope,
            requireReceiverAcceptance = true,
            isPeerEncrypted = { peerId -> trustStore.getSessionKey(FlashDeviceId(peerId)) != null },
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
            groupMemberDao = db.groupMemberDao(),
            groupDeliveryDao = db.groupDeliveryDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            isTrustedPeer = { peerId -> trustStore.isTrusted(peerId) },
            isChannelEncrypted = { peerId -> trustStore.getSessionKey(FlashDeviceId(peerId)) != null },
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
                            com.transfer.flash.core.transfer.model.FlashTransferState.Paused ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.Paused
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
            groupTransportSink = { targetDeviceId, wireFrame ->
                val session = networkImpl.activeSessions.value[FlashDeviceId(targetDeviceId)] as? WsSession
                if (session == null) {
                    Log.w(TAG, "Group frame dropped: no active session for $targetDeviceId")
                    return@RealFlashChatRepository false
                }
                session.connection.sendText(GroupFrameCodec.encode(wireFrame))
            },
        )
        val cleanupInbound: (String, String) -> Unit = { transferId, reason ->
            receivePipeline.cancelSession(transferId)
            openHandles.remove(transferId)?.let { handle -> runCatching { handle.close() } }
            incomingMeta.remove(transferId)
            receivedPaths.remove(transferId)
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
        this.sendXfer = sendXfer
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
                    // A live call cannot carry ICE without its signaling session, but a mesh roam
                    // takes that session down as a matter of course and the dialer redials it in
                    // seconds: open a recovery window rather than ending the call (ERROR-033). The
                    // call engine is the facade's to notify, so the host does not duplicate this.
                    facade?.onCallSignalingLost(stale.peerDeviceId.value)
                }
                sessions.values.forEach { session ->
                    if (session is WsSession && !sessionJobs.containsKey(session)) {
                        // Bug 5: a peer session is up (first connect or reconnect) — flush the
                        // durable outbox so messages queued while this peer was offline send now.
                        // The peer id additionally makes that member's group deliveries retryable.
                        chatImpl.notifyPeerSessionUp(session.peerDeviceId.value)
                        // F3: holder-coordinated group catch-up (FLASH_GSYNC) with the returning peer.
                        chatImpl.sendGroupSyncRequests(session.peerDeviceId.value)
                        // F7: heal a membership frame this peer may have missed while it was offline -
                        // membership frames have no delivery table, so a dropped Add/State is never retried.
                        chatImpl.reconcileGroupMembership(session.peerDeviceId.value)
                        // Closes any recovery window the matching onCallSignalingLost opened, so a
                        // roam that resolved in two seconds does not cost the full grace period, and
                        // the ICE restart offer has a channel to travel on (ERROR-033).
                        facade?.onCallSignalingRestored(session.peerDeviceId.value)
                        // Restart sends the peer's last disconnect killed. Byte-accurate resume
                        // already existed and nothing called it (ERROR-035).
                        scope.launch {
                            resumeRoamKilledSends(transferImpl, networkImpl, session.peerDeviceId.value)
                        }
                        sessionJobs[session] = scope.launch {
                            launch {
                                session.incomingText.collect { text ->
                                    handleInboundText(
                                        chatImpl,
                                        transferImpl,
                                        session.peerDeviceId.value,
                                        text,
                                    )
                                }
                            }
                            launch {
                                while (true) {
                                    pausedIntakeIds.first { it.isEmpty() }
                                    val data = runCatching { session.awaitBinaryFrame() }.getOrElse { break }
                                    handleInboundBinary(
                                        transferImpl, receivePipeline, session.peer.friendlyName,
                                        session.peerDeviceId.value, data,
                                        { bytes -> session.connection.sendBinaryConsuming(bytes) },
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
            boundServerPort = serverPort
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

        val facade = DefaultFlashEngine(
            chats = chatImpl,
            transfers = transferImpl,
            discovery = engine,
            network = networkImpl,
            trustStore = trustStore,
            settings = settings,
            // PTT (ADR-032) is opt-in and app-hosted: the facade owns identity/trust/session
            // plumbing, the host owns the mic grant and the foreground service. Wired lazily so an
            // app that never presses PTT never opens a capture path.
            pttFactory = { hasMicPermission, isCallActive, audioRateHz ->
                PttSessionEngine(
                    localId = { localId },
                    localName = { identity.friendlyName },
                    isTrustedPeer = { peerId -> trustStore.isTrusted(FlashDeviceId(peerId)) },
                    snapshotMembers = {
                        networkImpl.activeSessions.value.keys
                            .map { it.value }
                            .filter { it != localId && trustStore.isTrusted(FlashDeviceId(it)) }
                    },
                    // Blocking socket writes by contract, exactly like the app host: the engine
                    // calls these from its own IO-dispatched loops, never from main or capture.
                    sendControl = { peerId, text ->
                        val session = networkImpl.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                        if (session == null) {
                            Log.w(TAG, "PTT control dropped: no session for $peerId")
                            false
                        } else {
                            runCatching { session.connection.sendText(text) }
                                .onFailure { Log.w(TAG, "PTT control send failed peer=$peerId", it) }
                                .getOrDefault(false)
                        }
                    },
                    sendAudio = { peerId, bytes ->
                        val session = networkImpl.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                        if (session == null) {
                            Log.w(TAG, "PTT audio dropped: no session for $peerId")
                        } else {
                            runCatching { session.connection.sendBinary(bytes) }
                                .onFailure { Log.w(TAG, "PTT audio send failed peer=$peerId", it) }
                        }
                    },
                    hasMicPermission = hasMicPermission,
                    isCallActive = isCallActive,
                    audioRateHz = audioRateHz,
                )
            },
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
        // Inbound session collectors below close over this wiring, so they read the seam through
        // the facade instead of a second copy of the attached-engine reference.
        this.facade = facade
        return facade
    }
    private suspend fun handleInboundText(
        chatImpl: RealFlashChatRepository,
        transferImpl: RealFlashTransferRepository,
        peerDeviceId: String,
        text: String,
    ) {
        // Calling first (ADR-025): the most latency-sensitive frame class, and the only handler that
        // is attached at runtime instead of built by Flash.create. A recognized call frame is
        // consumed or dropped here — never handed on. `CallCoordinator.onInboundText` answers false
        // for a frame it has nothing to do with (a stale call id, a group query with no live call),
        // and that frame still belongs to calling, so the branch returns either way.
        if (isCallFrameText(text)) {
            val consumed = facade?.onInboundCallText(peerDeviceId, text) == true
            if (!consumed) {
                Log.w(
                    TAG,
                    "Call frame dropped (no calling engine attached, or nothing to route it to) peer=$peerDeviceId",
                )
            }
            return
        }
        // PTT next (ADR-032): ping + voice-session control share one entry point. When an engine
        // is attached it owns decode, dedup, the fail-closed trust/transport-binding check and the
        // floor reduction, and it answers true for recognized-but-rejected frames too. The
        // no-engine branch below is what keeps a frame from falling through when nothing can
        // handle it — it is deliberately `else`, so an attached engine pays two prefix parses once
        // instead of twice on every inbound chat frame.
        val ptt = facade?.ptt
        if (ptt != null) {
            if (ptt.onInboundText(peerDeviceId, text)) return
        } else if (PttFrameCodec.decode(text) != null || PttSessionCodec.decode(text) != null) {
            Log.w(TAG, "PTT frame dropped (no PTT engine attached) peer=$peerDeviceId")
            return
        }
        GroupFrameCodec.decode(text)?.let { frame ->
            Log.i(TAG, "Inbound group frame ${frame.javaClass.simpleName} from id=$peerDeviceId")
            chatImpl.onInboundGroupWireFrame(peerDeviceId, frame)
            return
        }
        val plainText = if (E2eFrameCodec.isSecuredFrame(text)) {
            val sessionKey = trustStoreRef?.getSessionKey(FlashDeviceId(peerDeviceId))
            if (sessionKey != null) {
                E2eFrameCodec.decryptWireFrame(text, sessionKey) ?: return
            } else {
                return
            }
        } else {
            // Audit S1b: the direct-chat family is ALWAYS encrypted by the sender once a session key
            // exists, so a plaintext one from a keyed peer is a downgrade — drop it.
            if (DirectChatFamily.matches(text) && trustStoreRef?.getSessionKey(FlashDeviceId(peerDeviceId)) != null) {
                Log.w(TAG, "Dropped plaintext direct-chat frame from keyed peer $peerDeviceId (downgrade)")
                return
            }
            text
        }

        DirectMessageActionCodec.decode(plainText)?.let { frame ->
            chatImpl.onInboundWireFrame(frame, transportPeerId = peerDeviceId)
            return
        }
        // Direct-chat text family through the shared codec (slice 3). Invalid-but-recognized
        // frames drop, exactly as before; anything else falls through to the transfer family.
        // transportPeerId is passed for ALL five families: the codec decodes the direct-chat
        // family only (group frames travel a separate path), so for legit traffic the frame author
        // IS the transport peer — and PR #11's fail-closed spoof guards (`transportPeerId != null`
        // checks) only fire when it is non-null. Passing null here would silently neutralize them.
        when (val decoded = ChatTextFrameCodec.decode(plainText, System.currentTimeMillis(), peerDeviceId)) {
            is ChatTextFrameCodec.DecodeResult.Frame -> {
                val frame = decoded.frame
                chatImpl.onInboundWireFrame(
                    frame,
                    transportPeerId = peerDeviceId,
                )
                return
            }
            ChatTextFrameCodec.DecodeResult.RecognizedButInvalid -> return
            null -> Unit
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
        // PTT voice audio first (ADR-032): the "PTT1" magic is disjoint from the transfer
        // pipeline's "FLSH", so this costs one 4-byte compare and PTT audio can never reach the
        // sender dispatcher or the receive pipeline — attached engine or not.
        if (PttAudioFrame.isPttAudio(data)) {
            facade?.ptt?.onInboundBinary(peerDeviceId, data)
            return
        }
        val sessionKey = peerDeviceId?.let { trustStoreRef?.getSessionKey(FlashDeviceId(it)) }
        val frameData = if (SecureBinaryFrameCodec.isSecureFrame(data)) {
            if (sessionKey == null) {
                Log.w(TAG, "Received encrypted binary frame from $peerDeviceId but no session key exists")
                return
            }
            val decrypted = SecureBinaryFrameCodec.decryptOrNull(data, sessionKey)
            if (decrypted == null) {
                Log.w(TAG, "Failed to decrypt binary frame from $peerDeviceId (tampered or wrong key)")
                return
            }
            decrypted
        } else {
            data
        }

        val secureReply: (ByteArray) -> Boolean = { replyBytes ->
            val toSend = if (sessionKey != null) {
                SecureBinaryFrameCodec.encrypt(replyBytes, sessionKey)
            } else {
                replyBytes
            }
            reply(toSend)
        }

        // Sender-side ACK/COMPLETE first; if consumed, not a receiver frame.
        val consumedBySender = try {
            transferImpl.onInboundFrame(frameData)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to route inbound frame to sender: ${t.message}")
            false
        }
        if (consumedBySender) return
        val events = try {
            receivePipeline.onFrame(frameData)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to process inbound binary frame: ${e.message}")
            return
        }
        for (event in events) {
            when (event) {
                is ReceiveEvent.SessionStarted -> {
                    val frame = event.frame
                    incomingMeta[frame.transferId] = frame
                    peerDeviceId?.let { pid ->
                        incomingByPeer.getOrPut(pid) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }.add(frame.transferId)
                    }
                    // If this transfer was already completed locally, reply COMPLETE immediately so the sender
                    // stops re-offering, and do not redownload or overwrite the local file.
                    val existing = transferImpl.activeTransfers.value.firstOrNull { it.id.value == frame.transferId }
                    val existingPath = receivedPaths[frame.transferId] ?: existing?.localPath
                    val alreadyCompleted = (existing != null && existing.state == com.transfer.flash.core.transfer.model.FlashTransferState.Completed) ||
                        (existingPath != null && java.io.File(existingPath).exists() && java.io.File(existingPath).length() == frame.totalBytes)

                    if (alreadyCompleted) {
                        secureReply(com.transfer.flash.core.transfer.chunked.ChunkFrame.serialize(com.transfer.flash.core.transfer.chunked.ChunkFrame.Complete(frame.transferId, frame.fileId, verified = true)))
                        peerDeviceId?.let { pid ->
                            sendXfer?.invoke(pid, RealFlashTransferRepository.ACTION_RESUME, frame.transferId)
                        }
                        continue
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
                        peerDeviceId?.let { pid ->
                            sendXfer?.invoke(pid, RealFlashTransferRepository.ACTION_RESUME, frame.transferId)
                        }
                        continue
                    }
                    transferImpl.onIncomingOffered(frame.transferId, frame.fileId, frame.fileName, frame.totalBytes, peerLabel, peerDeviceId)
                    if (config.autoAcceptIncoming) acceptOffer?.invoke(frame.transferId)
                }
                is ReceiveEvent.AckBatchReady -> {
                    transferImpl.onIncomingChunkConfirmed(event.frame.transferId, event.frame.indexes)
                    updateIncomingProgress(transferImpl, receivePipeline, event.frame.transferId)
                    secureReply(ChunkFrame.serialize(event.frame))
                }
                is ReceiveEvent.Completed -> {
                    val transferId = event.frame.transferId
                    openHandles.remove(transferId)?.let { it.flush(); it.close() }
                    incomingByPeer.values.forEach { it.remove(transferId) }
                    val path = receivedPaths.remove(transferId)
                    val expectedHex = incomingMeta.remove(transferId)?.fileSha256Hex
                    val verified = event.frame.verified && verifyWholeFile(path, expectedHex)
                    transferImpl.onIncomingCompleted(transferId, verified, path)
                    secureReply(ChunkFrame.serialize(event.frame))
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
        // F1: a NAMED peer with no session fails the stream — the old
        // `?: active.values.firstOrNull()` leaked group-addressed transfers (whose id is a
        // groupId, never a session key) to an arbitrary connected peer. The anonymous fallback
        // is only correct when no peer was named at all (legacy path).
        val wsSession = (if (peerDeviceId == null) {
            active.values.firstOrNull()
        } else {
            active[FlashDeviceId(peerDeviceId)]
        }) as? WsSession

        val resolvedPeerId = peerDeviceId ?: wsSession?.peerDeviceId?.value
        val sessionKey = resolvedPeerId?.let { trustStoreRef?.getSessionKey(FlashDeviceId(it)) }

        fun wsFallback(): StreamChannel? {
            if (wsSession == null) return null
            return object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    val toSend = if (sessionKey != null) {
                        SecureBinaryFrameCodec.encrypt(frameBytes, sessionKey)
                    } else {
                        frameBytes
                    }
                    return wsSession.connection.sendBinaryConsuming(toSend)
                }
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
                targetDeviceId = peerDeviceId,
                channelId = channelId,
                onFrame = { bytes ->
                    val key = trustStoreRef?.getSessionKey(FlashDeviceId(peerDeviceId))
                    val decrypted = if (SecureBinaryFrameCodec.isSecureFrame(bytes)) {
                        if (key != null) SecureBinaryFrameCodec.decryptOrNull(bytes, key) else null
                    } else {
                        bytes
                    }
                    if (decrypted != null) {
                        transferRef?.onInboundFrame(decrypted)
                    }
                },
                onClosed = { },
                localDeviceId = localId,
            )
            if (candidate != null) {
                dataPortCache[peerDeviceId] = offset
                return object : StreamChannel {
                    override val id: Int = channelId
                    override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                        val toSend = if (sessionKey != null) {
                            SecureBinaryFrameCodec.encrypt(frameBytes, sessionKey)
                        } else {
                            frameBytes
                        }
                        return candidate.send(toSend)
                    }
                }
            }
        }
        return wsFallback()
    }
    private fun sendChatFrame(networkImpl: WsFlashNetwork, targetDeviceId: String, wireFrame: MessageWireFrame): Boolean {
        val session = networkImpl.activeSessions.value[FlashDeviceId(targetDeviceId)] as? WsSession ?: return false
        // The five direct-chat families encode through the shared codec (slice 3);
        // DeleteForEveryone keeps its own (DirectMessageActionCodec).
        val frameText = when (wireFrame) {
            is MessageWireFrame.DeleteForEveryone -> DirectMessageActionCodec.encode(wireFrame)
            is MessageWireFrame.TextMessage,
            is MessageWireFrame.DeliveryReceipt,
            is MessageWireFrame.ReadReceipt,
            is MessageWireFrame.ReactionFrame,
            is MessageWireFrame.TypingFrame -> ChatTextFrameCodec.encode(wireFrame) ?: return false
        }
        val sessionKey = trustStoreRef?.getSessionKey(FlashDeviceId(targetDeviceId))
        val wirePayload = if (sessionKey != null) {
            E2eFrameCodec.encryptToWireFrame(frameText, sessionKey)
        } else {
            frameText
        }
        return session.connection.sendText(wirePayload)
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

    /**
     * Restarts outbound transfers that this peer's previous disconnect failed (ERROR-035).
     *
     * The session-up edge is the only moment a resume can succeed: `relaunchSend` needs a live session
     * for the re-offer, and it reproduces the original `wireFileId`/`sourceUri` so the receiver
     * continues the session it has rather than starting over. [SETTLE_BEFORE_RESUME_MS] waits out the
     * two-way-dial glare that `WsFlashNetwork.registerSession` resolves, so a re-offer is not spent on
     * a session that is about to be closed as the loser.
     */
    private suspend fun resumeRoamKilledSends(
        transfers: RealFlashTransferRepository,
        networkImpl: WsFlashNetwork,
        peerDeviceId: String,
    ) {
        delay(SETTLE_BEFORE_RESUME_MS)
        if (networkImpl.activeSessions.value[FlashDeviceId(peerDeviceId)] == null) return
        val snapshot = transfers.activeTransfers.value
        reconnectResume.retainOnly(snapshot.mapTo(HashSet(snapshot.size)) { it.id })
        val toResume = reconnectResume.onPeerSessionUp(peerDeviceId, snapshot)
        if (toResume.isEmpty()) return
        Log.i(TAG, "Session up for $peerDeviceId: auto-resuming ${toResume.size} failed send(s)")
        toResume.forEach { transferId ->
            runCatching { transfers.resumeTransfer(transferId) }
                .onFailure { error -> Log.w(TAG, "Auto-resume threw for ${transferId.value}", error) }
        }
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

