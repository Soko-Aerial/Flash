package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.engine.MagicFrameRouter
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.swarm.api.FlashSwarm
import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.swarm.driver.SwarmDriver
import com.transfer.flash.core.swarm.driver.SwarmGroupContext
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmStateStore
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.transfer.ExternalTransferControl
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The single place that wires the swarm engine into a host (§5.2, SW-8 Task 3).
 *
 * Connects:
 * - [SwarmDriver] with platform I/O ports ([NetworkSwarmTransport], [SwarmGroupContext], [PieceStorage], [SwarmStateStore]).
 * - Registers `"FSW1"` 4-byte magic with [MagicFrameRouter].
 * - Feeds active session peer features (including `"sw1"`) to [NetworkSwarmTransport].
 * - Wires network state transitions to [SwarmDriver.onNetworkUp] and [SwarmDriver.onNetworkDown].
 * - Attaches swarm transfer rows into [RealFlashTransferRepository] with full [ExternalTransferControl].
 */
public class SwarmHostBinding(
    public val config: FlashSwarmConfig,
    public val localDeviceId: String,
    private val scope: CoroutineScope,
    private val magicRouter: MagicFrameRouter,
    private val network: FlashNetwork,
    private val transferRepository: RealFlashTransferRepository,
    private val groupContext: SwarmGroupContext,
    private val storage: PieceStorage,
    private val stateStore: SwarmStateStore,
    private val chatRepository: com.transfer.flash.core.messaging.FlashChatRepository? = null,
    private val isCallActive: (() -> Boolean)? = null,
    private val isServingEnabled: (() -> Boolean)? = null,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    seed: Long = 42L,
) {

    private val bindingJob = Job()
    private val bindingScope = CoroutineScope(scope.coroutineContext + bindingJob)

    public val transport: NetworkSwarmTransport = NetworkSwarmTransport(
        sendFrameToPeer = { peerId, frameBytes ->
            val session = network.activeSessions.value[FlashDeviceId(peerId)]
            if (session != null) {
                session.send(frameBytes) is FlashResult.Success
            } else {
                false
            }
        },
        requestSessionForPeer = { peerId ->
            network.retryConnection()
        },
    )

    public val driver: SwarmDriver = SwarmDriver(
        config = config,
        localDeviceId = localDeviceId,
        transport = transport,
        groupContext = groupContext,
        storage = storage,
        stateStore = stateStore,
        scope = bindingScope,
        workerDispatcher = workerDispatcher,
        seed = seed,
    )

    public val swarm: FlashSwarm get() = driver

    init {
        // 1. Register FSW1 magic with MagicFrameRouter
        magicRouter.register(MagicFrameRouter.FSW1_MAGIC) { peerDeviceId, frame, _ ->
            if (peerDeviceId != null) {
                bindingScope.launch(workerDispatcher) {
                    driver.onInboundFrame(peerDeviceId, frame)
                }
                true
            } else {
                false
            }
        }

        // 2. Track active sessions to update connected peers and features
        bindingScope.launch {
            network.activeSessions.collect { sessions ->
                val peers = sessions.map { (id, session) ->
                    id.value to session.peer.features
                }.toMap()
                transport.updateConnectedPeers(peers)
            }
        }

        // 3. Track network state (NetworkUp / NetworkDown)
        bindingScope.launch {
            network.networkState.collect { state ->
                if (state.isRunning) {
                    driver.onNetworkUp()
                } else {
                    driver.onNetworkDown()
                }
            }
        }

        // 4. Attach external rows to RealFlashTransferRepository
        val externalControl = object : ExternalTransferControl {
            override fun owns(transferId: String): Boolean {
                return driver.rows.value.any { it.id.value == transferId }
            }

            override suspend fun accept(transferId: String) {
                driver.accept(transferId)
            }

            override suspend fun decline(transferId: String) {
                driver.decline(transferId)
            }

            override suspend fun pause(transferId: String) {
                driver.pause(transferId)
            }

            override suspend fun resume(transferId: String) {
                driver.resume(transferId)
            }

            override suspend fun cancel(transferId: String) {
                val row = driver.rows.value.find { it.id.value == transferId }
                if (row?.direction == FlashTransferDirection.Sending) {
                    driver.cancelAsOrigin(transferId)
                } else {
                    driver.cancelLocal(transferId)
                }
            }

            override suspend fun pauseForSystem(transferId: String, reason: String) {
                driver.pauseForSystem(transferId, reason)
            }
        }

        transferRepository.attachExternalRows(driver.rows, externalControl)

        // 4b. The host hands over plain lambdas (call state, discovery mode); nothing pushes their changes,
        // so they are sampled. Without this the call throttle and the ECO "do not serve" rule never took effect.
        bindingScope.launch(workerDispatcher) {
            while (isActive) {
                runCatching { updateEnvironment() }
                delay(ENVIRONMENT_POLL_MS)
            }
        }

        // 5. Wire inbound swarm announcements from chatRepository
        chatRepository?.let { repo ->
            repo.swarmAnnouncementListener = com.transfer.flash.core.messaging.GroupSwarmAnnouncementListener { groupId, messageId, transferId, from, root, pieceSize, totalSize, fileName, mimeType, sentAt, rootSig ->
                bindingScope.launch(workerDispatcher) {
                    val now = com.transfer.flash.core.common.time.SystemTimeSource.nowMs()
                    val authorKey = groupContext.authorKey(groupId, from) ?: ""
                    val event = com.transfer.flash.core.swarm.engine.SwarmEvent.Announced(
                        groupId = groupId,
                        messageId = messageId,
                        originId = from,
                        originKey = authorKey,
                        root = com.transfer.flash.core.swarm.model.ContentRoot(root),
                        totalSize = totalSize,
                        pieceSize = pieceSize,
                        fileName = fileName,
                        mime = mimeType,
                        sentAtMs = sentAt,
                        expiresAtMs = sentAt + (7 * 24 * 3600 * 1000L),
                        isOrigin = false,
                        localUri = null,
                        autoAccept = config.autoAccept,
                        nowMs = now,
                    )
                    driver.announceContent(event)
                }
            }
        }

        // 6. Wire delete-for-everyone to origin cancel (SW-9 Task 3)
        chatRepository?.let { repo ->
            repo.onGroupMessageDeletedForEveryone = { groupId, messageId ->
                bindingScope.launch(workerDispatcher) {
                    driver.cancelAsOrigin(messageId, reason = SwarmTombstoneReason.DELETED)
                }
            }
        }
    }

    private var lastCallActive: Boolean? = null
    private var lastServingEnabled: Boolean? = null

    /**
     * Re-evaluates call active status and serving enabled status; the engine hears only about changes.
     */
    public fun updateEnvironment() {
        isCallActive?.let {
            val now = it()
            if (now != lastCallActive) {
                lastCallActive = now
                driver.onCallActive(now)
            }
        }
        isServingEnabled?.let {
            val now = it()
            if (now != lastServingEnabled) {
                lastServingEnabled = now
                driver.onServingEnabled(now)
            }
        }
    }

    /**
     * Registers and announces a local file as swarm origin content (SW-8 Task 7).
     * Computes the origin's domain-separated announcement signature and persists the record.
     * Returns the base64-encoded root signature, or null if signing fails.
     */
    public suspend fun registerOrigin(
        groupId: String,
        messageId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        uri: String,
        manifest: com.transfer.flash.core.swarm.model.SwarmManifest,
    ): String? {
        val myId = localDeviceId
        val now = com.transfer.flash.core.common.time.SystemTimeSource.nowMs()
        val stmt = com.transfer.flash.core.swarm.codec.SwarmStatement.announce(
            groupId = groupId,
            messageId = messageId,
            originId = myId,
            root = manifest.root,
            sizeBytes = sizeBytes,
            fileName = fileName,
            mimeType = mimeType,
            sentAt = now,
        )
        val sigBytes = groupContext.signStatement(groupId, stmt) ?: return null
        val rootSig = com.transfer.flash.core.common.protocol.Base64.encode(sigBytes)
        val authorKey = groupContext.authorKey(groupId, myId) ?: ""
        val manifestBytes = com.transfer.flash.core.swarm.codec.ManifestCodec.encodeCanonical(manifest)

        val allBits = com.transfer.flash.core.swarm.model.Bitfield(manifest.pieceCount).apply {
            for (i in 0 until manifest.pieceCount) set(i)
        }
        val record = SwarmContentRecord(
            groupId = groupId,
            root = manifest.root,
            messageId = messageId,
            role = SwarmRole.ORIGIN,
            originId = myId,
            originKey = authorKey,
            fileName = fileName,
            mime = mimeType,
            totalSize = sizeBytes,
            pieceSize = manifest.pieceSize,
            manifestBytes = manifestBytes,
            bits = allBits.toByteArray(),
            bytesDone = sizeBytes,
            state = com.transfer.flash.core.swarm.model.SwarmLifecycleState.ACTIVE,
            waitReason = null,
            failReason = null,
            localTransferId = messageId,
            sourceUri = uri,
            sourcePersistent = true,
            partialKey = SwarmDriver.partialKeyFor(manifest.root, groupId),
            finalPath = null,
            identitySize = sizeBytes,
            identityModifiedMs = now,
            deliveredTo = emptySet(),
            createdAtMs = now,
            lastProgressAtMs = now,
            expiresAtMs = now + (7 * 24 * 3600 * 1000L),
        )
        stateStore.upsert(record)

        val event = com.transfer.flash.core.swarm.engine.SwarmEvent.Announced(
            groupId = groupId,
            messageId = messageId,
            originId = myId,
            originKey = authorKey,
            root = manifest.root,
            totalSize = sizeBytes,
            pieceSize = manifest.pieceSize,
            fileName = fileName,
            mime = mimeType,
            sentAtMs = now,
            expiresAtMs = record.expiresAtMs,
            isOrigin = true,
            localUri = uri,
            autoAccept = true,
            manifest = manifest,
            nowMs = now,
        )
        driver.announceContent(event)
        return rootSig
    }

    /**
     * Prepares and hashes a local file for swarm origin streaming (SW-8 Task 7).
     * Builds the manifest via [storage] and registers the origin record.
     */
    public suspend fun prepareOrigin(
        groupId: String,
        messageId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        uri: String,
    ): Pair<com.transfer.flash.core.swarm.model.SwarmManifest, String>? {
        val pieceSize = com.transfer.flash.core.swarm.model.PieceMath.choosePieceSize(sizeBytes) ?: return null
        val handle = storage.openSource(uri) ?: return null
        val manifest = try {
            val builder = com.transfer.flash.core.swarm.model.ManifestBuilder(pieceSize)
            val buf = ByteArray(64 * 1024)
            var offset = 0L
            while (offset < sizeBytes) {
                val toRead = minOf(buf.size.toLong(), sizeBytes - offset).toInt()
                val read = handle.readAt(offset, buf, toRead)
                if (read <= 0) return null
                builder.addBlock(buf, 0, read)
                offset += read
            }
            builder.build()
        } catch (_: Throwable) {
            return null
        } finally {
            handle.close()
        }
        val rootSig = registerOrigin(
            groupId = groupId,
            messageId = messageId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            uri = uri,
            manifest = manifest,
        ) ?: return null
        return manifest to rootSig
    }

    private companion object {
        const val ENVIRONMENT_POLL_MS: Long = 2_000L
    }

    /**
     * Detaches the swarm from the host and unregisters frame routers.
     */
    public fun detach() {
        if (chatRepository?.swarmAnnouncementListener != null) {
            chatRepository.swarmAnnouncementListener = null
        }
        if (chatRepository?.onGroupMessageDeletedForEveryone != null) {
            chatRepository.onGroupMessageDeletedForEveryone = null
        }
        magicRouter.unregister(MagicFrameRouter.FSW1_MAGIC)
        bindingJob.cancel()
    }
}
