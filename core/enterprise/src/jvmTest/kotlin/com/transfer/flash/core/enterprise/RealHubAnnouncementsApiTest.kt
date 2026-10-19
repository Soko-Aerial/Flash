@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.announcements.HubAnnouncementError
import com.transfer.flash.core.enterprise.announcements.RealHubAnnouncementsApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.AnnouncementFeedDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementSearchResponseDto
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.PostAnnouncementRequestDto
import com.transfer.flash.core.hubprotocol.rest.UpdateAnnouncementRequestDto
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealHubAnnouncementsApiTest {

    private val hubUrl = "https://hub.example"
    private val fake = FakeHub()
    private val api = RealHubAnnouncementsApi(
        httpClient = fake.client(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun sampleItem(id: String = "ann-1") = AnnouncementItemDto(
        id = id,
        scope = "org",
        title = "Important News",
        body = "Plain text body",
        seq = 10L,
        createdAt = 1000L,
    )

    @Test
    fun fetchFeedReturnsDtoOnSuccess() = runTest {
        val feed = AnnouncementFeedDto(
            epoch = "ep-1",
            seq = 42L,
            items = listOf(sampleItem("a1"), sampleItem("a2")),
        )
        fake.respond("GET", HubContract.PATH_ANNOUNCEMENTS, HttpStatusCode.OK, HubRestCodec.encode(feed))

        val result = api.fetchFeed(hubUrl, since = 0L)
        assertIs<FlashResult.Success<AnnouncementFeedDto>>(result)
        assertEquals("ep-1", result.value.epoch)
        assertEquals(42L, result.value.seq)
        assertEquals(2, result.value.items.size)
    }

    @Test
    fun postSendsJsonAndReturnsCreatedItem() = runTest {
        val req = PostAnnouncementRequestDto(
            scope = "department",
            departmentId = "dept-1",
            title = "Dept Update",
            body = "Hello department",
        )
        val created = sampleItem("ann-new").copy(
            scope = req.scope,
            departmentId = req.departmentId,
            title = req.title,
            body = req.body,
        )
        fake.respond("POST", HubContract.PATH_ANNOUNCEMENTS, HttpStatusCode.Created, HubRestCodec.encode(created))

        val result = api.post(hubUrl, req)
        assertIs<FlashResult.Success<AnnouncementItemDto>>(result)
        assertEquals("ann-new", result.value.id)
        assertEquals("Dept Update", result.value.title)
    }

    @Test
    fun updateSendsExpectedSeqAndReturnsUpdatedItem() = runTest {
        val req = UpdateAnnouncementRequestDto(
            title = "Edited Title",
            body = "Edited Body",
            expectedSeq = 5L,
        )
        val updated = sampleItem("ann-1").copy(title = req.title, body = req.body, edited = true, seq = 6L)
        fake.respond("PUT", "${HubContract.PATH_ANNOUNCEMENTS}/ann-1", HttpStatusCode.OK, HubRestCodec.encode(updated))

        val result = api.update(hubUrl, "ann-1", req)
        assertIs<FlashResult.Success<AnnouncementItemDto>>(result)
        assertEquals("Edited Title", result.value.title)
        assertTrue(result.value.edited)
    }

    @Test
    fun deleteCallsDeleteRoute() = runTest {
        fake.respond("DELETE", "${HubContract.PATH_ANNOUNCEMENTS}/ann-1", HttpStatusCode.NoContent, "")
        val result = api.delete(hubUrl, "ann-1")
        assertIs<FlashResult.Success<Unit>>(result)
    }

    @Test
    fun pinAndUnpinCallDedicatedRoutes() = runTest {
        fake.respond("POST", "${HubContract.PATH_ANNOUNCEMENTS}/ann-1/pin", HttpStatusCode.OK, "")
        val pinRes = api.setPinned(hubUrl, "ann-1", true)
        assertIs<FlashResult.Success<Unit>>(pinRes)

        fake.respond("POST", "${HubContract.PATH_ANNOUNCEMENTS}/ann-1/unpin", HttpStatusCode.OK, "")
        val unpinRes = api.setPinned(hubUrl, "ann-1", false)
        assertIs<FlashResult.Success<Unit>>(unpinRes)
    }

    @Test
    fun errorMappingPinLimit() = runTest {
        fake.respond(
            "POST",
            "${HubContract.PATH_ANNOUNCEMENTS}/ann-1/pin",
            HttpStatusCode.BadRequest,
            HubRestCodec.encode(ErrorBody(code = HubErrorCodes.ERR_PIN_LIMIT, message = "You can pin up to 3 announcements here. Unpin one first.")),
        )
        val res = api.setPinned(hubUrl, "ann-1", true)
        assertIs<FlashResult.Failure>(res)
        val err = HubAnnouncementError.fromFlashError(res.error)
        assertIs<HubAnnouncementError.PinLimit>(err)
    }

    @Test
    fun errorMappingTooLong() = runTest {
        fake.respond(
            "POST",
            HubContract.PATH_ANNOUNCEMENTS,
            HttpStatusCode.BadRequest,
            HubRestCodec.encode(ErrorBody(code = HubErrorCodes.ERR_BAD_REQUEST, message = "Announcement title exceeds limit of 120 characters")),
        )
        val res = api.post(hubUrl, PostAnnouncementRequestDto(scope = "org", title = "Too long", body = "x"))
        assertIs<FlashResult.Failure>(res)
        val err = HubAnnouncementError.fromFlashError(res.error)
        assertIs<HubAnnouncementError.TooLong>(err)
        assertEquals(120, err.maxChars)
    }

    @Test
    fun errorMappingConflict() = runTest {
        val currentOnServer = sampleItem("ann-1").copy(seq = 15L, title = "Changed by someone else")
        fake.respond(
            "PUT",
            "${HubContract.PATH_ANNOUNCEMENTS}/ann-1",
            HttpStatusCode.Conflict,
            HubRestCodec.encode(currentOnServer),
        )
        val res = api.update(hubUrl, "ann-1", UpdateAnnouncementRequestDto(title = "My Edit", body = "b", expectedSeq = 10L))
        assertIs<FlashResult.Failure>(res)
        val err = HubAnnouncementError.fromFlashError(res.error)
        assertIs<HubAnnouncementError.Conflict>(err)
        assertNotNull(err.current)
        assertEquals("Changed by someone else", err.current?.title)
    }

    @Test
    fun errorMappingRateLimited() = runTest {
        fake.respond(
            "POST",
            HubContract.PATH_ANNOUNCEMENTS,
            HttpStatusCode.TooManyRequests,
            HubRestCodec.encode(ErrorBody(code = HubErrorCodes.ERR_RATE_LIMIT, message = "Too fast")),
        )
        val res = api.post(hubUrl, PostAnnouncementRequestDto(scope = "org", title = "News", body = "x"))
        assertIs<FlashResult.Failure>(res)
        val err = HubAnnouncementError.fromFlashError(res.error)
        assertIs<HubAnnouncementError.RateLimited>(err)
        assertEquals(5, err.retryAfterSec)
    }

    @Test
    fun errorMappingForbiddenAndNotFound() = runTest {
        fake.respond("POST", HubContract.PATH_ANNOUNCEMENTS, HttpStatusCode.Forbidden, FakeHub.error(HubErrorCodes.ERR_FORBIDDEN))
        val forRes = api.post(hubUrl, PostAnnouncementRequestDto(scope = "org", title = "News", body = "x"))
        assertIs<FlashResult.Failure>(forRes)
        val err = HubAnnouncementError.fromFlashError(forRes.error)
        assertIs<HubAnnouncementError.NotAllowed>(err)

        fake.respond("GET", "${HubContract.PATH_ANNOUNCEMENTS}/missing", HttpStatusCode.NotFound, "")
        val notFoundRes = api.getById(hubUrl, "missing")
        assertIs<FlashResult.Success<AnnouncementItemDto?>>(notFoundRes)
        assertEquals(null, notFoundRes.value)
    }

    // --- Hub URL policy (CLI-01): the device bearer never leaves for another host, and never over cleartext ---

    private fun feed(seq: Long = 1L) = HubRestCodec.encode(AnnouncementFeedDto(epoch = "ep-1", seq = seq))

    @Test
    fun refusesAHubUrlThatIsNotThePinnedHub() = runTest {
        fake.respond("GET", HubContract.PATH_ANNOUNCEMENTS, HttpStatusCode.OK, feed())
        val pinnedApi = RealHubAnnouncementsApi(
            httpClient = fake.client(),
            ioDispatcher = Dispatchers.Unconfined,
            pinnedHubUrl = { "https://hub.example" },
        )

        val refused = pinnedApi.fetchFeed("https://evil.example", since = 0L)
        assertIs<FlashResult.Failure>(refused)
        assertTrue(fake.requests.none(), "no request may leave for a host that is not the pinned hub")

        val accepted = pinnedApi.fetchFeed("https://hub.example", since = 0L)
        assertIs<FlashResult.Success<AnnouncementFeedDto>>(accepted)
    }

    @Test
    fun theSameHubIsAcceptedAcrossSchemeSpellings() = runTest {
        fake.respond("GET", HubContract.PATH_ANNOUNCEMENTS, HttpStatusCode.OK, feed())
        val pinnedApi = RealHubAnnouncementsApi(
            httpClient = fake.client(),
            ioDispatcher = Dispatchers.Unconfined,
            pinnedHubUrl = { "wss://hub.example" },
        )

        // WSS pins port 443, HTTPS uses 443: the same hub.
        assertIs<FlashResult.Success<AnnouncementFeedDto>>(pinnedApi.fetchFeed("https://hub.example", since = 0L))
    }

    @Test
    fun cleartextHubIsRefusedUntilTheDebugOptInAllowsIt() = runTest {
        fake.respond("GET", HubContract.PATH_ANNOUNCEMENTS, HttpStatusCode.OK, feed())
        val lan = "http://192.168.1.9:9443"

        val strict = RealHubAnnouncementsApi(
            httpClient = fake.client(),
            ioDispatcher = Dispatchers.Unconfined,
            pinnedHubUrl = { lan },
        )
        assertIs<FlashResult.Failure>(strict.fetchFeed(lan, since = 0L))
        assertTrue(fake.requests.none(), "no request may leave over cleartext without the debug opt-in")

        val optedIn = RealHubAnnouncementsApi(
            httpClient = fake.client(),
            ioDispatcher = Dispatchers.Unconfined,
            pinnedHubUrl = { lan },
            allowCleartext = { true },
        )
        assertIs<FlashResult.Success<AnnouncementFeedDto>>(optedIn.fetchFeed(lan, since = 0L))

        // Loopback cleartext is always accepted (development and tests).
        val loopback = RealHubAnnouncementsApi(
            httpClient = fake.client(),
            ioDispatcher = Dispatchers.Unconfined,
            pinnedHubUrl = { "http://127.0.0.1:9443" },
        )
        assertIs<FlashResult.Success<AnnouncementFeedDto>>(loopback.fetchFeed("http://127.0.0.1:9443", since = 0L))
    }
}
