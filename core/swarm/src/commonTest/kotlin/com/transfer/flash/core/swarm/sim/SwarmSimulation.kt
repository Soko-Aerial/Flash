package com.transfer.flash.core.swarm.sim

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.engine.SwarmConfig
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.transfer.chunked.Sha256

/**
 * Scenario DSL and runner for virtual multi-node swarm simulations (SW-5).
 */
class SwarmSimulation(
    val seed: Long = 42L,
) {
    val clock = SimClock(nowMs = 1000L)
    val queue = SimEventQueue(clock)
    val network = SimNetwork(clock, queue)
    val nodes = LinkedHashMap<String, SimNode>()

    data class ScenarioReport(
        val scenarioId: String,
        val seed: Long,
        val nodeCount: Int,
        val pieceCount: Int,
        val totalBytes: Long,
        val virtualDurationMs: Long,
        val realDurationMs: Long,
        val originBytesUploaded: Long,
        val originUploadRatio: Double,
        val framesSentWhileWaiting: Int,
        val corruptWrites: Int,
        val allCompleted: Boolean,
    )

    fun createManifest(
        pieceCount: Int,
        pieceSize: Int = 65536,
    ): Pair<SwarmManifest, ByteArray> {
        val totalSize = pieceCount.toLong() * pieceSize
        val pieceHashes = List(pieceCount) { idx ->
            val pieceLen = PieceMath.pieceLength(idx, totalSize, pieceSize)
            val dummyBytes = SimStorage.generateDefaultPieceBytes(idx, pieceLen)
            Sha256.digest(dummyBytes)
        }
        val fileSha = Sha256.digest(ByteArray(pieceCount) { (it and 0xFF).toByte() })
        val unsigned = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = totalSize,
            fileSha256 = fileSha,
            pieceHashes = pieceHashes,
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        val canonicalBytes = ManifestCodec.encodeCanonical(unsigned)
        val root = ManifestCodec.computeRoot(canonicalBytes)
        val manifest = unsigned.copy(root = root)
        return manifest to canonicalBytes
    }

    fun addNode(
        id: String,
        isOrigin: Boolean = false,
        isLegacy: Boolean = false,
        manifest: SwarmManifest,
        canonicalBytes: ByteArray,
        config: SwarmConfig = SwarmConfig(autoAcceptIncoming = true),
        seedOffset: Long = 0L,
    ): SimNode {
        val storage = SimStorage(
            pieceCount = manifest.pieceHashes.size,
            pieceSize = manifest.pieceSize,
            totalSize = manifest.totalSize,
            fileSha256 = manifest.fileSha256,
            pieceHashes = manifest.pieceHashes,
        )
        if (isOrigin) {
            for (i in 0 until manifest.pieceHashes.size) {
                storage.writtenBits.set(i, true)
            }
            storage.sync(storage.writtenBits.toByteArray())
        }

        val node = SimNode(
            id = id,
            clock = clock,
            queue = queue,
            network = network,
            storage = storage,
            store = SimStore(),
            config = config,
            isOrigin = isOrigin,
            isLegacy = isLegacy,
            seed = seed + seedOffset,
        )
        node.setContentManifest(manifest, canonicalBytes)
        nodes[id] = node
        return node
    }

    fun connectAll() {
        val nodeIds = nodes.keys.toList()
        for (i in nodeIds.indices) {
            for (j in i + 1 until nodeIds.size) {
                val a = nodes[nodeIds[i]] ?: continue
                val b = nodes[nodeIds[j]] ?: continue
                val aFeatures = if (a.isLegacy) emptySet() else setOf("sw1")
                val bFeatures = if (b.isLegacy) emptySet() else setOf("sw1")
                a.onPeerUp(b.id, bFeatures)
                b.onPeerUp(a.id, aFeatures)
            }
        }
    }

    fun announceContent(
        groupId: String,
        messageId: String,
        originId: String,
        manifest: SwarmManifest,
        canonicalBytes: ByteArray,
    ) {
        val origin = nodes[originId] ?: error("Origin $originId not found")
        val originCmds = origin.engine.handle(
            SwarmEvent.Announced(
                groupId = groupId,
                messageId = messageId,
                originId = originId,
                originKey = "key-$originId",
                root = manifest.root,
                totalSize = manifest.totalSize,
                pieceSize = manifest.pieceSize,
                fileName = "test.bin",
                mime = "application/octet-stream",
                sentAtMs = clock.nowMs,
                expiresAtMs = clock.nowMs + 3_600_000L,
                isOrigin = true,
                autoAccept = true,
                nowMs = clock.nowMs,
            )
        )
        origin.processCommands(originCmds)

        for ((id, node) in nodes) {
            if (id != originId && !node.isLegacy) {
                val rxCmds = node.engine.handle(
                    SwarmEvent.Announced(
                        groupId = groupId,
                        messageId = messageId,
                        originId = originId,
                        originKey = "key-$originId",
                        root = manifest.root,
                        totalSize = manifest.totalSize,
                        pieceSize = manifest.pieceSize,
                        fileName = "test.bin",
                        mime = "application/octet-stream",
                        sentAtMs = clock.nowMs,
                        expiresAtMs = clock.nowMs + 3_600_000L,
                        isOrigin = false,
                        autoAccept = true,
                        nowMs = clock.nowMs,
                    )
                )
                node.processCommands(rxCmds)
            }
        }
    }

    fun startPeriodicTicks(intervalMs: Long = 250L) {
        fun scheduleNextTick() {
            queue.schedule(intervalMs) {
                for (node in nodes.values) {
                    node.tick()
                }
                scheduleNextTick()
            }
        }
        scheduleNextTick()
    }

    fun runUntilComplete(maxVirtualTimeMs: Long = 120_000L): Boolean {
        val receivers = nodes.values.filter { !it.isOrigin && !it.isLegacy }
        return queue.runUntil(maxVirtualTimeMs) {
            receivers.all { it.completed }
        }
    }

    fun generateReport(scenarioId: String, realDurationMs: Long, manifest: SwarmManifest): ScenarioReport {
        val origin = nodes.values.firstOrNull { it.isOrigin }
        val originUpload = origin?.originUploadBytes ?: 0L
        val totalBytes = manifest.totalSize
        val ratio = if (totalBytes > 0) originUpload.toDouble() / totalBytes.toDouble() else 0.0
        val waitingFrames = nodes.values.sumOf { it.framesSentWhileWaiting }
        val corruptWrites = nodes.values.sumOf { it.corruptWritesCount }
        val receivers = nodes.values.filter { !it.isOrigin && !it.isLegacy }
        val allComplete = receivers.isNotEmpty() && receivers.all { it.completed }

        return ScenarioReport(
            scenarioId = scenarioId,
            seed = seed,
            nodeCount = nodes.size,
            pieceCount = manifest.pieceHashes.size,
            totalBytes = totalBytes,
            virtualDurationMs = clock.nowMs - 1000L,
            realDurationMs = realDurationMs,
            originBytesUploaded = originUpload,
            originUploadRatio = ratio,
            framesSentWhileWaiting = waitingFrames,
            corruptWrites = corruptWrites,
            allCompleted = allComplete,
        )
    }
}
