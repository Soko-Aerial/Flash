@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.server.files

import com.transfer.flash.core.enterprise.files.ByteArrayHubFileSource
import com.transfer.flash.core.enterprise.files.DiskHubFileStorage
import com.transfer.flash.core.enterprise.files.HubDownloadOutcome
import com.transfer.flash.core.enterprise.files.HubDownloadRequest
import com.transfer.flash.core.enterprise.files.HubFileError
import com.transfer.flash.core.enterprise.files.HubFileResult
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.enterprise.files.HubFileTransfers
import com.transfer.flash.core.enterprise.files.HubTransferPolicy
import com.transfer.flash.core.enterprise.files.HubFilesApi
import com.transfer.flash.core.enterprise.files.HubUploadOutcome
import com.transfer.flash.core.enterprise.files.HubUploadRequest
import com.transfer.flash.core.enterprise.files.MemoryHubFileStorage
import com.transfer.flash.core.enterprise.files.RealHubFilesApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.FileTicketDto
import com.transfer.flash.core.security.enterprise.HubFileHasher
import com.transfer.flash.core.security.enterprise.HubFileSealing
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * HF-C4 against HF-S3: the client's real engine and HTTP client ([HubFileTransfers] over [RealHubFilesApi]) against the
 * hub's real routes and store, through Ktor's test host. This is the proof that the two halves agree on the wire: ids,
 * sizes, the sealing allowance, chunk hashes, idempotent re-sends, recipient checks and resume.
 */
class E2eHubFileTransferContractTest {

    private val db: ServerDatabase = ServerDatabase.inMemory()
    private val root: File = Files.createTempDirectory("flash-e2e-files").toFile()
    private val tokens = BearerTokenStore()
    private val groups = mutableMapOf<String, List<String>>()
    private var limits = FileLimits()
    private val store = FileStore.open(
        db = db,
        rootDir = File(root, "hub"),
        clock = { 1_700_000_000_000L },
        limits = { limits },
        groupMembers = { id -> groups[id] },
        knownDevice = { true },
    )
    private val hub = "http://localhost"

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun ApplicationTestBuilder.mount() {
        routing {
            fileRoutes(
                store = store,
                principalResolver = BearerPrincipalResolver(tokens),
                audit = AuditSink { _, _, _ -> },
                limits = HubRateLimits.unlimited(),
            )
        }
    }

    /** The device's view of the hub: its own bearer, cleartext allowed only because the test host is plain HTTP. */
    private fun api(client: HttpClient, deviceId: String): RealHubFilesApi {
        val token = tokens.issue(HubPrincipal(deviceId, "member", null, 100L)).first
        return RealHubFilesApi(
            httpClient = client,
            ioDispatcher = Dispatchers.IO,
            bearerSource = { token },
            pinnedHubUrl = { hub },
            allowCleartext = { true },
        )
    }

    /** Fails every call after [budget] calls of [route] as a dropped network would, until [restore]. */
    private class Flaky(private val inner: HubFilesApi, private val route: String, private var budget: Int) : HubFilesApi {
        private var down = false
        fun restore() {
            down = false
            budget = Int.MAX_VALUE
        }

        private fun spend(name: String): Boolean {
            if (name == route && budget-- <= 0) down = true
            return down
        }

        private val offline = HubFileResult.Err(HubFileError.Offline)

        override suspend fun create(hubUrl: String, request: CreateFileRequestDto): HubFileResult<FileTicketDto> =
            if (spend("create")) offline else inner.create(hubUrl, request)

        override suspend fun putChunk(hubUrl: String, fileId: String, index: Int, bytes: ByteArray, sha256Hex: String) =
            if (spend("put")) offline else inner.putChunk(hubUrl, fileId, index, bytes, sha256Hex)

        override suspend fun complete(hubUrl: String, fileId: String): HubFileResult<FileStatusDto> =
            if (spend("complete")) offline else inner.complete(hubUrl, fileId)

        override suspend fun status(hubUrl: String, fileId: String): HubFileResult<FileStatusDto> =
            if (spend("status")) offline else inner.status(hubUrl, fileId)

        override suspend fun readChunk(hubUrl: String, fileId: String, index: Int, expectedBytes: Int) =
            if (spend("read")) offline else inner.readChunk(hubUrl, fileId, index, expectedBytes)

        override suspend fun cancel(hubUrl: String, fileId: String): HubFileResult<Unit> =
            if (spend("cancel")) offline else inner.cancel(hubUrl, fileId)

        override suspend fun hubInfo(hubUrl: String) = if (spend("info")) offline else inner.hubInfo(hubUrl)
    }

    private fun sha(bytes: ByteArray) = HubFileHasher().also { it.update(bytes) }.digestHex()

    private fun upload(id: String, bytes: ByteArray, to: String? = "device-b", group: String? = null) = HubUploadRequest(
        hubUrl = hub, fileId = id, source = ByteArrayHubFileSource(bytes), toDeviceId = to, groupId = group,
        maxFileBytes = limits.maxFileBytes,
    )

