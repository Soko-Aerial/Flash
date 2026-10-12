package com.transfer.flash.server.admin

import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.DelegationCert
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.directory.OrganizationDirectory
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun testGenerateInviteAndEnrollWithEnrollmentService() = testApplication {
        val authService = AdminAuthService(printSetupTokenToConsole = false)
        val directory = OrganizationDirectory()
        val auditLogger = AuditLogger()

        val rootKeyPair = FlashCrypto.generateEcKeyPair()
        val serverKeyPair = FlashCrypto.generateEcKeyPair()
        val rootPubKey = rootKeyPair.toBase64Spki()
        val serverPubKey = serverKeyPair.toBase64Spki()

        val now = System.currentTimeMillis()
        val delegationCert = DelegationCert.issue(
            rootPrivateKey = rootKeyPair.private,
            rootPublicKey = rootPubKey,
            delegatePublicKey = serverPubKey,
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 10_000,
            validTo = now + 86400_000,
            maxDevices = 100,
            serial = 1L,
        )

        val enrollmentService = EnrollmentService(
            rootPublicKey = rootPubKey,
            serverPrivateKey = serverKeyPair.private,
            delegationCert = delegationCert,
        )

        routing {
            adminDashboardRoutes(
                adminAuthService = authService,
                directory = directory,
                auditLogger = auditLogger,
                enrollmentService = enrollmentService,
            )
        }

        val session = authService.createSession("admin", "ORG_ADMIN")
        val formRes = client.post("/admin/invites") {
            header("Cookie", "flash_admin_session=${session.token}")
            setBody(FormDataContent(parametersOf(
                "employeeName" to listOf("Alice Smith"),
                "departmentId" to listOf("eng"),
                "ttlMinutes" to listOf("60"),
            )))
        }

        assertEquals(HttpStatusCode.OK, formRes.status)
        val html = formRes.bodyAsText()
        assertTrue(html.contains("flash://enroll?server="))

        // Extract token from HTML
        val linkRegex = Regex("""(flash://enroll\?[^"'\s<]+)""")
        val match = linkRegex.find(html)
        assertNotNull(match)
        val link = match.value
        val token = link.substringAfter("token=").substringBefore("&")

        // Validate that token is enrolled in EnrollmentService
        val tokenRecord = enrollmentService.getTokenRecord(token)
        assertNotNull(tokenRecord)
        assertEquals("eng", tokenRecord.scope.department)

        // Enroll device with this token
        val clientKeyPair = FlashCrypto.generateEcKeyPair()
        val enrollResult = enrollmentService.enrollDevice(
            token = token,
            deviceId = "device-alice",
            subjectPublicKey = clientKeyPair.toBase64Spki(),
        )
        assertTrue(enrollResult.isSuccess)
    }
}
