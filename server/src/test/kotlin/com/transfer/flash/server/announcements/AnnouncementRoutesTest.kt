package com.transfer.flash.server.announcements

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.AnnouncementFeedDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementSearchResponseDto
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.PostAnnouncementRequestDto
import com.transfer.flash.core.hubprotocol.rest.UpdateAnnouncementRequestDto
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnnouncementRoutesTest {

    private class World {
        val db = ServerDatabase.inMemory()
        val store = AnnouncementStore.open(db)
        val tokens = BearerTokenStore()
        val audit = mutableListOf<String>()

        fun token(deviceId: String, role: String, dept: String?): String =
            tokens.issue(HubPrincipal(deviceId, role, dept, 100L)).first
    }

    private fun ApplicationTestBuilder.mount(w: World) {
        routing {
            announcementRoutes(
                store = w.store,
                principalResolver = BearerPrincipalResolver(w.tokens),
                router = null,
                audit = AuditSink { actor, action, details -> w.audit += "$actor|$action|$details" },
                limits = HubRateLimits.unlimited(),
            )
        }
    }

    private suspend fun HttpClient.getAs(path: String, token: String? = null): HttpResponse =
        get(path) { if (token != null) header("Authorization", "Bearer $token") }

    private suspend inline fun <reified T> HttpClient.postAs(path: String, token: String?, body: T): HttpResponse =
        post(path) {
            if (token != null) header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    private suspend inline fun <reified T> HttpClient.putAs(path: String, token: String?, body: T): HttpResponse =
        put(path) {
            if (token != null) header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    private suspend fun HttpClient.deleteAs(path: String, token: String?): HttpResponse =
        delete(path) { if (token != null) header("Authorization", "Bearer $token") }

    @Test
    fun everyRouteRequiresBearerToken() = testApplication {
        val w = World()
        mount(w)

        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/announcements").status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/announcements/some-id").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.postAs("/api/v1/announcements", null, PostAnnouncementRequestDto("org", null, "Title", "Body")).status
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.putAs("/api/v1/announcements/some-id", null, UpdateAnnouncementRequestDto("Title", "Body")).status
        )
        assertEquals(HttpStatusCode.Unauthorized, client.deleteAs("/api/v1/announcements/some-id", null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postAs("/api/v1/announcements/some-id/pin", null, "").status)
        assertEquals(HttpStatusCode.Unauthorized, client.postAs("/api/v1/announcements/some-id/unpin", null, "").status)
    }

    @Test
    fun regularEmployeeCannotPostAnnouncements() = testApplication {
        val w = World()
        mount(w)
        val empToken = w.token("emp-01", "member", "dept-eng")

        val r = client.postAs(
            "/api/v1/announcements",
            empToken,
            PostAnnouncementRequestDto("department", "dept-eng", "Title", "Body"),
        )
        assertEquals(HttpStatusCode.Forbidden, r.status)
        val err = HubRestCodec.decode<ErrorBody>(r.bodyAsText())
        assertEquals(HubErrorCodes.ERR_FORBIDDEN, err.code)
    }

    @Test
    fun orgAdminCanPostOrgWideAndToDepartment() = testApplication {
        val w = World()
        mount(w)
        val adminToken = w.token("admin-01", "ORG_ADMIN", null)

        // Post org-wide
        val rOrg = client.postAs(
            "/api/v1/announcements",
            adminToken,
            PostAnnouncementRequestDto("org", null, "All Hands", "Company meeting at 3pm"),
        )
        assertEquals(HttpStatusCode.Created, rOrg.status)
        val orgAnn = HubRestCodec.decode<AnnouncementItemDto>(rOrg.bodyAsText())
        assertEquals("org", orgAnn.scope)
        assertEquals("All Hands", orgAnn.title)

        // Post to department
        val rDept = client.postAs(
            "/api/v1/announcements",
            adminToken,
            PostAnnouncementRequestDto("department", "dept-eng", "Eng Sync", "Engineering sprint retro"),
        )
        assertEquals(HttpStatusCode.Created, rDept.status)
        val deptAnn = HubRestCodec.decode<AnnouncementItemDto>(rDept.bodyAsText())
        assertEquals("department", deptAnn.scope)
        assertEquals("dept-eng", deptAnn.departmentId)
    }

    @Test
    fun departmentHeadCanPostOnlyToOwnDepartment() = testApplication {
        val w = World()
        mount(w)
        val headToken = w.token("head-eng", "DEPT_HEAD", "dept-eng")

        // Posting org-wide rejected
        val rOrg = client.postAs(
            "/api/v1/announcements",
            headToken,
            PostAnnouncementRequestDto("org", null, "Illegal Org Post", "Body"),
        )
        assertEquals(HttpStatusCode.Forbidden, rOrg.status)

        // Posting to other department rejected
        val rOther = client.postAs(
            "/api/v1/announcements",
            headToken,
            PostAnnouncementRequestDto("department", "dept-finance", "Illegal Dept Post", "Body"),
        )
        assertEquals(HttpStatusCode.Forbidden, rOther.status)

        // Posting to own department succeeds
        val rOwn = client.postAs(
            "/api/v1/announcements",
            headToken,
            PostAnnouncementRequestDto("department", "dept-eng", "Eng Only", "Standup in 10 minutes"),
        )
        assertEquals(HttpStatusCode.Created, rOwn.status)
        val ann = HubRestCodec.decode<AnnouncementItemDto>(rOwn.bodyAsText())
        assertEquals("dept-eng", ann.departmentId)
        assertEquals("Eng Only", ann.title)
    }

    @Test
    fun outsiderCannotReadOrSearchOtherDepartmentAnnouncement() = testApplication {
        val w = World()
        mount(w)
        val adminToken = w.token("admin-01", "ORG_ADMIN", null)
        val engToken = w.token("emp-eng", "member", "dept-eng")

        // Admin posts secret finance announcement
        val rFin = client.postAs(
            "/api/v1/announcements",
            adminToken,
            PostAnnouncementRequestDto("department", "dept-finance", "Financials Q3", "Confidential quarterly figures"),
        )
        assertEquals(HttpStatusCode.Created, rFin.status)
        val finAnn = HubRestCodec.decode<AnnouncementItemDto>(rFin.bodyAsText())

        // Eng employee tries to fetch by ID directly -> 404
        val rDirect = client.getAs("/api/v1/announcements/${finAnn.id}", engToken)
        assertEquals(HttpStatusCode.NotFound, rDirect.status)

        // Eng employee searches for "Confidential" -> 0 results
        val rSearch = client.getAs("/api/v1/announcements?q=Confidential", engToken)
        assertEquals(HttpStatusCode.OK, rSearch.status)
        val searchDto = HubRestCodec.decode<AnnouncementSearchResponseDto>(rSearch.bodyAsText())
        assertTrue(searchDto.items.isEmpty())

        // Eng employee syncs feed -> does NOT receive the finance announcement
        val rFeed = client.getAs("/api/v1/announcements?since=0", engToken)
        assertEquals(HttpStatusCode.OK, rFeed.status)
        val feedDto = HubRestCodec.decode<AnnouncementFeedDto>(rFeed.bodyAsText())
        assertFalse(feedDto.items.any { it.id == finAnn.id })
    }

    @Test
    fun pinLimitEnforcedAtMaxThree() = testApplication {
        val w = World()
        mount(w)
        val adminToken = w.token("admin-01", "ORG_ADMIN", null)

        val pinnedIds = mutableListOf<String>()
        for (i in 1..3) {
            val r = client.postAs(
                "/api/v1/announcements",
                adminToken,
                PostAnnouncementRequestDto("org", null, "Pinned $i", "Body $i", pinned = true),
            )
            assertEquals(HttpStatusCode.Created, r.status)
            pinnedIds += HubRestCodec.decode<AnnouncementItemDto>(r.bodyAsText()).id
        }

        // 4th pin at creation fails with 409 ERR_PIN_LIMIT
        val r4 = client.postAs(
            "/api/v1/announcements",
            adminToken,
            PostAnnouncementRequestDto("org", null, "Pinned 4", "Body 4", pinned = true),
        )
        assertEquals(HttpStatusCode.Conflict, r4.status)
        val err = HubRestCodec.decode<ErrorBody>(r4.bodyAsText())
        assertEquals(HubErrorCodes.ERR_PIN_LIMIT, err.code)

        // Create unpinned 4th announcement
        val r4Unpinned = client.postAs(
            "/api/v1/announcements",
            adminToken,
            PostAnnouncementRequestDto("org", null, "Unpinned 4", "Body 4", pinned = false),
        )
        val unpinnedId = HubRestCodec.decode<AnnouncementItemDto>(r4Unpinned.bodyAsText()).id

        // Attempting to pin the 4th via /pin route also fails with 409 ERR_PIN_LIMIT
        val rPinFail = client.postAs("/api/v1/announcements/$unpinnedId/pin", adminToken, "")
        assertEquals(HttpStatusCode.Conflict, rPinFail.status)

        // Unpin one
        val rUnpin = client.postAs("/api/v1/announcements/${pinnedIds[0]}/unpin", adminToken, "")
        assertEquals(HttpStatusCode.OK, rUnpin.status)

        // Now pinning the 4th succeeds!
        val rPinOk = client.postAs("/api/v1/announcements/$unpinnedId/pin", adminToken, "")
        assertEquals(HttpStatusCode.OK, rPinOk.status)
    }

    @Test
    fun optimisticConcurrencyConflictReturns409() = testApplication {
        val w = World()
        mount(w)
        val adminToken = w.token("admin-01", "ORG_ADMIN", null)

        val created = HubRestCodec.decode<AnnouncementItemDto>(
            client.postAs(
                "/api/v1/announcements",
                adminToken,
                PostAnnouncementRequestDto("org", null, "V1", "Initial content"),
            ).bodyAsText()
        )

        // Concurrent edit with stale expectedSeq
        val rConflict = client.putAs(
            "/api/v1/announcements/${created.id}",
            adminToken,
            UpdateAnnouncementRequestDto(title = "V2 Stale", expectedSeq = created.seq - 1),
        )
        assertEquals(HttpStatusCode.Conflict, rConflict.status)
        assertEquals(HubErrorCodes.ERR_CONFLICT, HubRestCodec.decode<ErrorBody>(rConflict.bodyAsText()).code)

        // Valid edit with current expectedSeq succeeds
        val rOk = client.putAs(
            "/api/v1/announcements/${created.id}",
            adminToken,
            UpdateAnnouncementRequestDto(title = "V2 Fresh", expectedSeq = created.seq),
        )
        assertEquals(HttpStatusCode.OK, rOk.status)
        val updated = HubRestCodec.decode<AnnouncementItemDto>(rOk.bodyAsText())
        assertEquals("V2 Fresh", updated.title)
        assertTrue(updated.seq > created.seq)
    }

    @Test
    fun softDeleteLeavesTombstoneInChangesFeed() = testApplication {
        val w = World()
        mount(w)
        val adminToken = w.token("admin-01", "ORG_ADMIN", null)

        val created = HubRestCodec.decode<AnnouncementItemDto>(
            client.postAs(
                "/api/v1/announcements",
                adminToken,
                PostAnnouncementRequestDto("org", null, "Temporary", "Will be removed"),
            ).bodyAsText()
        )

        // Delete
        val rDel = client.deleteAs("/api/v1/announcements/${created.id}", adminToken)
        assertEquals(HttpStatusCode.OK, rDel.status)

        // Direct get now 404
        val rGet = client.getAs("/api/v1/announcements/${created.id}", adminToken)
        assertEquals(HttpStatusCode.NotFound, rGet.status)

        // Feed includes tombstone via delta sync (since > 0 per Plan A3 §3.3)
        val rFeed = client.getAs("/api/v1/announcements?since=${created.seq}", adminToken)
        val feed = HubRestCodec.decode<AnnouncementFeedDto>(rFeed.bodyAsText())
        val tombstone = feed.items.find { it.id == created.id }
        assertNotNull(tombstone)
        assertTrue(tombstone.deleted)
        assertEquals("", tombstone.title)
        assertEquals("", tombstone.body)
    }

    @Test
    fun auditLogHygieneNeverContainsTitleOrBody() = testApplication {
        val w = World()
        mount(w)
        val adminToken = w.token("admin-01", "ORG_ADMIN", null)

        val sensitiveTitle = "TOP SECRET TITLE"
        val sensitiveBody = "HIGHLY SENSITIVE BODY CONTENT"

        client.postAs(
            "/api/v1/announcements",
            adminToken,
            PostAnnouncementRequestDto("org", null, sensitiveTitle, sensitiveBody),
        )

        // Check audit sink entries
        assertTrue(w.audit.isNotEmpty())
        for (entry in w.audit) {
            assertFalse(entry.contains(sensitiveTitle), "Audit log must never contain announcement title: $entry")
            assertFalse(entry.contains(sensitiveBody), "Audit log must never contain announcement body: $entry")
        }
    }

    @Test
    fun departmentHeadCannotChangeAnAdminsAnnouncementAndGetsForbiddenNotServerError() = testApplication {
        val w = World()
        mount(w)
        val adminToken = w.token("admin-01", "ORG_ADMIN", null)
        val headToken = w.token("head-eng", "DEPT_HEAD", "dept-eng")
        val created = HubRestCodec.decode<AnnouncementItemDto>(
            client.postAs(
                "/api/v1/announcements",
                adminToken,
                PostAnnouncementRequestDto("department", "dept-eng", "From the admin", "Body"),
            ).bodyAsText(),
        )
        assertEquals(HttpStatusCode.Forbidden, client.deleteAs("/api/v1/announcements/${created.id}", headToken).status)
        assertEquals(HttpStatusCode.Forbidden, client.postAs("/api/v1/announcements/${created.id}/unpin", headToken, "").status)
        assertEquals(HttpStatusCode.Forbidden, client.postAs("/api/v1/announcements/${created.id}/pin", headToken, "").status)
    }

    @Test
    fun aDepartmentIdWithAQuoteCannotBreakTheSearchQuery() = testApplication {
        val w = World()
        mount(w)
        val token = w.token("emp", "member", "dept-eng' OR '1'='1")
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/announcements?q=anything", token).status)
    }
}