    @Test
    fun aFileOfExactlyTheFiftyMegabyteLimitCrossesTheRealHubSealedAndIntact() = testApplication {
        mount()
        val plain = Random(50).nextBytes(HubContract.DEFAULT_MAX_FILE_BYTES.toInt())
        val id = HubFileSealing.newFileId()
        val sent = HubFileTransfers(api(client, "device-a"), MemoryHubFileStorage()).upload(upload(id, plain))
        val ticket = assertIs<HubUploadOutcome.Done>(sent).ticket
        assertEquals(51, ticket.chunkCount, "50 MiB of plaintext is 51 sealed chunks of 1 MiB")

        // The hub's copy is ciphertext: no 32-byte run of the plaintext appears in a stored chunk.
        val chunk0 = File(root, "hub/$id/0.chunk").readBytes()
        val probe = plain.copyOfRange(4096, 4128)
        assertFalse((0..chunk0.size - probe.size).any { chunk0.copyOfRange(it, it + probe.size).contentEquals(probe) })

        val disk = DiskHubFileStorage(File(root, "device-b").path)
        val got = HubFileTransfers(api(client, "device-b"), disk)
            .download(HubDownloadRequest(hub, ticket, senderDeviceId = "device-a", plainSha256 = sha(plain)))
        val done = assertIs<HubDownloadOutcome.Done>(got)
        assertContentEquals(plain, File(done.path).readBytes())
        assertEquals(sha(plain), done.plainSha256)
    }

    @Test
    fun oneByteOverTheLimitIsRefusedByTheClientAndByTheHub() = testApplication {
        mount()
        limits = FileLimits(maxFileBytes = 2L * 1024 * 1024)
        val over = Random(2).nextBytes(2 * 1024 * 1024 + 1)
        val client = api(client, "device-a")
        val refused = HubFileTransfers(client, MemoryHubFileStorage()).upload(upload(HubFileSealing.newFileId(), over))
        assertEquals(HubFileError.TooLarge(limits.maxFileBytes), assertIs<HubUploadOutcome.Failed>(refused).error)
        // A client that lies about the limit still meets the hub's own check.
        val lying = upload(HubFileSealing.newFileId(), over).copy(maxFileBytes = Long.MAX_VALUE)
        val hubSaid = HubFileTransfers(client, MemoryHubFileStorage()).upload(lying)
        assertEquals(HubFileError.TooLarge(null), assertIs<HubUploadOutcome.Failed>(hubSaid).error)
    }

    @Test
    fun anUploadAndADownloadCutHalfWayResumeOnTheRealHub() = testApplication {
        mount()
        limits = FileLimits(maxFileBytes = 8L * 1024 * 1024)
        val plain = Random(9).nextBytes(5 * 1024 * 1024 + 123) // 6 chunks
        val id = HubFileSealing.newFileId()

        // The backoff schedule is proved on virtual time in HubFileTransfersTest; here real time would only make it slow.
        val quick = HubTransferPolicy(maxConsecutiveFailures = 2, baseBackoffMs = 1, maxBackoffMs = 2)
        val senderDisk = MemoryHubFileStorage()
        val flakyUp = Flaky(api(client, "device-a"), "put", budget = 3)
        assertIs<HubUploadOutcome.Interrupted>(HubFileTransfers(flakyUp, senderDisk, quick).upload(upload(id, plain)))
        flakyUp.restore()
        // A new process: same storage, a new engine.
        val ticket = assertIs<HubUploadOutcome.Done>(HubFileTransfers(flakyUp, senderDisk, quick).upload(upload(id, plain))).ticket

        val recipientDisk = DiskHubFileStorage(File(root, "device-b").path)
        val flakyDown = Flaky(api(client, "device-b"), "read", budget = 2)
        val request = HubDownloadRequest(hub, ticket, senderDeviceId = "device-a", plainSha256 = sha(plain))
        val cut = assertIs<HubDownloadOutcome.Interrupted>(HubFileTransfers(flakyDown, recipientDisk, quick).download(request))
        assertEquals(2, cut.progress.chunksDone)
        flakyDown.restore()
        val done = assertIs<HubDownloadOutcome.Done>(HubFileTransfers(flakyDown, recipientDisk, quick).download(request))
        assertContentEquals(plain, File(done.path).readBytes())
    }

    @Test
    fun aGroupFileReachesMembersAndNotSomeoneRemovedBeforeTheyFetchedIt() = testApplication {
        mount()
        groups["g2-team"] = listOf("device-a", "device-b", "device-c")
        val plain = Random(4).nextBytes(3000)
        val sent = HubFileTransfers(api(client, "device-a"), MemoryHubFileStorage())
            .upload(upload(HubFileSealing.newFileId(), plain, to = null, group = "g2-team"))
        val ticket: HubFileTicket = assertIs<HubUploadOutcome.Done>(sent).ticket
        groups["g2-team"] = listOf("device-a", "device-b") // C left the group after the message was sent

        val member = HubFileTransfers(api(client, "device-b"), MemoryHubFileStorage()).download(HubDownloadRequest(hub, ticket))
        assertIs<HubDownloadOutcome.Done>(member)
        val removed = HubFileTransfers(api(client, "device-c"), MemoryHubFileStorage()).download(HubDownloadRequest(hub, ticket))
        assertEquals(HubFileError.Forbidden, assertIs<HubDownloadOutcome.Failed>(removed).error)
    }

    @Test
    fun aCancelledUploadIsGoneForTheRecipient() = testApplication {
        mount()
        val plain = Random(5).nextBytes(4000)
        val id = HubFileSealing.newFileId()
        val sender = HubFileTransfers(api(client, "device-a"), MemoryHubFileStorage())
        val ticket = assertIs<HubUploadOutcome.Done>(sender.upload(upload(id, plain))).ticket
        assertIs<HubFileResult.Ok<Unit>>(sender.cancelUpload(hub, id))
        val got = HubFileTransfers(api(client, "device-b"), MemoryHubFileStorage()).download(HubDownloadRequest(hub, ticket))
        assertEquals(HubFileError.NotFound, assertIs<HubDownloadOutcome.Failed>(got).error)
        assertTrue(!File(root, "hub/$id").exists(), "the hub deleted the bytes")
    }
}
