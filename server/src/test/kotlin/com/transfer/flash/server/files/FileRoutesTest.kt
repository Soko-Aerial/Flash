package com.transfer.flash.server.files

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.FileStates
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.FileTicketDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.TokenBucketLimiter
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HF-S3: the HTTP shape of the sealed file routes. These tests prove the contract the client will code against: opaque
 * octet-stream bodies, the 413 before buffering, per-device budgets, and that no audit line ever carries a whole id.
 */
class FileRoutesTest {

    private class World {
        val db: ServerDatabase = ServerDatabase.inMemory()
        val root: File = Files.createTempDirectory("flash-file-routes").toFile()
        var now: Long = 1_700_000_000_000L
        var limits: FileLimits = FileLimits()
        val groups: MutableMap<String, List<String>> = mutableMapOf()
        val tokens: BearerTokenStore = BearerTokenStore()
        val audit: MutableList<String> = mutableListOf()
        lateinit var store: FileStore

        fun open() {
            store = FileStore.open(
                db = db,
                rootDir = root,
                clock = { now },
                limits = { limits },
                groupMembers = { id -> groups[id] },
                knownDevice = { true },
            )
        }

        fun token(deviceId: String, role: String = "member", department: String? = null): String =
            tokens.issue(HubPrincipal(deviceId, role, department, 100L)).first
    }

    private var world: World? = null

    @BeforeTest
    fun setUp() {
        world = World().also { it.open() }
    }

    @AfterTest
    fun tearDown() {
        world?.db?.close()
        world?.root?.deleteRecursively()
    }

    private fun ApplicationTestBuilder.mount(w: World, limits: HubRateLimits = HubRateLimits.unlimited()) {
        routing {
            fileRoutes(
                store = w.store,
                principalResolver = BearerPrincipalResolver(w.tokens),
                audit = AuditSink { actor, action, details -> w.audit += "$actor|$action|$details" },
                limits = limits,
            )
        }
    }

    // ----- helpers ----------------------------------------------------------------------------------------------

    private suspend fun HttpClient.getAs(path: String, token: String? = null): HttpResponse =
        get(path) { if (token != null) header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.deleteAs(path: String, token: String?): HttpResponse =
        delete(path) { if (token != null) header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.postAs(path: String, token: String?, body: String): HttpResponse =
        post(path) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.putChunk(
        path: String,
        token: String?,
        bytes: ByteArray,
        declaredSha256: String? = null,
    ): HttpResponse =
        put(path) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (declaredSha256 != null) header(HEADER_CHUNK_SHA256, declaredSha256)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }

    private fun id(n: Int = 1): String = "%032x".format(n)

    private fun createBody(
        fileId: String = id(),
        totalBytes: Long = 2548,
        chunkSize: Int = 1024,
        chunkCount: Int = 3,
        sha: String = "a".repeat(64),
        toDeviceId: String? = "device-b",
        groupId: String? = null,
    ): String = HubRestCodec.encode(
        CreateFileRequestDto(
            fileId = fileId,
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            sha256 = sha,
            toDeviceId = toDeviceId,
            groupId = groupId,
        ),
    )

    private fun bodyOf(response: HttpResponse, text: String): ErrorBody {
        val decoded = HubRestCodec.decodeOrNull<ErrorBody>(text)
        return decoded ?: fail("expected the contract error body, got: ${text.take(200)}")
    }

    // ----- tests ------------------------------------------------------------------------------------------------

    @Test
    fun everyFileRouteRequiresABearer() = testApplication {
        val w = world!!
        mount(w)
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/files/${id()}").status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/files/${id()}/chunks/0").status)
        assertEquals(HttpStatusCode.Unauthorized, client.postAs("/api/v1/files", null, createBody()).status)
        assertEquals(HttpStatusCode.Unauthorized, client.putChunk("/api/v1/files/${id()}/chunks/0", null, ByteArray(4)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postAs("/api/v1/files/${id()}/complete", null, "").status)
        assertEquals(HttpStatusCode.Unauthorized, client.deleteAs("/api/v1/files/${id()}", null).status)
    }

