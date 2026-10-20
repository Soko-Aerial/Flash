@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.FileStates
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.FileTicketDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** HF-C2: the HTTP client of the file routes, against a scripted binary engine. The real routes are in `:server`'s e2e test. */
class RealHubFilesApiTest {

    private class Seen(val method: String, val path: String, val headers: Headers, val body: ByteArray)

    private class Reply(val status: HttpStatusCode, val body: ByteArray, val headers: Headers = Headers.Empty)

    private class Engine(val handler: (Seen) -> Reply) : HttpClientEngineBase("files-test") {
        val seen = mutableListOf<Seen>()
        var down = false
        override val config: HttpClientEngineConfig = HttpClientEngineConfig()
        override val dispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

        @OptIn(io.ktor.utils.io.InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            if (down) throw IOException("network is down")
            val body = when (val content = data.body) {
                is TextContent -> content.text.encodeToByteArray()
                is OutgoingContent.ByteArrayContent -> content.bytes()
                else -> ByteArray(0)
            }
            val request = Seen(data.method.value, data.url.encodedPath, data.headers, body)
            seen += request
            val reply = handler(request)
            return HttpResponseData(
                reply.status, GMTDate(), reply.headers, HttpProtocolVersion.HTTP_1_1, ByteReadChannel(reply.body), callContext(),
            )
        }
    }

    private val hub = "https://hub.example"
    private val id = "0123456789abcdef0123456789abcdef"
    private val sha = "a".repeat(64)

    private fun api(
        engine: Engine,
        pinned: String? = hub,
        cleartext: Boolean = false,
        bearer: String? = "tok-1",
    ) = RealHubFilesApi(
        httpClient = HttpClient(engine),
        ioDispatcher = Dispatchers.Unconfined,
        bearerSource = { bearer },
        pinnedHubUrl = { pinned },
        allowCleartext = { cleartext },
    )

    private fun json(status: HttpStatusCode, text: String) = Reply(status, text.encodeToByteArray())

    private fun error(status: HttpStatusCode, code: String, retryAfter: String? = null) = Reply(
        status,
        HubRestCodec.encode(ErrorBody(code)).encodeToByteArray(),
        if (retryAfter != null) headersOf(HubContract.HEADER_RETRY_AFTER, retryAfter) else Headers.Empty,
    )

    private fun status(fileId: String = id) = FileStatusDto(
        fileId = fileId, state = FileStates.READY, totalBytes = 48, chunkSize = 32, chunkCount = 2, sha256 = sha, senderDeviceId = "dev-a",
    )

    private val create = CreateFileRequestDto(fileId = id, totalBytes = 48, chunkSize = 32, chunkCount = 2, sha256 = sha, toDeviceId = "dev-b")

    @Test
    fun createSendsTheRequestWithTheBearerAndReadsTheTicket() = runTest {
        val engine = Engine { json(HttpStatusCode.Created, HubRestCodec.encode(FileTicketDto(id, 99L))) }
        val result = api(engine).create(hub, create)
        assertIs<HubFileResult.Ok<FileTicketDto>>(result)
        assertEquals(99L, result.value.uploadExpiresAtMs)
        val seen = engine.seen.single()
        assertEquals("POST", seen.method)
        assertEquals(HubContract.PATH_FILES, seen.path)
        assertEquals(HubContract.BEARER_PREFIX + "tok-1", seen.headers[HubContract.HEADER_AUTHORIZATION])
        assertEquals(create, HubRestCodec.decodeOrNull<CreateFileRequestDto>(seen.body.decodeToString()))
    }

    @Test
    fun aTicketForAnotherIdIsAServerFaultNotASuccess() = runTest {
        val engine = Engine { json(HttpStatusCode.Created, HubRestCodec.encode(FileTicketDto("f".repeat(32), 99L))) }
        assertEquals(HubFileError.Server(201), (api(engine).create(hub, create) as HubFileResult.Err).error)
    }

    @Test
    fun putSendsTheRawBytesAndTheChunkHash() = runTest {
        val engine = Engine { Reply(HttpStatusCode.NoContent, ByteArray(0)) }
        val bytes = ByteArray(32) { it.toByte() }
        assertIs<HubFileResult.Ok<Unit>>(api(engine).putChunk(hub, id, 1, bytes, sha))
        val seen = engine.seen.single()
        assertEquals("PUT", seen.method)
        assertEquals("${HubContract.PATH_FILES}/$id/chunks/1", seen.path)
        assertEquals(sha, seen.headers[HubContract.HEADER_CHUNK_SHA256])
        assertContentEquals(bytes, seen.body)
    }

    @Test
    fun aUrlThatIsNotThePinnedHubIsRefusedBeforeAnyRequest() = runTest {
        val engine = Engine { json(HttpStatusCode.OK, HubRestCodec.encode(status())) }
        val result = api(engine).status("https://evil.example", id)
        assertEquals(HubFileError.NotThisHub, (result as HubFileResult.Err).error)
        assertTrue(engine.seen.isEmpty(), "no request, so no bearer, may leave for another host")
    }

    @Test
    fun cleartextIsRefusedOutsideTheDebugOptIn() = runTest {
        val engine = Engine { json(HttpStatusCode.OK, HubRestCodec.encode(status())) }
        val result = api(engine, pinned = "http://hub.example").status("http://hub.example", id)
        assertEquals(HubFileError.InsecureAddress, (result as HubFileResult.Err).error)
        assertTrue(engine.seen.isEmpty())
        assertIs<HubFileResult.Ok<FileStatusDto>>(api(engine, pinned = "http://hub.example", cleartext = true).status("http://hub.example", id))
    }

