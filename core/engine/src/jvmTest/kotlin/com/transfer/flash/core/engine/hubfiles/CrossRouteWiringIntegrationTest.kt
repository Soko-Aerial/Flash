@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.hubfiles

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.enterprise.files.DiskHubFileStorage
import com.transfer.flash.core.enterprise.files.FakeHubFileServer
import com.transfer.flash.core.enterprise.files.FakeHubFilesApi
import com.transfer.flash.core.enterprise.files.HubDownloadRequest
import com.transfer.flash.core.enterprise.files.HubFileResult
import com.transfer.flash.core.enterprise.files.HubFileSource
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.enterprise.files.HubFileTransfers
import com.transfer.flash.core.enterprise.files.HubFilesApi
import com.transfer.flash.core.enterprise.files.HubTransferPolicy
import com.transfer.flash.core.enterprise.files.HubUploadOutcome
import com.transfer.flash.core.enterprise.files.HubUploadRequest
import com.transfer.flash.core.messaging.hub.HubFileRoute
import com.transfer.flash.core.persistence.db.FlashDatabase
import com.transfer.flash.core.persistence.db.entity.HubFileEntity
import com.transfer.flash.core.persistence.db.entity.HubFileStates
import com.transfer.flash.core.persistence.db.openEncryptedFlashDatabase
import com.transfer.flash.core.security.enterprise.HubFileSealing
import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.SourceIdentity
import com.transfer.flash.core.transfer.SourceIdentityProvider
import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.FileMeta
import com.transfer.flash.core.transfer.chunked.IncrementalSha256
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.chunked.WholeFileCheck
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.policy.OkioRandomAccessSinkHandle
import com.transfer.flash.core.transfer.policy.RandomAccessChunkSink
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * End-to-end production-wiring integration tests for HR8 across [RealFlashTransferRepository],
 * [HubFileBinding], [TransferRouteCoordinator], [EngineTransferRouteContinuityPort], and [HubFileTransfers].
 */
class CrossRouteWiringIntegrationTest {

