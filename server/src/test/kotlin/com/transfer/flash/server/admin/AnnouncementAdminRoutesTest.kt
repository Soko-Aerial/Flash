package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementPreviewRequestDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementPreviewResponseDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementSearchResponseDto
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.PostAnnouncementRequestDto
import com.transfer.flash.server.announcements.AnnouncementStore
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnouncementAdminRoutesTest {

    private val bossPassword = "BossPassword123!"
    private val headPassword = "HeadPassword123!"

    private class Sess(val cookie: String, val csrf: String)

    private inner class Realm {
        var now: Long = System.currentTimeMillis()
        val db = ServerDatabase.inMemory()
        val admins = AdminAuthService(printSetupTokenToConsole = false, db = db, clock = { now }, passwordIterations = 1000)
        val audit = AuditLogger(db, clock = { now })
        val directory = OrganizationDirectory.open(db)
        val identities = IdentityStore.open(db)
        val store = AnnouncementStore.open(db)
        val config = ServerConfig(port = 8443, adminPort = 8444, host = "127.0.0.1", dataDir = "./test-data", tlsMode = "proxy")
        val deps = AdminApiDeps(
            web = AdminWeb(admins, externalUrl = "http://localhost", policy = AdminListenerPolicy.Open),
            admins = admins,
            audit = audit,
            directory = directory,
            identities = identities,
            stepUp = StepUpAuth(admins, identities, clock = { now }),
            config = config,
            limits = HubRateLimits.unlimited(),
            clock = { now },
            announcements = store,
        )
        val bossTotp = admins.generateTotpSecret()
        var headTotp = ""

        init {
            admins.addAdminDirectlyForTesting(AdminUser("boss", admins.hashPassword(bossPassword), bossTotp))
            directory.createDepartment("dept-a", "Alpha", emptyList(), DirectoryActor.CONSOLE)
            directory.createDepartment("dept-b", "Beta", emptyList(), DirectoryActor.CONSOLE)
            headTotp = admins.createHead("head-a", headPassword, "boss", "Head Ama", listOf("dept-a"), null).totpSecret
        }

        fun totp(secret: String): String {
            now += 31_000
            return admins.generateTotp(secret, now / 1000)
        }
    }

    private fun ApplicationTestBuilder.mount(r: Realm) {
        routing { adminApiRoutes(r.deps) }
    }

    private fun HttpRequestBuilder.withSession(s: Sess?) {
        if (s != null) {
            header("Cookie", "flash_admin_session=${s.cookie}")
            header("X-CSRF-Token", s.csrf)
        }
        header("Origin", "http://localhost")
    }

    private suspend fun HttpClient.loginAs(r: Realm, user: String, password: String, secret: String): Sess {
        val resp = post("/admin/api/v1/login") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(LoginBody(user, password, r.totp(secret))))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val cookie = resp.setCookie().first { it.name == "flash_admin_session" }.value
        return Sess(cookie, HubRestCodec.decode<LoginDto>(resp.bodyAsText()).csrfToken)
    }

    private suspend fun HttpClient.getA(path: String, s: Sess?): HttpResponse =
        get("/admin/api/v1$path") { withSession(s) }

    private suspend inline fun <reified T> HttpClient.postA(path: String, s: Sess?, body: T): HttpResponse =
        post("/admin/api/v1$path") {
            withSession(s)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    @Test
    fun previewCleansTextExtractsLinksAndShowsHonestyNotice() = testApplication {
        val r = Realm()
        mount(r)
        val bossSess = client.loginAs(r, "boss", bossPassword, r.bossTotp)

        val rawTitle = "Safe Announcement Title"
        val rawBody = "Welcome team!\nPlease review the handbook at https://example.com/handbook\nThanks!"

        val resp = client.postA(
            "/announcements/preview",
            bossSess,
            AnnouncementPreviewRequestDto(scope = "org", title = rawTitle, body = rawBody),
        )
        assertEquals(HttpStatusCode.OK, resp.status)
        val preview = HubRestCodec.decode<AnnouncementPreviewResponseDto>(resp.bodyAsText())

        assertEquals("Safe Announcement Title", preview.title)
        assertTrue(preview.body.contains("Welcome team!"))
        assertEquals(listOf("https://example.com/handbook"), preview.links)
        assertEquals("Entire organisation", preview.audienceScope)
        assertTrue(preview.honestyNotice.contains("plain text", ignoreCase = true))
        assertTrue(preview.honestyNotice.contains("ADR-138"))
    }

    @Test
    fun departmentHeadScopedToOwnDepartmentInConsole() = testApplication {
        val r = Realm()
        mount(r)
        val bossSess = client.loginAs(r, "boss", bossPassword, r.bossTotp)
        val headSess = client.loginAs(r, "head-a", headPassword, r.headTotp)

        // Boss creates org and dept-b announcements
        val rOrg = client.postA(
            "/announcements",
            bossSess,
            PostAnnouncementRequestDto("org", null, "Org Wide News", "Everyone read this"),
        )
        assertEquals(HttpStatusCode.Created, rOrg.status)

        val rDeptB = client.postA(
            "/announcements",
            bossSess,
            PostAnnouncementRequestDto("department", "dept-b", "Dept B News", "Beta only"),
        )
        assertEquals(HttpStatusCode.Created, rDeptB.status)

        // Head A tries to post to Org -> 403
        val rHeadOrg = client.postA(
            "/announcements",
            headSess,
            PostAnnouncementRequestDto("org", null, "Head Org Try", "Should fail"),
        )
        assertEquals(HttpStatusCode.Forbidden, rHeadOrg.status)

        // Head A tries to post to Dept B -> 403
        val rHeadDeptB = client.postA(
            "/announcements",
            headSess,
            PostAnnouncementRequestDto("department", "dept-b", "Head Dept B Try", "Should fail"),
        )
        assertEquals(HttpStatusCode.Forbidden, rHeadDeptB.status)

        // Head A posts to Dept A -> 201
        val rHeadDeptA = client.postA(
            "/announcements",
            headSess,
            PostAnnouncementRequestDto("department", "dept-a", "Dept A News", "Alpha updates"),
        )
        assertEquals(HttpStatusCode.Created, rHeadDeptA.status)

        // Head A lists announcements -> sees ONLY Dept A, never Org or Dept B
        val rList = client.getA("/announcements", headSess)
        assertEquals(HttpStatusCode.OK, rList.status)
        val list = HubRestCodec.decode<AnnouncementSearchResponseDto>(rList.bodyAsText())
        assertEquals(1, list.items.size)
        assertEquals("dept-a", list.items[0].departmentId)
    }
}
