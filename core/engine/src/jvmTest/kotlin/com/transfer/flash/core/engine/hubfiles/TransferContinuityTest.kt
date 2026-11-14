package com.transfer.flash.core.engine.hubfiles

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.enterprise.files.DiskHubFileStorage
import com.transfer.flash.core.enterprise.files.FakeHubFileServer
import com.transfer.flash.core.enterprise.files.FakeHubFilesApi
import com.transfer.flash.core.enterprise.files.HubDownloadOutcome
import com.transfer.flash.core.enterprise.files.HubDownloadRequest
import com.transfer.flash.core.enterprise.files.HubFileError
import com.transfer.flash.core.enterprise.files.HubFileResult
import com.transfer.flash.core.enterprise.files.HubFileSource
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.enterprise.files.HubFileTransfers
import com.transfer.flash.core.enterprise.files.HubFilesApi
import com.transfer.flash.core.enterprise.files.HubTransferPolicy
import com.transfer.flash.core.enterprise.files.HubUploadOutcome
import com.transfer.flash.core.enterprise.files.HubUploadRequest
import com.transfer.flash.core.security.enterprise.HubFileSealing
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.chunked.WholeFileDigestProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@OptIn(FlashInternalApi::class)
class TransferContinuityTest {

    private lateinit var tempDir: File
    private lateinit var server: FakeHubFileServer
    private val hubUrl = "https://hub.example"
    private val fastPolicy = HubTransferPolicy(maxConsecutiveFailures = 1, baseBackoffMs = 0L, maxBackoffMs = 0L)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("flash-hr8-continuity").toFile()
        server = FakeHubFileServer(maxFileBytes = 32L * 1024 * 1024)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private class ByteArraySource(private val bytes: ByteArray) : HubFileSource {
        override val size: Long = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray =
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private class MemoryChunkSink(totalBytes: Long, private val chunkSize: Int) : ChunkSink {
        val backing = ByteArray(totalBytes.toInt())
        override fun write(index: Int, data: ByteArray) {
            val start = index * chunkSize
            System.arraycopy(data, 0, backing, start, data.size)
        }
    }

    private fun testPayload(size: Int): ByteArray = ByteArray(size).also { Random(424242).nextBytes(it) }

    private suspend fun uploadToHub(bytes: ByteArray, fileId: String = HubFileSealing.newFileId()): HubFileTicket {
        val storage = DiskHubFileStorage(File(tempDir, "ann-up-$fileId").path)
        val uploader = HubFileTransfers(FakeHubFilesApi(server, "dev-a", hubUrl), storage, fastPolicy, Dispatchers.Default)
        val outcome = uploader.upload(
            HubUploadRequest(
                hubUrl = hubUrl,
                fileId = fileId,
                source = ByteArraySource(bytes),
                toDeviceId = "dev-b",
                maxFileBytes = server.maxFileBytes,
            ),
        )
        return assertIs<HubUploadOutcome.Done>(outcome).ticket
    }

    @Test
    fun `HR8 byte alignment converts 1MiB Hub chunks to 64KiB LAN chunks without partial overlap`() {
        val totalBytes = 2_500_000L
        val hubChunkSize = 1_048_560 // Hub plaintext chunk size (1 MiB ciphertext - 16-byte tag)
        val lanChunkSize = 65_536

        // Only Hub chunk 0 is complete -> [0, 1_048_560). That spans 15 full 64 KiB chunks (0..14 = 983,040 bytes),
        // leaving LAN chunk 15 [983,040, 1_048_576) short by 16 bytes -> MUST NOT be marked covered!
        val spans = TransferByteAlignment.chunkRanges(setOf(0), hubChunkSize, totalBytes)
        val lanChunks = TransferByteAlignment.intersectChunksCoveredBySpans(spans, lanChunkSize, totalBytes)
        assertEquals((0..14).toSet(), lanChunks)
        assertFalse(15 in lanChunks, "partial LAN chunk overlapping by 16 bytes must be excluded")

        // Inverse: 32 contiguous 64 KiB LAN chunks = 2,097,152 bytes covers Hub chunks 0 and 1 (2 * 1,048,560 = 2,097,120).
        val lanSpans = TransferByteAlignment.chunkRanges((0..31).toSet(), lanChunkSize, totalBytes)
        val hubChunks = TransferByteAlignment.intersectChunksCoveredBySpans(lanSpans, hubChunkSize, totalBytes)
        assertEquals(setOf(0, 1), hubChunks)

        // 31 LAN chunks = 2,031,616 bytes -> covers Hub chunk 0 only, NOT Hub chunk 1 (needs 2,097,120).
        val partialLanSpans = TransferByteAlignment.chunkRanges((0..30).toSet(), lanChunkSize, totalBytes)
        val hubChunksPartial = TransferByteAlignment.intersectChunksCoveredBySpans(partialLanSpans, hubChunkSize, totalBytes)
        assertEquals(setOf(0), hubChunksPartial)
    }

    @Test
    fun `HR8 end-to-end Hub interrupted download resumes over LAN ReceivePipeline with single completion`(): Unit = runBlocking<Unit> {
        val payload = testPayload(2_400_000) // > 2 Hub chunks (chunk 0 = 1,048,560, chunk 1 = 1,048,560, chunk 2 = 302,880)
        val sha256 = Sha256.digestHex(payload)
        val ticket = uploadToHub(payload)
        val identity = LogicalTransferIdentity(
            logicalTransferId = ticket.fileId,
            senderDeviceId = "dev-a",
            recipientDeviceId = "dev-b",
            fileName = "archive.tar",
            plainBytes = payload.size.toLong(),
            fileSha256Hex = sha256,
        )

        val coordinator = TransferRouteCoordinator()
        val hubClaim = assertNotNull(coordinator.tryAcquireRoute(identity, RouteOwner.HUB))

        // Simulate interrupted Hub download that stopped after downloading chunk 0 cleanly.
        val baseApi = FakeHubFilesApi(server, "dev-b", hubUrl)
        val failOnceOnChunk1 = object : HubFilesApi by baseApi {
            private val seenChunk1 = AtomicInteger(0)
            override suspend fun readChunk(hubUrl: String, fileId: String, index: Int, expectedBytes: Int): HubFileResult<ByteArray> {
                if (index == 1 && seenChunk1.incrementAndGet() <= 5) {
                    return HubFileResult.Err(HubFileError.Offline)
                }
                return baseApi.readChunk(hubUrl, fileId, index, expectedBytes)
            }
        }
        val hubStorage = DiskHubFileStorage(File(tempDir, "bob-hub-down").path)
        val hubTransfers = HubFileTransfers(failOnceOnChunk1, hubStorage, fastPolicy, Dispatchers.Default)
        val interrupted = hubTransfers.download(
            HubDownloadRequest(hubUrl = hubUrl, ticket = ticket, senderDeviceId = "dev-a", plainSha256 = sha256),
        )
        assertIs<HubDownloadOutcome.Interrupted>(interrupted)

        // Export verified Hub chunk 0 and handover ownership to LAN route.
        val lanClaim = assertNotNull(coordinator.handoverRoute(ticket.fileId, hubClaim.epoch, RouteOwner.HUB, RouteOwner.LAN))
        val lanChunkSize = 65_536
        val lanSink = MemoryChunkSink(payload.size.toLong(), lanChunkSize)
        val plan = coordinator.adoptHubToLan(
            transfers = hubTransfers,
            fileId = ticket.fileId,
            candidateIdentity = identity,
            lanChunkSize = lanChunkSize,
            writeLanChunk = { _, offset, bytes ->
                System.arraycopy(bytes, 0, lanSink.backing, offset.toInt(), bytes.size)
                true
            },
            verifyChunkPlaintext = { idx, bytes ->
                val expectedSlice = payload.copyOfRange(idx * lanChunkSize, idx * lanChunkSize + bytes.size)
                bytes.contentEquals(expectedSlice)
            },
        )
        assertTrue(plan.compatible)
        assertEquals((0..14).toSet(), plan.coveredTargetChunks)

        val receivePipeline = ReceivePipeline(
            sink = lanSink,
            recheckWholeFileDigest = true,
            wholeFileDigest = WholeFileDigestProvider { Sha256.digestHex(lanSink.backing) },
            resumeIndexesProvider = { plan.coveredTargetChunks.toList() },
        )

        val totalLanChunks = TransferByteAlignment.chunkCount(payload.size.toLong(), lanChunkSize)
        val fileStartBytes = ChunkFrame.serialize(
            ChunkFrame.FileStart(
                transferId = ticket.fileId,
                fileId = ticket.fileId,
                fileName = "archive.tar",
                totalBytes = payload.size.toLong(),
                totalChunks = totalLanChunks,
                chunkSize = lanChunkSize,
                fileSha256Hex = sha256,
            ),
        )
        receivePipeline.onFrame(fileStartBytes)

        // Send only LAN chunks NOT already covered by Hub chunk 0 adoption!
        var completedCount = 0
        for (idx in 0 until totalLanChunks) {
            if (idx in plan.coveredTargetChunks) continue
            val start = idx * lanChunkSize
            val end = kotlin.math.min(payload.size, start + lanChunkSize)
            val slice = payload.copyOfRange(start, end)
            val frame = ChunkFrame.serialize(
                ChunkFrame.Chunk(
                    transferId = ticket.fileId,
                    fileId = ticket.fileId,
                    index = idx,
                    data = slice,
                    chunkSha256 = Sha256.digest(slice),
                ),
            )
            val events = receivePipeline.onFrame(frame)
            events.filterIsInstance<ReceiveEvent.Completed>().forEach { ev ->
                assertTrue(ev.frame.verified, "whole-file digest after cross-route resume must verify")
                completedCount++
            }
        }
        assertEquals(1, completedCount)
        assertEquals(sha256, Sha256.digestHex(lanSink.backing))

        assertTrue(
            coordinator.completeTransfer(
                logicalTransferId = ticket.fileId,
                expectedEpoch = lanClaim.epoch,
                route = RouteOwner.LAN,
                publishedPath = "/received/archive.tar",
                actualSha256Hex = sha256,
            ),
        )
        // Stale Hub route trying to complete after LAN already won MUST be rejected!
        assertFalse(
            coordinator.completeTransfer(
                logicalTransferId = ticket.fileId,
                expectedEpoch = hubClaim.epoch,
                route = RouteOwner.HUB,
                publishedPath = "/received/archive (2).tar",
                actualSha256Hex = sha256,
            ),
        )
    }

    @Test
    fun `HR8 end-to-end LAN partial receive resumes over Hub skipping verified chunks`(): Unit = runBlocking<Unit> {
        val payload = testPayload(2_400_000)
        val sha256 = Sha256.digestHex(payload)
        val ticket = uploadToHub(payload)
        val identity = LogicalTransferIdentity(
            logicalTransferId = ticket.fileId,
            senderDeviceId = "dev-a",
            recipientDeviceId = "dev-b",
            fileName = "video.mp4",
            plainBytes = payload.size.toLong(),
            fileSha256Hex = sha256,
        )

        val coordinator = TransferRouteCoordinator()
        val lanClaim = assertNotNull(coordinator.tryAcquireRoute(identity, RouteOwner.LAN))
        val lanChunkSize = 65_536
        val totalLanChunks = TransferByteAlignment.chunkCount(payload.size.toLong(), lanChunkSize)
        val lanSink = MemoryChunkSink(payload.size.toLong(), lanChunkSize)
        val receivePipeline = ReceivePipeline(sink = lanSink)
        receivePipeline.onFrame(
            ChunkFrame.serialize(
                ChunkFrame.FileStart(
                    transferId = ticket.fileId,
                    fileId = ticket.fileId,
                    fileName = "video.mp4",
                    totalBytes = payload.size.toLong(),
                    totalChunks = totalLanChunks,
                    chunkSize = lanChunkSize,
                    fileSha256Hex = sha256,
                ),
            ),
        )
        // Receive LAN chunks 0..19 (20 * 65536 = 1,310,720 bytes -> fully covers Hub chunk 0 = 1,048,560 bytes).
        val receivedLan = (0..19).toSet()
        for (idx in receivedLan) {
            val start = idx * lanChunkSize
            val end = kotlin.math.min(payload.size, start + lanChunkSize)
            val slice = payload.copyOfRange(start, end)
            receivePipeline.onFrame(
                ChunkFrame.serialize(
                    ChunkFrame.Chunk(ticket.fileId, ticket.fileId, idx, slice, Sha256.digest(slice)),
                ),
            )
        }
        assertTrue(coordinator.recordVerifiedChunks(ticket.fileId, lanClaim.epoch, receivedLan, lanChunkSize))

        // Hand over to Hub and seed Hub storage with verified bytes from LAN sink.
        val hubClaim = assertNotNull(coordinator.handoverRoute(ticket.fileId, lanClaim.epoch, RouteOwner.LAN, RouteOwner.HUB))
        val fetchedIndexes = mutableListOf<Int>()
        val baseApi = FakeHubFilesApi(server, "dev-b", hubUrl)
        val countingApi = object : HubFilesApi by baseApi {
            override suspend fun readChunk(hubUrl: String, fileId: String, index: Int, expectedBytes: Int): HubFileResult<ByteArray> {
                fetchedIndexes.add(index)
                return baseApi.readChunk(hubUrl, fileId, index, expectedBytes)
            }
        }
        val hubStorage = DiskHubFileStorage(File(tempDir, "bob-lan-to-hub").path)
        val hubTransfers = HubFileTransfers(countingApi, hubStorage, fastPolicy, Dispatchers.Default)
        val downloadRequest = HubDownloadRequest(
            hubUrl = hubUrl,
            ticket = ticket,
            senderDeviceId = "dev-a",
            plainSha256 = sha256,
        )

        val adoptPlan = coordinator.adoptLanToHub(
            transfers = hubTransfers,
            candidateIdentity = identity,
            request = downloadRequest,
            readLanSpan = { offset, len ->
                lanSink.backing.copyOfRange(offset.toInt(), offset.toInt() + len)
            },
        )
        assertTrue(adoptPlan.compatible)
        assertEquals(setOf(0), adoptPlan.coveredTargetChunks)

        val outcome = hubTransfers.download(downloadRequest)
        val done = assertIs<HubDownloadOutcome.Done>(outcome)
        assertEquals(sha256, done.plainSha256)
        assertFalse(0 in fetchedIndexes, "Hub download must skip chunk 0 already verified from LAN")
        assertTrue(1 in fetchedIndexes && 2 in fetchedIndexes)
        assertTrue(coordinator.completeTransfer(ticket.fileId, hubClaim.epoch, RouteOwner.HUB, done.path, done.plainSha256))
    }

    @Test
    fun `HR8 refuses changed source digest, wrong peer, tampered partial bytes, route race, and cancel`(): Unit = runBlocking<Unit> {
        val payload = testPayload(1_200_000)
        val sha256 = Sha256.digestHex(payload)
        val ticket = uploadToHub(payload)
        val identity = LogicalTransferIdentity(
            logicalTransferId = ticket.fileId,
            senderDeviceId = "dev-a",
            recipientDeviceId = "dev-b",
            fileName = "doc.bin",
            plainBytes = payload.size.toLong(),
            fileSha256Hex = sha256,
        )

        val coordinator = TransferRouteCoordinator()
        val claim = assertNotNull(coordinator.tryAcquireRoute(identity, RouteOwner.LAN))
        assertTrue(coordinator.recordVerifiedChunks(ticket.fileId, claim.epoch, setOf(0, 1), 65_536))

        // Concurrent route acquisition by HUB while LAN holds ownership MUST fail.
        assertNull(coordinator.tryAcquireRoute(identity, RouteOwner.HUB))

        // Wrong source digest -> incompatible.
        val badDigest = "0".repeat(64)
        val changedSourcePlan = coordinator.planResume(identity.copy(fileSha256Hex = badDigest), 65_536)
        assertFalse(changedSourcePlan.compatible)
        assertEquals("source_identity_mismatch", changedSourcePlan.reason)

        // Wrong recipient -> incompatible.
        val wrongPeerPlan = coordinator.planResume(identity.copy(recipientDeviceId = "dev-evil"), 65_536)
        assertFalse(wrongPeerPlan.compatible)

        // Reset clean checkpoint for Hub->LAN tamper rejection check.
        val coord2 = TransferRouteCoordinator()
        val hubStorage = DiskHubFileStorage(File(tempDir, "bob-tamper").path)
        val hubTransfers = HubFileTransfers(FakeHubFilesApi(server, "dev-b", hubUrl), hubStorage, fastPolicy, Dispatchers.Default)
        val req = HubDownloadRequest(hubUrl = hubUrl, ticket = ticket, senderDeviceId = "dev-a", plainSha256 = sha256)
        assertEquals(
            1,
            hubTransfers.seedDownloadFromVerifiedBytes(
                request = req,
                verifiedChunkIndexes = setOf(0),
                readChunkPlaintext = { _, offset, len ->
                    val copy = payload.copyOfRange(offset.toInt(), offset.toInt() + len)
                    copy[0] = (copy[0].toInt() xor 0xFF).toByte() // tamper byte 0!
                    copy
                },
            ),
        )
        coord2.tryAcquireRoute(identity, RouteOwner.HUB)
        val tamperPlan = coord2.adoptHubToLan(
            transfers = hubTransfers,
            fileId = ticket.fileId,
            candidateIdentity = identity,
            lanChunkSize = 65_536,
            writeLanChunk = { _, _, _ -> true },
            verifyChunkPlaintext = { idx, bytes ->
                bytes.contentEquals(payload.copyOfRange(idx * 65_536, idx * 65_536 + bytes.size))
            },
        )
        assertFalse(tamperPlan.compatible)
        assertEquals("tampered_partial_bytes", tamperPlan.reason)

        // Cancellation blocks subsequent acquire, resume, and completion.
        assertTrue(coordinator.cancelTransfer(ticket.fileId))
        assertNull(coordinator.tryAcquireRoute(identity, RouteOwner.HUB))
        assertEquals("cancelled", coordinator.planResume(identity, 65_536).reason)
        assertFalse(coordinator.completeTransfer(ticket.fileId, claim.epoch, RouteOwner.LAN, "/tmp/x", sha256))
    }

    @Test
    fun `HR8 HubFileTransfers seedDownloadFromVerifiedBytes rejects mismatched SHA or chunk length`(): Unit = runBlocking<Unit> {
        val payload = testPayload(200_000)
        val ticket = uploadToHub(payload)
        val hubStorage = DiskHubFileStorage(File(tempDir, "bob-badseed").path)
        val hubTransfers = HubFileTransfers(FakeHubFilesApi(server, "dev-b", hubUrl), hubStorage, fastPolicy, Dispatchers.Default)
        val req = HubDownloadRequest(hubUrl = hubUrl, ticket = ticket, senderDeviceId = "dev-a")

        // Seed returns wrong length -> must not mark chunk done.
        val seeded = hubTransfers.seedDownloadFromVerifiedBytes(
            request = req,
            verifiedChunkIndexes = setOf(0),
            readChunkPlaintext = { _, _, _ -> ByteArray(10) },
        )
        assertEquals(0, seeded)
        assertNull(hubTransfers.exportVerifiedChunks(ticket.fileId))

        // Verify file status is still readable via FakeHubFilesApi.
        assertIs<HubFileResult.Ok<*>>(FakeHubFilesApi(server, "dev-b", hubUrl).status(hubUrl, ticket.fileId))
    }
}
