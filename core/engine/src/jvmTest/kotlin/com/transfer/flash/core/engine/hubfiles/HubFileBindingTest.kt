@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.hubfiles

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.files.DiskHubFileStorage
import com.transfer.flash.core.enterprise.files.FakeHubFileServer
import com.transfer.flash.core.enterprise.files.FakeHubFilesApi
import com.transfer.flash.core.enterprise.files.HubFileError
import com.transfer.flash.core.enterprise.files.HubFileText
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.enterprise.files.HubFileTransfers
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
import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HF-M5 (ADR-216): [HubFileBinding] between the transfer list, a real encrypted database and the resumable engine over
 * [FakeHubFileServer]. Everything that touches the (single-threaded) fake hub runs on one worker thread.
 */
class HubFileBindingTest {

    private lateinit var tempDir: File
    private lateinit var worker: ExecutorCoroutineDispatcher
    private val dbs = mutableListOf<FlashDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val hub = "https://hub.example"
    private val server = FakeHubFileServer(maxFileBytes = 8L * 1024 * 1024)
    private val fastPolicy = HubTransferPolicy(maxConsecutiveFailures = 1, baseBackoffMs = 0, maxBackoffMs = 0)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("flash-hubfile-binding").toFile()
        worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        dbs.forEach { it.close() }
        worker.close()
        tempDir.deleteRecursively()
    }

    private class Rig(
        val db: FlashDatabase,
        val api: FakeHubFilesApi,
        val repo: RealFlashTransferRepository,
        val binding: HubFileBinding,
        val queued: CopyOnWriteArrayList<String>,
        val ended: CopyOnWriteArrayList<String>,
        val receivedDir: File,
    ) {
        val dao get() = db.hubFileDao()
    }

    private fun rig(
        deviceId: String,
        db: FlashDatabase = openEncryptedFlashDatabase(File(tempDir, "$deviceId-${dbs.size}.db"), "key").also { dbs += it },
        api: FakeHubFilesApi = FakeHubFilesApi(server, deviceId, hub),
        reachable: Boolean = false,
        route: HubFileRoute = HubFileRoute.READY,
        enrolled: Boolean = true,
        autoDownload: Boolean = false,
        retryDelayMs: Long = 20L,
    ): Rig {
        val scope = CoroutineScope(SupervisorJob() + worker).also { scopes += it }
        val opener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }
        val repo = RealFlashTransferRepository(streamChannelFactory = { _, _ -> null }, fileSourceOpener = opener, store = null, repositoryScope = scope)
        val queued = CopyOnWriteArrayList<String>()
        val ended = CopyOnWriteArrayList<String>()
        val receivedDir = File(tempDir, "received-$deviceId").apply { mkdirs() }
        val transfers = HubFileTransfers(api, DiskHubFileStorage(File(tempDir, "engine-$deviceId").path), fastPolicy, worker, Random(1))
        val binding = HubFileBinding(
            scope = scope,
            dao = db.hubFileDao(),
            transfers = transfers,
            api = api,
            transferRepository = repo,
            hubUrl = { if (enrolled) hub else null },
            fileRoute = { route },
            queueTicket = { id -> queued += id; true },
            outgoingEnded = { id -> ended += id },
            openSource = opener,
            receivedDirectory = { receivedDir.path },
            isDirectlyReachable = { reachable },
            peerName = { id -> if (id == "dev-a") "Ann" else if (id == "dev-b") "Bea" else null },
            autoDownload = { peer, mime -> autoDownload && peer == "dev-a" && mime == "text/plain" },
            workerDispatcher = worker,
            retryDelayMs = retryDelayMs,
            maxRetryDelayMs = retryDelayMs * 2,
        )
        return Rig(db, api, repo, binding, queued, ended, receivedDir)
    }

    private fun source(bytes: Int, seed: Int = 7): Pair<File, ByteArray> {
        val data = Random(seed).nextBytes(bytes)
        val f = File(tempDir, "src-$seed-$bytes.bin").apply { writeBytes(data) }
        return f to data
    }

    private val bea = FlashDevice(FlashDeviceId("dev-b"), "Bea", FlashTransportType.LAN)

    private suspend fun eventually(what: String, timeoutMs: Long = 10_000L, check: suspend () -> Boolean) {
        val met = runCatching { withTimeout(timeoutMs) { while (!check()) delay(10) } }.isSuccess
        assertTrue(met, "timed out waiting: $what")
    }

    private suspend fun Rig.send(file: File, name: String = "report.pdf", size: Long = file.length()): String {
        val result = repo.sendFile(bea, file.path, name, size)
        return assertIs<FlashResult.Success<FlashTransferId>>(result).value.value
    }

    /** True when the hub took the send; the direct path's own result (it has no network here) does not matter. */
    private suspend fun Rig.hubTook(file: File): Boolean =
        (repo.sendFile(bea, file.path, "a.bin", file.length()) as? FlashResult.Success)?.value?.value?.startsWith(HubFileStates.TRANSFER_PREFIX_OUT) == true

    /** A file uploaded by Ann to Bea, outside any binding, and the row a received ticket becomes (what the chat stores). */
    private suspend fun ticketFromAnn(bytes: ByteArray): HubFileTicket {
        val sender = HubFileTransfers(FakeHubFilesApi(server, "dev-a", hub), DiskHubFileStorage(File(tempDir, "ann-${Random.nextInt()}").path), fastPolicy, worker)
        val done = sender.upload(
            HubUploadRequest(hub, HubFileSealing.newFileId(), ByteArrayHubSource(bytes), toDeviceId = "dev-b", maxFileBytes = server.maxFileBytes),
        )
        return assertIs<HubUploadOutcome.Done>(done).ticket
    }

    private class ByteArrayHubSource(private val bytes: ByteArray) : com.transfer.flash.core.enterprise.files.HubFileSource {
        override val size: Long = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun offeredRow(t: HubFileTicket, name: String = "notes.txt", sha: String? = t.cipherSha256) = HubFileEntity(
        transferId = HubFileStates.TRANSFER_PREFIX_IN + t.fileId, direction = HubFileStates.IN, fileId = t.fileId,
        peerId = "dev-a", hubUrl = hub, fileName = name, mime = "text/plain", plainBytes = t.plainBytes, sourceUri = null,
        fileKey = t.key.encode(), cipherSha256 = sha, cipherChunkBytes = t.cipherChunkBytes, chunkCount = t.chunkCount,
        expiresAtMs = t.expiresAtMs, messageId = "m-" + t.fileId.take(8), state = HubFileStates.OFFERED, paused = false,
        errorText = null, bytesDone = 0L, localPath = null, createdAtMs = 1L, updatedAtMs = 1L,
    )

    // ----- sending ----------------------------------------------------------------------------------------------

    @Test
    fun `U26 a file for a peer out of reach is taken by the hub, uploaded without its name, and handed to the chat`() = runBlocking(worker) {
        val r = rig("dev-a")
        val (file, _) = source(2 * 1024 * 1024 + 17)
        val id = r.send(file)
        assertTrue(id.startsWith(HubFileStates.TRANSFER_PREFIX_OUT))
        eventually("the ticket is handed to the chat") { r.queued == listOf(id) }
        val row = assertNotNull(r.dao.get(id))
        assertEquals(HubFileStates.READY, row.state)
        assertNotNull(row.fileKey)
        assertEquals("report.pdf", row.fileName)
        val stored = assertNotNull(server.files[row.fileId])
        assertNull(stored.request.nameHint, "the hub never learns the file name")
        assertEquals("dev-b", stored.request.toDeviceId)
        eventually("the row shows as sent through the hub") {
            r.repo.activeTransfers.value.any { it.id.value == id && it.state == FlashTransferState.Completed && it.viaHub && it.isEncrypted }
        }
        assertTrue(r.ended.isEmpty())
    }

    @Test
    fun `U27 a reachable peer, a device without a hub and an unavailable route keep the direct path`() = runBlocking(worker) {
        val (file, _) = source(4096)
        for (r in listOf(rig("dev-a", reachable = true), rig("dev-a", enrolled = false), rig("dev-a", route = HubFileRoute.UNAVAILABLE))) {
            assertFalse(r.hubTook(file), "the hub must not take this send")
            assertTrue(r.dao.runnable(HubFileStates.RUNNING).isEmpty())
        }
        assertEquals(0, server.files.size)
    }

    @Test
    fun `U28 a send the hub cannot carry is a failed row with the plan's sentence and nothing is uploaded`() = runBlocking(worker) {
        val (small, _) = source(4096)
        val sealing = rig("dev-a", route = HubFileRoute.NEEDS_SEALING)
        val a = sealing.send(small)
        assertEquals(HubFileText.needsSealing("Bea"), sealing.dao.get(a)?.errorText)
        assertEquals(HubFileStates.FAILED, sealing.dao.get(a)?.state)

        server.maxFileBytes = 1000
        val big = rig("dev-a")
        val b = big.send(small)
        assertEquals(HubFileText.tooLarge(TransferFailureText.formatSize(4096), TransferFailureText.formatSize(1000)), big.dao.get(b)?.errorText)

        server.maxFileBytes = 0
        val old = rig("dev-a")
        val c = old.send(small)
        assertEquals(HubFileText.HUB_OLD, old.dao.get(c)?.errorText)

        assertEquals(0, server.files.size)
        assertEquals(0, (sealing.api.calls[FakeHubFilesApi.ROUTE_CREATE] ?: 0) + (big.api.calls[FakeHubFilesApi.ROUTE_CREATE] ?: 0))
        assertTrue(sealing.queued.isEmpty() && big.queued.isEmpty() && old.queued.isEmpty())
        eventually("a refused send shows as Failed") {
            sealing.repo.activeTransfers.value.any { it.id.value == a && it.state == FlashTransferState.Failed }
        }
    }

    @Test
    fun `U29 an upload cut by the network waits, says why, and finishes by itself when the hub is back`() = runBlocking(worker) {
        val r = rig("dev-a")
        r.api.offline = true
        val (file, _) = source(3 * 1024 * 1024)
        val id = r.send(file)
        eventually("the reason is shown") { r.dao.get(id)?.errorText == HubFileText.reason(HubFileError.Offline) }
        assertEquals(HubFileStates.UPLOADING, r.dao.get(id)?.state)
        assertTrue(r.queued.isEmpty())
        r.api.offline = false
        eventually("the upload finishes once the hub answers") { r.queued == listOf(id) }
        assertNull(r.dao.get(id)?.errorText)
    }

    @Test
    fun `U30 cancelling an upload stops it, deletes it on the hub and gives the message up`() = runBlocking(worker) {
        // A long retry delay keeps the interrupted upload still while the test cancels it.
        val r = rig("dev-a", retryDelayMs = 60_000L)
        var puts = 0
        r.api.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && ++puts > 1) r.api.offline = true }
        val (file, _) = source(3 * 1024 * 1024)
        val id = r.send(file)
        eventually("the upload is waiting") { r.dao.get(id)?.errorText != null }
        val fileId = assertNotNull(r.dao.get(id)).fileId
        assertNotNull(server.files[fileId], "the hub holds the part already sent")
        r.api.beforeCall = {}
        r.api.offline = false
        r.repo.cancelTransfer(FlashTransferId(id))
        assertEquals(HubFileStates.CANCELLED, r.dao.get(id)?.state)
        assertEquals(listOf(id), r.ended.toList())
        assertNull(server.files[fileId], "the hub deletes what it held")
        // A second cancel is harmless and does not end the message twice; no ticket ever appears.
        r.repo.cancelTransfer(FlashTransferId(id))
        assertEquals(1, r.ended.size)
        assertTrue(r.queued.isEmpty())
    }

    @Test
    fun `U31 a paused upload does not move until it is resumed`() = runBlocking(worker) {
        val r = rig("dev-a")
        r.api.offline = true
        val (file, _) = source(1024 * 1024 + 5)
        val id = r.send(file)
        eventually("waiting") { r.dao.get(id)?.errorText != null }
        r.repo.pauseTransfer(FlashTransferId(id))
        eventually("shown paused") { r.repo.activeTransfers.value.any { it.id.value == id && it.state == FlashTransferState.Paused } }
        r.api.offline = false
        delay(150)
        assertTrue(r.queued.isEmpty(), "a paused upload stays paused when the hub comes back")
        r.repo.resumeTransfer(FlashTransferId(id))
        eventually("resumed and finished") { r.queued == listOf(id) }
    }

    @Test
    fun `U32 an unfinished upload continues in a new process from what the hub already has`() = runBlocking(worker) {
        val db = openEncryptedFlashDatabase(File(tempDir, "restart.db"), "key").also { dbs += it }
        val api = FakeHubFilesApi(server, "dev-a", hub)
        var puts = 0
        api.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && ++puts > 2) api.offline = true }
        val first = rig("dev-a", db = db, api = api)
        val (file, _) = source(4 * 1024 * 1024 + 1)
        val id = first.send(file)
        eventually("cut after two chunks") { first.dao.get(id)?.errorText != null }
        first.binding.detach()
        api.beforeCall = {}
        api.offline = false
        api.calls.clear()

        val second = rig("dev-a", db = db, api = api)
        second.binding.start()
        eventually("the restarted binding finishes it") { second.queued == listOf(id) }
        val chunks = assertNotNull(second.dao.get(id)).chunkCount
        assertTrue(chunks > 2)
        assertEquals(chunks - 2, api.calls[FakeHubFilesApi.ROUTE_PUT], "only the chunks the hub lacks are sent again")
    }

    @Test
    fun `U33 detach gives the send path back`() = runBlocking(worker) {
        val r = rig("dev-a")
        r.binding.detach()
        assertNull(r.repo.sendInterceptor)
        val (file, _) = source(4096)
        assertFalse(r.hubTook(file))
    }

    // ----- receiving --------------------------------------------------------------------------------------------

    @Test
    fun `U34 an accepted file is downloaded, checked and placed under its own folder`() = runBlocking(worker) {
        val plain = Random(3).nextBytes(1024 * 1024 + 99)
        val ticket = ticketFromAnn(plain)
        val r = rig("dev-b")
        val row = offeredRow(ticket, name = "../../evil/notes.txt")
        r.dao.insert(row)
        eventually("offered") { r.repo.activeTransfers.value.any { it.id.value == row.transferId && it.state == FlashTransferState.Offered } }
        r.repo.acceptIncoming(FlashTransferId(row.transferId))
        eventually("downloaded") { r.dao.get(row.transferId)?.state == HubFileStates.DONE }
        val path = assertNotNull(r.dao.get(row.transferId)?.localPath)
        val placed = File(path)
        assertContentEquals(plain, placed.readBytes())
        assertEquals(File(r.receivedDir, row.transferId).canonicalPath, placed.parentFile.canonicalPath, "the file stays in its own folder")
        assertFalse(placed.name.contains("/") || placed.name.contains("\\") || placed.name == "..", "one plain name, no path")
        assertFalse(File(tempDir, "evil").exists() || File(r.receivedDir, "evil").exists(), "nothing escaped the folder")
    }

    @Test
    fun `U35 with automatic acceptance a ticket downloads without a tap, and a declined one never does`() = runBlocking(worker) {
        val ticket = ticketFromAnn(Random(4).nextBytes(5000))
        val auto = rig("dev-b", autoDownload = true)
        auto.dao.insert(offeredRow(ticket))
        eventually("downloaded automatically") { auto.dao.get(HubFileStates.TRANSFER_PREFIX_IN + ticket.fileId)?.state == HubFileStates.DONE }
        // The policy decides per sender and type: an unpaired sender's file, or a type left off, waits for a tap.
        val stranger = ticketFromAnn(Random(11).nextBytes(500))
        auto.dao.insert(offeredRow(stranger).copy(peerId = "dev-c"))
        val video = ticketFromAnn(Random(12).nextBytes(500))
        auto.dao.insert(offeredRow(video).copy(mime = "video/mp4"))
        delay(200)
        assertEquals(HubFileStates.OFFERED, auto.dao.get(HubFileStates.TRANSFER_PREFIX_IN + stranger.fileId)?.state)
        assertEquals(HubFileStates.OFFERED, auto.dao.get(HubFileStates.TRANSFER_PREFIX_IN + video.fileId)?.state)

        val manual = rig("dev-b")
        val t2 = ticketFromAnn(Random(5).nextBytes(5000))
        val id = HubFileStates.TRANSFER_PREFIX_IN + t2.fileId
        manual.dao.insert(offeredRow(t2))
        manual.repo.cancelTransfer(FlashTransferId(id))
        assertEquals(HubFileStates.CANCELLED, manual.dao.get(id)?.state)
        manual.repo.acceptIncoming(FlashTransferId(id))
        delay(100)
        assertEquals(HubFileStates.CANCELLED, manual.dao.get(id)?.state, "accept after cancel does nothing")
    }

    @Test
    fun `U36 a file gone from the hub or altered on it fails with a sentence and leaves nothing behind`() = runBlocking(worker) {
        val gone = ticketFromAnn(Random(6).nextBytes(4000))
        server.sweep(gone.fileId)
        val r = rig("dev-b")
        r.dao.insert(offeredRow(gone))
        r.repo.acceptIncoming(FlashTransferId(HubFileStates.TRANSFER_PREFIX_IN + gone.fileId))
        eventually("fails as expired") { r.dao.get(HubFileStates.TRANSFER_PREFIX_IN + gone.fileId)?.state == HubFileStates.FAILED }
        assertEquals(HubFileText.expired("Ann"), r.dao.get(HubFileStates.TRANSFER_PREFIX_IN + gone.fileId)?.errorText)

        val altered = ticketFromAnn(Random(8).nextBytes(4000))
        server.tamper(altered.fileId, 0)
        val id = HubFileStates.TRANSFER_PREFIX_IN + altered.fileId
        r.dao.insert(offeredRow(altered))
        r.repo.acceptIncoming(FlashTransferId(id))
        eventually("fails as damaged") { r.dao.get(id)?.state == HubFileStates.FAILED }
        assertEquals(HubFileText.sentence(HubFileError.Integrity, "Ann"), r.dao.get(id)?.errorText)
        assertNull(r.dao.get(id)?.localPath)
        assertTrue(r.receivedDir.walk().none { it.isFile }, "no partial or damaged file reaches the received folder")
    }

    @Test
    fun `U37 a ticket without a usable key fails instead of downloading`() = runBlocking(worker) {
        val t = ticketFromAnn(Random(9).nextBytes(3000))
        val r = rig("dev-b")
        r.dao.insert(offeredRow(t).copy(fileKey = "not-a-key"))
        val id = HubFileStates.TRANSFER_PREFIX_IN + t.fileId
        r.repo.acceptIncoming(FlashTransferId(id))
        eventually("failed") { r.dao.get(id)?.state == HubFileStates.FAILED }
        assertEquals(0, r.api.calls[FakeHubFilesApi.ROUTE_READ] ?: 0, "nothing is fetched without a key")
    }

    @Test
    fun `U38 a download cut by the network resumes and the file is whole`() = runBlocking(worker) {
        val plain = Random(10).nextBytes(3 * 1024 * 1024 + 3)
        val t = ticketFromAnn(plain)
        val r = rig("dev-b")
        var reads = 0
        r.api.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_READ && ++reads == 2) r.api.offline = true }
        val id = HubFileStates.TRANSFER_PREFIX_IN + t.fileId
        r.dao.insert(offeredRow(t))
        r.repo.acceptIncoming(FlashTransferId(id))
        eventually("interrupted") { r.dao.get(id)?.errorText == HubFileText.reason(HubFileError.Offline) }
        r.api.beforeCall = {}
        r.api.offline = false
        eventually("finished") { r.dao.get(id)?.state == HubFileStates.DONE }
        assertContentEquals(plain, File(assertNotNull(r.dao.get(id)?.localPath)).readBytes())
    }

    @Test
    fun `U44 a system pause keeps everything and the next start continues, while a person's pause survives it`() = runBlocking(worker) {
        val r = rig("dev-a", retryDelayMs = 60_000L)
        r.api.offline = true
        val (file, _) = source(1024 * 1024 + 9)
        val id = r.send(file)
        eventually("waiting") { r.dao.get(id)?.errorText != null }
        r.repo.pauseForSystem(FlashTransferId(id), "SERVICE_TIMEOUT")
        assertEquals(HubFileStates.UPLOADING, r.dao.get(id)?.state)
        assertFalse(assertNotNull(r.dao.get(id)).paused, "the system pause is not the person's pause")
        r.api.offline = false
        r.binding.start()
        eventually("continued by start") { r.queued == listOf(id) }

        val (other, _) = source(4000, seed = 21)
        r.api.offline = true
        val second = r.send(other)
        eventually("waiting") { r.dao.get(second)?.errorText != null }
        r.repo.pauseTransfer(FlashTransferId(second))
        r.api.offline = false
        r.binding.start()
        delay(150)
        assertEquals(HubFileStates.UPLOADING, r.dao.get(second)?.state, "start never resumes what the person paused")
    }
}
