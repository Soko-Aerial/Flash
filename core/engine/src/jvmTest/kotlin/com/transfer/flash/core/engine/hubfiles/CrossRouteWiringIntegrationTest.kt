@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.hubfiles

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

    private fun HubFileTicket.plainChunkBytesOrDefault(): Int =
        com.transfer.flash.core.security.enterprise.HubFileLayout.of(plainBytes, cipherChunkBytes)?.plainChunkBytes
            ?: (cipherChunkBytes - com.transfer.flash.core.security.enterprise.HubFileSealing.TAG_BYTES)
}