    @Test
    fun aMalformedFileIdNeverBecomesAPath() = runTest {
        val engine = Engine { json(HttpStatusCode.OK, "{}") }
        val api = api(engine)
        for (bad in listOf("../../admin", id.uppercase(), id + "0", "", "0123456789abcdef0123456789abcde/")) {
            assertIs<HubFileResult.Err>(api.status(hub, bad))
            assertIs<HubFileResult.Err>(api.readChunk(hub, bad, 0, 32))
            assertIs<HubFileResult.Err>(api.cancel(hub, bad))
        }
        assertTrue(engine.seen.isEmpty())
    }

    @Test
    fun noBearerIsSignedOutAndADeadNetworkIsOffline() = runTest {
        val engine = Engine { json(HttpStatusCode.OK, HubRestCodec.encode(status())) }
        assertEquals(HubFileError.SignedOut, (api(engine, bearer = null).status(hub, id) as HubFileResult.Err).error)
        engine.down = true
        assertEquals(HubFileError.Offline, (api(engine).status(hub, id) as HubFileResult.Err).error)
    }

    @Test
    fun everyErrorStatusMapsToItsMeaning() = runTest {
        data class Case(val reply: Reply, val expected: HubFileError, val call: suspend RealHubFilesApi.() -> HubFileResult<*>)
        val read: suspend RealHubFilesApi.() -> HubFileResult<*> = { readChunk(hub, id, 0, 32) }
        val st: suspend RealHubFilesApi.() -> HubFileResult<*> = { status(hub, id) }
        val mk: suspend RealHubFilesApi.() -> HubFileResult<*> = { create(hub, create) }
        val cases = listOf(
            Case(error(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST), HubFileError.Refused(HubErrorCodes.ERR_BAD_REQUEST), st),
            Case(error(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN), HubFileError.Forbidden, st),
            Case(error(HttpStatusCode.Forbidden, HubErrorCodes.ERR_REVOKED), HubFileError.SignedOut, st),
            Case(error(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND), HubFileError.NotFound, st),
            Case(error(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND), HubFileError.HubTooOld, mk),
            Case(error(HttpStatusCode.NotFound, HubErrorCodes.ERR_UNKNOWN_RECIPIENT), HubFileError.UnknownRecipient, mk),
            Case(error(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT), HubFileError.Conflict, mk),
            Case(error(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT), HubFileError.NotReady, read),
            Case(error(HttpStatusCode.PayloadTooLarge, HubErrorCodes.ERR_TOO_LARGE), HubFileError.TooLarge(null), mk),
            Case(error(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_QUOTA_EXCEEDED), HubFileError.HubFull, mk),
            Case(error(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "7"), HubFileError.RateLimited(7), st),
            Case(error(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "99999999"), HubFileError.RateLimited(3600), st),
            Case(error(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT), HubFileError.RateLimited(5), st),
            Case(json(HttpStatusCode.ServiceUnavailable, ""), HubFileError.Unavailable, read),
            Case(json(HttpStatusCode.BadGateway, "<html>"), HubFileError.Server(502), st),
            Case(json(HttpStatusCode.OK, "not json"), HubFileError.Server(200), st),
            Case(json(HttpStatusCode.OK, HubRestCodec.encode(status("f".repeat(32)))), HubFileError.Server(200), st),
        )
        for (case in cases) {
            val engine = Engine { case.reply }
            val result = api(engine).(case.call)()
            assertEquals(case.expected, (result as HubFileResult.Err).error, "for ${case.reply.status}")
        }
    }

    @Test
    fun aChunkOfTheWrongLengthIsAnIntegrityFailureAndALongOneIsNotReadInFull() = runTest {
        assertEquals(
            HubFileError.Integrity,
            (api(Engine { Reply(HttpStatusCode.OK, ByteArray(31)) }).readChunk(hub, id, 0, 32) as HubFileResult.Err).error,
        )
        // 64 MiB of reply: the client stops at expected + 1 bytes, so this returns fast and small.
        val huge = Engine { Reply(HttpStatusCode.OK, ByteArray(64 * 1024 * 1024)) }
        assertEquals(HubFileError.Integrity, (api(huge).readChunk(hub, id, 0, 32) as HubFileResult.Err).error)
        // A declared length above the limit is refused from the header. The body is as long as declared, as on a real
        // connection: a header that disagrees with its body surfaced as a client failure (Offline) in this engine.
        val declaredBytes = HubContract.MAX_CHUNK_BYTES * 8
        val declared = Engine {
            Reply(HttpStatusCode.OK, ByteArray(declaredBytes), headersOf(HttpHeaders.ContentLength, declaredBytes.toString()))
        }
        assertEquals(HubFileError.Integrity, (api(declared).readChunk(hub, id, 0, 32) as HubFileResult.Err).error)
        val exact = ByteArray(32) { 7 }
        val ok = api(Engine { Reply(HttpStatusCode.OK, exact) }).readChunk(hub, id, 0, 32)
        assertContentEquals(exact, (ok as HubFileResult.Ok).value)
    }

    @Test
    fun cancellingAFileTheHubNoLongerHasIsDone() = runTest {
        val engine = Engine { error(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND) }
        assertIs<HubFileResult.Ok<Unit>>(api(engine).cancel(hub, id))
        assertEquals("DELETE", engine.seen.single().method)
    }
}
