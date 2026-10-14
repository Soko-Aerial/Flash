package com.transfer.flash.server.admin

import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.SqliteRevocationService
import com.transfer.flash.server.e2e.E2eHub
import com.transfer.flash.server.storage.ServerDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plan A1: DEPH-U02 (policy table complete over the real router), scope unit
 * matrix, and the mutation anchor (a route without a policy fails).
 */
class ConsoleScopeTest {

    private val hubs = ArrayList<E2eHub>()

    @AfterTest
    fun stop() = hubs.forEach { it.close() }

    // DEPH-U02: every /admin/api/v1 route the server answers has a declared policy.
    @Test
    fun everyAdminRouteHasADeclaredPolicy() {
        val hub = E2eHub(limits = { HubRateLimits.unlimited() }).provision().start().also { hubs += it }
        val adminRoutes = hub.services.engine.routeTable().filter { it.startsWith("GET /admin/api/v1") || it.startsWith("POST /admin/api/v1") || it.startsWith("PUT /admin/api/v1") || it.startsWith("DELETE /admin/api/v1") }
        assertTrue(adminRoutes.isNotEmpty(), "no admin routes found")
        val missing = adminRoutes.filter { line ->
            val method = line.substringBefore(' ')
            val template = line.substringAfter(' ')
            AdminRoutePolicies.policyFor(method, template) == null
        }
        assertEquals(emptyList(), missing, "routes without a declared policy (default deny): add them to AdminRoutePolicies")
    }

    private fun deps(): AdminApiDeps {
        val db = ServerDatabase.inMemory()
        val admins = AdminAuthService(printSetupTokenToConsole = false, db = db, passwordIterations = 1000)
        admins.addAdminDirectlyForTesting(AdminUser("boss", admins.hashPassword("BossPassword123!"), admins.generateTotpSecret()))
        admins.createHead("head-a", "HeadPassword123!", "boss", "Head Ama", listOf("dept-a"), null)
        admins.addAdminDirectlyForTesting(AdminUser("ghost", admins.hashPassword("GhostPassword123!"), admins.generateTotpSecret(), role = "NOBODY"))
        admins.setDisabled("ghost", false)
        val audit = AuditLogger(db)
        val directory = com.transfer.flash.server.directory.OrganizationDirectory.open(db)
        val identities = com.transfer.flash.server.identity.IdentityStore.open(db)
        val stepUp = StepUpAuth(admins, identities, SqliteRevocationService(db, "root"))
        val config = com.transfer.flash.server.config.ServerConfig(port = 1, adminPort = 2, host = "127.0.0.1", dataDir = "./test-data", tlsMode = "proxy")
        return AdminApiDeps(
            web = AdminWeb(admins, externalUrl = "http://localhost"),
            admins = admins, audit = audit, directory = directory, identities = identities, stepUp = stepUp, config = config,
        )
    }

    private fun sess(user: String, role: String) = AdminSession("tok", user, role, 0L, Long.MAX_VALUE, "csrf", 0L, "id")

    @Test
    fun scopeIsReReadPerRequestAndUnknownRolesAreEmpty() {
        val d = deps()
        assertEquals(ConsoleScope.Org, d.scopeOf(sess("boss", "ORG_ADMIN")))
        assertEquals(ConsoleScope.Departments(setOf("dept-a")), d.scopeOf(sess("head-a", "DEPT_HEAD")))
        // The session's role string is NOT trusted: scope comes from the database row.
        assertEquals(ConsoleScope.Departments(setOf("dept-a")), d.scopeOf(sess("head-a", "ORG_ADMIN")))
        assertEquals(ConsoleScope.Departments(emptySet()), d.scopeOf(sess("ghost", "NOBODY")))
        assertEquals(ConsoleScope.Departments(emptySet()), d.scopeOf(sess("nobody", "DEPT_HEAD")))
        d.admins.setDisabled("head-a", true)
        assertEquals(ConsoleScope.Departments(emptySet()), d.scopeOf(sess("head-a", "DEPT_HEAD")))
    }

    @Test
    fun allowsFollowsTheTable() {
        assertTrue(ConsoleScope.Org.allows(null))
        assertTrue(ConsoleScope.Org.allows("dept-a"))
        val head = ConsoleScope.Departments(setOf("dept-a", "dept-b"))
        assertTrue(head.allows("dept-a"))
        assertFalse(head.allows("dept-c"))
        assertFalse(head.allows(null))
        assertFalse(head.allows(""))
        assertFalse(ConsoleScope.Departments(emptySet()).allows("dept-a"))
    }

    @Test
    fun headDepartmentsRoundTrip() {
        val d = deps()
        assertEquals(setOf("dept-a"), d.admins.headDepartments("head-a"))
        d.admins.setHeadDepartments("head-a", listOf("dept-a", "dept-b"), "boss")
        assertEquals(setOf("dept-a", "dept-b"), d.admins.headDepartments("head-a"))
        d.admins.removeDepartmentScopes("dept-a")
        assertEquals(setOf("dept-b"), d.admins.headDepartments("head-a"))
    }
}