    @Test
    fun aSealedFileTravelsFromOneDeviceToAnotherAndTheAuditStaysShort() = testApplication {
        val w = world!!
        mount(w)
        val senderToken = w.token("device-a")
        val recipientToken = w.token("device-b")
        val outsiderToken = w.token("device-c")

        val first = ByteArray(1024) { (it % 251).toByte() }
        val second = ByteArray(1024) { ((it + 3) % 251).toByte() }
        val third = ByteArray(500) { ((it + 7) % 251).toByte() }
        val fileId = id(11)

        // 1. create
        val created = client.postAs("/api/v1/files", senderToken, createBody(fileId = fileId))
        assertEquals(HttpStatusCode.Created, created.status)
        val ticket = HubRestCodec.decode<FileTicketDto>(created.bodyAsText())
        assertEquals(fileId, ticket.fileId)
        assertEquals(HubContract.MAX_CHUNK_BYTES, ticket.maxChunkBytes)
        assertTrue(ticket.uploadExpiresAtMs > w.now)

        // 2. three chunks, each opaque
        assertEquals(
            HttpStatusCode.NoContent,
            client.putChunk("/api/v1/files/$fileId/chunks/0", senderToken, first, FileStore.sha256Hex(first)).status,
        )
        assertEquals(HttpStatusCode.NoContent, client.putChunk("/api/v1/files/$fileId/chunks/1", senderToken, second).status)
        assertEquals(HttpStatusCode.NoContent, client.putChunk("/api/v1/files/$fileId/chunks/2", senderToken, third).status)

        // 3. the recipient cannot see the upload yet
        val early = client.getAs("/api/v1/files/$fileId", recipientToken)
        assertEquals(HttpStatusCode.NotFound, early.status)
        assertEquals(FileStates.UPLOADING, HubRestCodec.decode<FileStatusDto>(client.getAs("/api/v1/files/$fileId", senderToken).bodyAsText()).state)

        // 4. complete
        val completed = client.postAs("/api/v1/files/$fileId/complete", senderToken, "")
        assertEquals(HttpStatusCode.OK, completed.status)
        val status = HubRestCodec.decode<FileStatusDto>(completed.bodyAsText())
        assertEquals(FileStates.READY, status.state)
        assertEquals(2548L, status.totalBytes)
        assertEquals(listOf("device-b"), status.recipients)
        assertEquals(3, status.receivedChunks)

        // 5. the recipient downloads byte-identical chunks
        val downloaded = client.getAs("/api/v1/files/$fileId/chunks/0", recipientToken)
        assertEquals(HttpStatusCode.OK, downloaded.status)
        assertTrue(first.contentEquals(downloaded.bodyAsBytes()))
        assertTrue(third.contentEquals(client.getAs("/api/v1/files/$fileId/chunks/2", recipientToken).bodyAsBytes()))

        // 6. an outsider gets nothing, and learns nothing but "no"
        val outsider = client.getAs("/api/v1/files/$fileId", outsiderToken)
        assertEquals(HttpStatusCode.Forbidden, outsider.status)
        assertEquals(HubErrorCodes.ERR_FORBIDDEN, bodyOf(outsider, outsider.bodyAsText()).code)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.getAs("/api/v1/files/$fileId/chunks/0", outsiderToken).status,
        )

        // 7. only the sender can cancel, and it deletes the bytes
        assertEquals(HttpStatusCode.Forbidden, client.deleteAs("/api/v1/files/$fileId", recipientToken).status)
        assertEquals(HttpStatusCode.NoContent, client.deleteAs("/api/v1/files/$fileId", senderToken).status)
        assertEquals(HttpStatusCode.NotFound, client.getAs("/api/v1/files/$fileId", senderToken).status)
        assertTrue(w.root.listFiles().orEmpty().none { it.name == fileId })

