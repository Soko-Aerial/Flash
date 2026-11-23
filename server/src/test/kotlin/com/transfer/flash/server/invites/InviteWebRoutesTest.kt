package com.transfer.flash.server.invites

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import com.transfer.flash.server.admin.AdminConsoleHeaders
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InviteWebRoutesTest {
    @Test
    fun fallbackIsStaticAndAssociationContainsOnlyConfiguredPublicSigningIdentity() = testApplication {
        val fingerprint = List(32) { "AB" }.joinToString(":")
        application {
            install(AdminConsoleHeaders)
            routing { inviteWebRoutes(this, listOf(fingerprint)) }
        }
        for (path in listOf("/invite/group", "/invite/enroll")) {
            val page = client.get("$path?token=do-not-reflect")
            assertEquals(HttpStatusCode.OK, page.status)
            assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
            assertEquals("no-referrer", page.headers["Referrer-Policy"])
            assertContains(page.headers["Content-Security-Policy"].orEmpty(), "script-src 'self'")
            val body = page.bodyAsText()
            assertContains(body, "Download Flash")
            assertFalse(body.contains("do-not-reflect"))
        }
        assertEquals(HttpStatusCode.OK, client.get("/invite/assets/invite.js").status)
        val association = client.get("/.well-known/assetlinks.json")
        assertEquals(HttpStatusCode.OK, association.status)
        assertContains(association.bodyAsText(), fingerprint)
        assertContains(association.bodyAsText(), "com.transfer.flash")
    }
}