    private lateinit var tempDir: File
    private lateinit var worker: ExecutorCoroutineDispatcher
    private val dbs = mutableListOf<FlashDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val hub = "https://hub.example"
    private val server = FakeHubFileServer(maxFileBytes = 16L * 1024 * 1024, clockMs = { System.currentTimeMillis() })
    private val fastPolicy = HubTransferPolicy(maxConsecutiveFailures = 1, baseBackoffMs = 0L, maxBackoffMs = 0L)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("flash-hr8-wiring").toFile()
        worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        dbs.forEach { it.close() }
        worker.close()
        tempDir.deleteRecursively()
    }

    private class ByteArrayHubSource(private val bytes: ByteArray) : HubFileSource {
        override val size: Long = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private class RecordingApi(
        private val delegate: HubFilesApi,
        private val failChunkOnce: Int? = null,
    ) : HubFilesApi by delegate {
        val fetchedChunks = CopyOnWriteArrayList<Int>()
        private var failedOnce = false

        override suspend fun readChunk(hubUrl: String, fileId: String, index: Int, expectedBytes: Int): HubFileResult<ByteArray> {
            if (failChunkOnce != null && index == failChunkOnce && !failedOnce) {
                failedOnce = true
                return HubFileResult.Err(com.transfer.flash.core.enterprise.files.HubFileError.Offline)
            }
            fetchedChunks.add(index)
            return delegate.readChunk(hubUrl, fileId, index, expectedBytes)
        }
    }

    private suspend fun uploadFromAlice(fileId: String, bytes: ByteArray): HubFileTicket {
        val api = FakeHubFilesApi(server, "dev-a", hub)
        val uploader = HubFileTransfers(api, DiskHubFileStorage(File(tempDir, "up-$fileId").path), fastPolicy, worker)
        val res = uploader.upload(
            HubUploadRequest(
                hubUrl = hub,
                fileId = fileId,
                source = ByteArrayHubSource(bytes),
                toDeviceId = "dev-b",
                maxFileBytes = server.maxFileBytes,
            ),
        )
        return assertIs<HubUploadOutcome.Done>(res).ticket
    }

    private fun offeredRow(t: HubFileTicket, name: String) = HubFileEntity(
        transferId = HubFileStates.TRANSFER_PREFIX_IN + t.fileId,
        direction = HubFileStates.IN,
        fileId = t.fileId,
        peerId = "dev-a",
        hubUrl = hub,
        fileName = name,
        mime = "application/octet-stream",
        plainBytes = t.plainBytes,
        sourceUri = null,
        fileKey = t.key.encode(),
        cipherSha256 = t.cipherSha256,
        cipherChunkBytes = t.cipherChunkBytes,
        chunkCount = t.chunkCount,
        expiresAtMs = t.expiresAtMs,
        messageId = "msg-${t.fileId.take(6)}",
        state = HubFileStates.DOWNLOADING,
        paused = false,
        errorText = null,
        bytesDone = 0L,
        localPath = null,
        createdAtMs = 1L,
        updatedAtMs = 1L,
    )

    private fun sha256Hex(bytes: ByteArray): String =
        IncrementalSha256().apply { update(bytes, 0, bytes.size) }.digestHex()

    private suspend fun eventually(what: String, timeoutMs: Long = 10_000L, check: suspend () -> Boolean) {
        val met = runCatching { withTimeout(timeoutMs) { while (!check()) delay(15) } }.isSuccess
        assertTrue(met, "timed out waiting: $what")
    }

    @Test
    fun `HR8 LAN to Hub receive continuity skips verified LAN chunks, publishes one copy, and blocks late LAN completion`() = runBlocking(worker) {
        val payload = ByteArray(2_500_000).also { Random(71).nextBytes(it) }
        val expectedSha = sha256Hex(payload)
        val fileId = HubFileSealing.newFileId()
        val ticket = uploadFromAlice(fileId, payload)

        val scope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val db = openEncryptedFlashDatabase(File(tempDir, "bob1.db"), "key").also { dbs += it }
        val api = RecordingApi(FakeHubFilesApi(server, "dev-b", hub))
        val coordinator = TransferRouteCoordinator()
        var transfersRef: HubFileTransfers? = null
        val port = EngineTransferRouteContinuityPort(
            localDeviceId = { "dev-b" },
            coordinator = coordinator,
            transfersProvider = { transfersRef },
        )
        val opener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }
        val receivedDir = File(tempDir, "received-bob1").apply { mkdirs() }
        val repo = RealFlashTransferRepository(
            streamChannelFactory = { _, _ -> null },
            fileSourceOpener = opener,
            store = null,
            repositoryScope = scope,
            workerDispatcher = worker,
            routeContinuity = port,
        )
        val transfers = HubFileTransfers(api, DiskHubFileStorage(File(tempDir, "hub-bob1").path), fastPolicy, worker, Random(2)).also {
            transfersRef = it
        }

        // Step 1: Receive first 17 * 64 KiB LAN chunks (1,114,112 bytes > 1,048,560-byte Hub chunk 0) into LAN .part file.
        val lanPart = File(tempDir, "lan-partial-1.bin.part")
        val prefixLen = 17 * 65_536
        lanPart.writeBytes(payload.copyOfRange(0, prefixLen))
        val lanTxId = "lan-tx-1"
        repo.onIncomingStarted(
            transferId = lanTxId,
            fileId = fileId,
            fileName = "archive.bin",
            totalBytes = payload.size.toLong(),
            peerName = "Alice",
            peerDeviceId = "dev-a",
            localPath = lanPart.absolutePath,
        )
        repo.onIncomingChunkConfirmed(lanTxId, (0 until 17).toList())
        repo.onIncomingFailed(lanTxId, "LAN dropped")

        // Step 2: HubFileBinding takes over download with the shared TransferRouteCoordinator + port.
        val binding = HubFileBinding(
            scope = scope,
            dao = db.hubFileDao(),
            transfers = transfers,
            api = api,
            transferRepository = repo,
            hubUrl = { hub },
            fileRoute = { HubFileRoute.READY },
            queueTicket = { true },
            outgoingEnded = {},
            openSource = opener,
            receivedDirectory = { receivedDir.path },
            isDirectlyReachable = { false },
            peerName = { "Alice" },
            autoDownload = { _, _ -> false },
            workerDispatcher = worker,
            retryDelayMs = 20L,
            maxRetryDelayMs = 40L,
            continuityCoordinator = coordinator,
            continuityPort = port,
            localDeviceId = { "dev-b" },
        )
        val hubRow = offeredRow(ticket, "archive.bin")
        db.hubFileDao().insert(hubRow)
        binding.start()

        eventually("hub download completes") {
            db.hubFileDao().get(hubRow.transferId)?.state == HubFileStates.DONE
        }

        // Chunk 0 (covered by 17 * 64 KiB LAN chunks) was NOT re-downloaded from the Hub.
        assertFalse(0 in api.fetchedChunks, "Chunk 0 must be skipped via LAN verified byte adoption")
        assertTrue(1 in api.fetchedChunks && 2 in api.fetchedChunks)

        // Single published copy matches original bytes.
        val doneRow = assertNotNull(db.hubFileDao().get(hubRow.transferId))
        val published = assertNotNull(doneRow.localPath)
        assertContentEquals(payload, File(published).readBytes())
        assertEquals(ContinuityStatus.COMPLETED, coordinator.snapshot(fileId)?.status)
        assertEquals(1, receivedDir.walkTopDown().filter { it.isFile }.count())

        // Step 3: Late LAN completion must NOT publish a second copy or overwrite Hub completion.
        val lateLanFile = File(tempDir, "late-lan-complete.bin").apply { writeBytes(payload) }
        val lateCheck = repo.onIncomingFileAssembled(lanTxId, lateLanFile.absolutePath, expectedSha, chunksVerified = true)
        assertEquals(WholeFileCheck.MISMATCH, lateCheck)
        assertFalse(lateLanFile.exists(), "Duplicate copy from losing route must be deleted")
        assertEquals(1, receivedDir.walkTopDown().filter { it.isFile }.count())
    }

    @Test
    fun `HR8 Hub to LAN receive continuity adopts verified Hub chunks into LAN file and completes once`() = runBlocking(worker) {
        val payload = ByteArray(2_200_000).also { Random(82).nextBytes(it) }
        val expectedSha = sha256Hex(payload)
        val fileId = HubFileSealing.newFileId()
        val ticket = uploadFromAlice(fileId, payload)

        val scope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val baseApi = FakeHubFilesApi(server, "dev-b", hub)
        val failingApi = RecordingApi(baseApi, failChunkOnce = 1)
        val coordinator = TransferRouteCoordinator()
        var transfersRef: HubFileTransfers? = null
        val port = EngineTransferRouteContinuityPort(
            localDeviceId = { "dev-b" },
            coordinator = coordinator,
            transfersProvider = { transfersRef },
        )
        val opener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }
        val repo = RealFlashTransferRepository(
            streamChannelFactory = { _, _ -> null },
            fileSourceOpener = opener,
            store = null,
            repositoryScope = scope,
            workerDispatcher = worker,
            routeContinuity = port,
        )
        val transfers = HubFileTransfers(failingApi, DiskHubFileStorage(File(tempDir, "hub-bob2").path), fastPolicy, worker, Random(3)).also {
            transfersRef = it
        }

        // Step 1: Start Hub download; chunk 0 verifies and saves, chunk 1 interrupts.
        val identity = LogicalTransferIdentity(fileId, "dev-a", "dev-b", "video.bin", payload.size.toLong(), expectedSha)
        val claim = assertNotNull(coordinator.tryAcquireRoute(identity, RouteOwner.HUB))
        val outcome = transfers.download(
            HubDownloadRequest(hub, ticket, senderDeviceId = "dev-a"),
            onChunkVerified = { idx ->
                coordinator.recordVerifiedChunks(fileId, claim.epoch, setOf(idx), ticket.plainChunkBytesOrDefault())
            },
        )
        assertIs<com.transfer.flash.core.enterprise.files.HubDownloadOutcome.Interrupted>(outcome)
        coordinator.releaseRoute(fileId, claim.epoch, RouteOwner.HUB)

        // Step 2: LAN reconnects with 64 KiB chunks. First 15 chunks (0..14 = 983,040 B) are 100%-covered by Hub chunk 0 (1,048,560 B).
        val lanChunkSize = 65_536
        val lanTxId = "lan-tx-2"
        val preSeed = repo.prepareLanReceiveResume(lanTxId, fileId, "video.bin", payload.size.toLong(), "dev-a", lanChunkSize)
        assertEquals((0..14).toList(), preSeed)

        val lanDest = File(tempDir, "lan-dest-2.bin")
        val adopted = repo.adoptHubPartialForLan(lanTxId, fileId, "video.bin", payload.size.toLong(), "dev-a", lanChunkSize, lanDest.absolutePath)
        assertEquals((0..14).toList(), adopted)
        assertEquals((0..14).toList(), repo.receiverDoneIndexes(lanTxId))

        // Feed remaining LAN chunks 15..last through ReceivePipeline seeded by prepareLanReceiveResume.
        val chunker = Chunker()
        val meta = FileMeta(lanTxId, fileId, "video.bin", payload.size.toLong())
        val plan = chunker.plan(meta, lanChunkSize)
        val pipeline = ReceivePipeline(
            sink = ChunkSink { _, _ -> },
            sinkFactory = { start ->
                RandomAccessChunkSink(OkioRandomAccessSinkHandle(lanDest.absolutePath.toPath(), start.totalBytes), start.chunkSize)
            },
            resumeIndexesProvider = { start ->
                repo.prepareLanReceiveResume(start.transferId, start.fileId, start.fileName, start.totalBytes, "dev-a", start.chunkSize)
            },
        )
        repo.onIncomingStarted(lanTxId, fileId, "video.bin", payload.size.toLong(), "Alice", "dev-a", lanDest.absolutePath)
        pipeline.onFrame(ChunkFrame.serialize(chunker.fileStart(meta, plan, expectedSha)))
        var completedEvent: ReceiveEvent.Completed? = null
        for (idx in 15 until plan.totalChunks) {
            val offset = idx.toLong() * lanChunkSize
            val end = minOf(payload.size.toLong(), offset + lanChunkSize).toInt()
            val slice = payload.copyOfRange(offset.toInt(), end)
            val frame = ChunkFrame.Chunk(lanTxId, fileId, idx, slice, Sha256.digest(slice))
            val events = pipeline.onFrame(ChunkFrame.serialize(frame))
            events.filterIsInstance<ReceiveEvent.Completed>().firstOrNull()?.let { completedEvent = it }
        }
        val completed = assertNotNull(completedEvent)
        assertTrue(completed.frame.verified)

        val assembledCheck = repo.onIncomingFileAssembled(lanTxId, lanDest.absolutePath, expectedSha, chunksVerified = true)
        assertEquals(WholeFileCheck.MATCH, assembledCheck)
        assertContentEquals(payload, lanDest.readBytes())
        val snap = assertNotNull(coordinator.snapshot(fileId))
        assertEquals(ContinuityStatus.COMPLETED, snap.status)
        assertEquals(expectedSha, snap.finalDigestHex)
        assertEquals(lanDest.absolutePath, snap.completedPath)
    }

    @Test
    fun `HR8 cancel on LAN marks Hub route cancelled and prevents duplicate delivery`() = runBlocking(worker) {
        val fileId = HubFileSealing.newFileId()
        val payload = ByteArray(128 * 1024).also { Random(93).nextBytes(it) }
        val ticket = uploadFromAlice(fileId, payload)

        val scope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val db = openEncryptedFlashDatabase(File(tempDir, "bob3.db"), "key").also { dbs += it }
        val coordinator = TransferRouteCoordinator()
        val port = EngineTransferRouteContinuityPort(
            localDeviceId = { "dev-b" },
            coordinator = coordinator,
            transfersProvider = { null },
        )
        val opener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }
        val repo = RealFlashTransferRepository(
            streamChannelFactory = { _, _ -> null },
            fileSourceOpener = opener,
            store = null,
            repositoryScope = scope,
            workerDispatcher = worker,
            routeContinuity = port,
        )

        val lanTxId = "lan-tx-cancel"
        repo.onIncomingStarted(lanTxId, fileId, "cancel.bin", payload.size.toLong(), "Alice", "dev-a", File(tempDir, "c.part").absolutePath)
        repo.cancelTransfer(FlashTransferId(lanTxId))

        assertEquals(ContinuityStatus.CANCELLED, coordinator.snapshot(fileId)?.status)

        val binding = HubFileBinding(
            scope = scope,
            dao = db.hubFileDao(),
            transfers = HubFileTransfers(FakeHubFilesApi(server, "dev-b", hub), DiskHubFileStorage(File(tempDir, "hub-bob3").path), fastPolicy, worker),
            api = FakeHubFilesApi(server, "dev-b", hub),
            transferRepository = repo,
            hubUrl = { hub },
            fileRoute = { HubFileRoute.READY },
            queueTicket = { true },
            outgoingEnded = {},
            openSource = opener,
            receivedDirectory = { File(tempDir, "rx3").apply { mkdirs() }.path },
            isDirectlyReachable = { false },
            peerName = { "Alice" },
            autoDownload = { _, _ -> false },
            workerDispatcher = worker,
            continuityCoordinator = coordinator,
            continuityPort = port,
            localDeviceId = { "dev-b" },
        )
        val hubRow = offeredRow(ticket, "cancel.bin")
        db.hubFileDao().insert(hubRow)
        binding.start()

        eventually("hub row moves to CANCELLED instead of downloading") {
            db.hubFileDao().get(hubRow.transferId)?.state == HubFileStates.CANCELLED
        }
    }

    @Test
    fun `HR8 normal sender sendFile and Retry switches LAN to Hub with same logical identity, single row, and skips verified receiver chunks`() = runBlocking(worker) {
        val payload = ByteArray(2_200_000).also { Random(101).nextBytes(it) }
        val expectedSha = sha256Hex(payload)
        val srcFile = File(tempDir, "report.bin").apply { writeBytes(payload) }
        val scope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val aliceDb = openEncryptedFlashDatabase(File(tempDir, "alice-l2h.db"), "key").also { dbs += it }
        val bobDb = openEncryptedFlashDatabase(File(tempDir, "bob-l2h.db"), "key").also { dbs += it }
        val opener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }

        val aliceCoordinator = TransferRouteCoordinator(FileContinuityCheckpointStore(File(tempDir, "alice-ckpt").path))
        val bobCoordinator = TransferRouteCoordinator(FileContinuityCheckpointStore(File(tempDir, "bob-ckpt").path))
        var aliceHubTransfersRef: HubFileTransfers? = null
        var bobHubTransfersRef: HubFileTransfers? = null
        val alicePort = EngineTransferRouteContinuityPort({ "dev-a" }, aliceCoordinator, { aliceHubTransfersRef })
        val bobPort = EngineTransferRouteContinuityPort({ "dev-b" }, bobCoordinator, { bobHubTransfersRef })

        val bobRxRoot = File(tempDir, "bob-l2h-rx").apply { mkdirs() }
        val bobPartial = File(tempDir, "bob-l2h-partial.part")
        lateinit var aliceRepo: RealFlashTransferRepository
        lateinit var bobRepo: RealFlashTransferRepository
        var bobLanChunkFrames = 0
        var directLanUp = true

        val bobPipeline = ReceivePipeline(
            sink = ChunkSink { _, _ -> },
            ackEvery = 1,
            emitSessionStarted = true,
            sinkFactory = { start ->
                RandomAccessChunkSink(OkioRandomAccessSinkHandle(bobPartial.absolutePath.toPath(), start.totalBytes), start.chunkSize)
            },
            resumeIndexesProvider = { start ->
                bobRepo.prepareLanReceiveResume(start.transferId, start.fileId, start.fileName, start.totalBytes, "dev-a", start.chunkSize)
            },
        )
        bobRepo = RealFlashTransferRepository(
            streamChannelFactory = { _, _ -> null },
            fileSourceOpener = opener,
            store = null,
            repositoryScope = scope,
            workerDispatcher = worker,
            routeContinuity = bobPort,
        )

        val bobDevice = FlashDevice(FlashDeviceId("dev-b"), "Bob", FlashTransportType.LAN)
        val aliceChannelFactory: (Int, String?) -> StreamChannel? = { chId, _ ->
            if (!directLanUp) {
                null
            } else {
                object : StreamChannel {
                    override val id: Int = chId
                    override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                        if (!directLanUp) return false
                        val decoded = ChunkFrame.parse(frameBytes)
                        if (decoded is ChunkFrame.Chunk) {
                            bobLanChunkFrames++
                            if (bobLanChunkFrames > 20) {
                                // Simulate mid-transfer LAN drop after the first 20 chunks (~1.25 MB).
                                directLanUp = false
                                return false
                            }
                        }
                        val events = bobPipeline.onFrame(frameBytes)
                        for (ev in events) {
                            when (ev) {
                                is ReceiveEvent.SessionStarted -> {
                                    bobRepo.onIncomingStarted(
                                        ev.frame.transferId,
                                        ev.frame.fileId,
                                        ev.frame.fileName,
                                        ev.frame.totalBytes,
                                        "Alice",
                                        "dev-a",
                                        bobPartial.absolutePath,
                                    )
                                }
                                is ReceiveEvent.AckBatchReady -> {
                                    bobRepo.onIncomingChunkConfirmed(ev.frame.transferId, ev.frame.indexes)
                                    aliceRepo.onInboundFrame(ChunkFrame.serialize(ev.frame))
                                }
                                else -> Unit
                            }
                        }
                        return true
                    }
                }
            }
        }
        aliceRepo = RealFlashTransferRepository(
            streamChannelFactory = aliceChannelFactory,
            fileSourceOpener = opener,
            store = null,
            repositoryScope = scope,
            workerDispatcher = worker,
            defaultStreams = 1,
            routeContinuity = alicePort,
            sourceIdentityProvider = object : SourceIdentityProvider {
                override suspend fun identity(fileUri: String): SourceIdentity? {
                    val f = File(fileUri)
                    if (!f.exists()) return null
                    val raw = f.readBytes()
                    return SourceIdentity(f.length(), f.lastModified(), sha256Hex(raw.copyOf(minOf(65536, raw.size))))
                }
            },
        )

        var queuedOutTransferId: String? = null
        val aliceHubTransfers = HubFileTransfers(FakeHubFilesApi(server, "dev-a", hub), DiskHubFileStorage(File(tempDir, "alice-hub").path), fastPolicy, worker)
        aliceHubTransfersRef = aliceHubTransfers
        val aliceBinding = HubFileBinding(
            scope = scope,
            dao = aliceDb.hubFileDao(),
            transfers = aliceHubTransfers,
            api = FakeHubFilesApi(server, "dev-a", hub),
            transferRepository = aliceRepo,
            hubUrl = { hub },
            fileRoute = { HubFileRoute.READY },
            queueTicket = { txId ->
                queuedOutTransferId = txId
                true
            },
            outgoingEnded = {},
            openSource = opener,
            receivedDirectory = { File(tempDir, "alice-rx").apply { mkdirs() }.path },
            isDirectlyReachable = { directLanUp },
            peerName = { "Bob" },
            autoDownload = { _, _ -> false },
            workerDispatcher = worker,
            continuityCoordinator = aliceCoordinator,
            continuityPort = alicePort,
            localDeviceId = { "dev-a" },
        ).also { it.start() }

        val bobRecordingApi = RecordingApi(FakeHubFilesApi(server, "dev-b", hub))
        val bobHubTransfers = HubFileTransfers(bobRecordingApi, DiskHubFileStorage(File(tempDir, "bob-hub").path), fastPolicy, worker)
        bobHubTransfersRef = bobHubTransfers
        val bobBinding = HubFileBinding(
            scope = scope,
            dao = bobDb.hubFileDao(),
            transfers = bobHubTransfers,
            api = bobRecordingApi,
            transferRepository = bobRepo,
            hubUrl = { hub },
            fileRoute = { HubFileRoute.READY },
            queueTicket = { true },
            outgoingEnded = {},
            openSource = opener,
            receivedDirectory = { bobRxRoot.path },
            isDirectlyReachable = { directLanUp },
            peerName = { "Alice" },
            autoDownload = { _, _ -> true },
            workerDispatcher = worker,
            continuityCoordinator = bobCoordinator,
            continuityPort = bobPort,
            localDeviceId = { "dev-b" },
        ).also { it.start() }

        // Step 1: Normal user sendFile while direct LAN is initially reachable -> fails partway.
        val sendRes = aliceRepo.sendFile(bobDevice, srcFile.absolutePath, "report.bin", payload.size.toLong())
        val txId = (sendRes as com.transfer.flash.core.common.result.FlashResult.Success).value
        eventually("LAN send fails partway after drop") {
            aliceRepo.activeTransfers.value.firstOrNull { it.id == txId }?.state == FlashTransferState.Failed
        }
        val failedRow = aliceRepo.activeTransfers.value.single { it.id == txId }
        val wireFileId = assertNotNull(failedRow.wireFileId)
        bobRepo.onIncomingFailed(txId.value, "connection dropped")

        // Step 2: Normal user Retry while LAN is offline -> seamlessly hands over to Hub under SAME transferId + fileId.
        aliceRepo.resumeTransfer(txId)
        eventually("Hub upload completes under same transferId") {
            queuedOutTransferId == txId.value &&
                aliceRepo.activeTransfers.value.singleOrNull { it.id == txId }?.state == FlashTransferState.Completed
        }
        assertEquals(1, aliceRepo.activeTransfers.value.count { it.wireFileId == wireFileId })
        val aliceHubEntity = assertNotNull(aliceDb.hubFileDao().get(txId.value))
        assertEquals(wireFileId, aliceHubEntity.fileId)

        // Step 3: Bob receives ticket, adopts verified LAN bytes, skips Hub chunk 0, and publishes ONE copy.
        val ticket = HubFileTicket(
            fileId = wireFileId,
            key = com.transfer.flash.core.security.enterprise.HubFileKey.decode(aliceHubEntity.fileKey!!)!!,
            cipherSha256 = aliceHubEntity.cipherSha256!!,
            plainBytes = aliceHubEntity.plainBytes,
            cipherChunkBytes = aliceHubEntity.cipherChunkBytes,
            chunkCount = aliceHubEntity.chunkCount,
            expiresAtMs = aliceHubEntity.expiresAtMs,
        )
        bobDb.hubFileDao().insert(offeredRow(ticket, "report.bin").copy(state = HubFileStates.OFFERED))
        eventually("Bob Hub download completes with one row") {
            bobDb.hubFileDao().get(HubFileStates.TRANSFER_PREFIX_IN + wireFileId)?.state == HubFileStates.DONE &&
                bobRepo.activeTransfers.value.singleOrNull { it.wireFileId == wireFileId }?.state == FlashTransferState.Completed
        }
        assertFalse(0 in bobRecordingApi.fetchedChunks, "Verified Hub chunk 0 covered by LAN must be skipped")
        assertEquals(1, bobRepo.activeTransfers.value.count { it.wireFileId == wireFileId })
        val bobFinal = bobRepo.activeTransfers.value.single { it.wireFileId == wireFileId }
        assertEquals(FlashTransferState.Completed, bobFinal.state)
        val bobPublished = File(assertNotNull(bobFinal.localPath))
        assertContentEquals(payload, bobPublished.readBytes())
        assertEquals(expectedSha, bobCoordinator.snapshot(wireFileId)?.finalDigestHex)
        aliceBinding.detach()
        bobBinding.detach()
    }

    @Test
    fun `HR8 normal sender Hub upload interrupted resumes onto LAN via Retry preserving transferId and fileId`() = runBlocking(worker) {
        val payload = ByteArray(1_400_000).also { Random(102).nextBytes(it) }
        val expectedSha = sha256Hex(payload)
        val srcFile = File(tempDir, "handoff.bin").apply { writeBytes(payload) }
        val scope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val aliceDb = openEncryptedFlashDatabase(File(tempDir, "alice-h2l.db"), "key").also { dbs += it }
        val opener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }
        val aliceCoordinator = TransferRouteCoordinator(FileContinuityCheckpointStore(File(tempDir, "alice-h2l-ckpt").path))
        var directLanUp = false

        // Failing Hub API: allows create + part 0, then fails part 1 cleanly so upload stops in FAILED/UPLOADING.
        val realAliceHubApi = FakeHubFilesApi(server, "dev-a", hub)
        val failingHubApi = object : HubFilesApi by realAliceHubApi {
            override suspend fun putChunk(
                hubUrl: String,
                fileId: String,
                index: Int,
                bytes: ByteArray,
                sha256Hex: String,
            ): HubFileResult<Unit> =
                if (index >= 1) HubFileResult.Err(com.transfer.flash.core.enterprise.files.HubFileError.NotFound)
                else realAliceHubApi.putChunk(hubUrl, fileId, index, bytes, sha256Hex)
        }
        val bobRxFile = File(tempDir, "bob-h2l-final.bin")
        lateinit var aliceRepo: RealFlashTransferRepository
        val bobPipeline = ReceivePipeline(
            sink = ChunkSink { _, _ -> },
            ackEvery = 1,
            sinkFactory = { start ->
                RandomAccessChunkSink(OkioRandomAccessSinkHandle(bobRxFile.absolutePath.toPath(), start.totalBytes), start.chunkSize)
            },
        )
        aliceRepo = RealFlashTransferRepository(
            streamChannelFactory = { chId, _ ->
                if (!directLanUp) null else object : StreamChannel {
                    override val id: Int = chId
                    override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                        for (ev in bobPipeline.onFrame(frameBytes)) {
                            when (ev) {
                                is ReceiveEvent.AckBatchReady -> aliceRepo.onInboundFrame(ChunkFrame.serialize(ev.frame))
                                is ReceiveEvent.Completed -> aliceRepo.onInboundFrame(ChunkFrame.serialize(ev.frame))
                                else -> Unit
                            }
                        }
                        return true
                    }
                }
            },
            fileSourceOpener = opener,
            store = null,
            repositoryScope = scope,
            workerDispatcher = worker,
            defaultStreams = 1,
            routeContinuity = EngineTransferRouteContinuityPort({ "dev-a" }, aliceCoordinator, { null }),
        )
        val aliceHubTransfers = HubFileTransfers(failingHubApi, DiskHubFileStorage(File(tempDir, "alice-h2l-hub").path), fastPolicy, worker)
        val aliceBinding = HubFileBinding(
            scope = scope,
            dao = aliceDb.hubFileDao(),
            transfers = aliceHubTransfers,
            api = failingHubApi,
            transferRepository = aliceRepo,
            hubUrl = { hub },
            fileRoute = { HubFileRoute.READY },
            queueTicket = { true },
            outgoingEnded = {},
            openSource = opener,
            receivedDirectory = { File(tempDir, "a-rx").apply { mkdirs() }.path },
            isDirectlyReachable = { directLanUp },
            peerName = { "Bob" },
            autoDownload = { _, _ -> false },
            workerDispatcher = worker,
            continuityCoordinator = aliceCoordinator,
            localDeviceId = { "dev-a" },
        ).also { it.start() }

        val bobDevice = FlashDevice(FlashDeviceId("dev-b"), "Bob", FlashTransportType.LAN)
        val sendRes = aliceRepo.sendFile(bobDevice, srcFile.absolutePath, "handoff.bin", payload.size.toLong())
        val txId = (sendRes as com.transfer.flash.core.common.result.FlashResult.Success).value
        eventually("initial Hub upload fails on unrecoverable chunk error") {
            aliceRepo.activeTransfers.value.firstOrNull { it.id == txId }?.state == FlashTransferState.Failed
        }
        val originalFileId = assertNotNull(aliceRepo.activeTransfers.value.single { it.id == txId }.wireFileId)

        // Now LAN returns and Alice clicks Retry -> hands over HUB -> LAN keeping exact transferId + fileId!
        directLanUp = true
        aliceRepo.resumeTransfer(txId)
        eventually("LAN completes transfer after Hub -> LAN handover under same transferId") {
            val row = aliceRepo.activeTransfers.value.singleOrNull { it.id == txId }
            row?.state == FlashTransferState.Completed && !row.viaHub
        }
        assertEquals(originalFileId, aliceRepo.activeTransfers.value.single { it.id == txId }.wireFileId)
        assertEquals(null, aliceDb.hubFileDao().get(txId.value), "Superseded hub_file row retired cleanly")
        assertContentEquals(payload, bobRxFile.readBytes())
        assertEquals(expectedSha, sha256Hex(bobRxFile.readBytes()))
        aliceBinding.detach()
    }

    @Test
    fun `HR8 process restart preserves continuity spans, partial path, and cancel fence across opposite-route resume`() = runBlocking(worker) {
        val payload = ByteArray(2_300_000).also { Random(103).nextBytes(it) }
        val expectedSha = sha256Hex(payload)
        val fileId = HubFileSealing.newFileId()
        val cancelFileId = HubFileSealing.newFileId()
        val ticket = uploadFromAlice(fileId, payload)
        val cancelTicket = uploadFromAlice(cancelFileId, ByteArray(64 * 1024).also { Random(104).nextBytes(it) })
        val ckptDir = File(tempDir, "bob-restart-ckpt").path
        val lanPartial = File(tempDir, "bob-restart-lan.part")
        val hubStorageDir = File(tempDir, "bob-restart-hub").path
        val dbFile = File(tempDir, "bob-restart.db")
        val rxRoot = File(tempDir, "bob-restart-rx").apply { mkdirs() }
        val opener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }

        // Session 1: Receive partial LAN chunks + cancel a second transfer, then shut down.
        val scope1 = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val db1 = openEncryptedFlashDatabase(dbFile, "key")
        val coord1 = TransferRouteCoordinator(FileContinuityCheckpointStore(ckptDir))
        val port1 = EngineTransferRouteContinuityPort({ "dev-b" }, coord1, { null })
        val repo1 = RealFlashTransferRepository(
            streamChannelFactory = { _, _ -> null },
            fileSourceOpener = opener,
            store = null,
            repositoryScope = scope1,
            workerDispatcher = worker,
            routeContinuity = port1,
        )
        val lanTxId = "lan-restart-tx"
        val lanChunkSize = 65_536
        repo1.onIncomingStarted(lanTxId, fileId, "big.bin", payload.size.toLong(), "Alice", "dev-a", lanPartial.absolutePath)
        lanPartial.outputStream().use { out ->
            out.write(payload, 0, 17 * lanChunkSize)
        }
        repo1.onIncomingChunkConfirmed(lanTxId, (0 until 17).toList())
        // Also cancel second transfer before restart.
        repo1.onIncomingStarted("lan-cancel-rx", cancelFileId, "no.bin", 65_536L, "Alice", "dev-a", File(tempDir, "no.part").absolutePath)
        repo1.cancelTransfer(FlashTransferId("lan-cancel-rx"))
        scope1.cancel()
        db1.close()

        // Session 2 (fresh restart): NEW coordinator + port + HubFileBinding with NO in-memory trackedByFileId!
        val scope2 = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val db2 = openEncryptedFlashDatabase(dbFile, "key").also { dbs += it }
        val coord2 = TransferRouteCoordinator(FileContinuityCheckpointStore(ckptDir))
        val restartedSnap = assertNotNull(coord2.snapshot(fileId))
        assertEquals(RouteOwner.NONE, restartedSnap.activeRoute, "Restart must clear stale active route lock")
        assertTrue(restartedSnap.verifiedBytes >= 17L * lanChunkSize)
        assertEquals(lanPartial.absolutePath, restartedSnap.lanPartialPath)
        assertEquals(ContinuityStatus.CANCELLED, coord2.snapshot(cancelFileId)?.status)

        val api2 = RecordingApi(FakeHubFilesApi(server, "dev-b", hub))
        val hubTransfers2 = HubFileTransfers(api2, DiskHubFileStorage(hubStorageDir), fastPolicy, worker)
        val port2 = EngineTransferRouteContinuityPort({ "dev-b" }, coord2, { hubTransfers2 })
        val repo2 = RealFlashTransferRepository(
            streamChannelFactory = { _, _ -> null },
            fileSourceOpener = opener,
            store = null,
            repositoryScope = scope2,
            workerDispatcher = worker,
            routeContinuity = port2,
        )
        val binding2 = HubFileBinding(
            scope = scope2,
            dao = db2.hubFileDao(),
            transfers = hubTransfers2,
            api = api2,
            transferRepository = repo2,
            hubUrl = { hub },
            fileRoute = { HubFileRoute.READY },
            queueTicket = { true },
            outgoingEnded = {},
            openSource = opener,
            receivedDirectory = { rxRoot.path },
            isDirectlyReachable = { false },
            peerName = { "Alice" },
            autoDownload = { _, _ -> false },
            workerDispatcher = worker,
            continuityCoordinator = coord2,
            continuityPort = port2,
            localDeviceId = { "dev-b" },
        )
        db2.hubFileDao().insert(offeredRow(ticket, "big.bin"))
        db2.hubFileDao().insert(offeredRow(cancelTicket, "no.bin"))
        binding2.start()

        eventually("Restarted receiver finishes via Hub skipping verified chunk 0 and blocks cancelled item") {
            db2.hubFileDao().get(HubFileStates.TRANSFER_PREFIX_IN + fileId)?.state == HubFileStates.DONE &&
                db2.hubFileDao().get(HubFileStates.TRANSFER_PREFIX_IN + cancelFileId)?.state == HubFileStates.CANCELLED
        }
        assertFalse(0 in api2.fetchedChunks, "Hub chunk 0 already covered by persisted LAN partial bytes")
        val doneEntity = assertNotNull(db2.hubFileDao().get(HubFileStates.TRANSFER_PREFIX_IN + fileId))
        assertContentEquals(payload, File(assertNotNull(doneEntity.localPath)).readBytes())
        assertEquals(expectedSha, coord2.snapshot(fileId)?.finalDigestHex)
    }

    @Test
    fun `HR8 refuses changed source and oversized Hub fallback explicitly without mixing bytes`() = runBlocking(worker) {
        val payload = ByteArray(256 * 1024).also { Random(105).nextBytes(it) }
        val srcFile = File(tempDir, "mutated.bin").apply { writeBytes(payload) }
        val oversizedFile = File(tempDir, "huge.bin").apply { writeBytes(ByteArray(300 * 1024) { 7 }) }
        val scope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val db = openEncryptedFlashDatabase(File(tempDir, "guard.db"), "key").also { dbs += it }
        val opener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }
        val coordinator = TransferRouteCoordinator(FileContinuityCheckpointStore(File(tempDir, "guard-ckpt").path))
        var hubUpCalls = 0
        val tinyServer = FakeHubFileServer(maxFileBytes = 200L * 1024L, clockMs = { System.currentTimeMillis() })
        val delegateApi = FakeHubFilesApi(tinyServer, "dev-a", hub)
        val trackingApi = object : HubFilesApi by delegateApi {
            override suspend fun create(
                hubUrl: String,
                request: com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto,
            ): HubFileResult<com.transfer.flash.core.hubprotocol.rest.FileTicketDto> {
                hubUpCalls++
                return delegateApi.create(hubUrl, request)
            }
        }
        var lanUp = true
        val repo = RealFlashTransferRepository(
            streamChannelFactory = { _, _ -> null }, // Fail initial LAN send immediately.
            fileSourceOpener = opener,
            store = null,
            repositoryScope = scope,
            workerDispatcher = worker,
            routeContinuity = EngineTransferRouteContinuityPort({ "dev-a" }, coordinator, { null }),
            sourceIdentityProvider = object : SourceIdentityProvider {
                override suspend fun identity(fileUri: String): SourceIdentity? {
                    val f = File(fileUri)
                    if (!f.exists()) return null
                    val raw = f.readBytes()
                    return SourceIdentity(f.length(), f.lastModified(), sha256Hex(raw.copyOf(minOf(65536, raw.size))))
                }
            },
        )
        HubFileBinding(
            scope = scope,
            dao = db.hubFileDao(),
            transfers = HubFileTransfers(trackingApi, DiskHubFileStorage(File(tempDir, "guard-hub").path), fastPolicy, worker),
            api = trackingApi,
            transferRepository = repo,
            hubUrl = { hub },
            fileRoute = { HubFileRoute.READY },
            queueTicket = { true },
            outgoingEnded = {},
            openSource = opener,
            receivedDirectory = { File(tempDir, "guard-rx").apply { mkdirs() }.path },
            isDirectlyReachable = { lanUp },
            peerName = { "Bob" },
            autoDownload = { _, _ -> false },
            workerDispatcher = worker,
            continuityCoordinator = coordinator,
            localDeviceId = { "dev-a" },
        ).also { it.start() }

        val bob = FlashDevice(FlashDeviceId("dev-b"), "Bob", FlashTransportType.LAN)
        val changedId = (repo.sendFile(bob, srcFile.absolutePath, "mutated.bin", payload.size.toLong()) as com.transfer.flash.core.common.result.FlashResult.Success).value
        val oversizedId = (repo.sendFile(bob, oversizedFile.absolutePath, "huge.bin", oversizedFile.length()) as com.transfer.flash.core.common.result.FlashResult.Success).value
        eventually("both initial LAN sends fail without session") {
            repo.activeTransfers.value.firstOrNull { it.id == changedId }?.state == FlashTransferState.Failed &&
                repo.activeTransfers.value.firstOrNull { it.id == oversizedId }?.state == FlashTransferState.Failed
        }

        // Tamper same-size source file content before Hub Retry -> must fail closed as INCOMPATIBLE / FILE_CHANGED!
        val modified = payload.copyOf().also { it[0] = (it[0].toInt() xor 0x5A).toByte() }
        srcFile.writeBytes(modified)
        srcFile.setLastModified(System.currentTimeMillis() + 5_000L)
        lanUp = false

        repo.resumeTransfer(changedId)
        val changedRow = repo.activeTransfers.value.single { it.id == changedId }
        assertEquals(FlashTransferState.Failed, changedRow.state)
        assertEquals(TransferFailureText.FILE_CHANGED, changedRow.errorMessage)
        assertEquals(ContinuityStatus.INCOMPATIBLE, coordinator.snapshot(changedRow.wireFileId!!)?.status)
        assertEquals(0, hubUpCalls)

        // Oversized Hub fallback fails explicitly instead of silently relaunching broken LAN or uploading.
        repo.resumeTransfer(oversizedId)
        val oversizedRow = repo.activeTransfers.value.single { it.id == oversizedId }
        assertEquals(FlashTransferState.Failed, oversizedRow.state)
        assertTrue(oversizedRow.errorMessage?.isNotBlank() == true)
        assertEquals(0, hubUpCalls)
    }

    private fun HubFileTicket.plainChunkBytesOrDefault(): Int =
        com.transfer.flash.core.security.enterprise.HubFileLayout.of(plainBytes, cipherChunkBytes)?.plainChunkBytes
            ?: (cipherChunkBytes - com.transfer.flash.core.security.enterprise.HubFileSealing.TAG_BYTES)
}
