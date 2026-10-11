package com.transfer.flash.server.admin

import com.transfer.flash.server.directory.OrganizationDirectory
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminDashboardRoutesTest {

    @Test
    fun testAdminDashboardRoutes() = testApplication {
        val authService = AdminAuthService(printSetupTokenToConsole = false)
        val directory = OrganizationDirectory()
        val auditLogger = AuditLogger()

        routing {
            adminDashboardRoutes(
                adminAuthService = authService,
                directory = directory,
                auditLogger = auditLogger
            )
        }

        // 1. Unauthenticated request to /admin/dashboard redirects to /admin/login (or setup)
        val unauthRes = client.get("/admin/dashboard")
        // Since no admins yet, /admin/login redirects to /admin/setup or 302
        assertTrue(unauthRes.status == HttpStatusCode.Found || unauthRes.status == HttpStatusCode.OK)

        // 2. Setup first admin
        val token = authService.getActiveSetupTokenForTesting()!!
        val totpSecret = authService.generateTotpSecret()
        authService.consumeSetupToken(token, "admin", "AdminPassword123!", totpSecret)

        // 3. /admin/login should now return 200 OK with login form
        val loginRes = client.get("/admin/login")
        assertEquals(HttpStatusCode.OK, loginRes.status)
        val loginHtml = loginRes.bodyAsText()
        assertTrue(loginHtml.contains("Admin Dashboard Sign In"))
        assertTrue(loginHtml.contains("Authenticator Code"))

        // 4. Create session and access protected routes
        val session = authService.createSession("admin", "ORG_ADMIN")

        val dashboardRes = client.get("/admin/dashboard") {
            header("Cookie", "flash_admin_session=${session.token}")
        }
        assertEquals(HttpStatusCode.OK, dashboardRes.status)
        val dashHtml = dashboardRes.bodyAsText()
        assertTrue(dashHtml.contains("Enterprise Overview"))
        assertTrue(dashHtml.contains("Departments"))

        // 5. Access audit log
        val auditRes = client.get("/admin/audit") {
            header("Cookie", "flash_admin_session=${session.token}")
        }
        assertEquals(HttpStatusCode.OK, auditRes.status)
        val auditHtml = auditRes.bodyAsText()
        assertTrue(auditHtml.contains("Immutable Audit Log"))
        assertTrue(auditHtml.contains("CRYPTOGRAPHIC HASH CHAIN"))
    }
}