        // 8. audit lines name the action, never a whole id
        assertTrue(w.audit.any { it.contains("|file.create|") }, "create is audited: ${w.audit}")
        assertTrue(w.audit.any { it.contains("|file.chunk|") })
        assertTrue(w.audit.any { it.contains("|file.read|") })
        assertTrue(w.audit.any { it.contains("|file.cancel|") })
        assertTrue(w.audit.none { it.contains(fileId) }, "no audit line carries a whole file id: ${w.audit}")
    }

    @Test
    fun aFiftyMegabyteFileGoesThroughTheRealKtorHostAndComesBackIdentically() = testApplication {
        val w = world!!
        w.limits = w.limits.copy(maxFileBytes = 60L * 1024 * 1024)
        mount(w)
        val token = w.token("device-a")
        val recipientToken = w.token("device-b")
        val fileId = id(71)
        val chunkSize = 1024 * 1024
        val count = 50
        val chunk = ByteArray(chunkSize) { (it % 251).toByte() }

        // Hash the whole blob without ever holding it: the point of the test is the route, not the client's memory.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        repeat(count) { digest.update(chunk) }
        val wholeSha = digest.digest().joinToString("") { "%02x".format(it) }
        val totalBytes = chunkSize.toLong() * count

        val created = client.postAs(
            "/api/v1/files",
            token,
            createBody(fileId = fileId, totalBytes = totalBytes, chunkSize = chunkSize, chunkCount = count, sha = wholeSha),
        )
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())

        for (i in 0 until count) {
            val put = client.putChunk("/api/v1/files/$fileId/chunks/$i", token, chunk, FileStore.sha256Hex(chunk))
            assertEquals(HttpStatusCode.NoContent, put.status, "chunk $i: ${put.bodyAsText()}")
        }

        val completed = client.postAs("/api/v1/files/$fileId/complete", token, "")
        assertEquals(HttpStatusCode.OK, completed.status)
        val status = HubRestCodec.decode<FileStatusDto>(completed.bodyAsText())
        assertEquals(totalBytes, status.totalBytes)
        assertEquals(count, status.receivedChunks)

        val last = client.getAs("/api/v1/files/$fileId/chunks/${count - 1}", recipientToken)
        assertEquals(HttpStatusCode.OK, last.status)
        assertTrue(chunk.contentEquals(last.bodyAsBytes()), "the last chunk comes back byte-identically")
        assertEquals(totalBytes, HubRestCodec.decode<FileStatusDto>(client.getAs("/api/v1/files/$fileId", recipientToken).bodyAsText()).totalBytes)
    }

    @Test
    fun aChunkBodyLargerThanTheHubWhereAcceptsIsRefusedBeforeItIsBuffered() = testApplication {
        val w = world!!
        mount(w)
        val token = w.token("device-a")
        val fileId = id(12)
        assertEquals(HttpStatusCode.Created, client.postAs("/api/v1/files", token, createBody(fileId = fileId, totalBytes = 2L * 1024 * 1024, chunkSize = 1024 * 1024, chunkCount = 2)).status)

        val tooBig = ByteArray(HubContract.MAX_CHUNK_BYTES + 1)
        val response = client.putChunk("/api/v1/files/$fileId/chunks/0", token, tooBig)
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(HubErrorCodes.ERR_TOO_LARGE, bodyOf(response, response.bodyAsText()).code)
    }

    @Test
    fun aFileAboveTheAdministratorsLimitSaysSo() = testApplication {
        val w = world!!
        w.limits = w.limits.copy(maxFileBytes = 2L * 1024 * 1024)
        mount(w)
        val token = w.token("device-a")

        val response = client.postAs(
            "/api/v1/files",
            token,
            createBody(fileId = id(13), totalBytes = 3L * 1024 * 1024, chunkSize = 1024 * 1024, chunkCount = 3),
        )
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        val body = bodyOf(response, response.bodyAsText())
        assertEquals(HubErrorCodes.ERR_TOO_LARGE, body.code)
        assertTrue(body.message.contains("larger"), body.message)
    }

    @Test
    fun theUploadBudgetIsPerDeviceAndAnswers429WithRetryAfter() = testApplication {
        val w = world!!
        // A frozen clock means no bucket refills: the burst is the whole budget.
        val frozen = { 1_000L }
        mount(w, HubRateLimits(clock = frozen, fileWrite = TokenBucketLimiter(2, 1e-9, clock = frozen)))
        val senderToken = w.token("device-a")
        val otherToken = w.token("device-x")

        assertEquals(HttpStatusCode.Created, client.postAs("/api/v1/files", senderToken, createBody(fileId = id(21))).status)
        assertEquals(HttpStatusCode.Created, client.postAs("/api/v1/files", senderToken, createBody(fileId = id(22))).status)

        val blocked = client.postAs("/api/v1/files", senderToken, createBody(fileId = id(23)))
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertEquals(HubErrorCodes.ERR_RATE_LIMIT, bodyOf(blocked, blocked.bodyAsText()).code)
        assertNotNull(blocked.headers[HttpHeaders.RetryAfter])

        // Another device has its own budget.
        assertEquals(HttpStatusCode.Created, client.postAs("/api/v1/files", otherToken, createBody(fileId = id(24))).status)
    }

    @Test
    fun aBodyThatIsNotAFileRequestAndABadIndexAreBoth400() = testApplication {
        val w = world!!
        mount(w)
        val token = w.token("device-a")
        val fileId = id(31)

        val notJson = client.postAs("/api/v1/files", token, "{not json")
        assertEquals(HttpStatusCode.BadRequest, notJson.status)
        assertEquals(HubErrorCodes.ERR_BAD_REQUEST, bodyOf(notJson, notJson.bodyAsText()).code)

        assertEquals(HttpStatusCode.Created, client.postAs("/api/v1/files", token, createBody(fileId = fileId)).status)
        val badIndex = client.putChunk("/api/v1/files/$fileId/chunks/zero", token, ByteArray(4))
        assertEquals(HttpStatusCode.BadRequest, badIndex.status)
        assertEquals(HubErrorCodes.ERR_BAD_REQUEST, bodyOf(badIndex, badIndex.bodyAsText()).code)

        val outOfRange = client.putChunk("/api/v1/files/$fileId/chunks/9", token, ByteArray(1024))
        assertEquals(HttpStatusCode.BadRequest, outOfRange.status)
        assertEquals(HubErrorCodes.ERR_BAD_REQUEST, bodyOf(outOfRange, outOfRange.bodyAsText()).code)
    }

    @Test
    fun aGroupFileIsVisibleToEveryMemberAndNobodyElse() = testApplication {
        val w = world!!
        w.groups["group-7"] = listOf("device-a", "device-b", "device-c")
        mount(w)
        val senderToken = w.token("device-a")
        val secondToken = w.token("device-c")
        val outsiderToken = w.token("device-zz")
        val fileId = id(41)
        val payload = ByteArray(1024) { 5 }

        val created = client.postAs(
            "/api/v1/files",
            senderToken,
            createBody(fileId = fileId, totalBytes = 1024, chunkCount = 1, toDeviceId = null, groupId = "group-7"),
        )
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(HttpStatusCode.NoContent, client.putChunk("/api/v1/files/$fileId/chunks/0", senderToken, payload).status)
        assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/files/$fileId/complete", senderToken, "").status)

        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/files/$fileId/chunks/0", secondToken).status)
        assertEquals(HttpStatusCode.Forbidden, client.getAs("/api/v1/files/$fileId/chunks/0", outsiderToken).status)
    }

    @Test
    fun aPutToAnUnknownFileAndAReadOfAnUnknownChunkAnswer404() = testApplication {
        val w = world!!
        mount(w)
        val token = w.token("device-a")

        val unknown = client.putChunk("/api/v1/files/${id(51)}/chunks/0", token, ByteArray(4))
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals(HubErrorCodes.ERR_NOT_FOUND, bodyOf(unknown, unknown.bodyAsText()).code)
        assertEquals(HttpStatusCode.NotFound, client.getAs("/api/v1/files/${id(51)}", token).status)
        assertEquals(HttpStatusCode.NotFound, client.deleteAs("/api/v1/files/${id(51)}", token).status)
    }

    @Test
    fun aReadyFileWhoseChunkBytesAreGoneAnswers503NotAnEmptyBody() = testApplication {
        val w = world!!
        mount(w)
        val token = w.token("device-a")
        val recipientToken = w.token("device-b")
        val fileId = id(61)
        val payload = ByteArray(1024) { 9 }

        assertEquals(HttpStatusCode.Created, client.postAs("/api/v1/files", token, createBody(fileId = fileId, totalBytes = 1024, chunkCount = 1)).status)
        assertEquals(HttpStatusCode.NoContent, client.putChunk("/api/v1/files/$fileId/chunks/0", token, payload).status)
        assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/files/$fileId/complete", token, "").status)

        File(w.root, "$fileId/0.chunk").delete()

        val response = client.getAs("/api/v1/files/$fileId/chunks/0", recipientToken)
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains(HubErrorCodes.ERR_INTERNAL))
    }
}
