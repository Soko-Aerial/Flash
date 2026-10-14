package com.transfer.flash.server.directory

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.OrgMemberStatement
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.TokenBucketLimiter
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.auth.verifySignature
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizationDirectorySyncTest {

    private fun testSpki(seed: String): String = Base64.encode(seed.padEnd(32, '0').encodeToByteArray())

    @Test
    fun testMigration2SchemaAndInitialState() {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)

        // Verify version column in existing tables
        db.withConnection { conn ->
            fun columnExists(table: String, column: String): Boolean {
                val rs: ResultSet = conn.metaData.getColumns(null, null, table, column)
                return rs.next()
            }
            fun tableExists(table: String): Boolean {
                val rs: ResultSet = conn.metaData.getTables(null, null, table, null)
                return rs.next()
            }

            assertTrue(columnExists("dir_departments", "version"), "dir_departments.version must exist")
            assertTrue(columnExists("dir_groups", "version"), "dir_groups.version must exist")
            assertTrue(columnExists("dir_members", "version"), "dir_members.version must exist")

            assertTrue(tableExists("org_member_statement"), "org_member_statement table must exist")
            assertTrue(tableExists("directory_state"), "directory_state table must exist")
            assertTrue(tableExists("directory_tombstone"), "directory_tombstone table must exist")

            conn.prepareStatement("SELECT current_version, epoch_id FROM directory_state WHERE id = 1").use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "directory_state row 1 must exist")
                assertEquals(0L, rs.getLong("current_version"))
                val epoch = rs.getString("epoch_id")
                assertNotNull(epoch)
                assertTrue(epoch.isNotBlank())
            }
        }
        assertEquals(0L, dir.currentVersion)
        assertNotNull(dir.epochId)
    }

    @Test
    fun testVersionBumpingAndTombstonesOnMutations() {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)
        assertEquals(0L, dir.currentVersion)

        // 1. Create department
        val dept = dir.createDepartment("dept-alpha", "Alpha Division", listOf("admin-1"), isOrgAdmin = true)
        assertEquals(1L, dir.currentVersion)
        assertEquals(1L, dept.version)

        // 2. Add department member
        val member1 = dir.addDepartmentMember(
            departmentId = "dept-alpha",
            deviceId = "dev-alpha-1",
            displayName = "Soldier One",
            role = MemberRole.MEMBER,
            subjectPublicKey = testSpki("SPKI-ALPHA-1"),
        )
        assertEquals(2L, dir.currentVersion)
        assertEquals(2L, member1.version)

        // 3. Add second member
        val member2 = dir.addDepartmentMember(
            departmentId = "dept-alpha",
            deviceId = "dev-alpha-2",
            displayName = "Soldier Two",
            role = MemberRole.MEMBER,
            subjectPublicKey = testSpki("SPKI-ALPHA-2"),
        )
        assertEquals(3L, dir.currentVersion)
        assertEquals(3L, member2.version)

        // 4. Create group
        val group = dir.createGroup(
            id = "grp-scout",
            name = "Scouts",
            departmentId = "dept-alpha",
            visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.OPEN_IN_SCOPE,
            callerDeviceId = "admin-1",
            isOrgAdmin = true,
        )
        assertEquals(4L, dir.currentVersion)
        assertEquals(4L, group.version)

        // 5. Add group member
        val afterAddGrp = dir.addMemberToGroup("grp-scout", "dev-alpha-1", callerDeviceId = "admin-1")
        assertEquals(5L, dir.currentVersion)
        assertEquals(5L, afterAddGrp.version)

        // 6. Remove group member
        val afterRemGrp = dir.removeMemberFromGroup("grp-scout", "dev-alpha-1", callerDeviceId = "admin-1")
        assertEquals(6L, dir.currentVersion)
        assertEquals(6L, afterRemGrp.version)

        // 7. Delete group -> records tombstone
        dir.deleteGroup("grp-scout", DirectoryActor.CONSOLE)
        assertEquals(7L, dir.currentVersion)

        db.withConnection { conn ->
            conn.prepareStatement("SELECT entity_type, entity_id, deleted_at_version FROM directory_tombstone WHERE entity_id = 'grp-scout'").use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Tombstone for group must be recorded")
                assertEquals("group", rs.getString("entity_type"))
                assertEquals("grp-scout", rs.getString("entity_id"))
                assertEquals(7L, rs.getLong("deleted_at_version"))
            }
        }

        // 8. Remove member -> records tombstone
        dir.removeDepartmentMember("dept-alpha", "dev-alpha-2")
        assertEquals(8L, dir.currentVersion)

        db.withConnection { conn ->
            conn.prepareStatement("SELECT entity_type, entity_id, deleted_at_version FROM directory_tombstone WHERE entity_id = 'dev-alpha-2'").use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Tombstone for member must be recorded")
                assertEquals("member", rs.getString("entity_type"))
                assertEquals("dev-alpha-2", rs.getString("entity_id"))
                assertEquals(8L, rs.getLong("deleted_at_version"))
            }
        }

        // Remove dev-alpha-1 so department becomes empty
        dir.removeDepartmentMember("dept-alpha", "dev-alpha-1")
        assertEquals(9L, dir.currentVersion)

        // 9. Delete department -> records tombstone
        dir.deleteDepartment("dept-alpha", DirectoryActor.CONSOLE)
        assertEquals(10L, dir.currentVersion)

        db.withConnection { conn ->
            conn.prepareStatement("SELECT entity_type, entity_id, deleted_at_version FROM directory_tombstone WHERE entity_id = 'dept-alpha'").use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Tombstone for department must be recorded")
                assertEquals("department", rs.getString("entity_type"))
                assertEquals("dept-alpha", rs.getString("entity_id"))
                assertEquals(10L, rs.getLong("deleted_at_version"))
            }
        }
    }

    @Test
    fun testMemberStatementSigningAndPersistence() {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)

        val rootKey = FlashCrypto.generateEcKeyPair()
        val delegateKey = FlashCrypto.generateEcKeyPair()
        val rootSpki = rootKey.toBase64Spki()
        val delegateSpki = delegateKey.toBase64Spki()

        val now = System.currentTimeMillis()
        val delegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootSpki,
            delegatePublicKey = delegateSpki,
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 10_000,
            validTo = now + 100_000,
            maxDevices = 10,
            serial = 1L,
        )

        dir.statementSigner = OrgMemberStatementSigner { deviceId, subjectPublicKey, departmentId, role, nowMs, serial ->
            val statement = OrgMemberStatement.issue(
                issuerPrivateKey = delegateKey.private,
                deviceId = deviceId,
                subjectPublicKey = subjectPublicKey,
                departmentId = departmentId,
                role = role,
                issuedAtMs = nowMs,
                statementSerial = serial,
            )
            statement to delegation.serial
        }

        val jamesSpki = testSpki("SPKI-JAMES")
        dir.createDepartment("dept-ops", "Operations", listOf("admin-ops"), isOrgAdmin = true)
        dir.addDepartmentMember(
            departmentId = "dept-ops",
            deviceId = "dev-agent-007",
            displayName = "James",
            role = MemberRole.MEMBER,
            subjectPublicKey = jamesSpki,
        )

        val statement = dir.getMemberStatement("dev-agent-007")
        assertNotNull(statement, "Member statement should be generated and stored")
        assertEquals("dev-agent-007", statement.deviceId)
        assertEquals(jamesSpki, statement.subjectPublicKey)
        assertEquals("dept-ops", statement.departmentId)
        assertEquals(com.transfer.flash.core.hubprotocol.certs.HubRoles.MEMBER, statement.role)
        assertTrue(statement.verifySignature(delegateSpki), "Signature must verify with delegate public key")

        // Reopen directory from same database -> verify statement was persisted and reloaded
        val reopened = OrganizationDirectory.open(db)
        val statementReopened = reopened.getMemberStatement("dev-agent-007")
        assertNotNull(statementReopened, "Member statement should be loaded after DB reopen")
        assertEquals(statement.statementSerial, statementReopened.statementSerial)
        assertEquals(statement.issuerSignature, statementReopened.issuerSignature)
        assertTrue(statementReopened.verifySignature(delegateSpki))
    }

    @Test
    fun testSyncChangesPaginationAndCursor() {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)

        val rootKey = FlashCrypto.generateEcKeyPair()
        val delegateKey = FlashCrypto.generateEcKeyPair()
        val rootSpki = rootKey.toBase64Spki()
        val delegateSpki = delegateKey.toBase64Spki()
        val now = System.currentTimeMillis()
        val delegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootSpki,
            delegatePublicKey = delegateSpki,
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 10_000,
            validTo = now + 100_000,
            maxDevices = 10,
            serial = 1L,
        )
        dir.statementSigner = OrgMemberStatementSigner { deviceId, subjectPublicKey, departmentId, role, nowMs, serial ->
            val statement = OrgMemberStatement.issue(
                issuerPrivateKey = delegateKey.private,
                deviceId = deviceId,
                subjectPublicKey = subjectPublicKey,
                departmentId = departmentId,
                role = role,
                issuedAtMs = nowMs,
                statementSerial = serial,
            )
            statement to delegation.serial
        }

        dir.createDepartment("dept-a", "Department A", emptyList(), isOrgAdmin = true)
        for (i in 1..5) {
            dir.addDepartmentMember(
                departmentId = "dept-a",
                deviceId = "dev-$i",
                displayName = "User $i",
                role = MemberRole.MEMBER,
                subjectPublicKey = testSpki("SPKI-$i"),
            )
        }

        val actor = DirectoryActor(
            deviceId = "admin-root",
            departmentId = null,
            role = MemberRole.ORG_ADMIN,
        )

        // Page 1 with limit 2
        val page1 = dir.getSyncChanges(since = 0L, cursor = null, limit = 2, actor = actor)
        assertEquals(dir.epochId, page1.directoryEpoch)
        assertEquals(dir.currentVersion, page1.version)
        assertFalse(page1.fullResync)
        assertEquals(1, page1.departments.size, "Page 1 should include departments")
        assertEquals(2, page1.members.size, "Page 1 should include 2 members")
        assertNotNull(page1.nextCursor)

        // Page 2 with limit 2
        val page2 = dir.getSyncChanges(since = 0L, cursor = page1.nextCursor, limit = 2, actor = actor)
        assertEquals(0, page2.departments.size, "Subsequent pages should not re-send departments")
        assertEquals(2, page2.members.size, "Page 2 should include 2 members")
        assertNotNull(page2.nextCursor)

        // Page 3 with limit 2
        val page3 = dir.getSyncChanges(since = 0L, cursor = page2.nextCursor, limit = 2, actor = actor)
        assertEquals(0, page3.departments.size)
        assertEquals(1, page3.members.size, "Page 3 should include last 1 member")
        assertNull(page3.nextCursor)

        // Total members received across all 3 pages must be dev-1 through dev-5
        val allMemberIds = (page1.members + page2.members + page3.members).map { it.deviceId }
        assertEquals(listOf("dev-1", "dev-2", "dev-3", "dev-4", "dev-5"), allMemberIds)

        // Query since currentVersion -> empty changes
        val emptyPage = dir.getSyncChanges(since = dir.currentVersion, cursor = null, limit = 10, actor = actor)
        assertNull(emptyPage.nextCursor)
        assertEquals(0, emptyPage.members.size)
        assertEquals(0, emptyPage.departments.size)
        assertEquals(0, emptyPage.removedDeviceIds.size)
    }

    @Test
    fun testEpochMismatchAndFutureSinceTriggersFullResync() {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)
        dir.createDepartment("dept-x", "Department X", emptyList(), isOrgAdmin = true)
        dir.addDepartmentMember("dept-x", "dev-x", "User X", role = MemberRole.MEMBER, subjectPublicKey = testSpki("SPKI-X"))

        val actor = DirectoryActor(
            deviceId = "dev-x",
            departmentId = "dept-x",
            role = MemberRole.MEMBER,
        )

        // 1. Mismatched epoch -> fullResync = true
        val mismatched = dir.getSyncChanges(since = 2L, cursor = null, limit = 10, actor = actor, requestedEpoch = "stale-epoch-id")
        assertTrue(mismatched.fullResync, "Mismatched epoch must trigger fullResync")
        assertEquals(dir.epochId, mismatched.directoryEpoch)
        assertEquals(1, mismatched.departments.size)

        // 2. Future since -> fullResync = true
        val future = dir.getSyncChanges(since = 999L, cursor = null, limit = 10, actor = actor, requestedEpoch = dir.epochId)
        assertTrue(future.fullResync, "Future since version must trigger fullResync")

        // 3. resetEpoch() resets epoch and next sync triggers fullResync
        val oldEpoch = dir.epochId
        dir.resetEpoch()
        assertTrue(dir.epochId != oldEpoch, "Epoch ID should change after reset")
        val afterReset = dir.getSyncChanges(since = 1L, cursor = null, limit = 10, actor = actor, requestedEpoch = oldEpoch)
        assertTrue(afterReset.fullResync)
    }

    @Test
    fun testSyncDepartmentScopingForNonAdmin() {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)

        val rootKey = FlashCrypto.generateEcKeyPair()
        val delegateKey = FlashCrypto.generateEcKeyPair()
        val now = System.currentTimeMillis()
        val delegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootKey.toBase64Spki(),
            delegatePublicKey = delegateKey.toBase64Spki(),
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 10_000,
            validTo = now + 100_000,
            maxDevices = 10,
            serial = 1L,
        )
        dir.statementSigner = OrgMemberStatementSigner { deviceId, subjectPublicKey, departmentId, role, nowMs, serial ->
            val statement = OrgMemberStatement.issue(
                issuerPrivateKey = delegateKey.private,
                deviceId = deviceId,
                subjectPublicKey = subjectPublicKey,
                departmentId = departmentId,
                role = role,
                issuedAtMs = nowMs,
                statementSerial = serial,
            )
            statement to delegation.serial
        }

        dir.createDepartment("dept-sales", "Sales", emptyList(), isOrgAdmin = true)
        dir.createDepartment("dept-eng", "Engineering", emptyList(), isOrgAdmin = true)

        dir.addDepartmentMember("dept-sales", "seller-1", "Alice", role = MemberRole.MEMBER, subjectPublicKey = testSpki("SPKI-A"))
        dir.addDepartmentMember("dept-eng", "coder-1", "Bob", role = MemberRole.MEMBER, subjectPublicKey = testSpki("SPKI-B"))

        // Plan A2 (option C, §3.3.6): member of dept-sales syncs non-hidden org-wide rows (both departments visible).
        val salesActor = DirectoryActor(
            deviceId = "seller-1",
            departmentId = "dept-sales",
            role = MemberRole.MEMBER,
        )
        val salesSync = dir.getSyncChanges(since = 0L, cursor = null, limit = 10, actor = salesActor)
        assertEquals(2, salesSync.members.size)
        assertEquals(setOf("seller-1", "coder-1"), salesSync.members.map { it.deviceId }.toSet())

        // Admin syncs: receives all members
        val adminActor = DirectoryActor(
            deviceId = "admin-1",
            departmentId = null,
            role = MemberRole.ORG_ADMIN,
        )
        val adminSync = dir.getSyncChanges(since = 0L, cursor = null, limit = 10, actor = adminActor)
        assertEquals(2, adminSync.members.size)
    }

    @Test
    fun testDirectorySyncHttpRouteIntegrationAndRateLimiting() = testApplication {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)
        val tokens = BearerTokenStore()

        val rootKey = FlashCrypto.generateEcKeyPair()
        val delegateKey = FlashCrypto.generateEcKeyPair()
        val now = System.currentTimeMillis()
        val delegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootKey.toBase64Spki(),
            delegatePublicKey = delegateKey.toBase64Spki(),
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 10_000,
            validTo = now + 100_000,
            maxDevices = 10,
            serial = 1L,
        )
        dir.statementSigner = OrgMemberStatementSigner { deviceId, subjectPublicKey, departmentId, role, nowMs, serial ->
            val statement = OrgMemberStatement.issue(
                issuerPrivateKey = delegateKey.private,
                deviceId = deviceId,
                subjectPublicKey = subjectPublicKey,
                departmentId = departmentId,
                role = role,
                issuedAtMs = nowMs,
                statementSerial = serial,
            )
            statement to delegation.serial
        }

        dir.createDepartment("dept-hq", "Headquarters", emptyList(), isOrgAdmin = true)
        dir.addDepartmentMember("dept-hq", "officer-1", "Major Smith", role = MemberRole.MEMBER, subjectPublicKey = testSpki("SPKI-OFFICER"))

        val token = tokens.issue(HubPrincipal("officer-1", DeviceCert.ROLE_MEMBER, "dept-hq", 1L)).first

        // Setup custom rate limits: capacity 2, refill very slow
        val customLimits = HubRateLimits(
            directorySync = TokenBucketLimiter(capacity = 2, refillPerSecond = 0.0001)
        )

        routing {
            directoryRoutes(
                directory = dir,
                principalResolver = BearerPrincipalResolver(tokens),
                limits = customLimits,
            )
        }

        // 1. Unauthenticated request -> 401 Unauthorized
        val unauth = client.get("/api/v1/directory/sync")
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)
        val err = HubRestCodec.decode<ErrorBody>(unauth.bodyAsText())
        assertEquals("ERR_AUTH", err.code)

        // 2. Authenticated request 1 -> 200 OK
        val res1 = client.get("/api/v1/directory/sync?since=0&limit=10") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, res1.status)
        val sync1 = HubRestCodec.decode<DirectorySyncDto>(res1.bodyAsText())
        assertEquals(dir.epochId, sync1.directoryEpoch)
        assertEquals(dir.currentVersion, sync1.version)
        assertEquals(1, sync1.members.size)
        assertEquals("officer-1", sync1.members[0].deviceId)

        // 3. Authenticated request 2 (uses alias sinceVersion & pageSize) -> 200 OK
        val res2 = client.get("/api/v1/directory/sync?sinceVersion=0&pageSize=10") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, res2.status)

        // 4. Authenticated request 3 -> 429 Too Many Requests (burst 2 exhausted)
        val res3 = client.get("/api/v1/directory/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.TooManyRequests, res3.status)
        val retryAfter = res3.headers[HttpHeaders.RetryAfter]
        assertNotNull(retryAfter, "Retry-After header must be present on 429")
        val err429 = HubRestCodec.decode<ErrorBody>(res3.bodyAsText())
        assertEquals(HubErrorCodes.ERR_RATE_LIMIT, err429.code)
    }
}
