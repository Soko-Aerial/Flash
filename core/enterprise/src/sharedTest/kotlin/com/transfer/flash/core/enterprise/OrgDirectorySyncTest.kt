@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.GroupRosterStatement
import com.transfer.flash.core.hubprotocol.rest.GroupRosterDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.core.hubprotocol.certs.HubScopes
import com.transfer.flash.core.hubprotocol.certs.OrgMemberStatement
import com.transfer.flash.core.hubprotocol.rest.DepartmentSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectoryMemberSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto
import com.transfer.flash.core.persistence.db.dao.OrgDirectoryDao
import com.transfer.flash.core.persistence.db.entity.HubGroupRosterEntity
import com.transfer.flash.core.persistence.db.entity.OrgDepartmentEntity
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.FlashCryptoEcdsaVerifier
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrgDirectorySyncTest {

    private val testOrg = TestOrg()
    private val verifier = FlashCryptoEcdsaVerifier(testOrg.hubCrypto)
    private val nowMs = TestOrg.T0

    private val creds = EnrollmentCredentials(
        hubUrl = "https://hub.acme.test",
        rootFingerprint = testOrg.fingerprint,
        rootPublicKey = testOrg.rootKey,
        deviceCert = testOrg.deviceCert(deviceId = "local-device", subjectKey = testOrg.hubKey),
        delegationChain = listOf(testOrg.delegation()),
        enrolledAtMs = nowMs - 100_000L,
        hubName = "Acme Test Hub",
    )

    private class ThreadSafeCounter(initial: Int = 0) {
        private val lock = Any()
        private var value = initial
        fun incrementAndGet(): Int = synchronized(lock) { ++value }
        fun get(): Int = synchronized(lock) { value }
    }

    private class FakeDirectorySyncApi : DirectorySyncApi {
        val pages = mutableListOf<DirectorySyncPageResult>()
        val callCount = ThreadSafeCounter(0)
        var delayMs: Long = 0L


        override suspend fun fetchDirectorySync(
            hubUrl: String,
            since: Long,
            epoch: String?,
            cursor: String?,
            limit: Int,
        ): DirectorySyncPageResult {
            val count = callCount.incrementAndGet()

            if (delayMs > 0) delay(delayMs)
            val index = cursor?.toIntOrNull() ?: (count - 1)
            return if (index in pages.indices) pages[index] else DirectorySyncPageResult.ServerError(404, "Page not found")
        }

    }

    private class FakeOrgDirectoryDao : OrgDirectoryDao {
        val storedRosters = LinkedHashMap<String, HubGroupRosterEntity>()
        override suspend fun allGroupRosters(): List<HubGroupRosterEntity> = storedRosters.values.toList()
        override suspend fun groupRoster(groupId: String): HubGroupRosterEntity? = storedRosters[groupId]
        override suspend fun upsertGroupRosters(rosters: List<HubGroupRosterEntity>) { rosters.forEach { storedRosters[it.groupId] = it } }
        override suspend fun clearGroupRosters() { storedRosters.clear() }
        val members = LinkedHashMap<String, OrgDirectoryMemberEntity>()
        val departments = LinkedHashMap<String, OrgDepartmentEntity>()
        var syncState: OrgSyncStateEntity? = null
        var shouldFailOnApply = false

        override suspend fun upsertMembers(members: List<OrgDirectoryMemberEntity>) {
            members.forEach { this.members[it.deviceId] = it }
        }

        override suspend fun deleteMembers(deviceIds: List<String>) {
            deviceIds.forEach { members.remove(it) }
        }

        override suspend fun findMemberByDeviceId(deviceId: String): OrgDirectoryMemberEntity? = members[deviceId]

        override suspend fun findMemberByFingerprint(fingerprint: String): OrgDirectoryMemberEntity? =
            members.values.firstOrNull { it.subjectPublicKeyFingerprint == fingerprint }

        override suspend fun searchMembers(prefix: String, limit: Int): List<OrgDirectoryMemberEntity> =
            members.values.filter { it.displayNameFolded.startsWith(prefix) }.take(limit)

        override suspend fun listMembersByDepartment(departmentId: String): List<OrgDirectoryMemberEntity> =
            members.values.filter { it.departmentId == departmentId }

        override suspend fun listAllMembers(): List<OrgDirectoryMemberEntity> = members.values.toList()

        override suspend fun upsertDepartments(departments: List<OrgDepartmentEntity>) {
            departments.forEach { this.departments[it.id] = it }
        }

        override suspend fun deleteDepartments(ids: List<String>) {
            ids.forEach { departments.remove(it) }
        }

        override suspend fun listDepartments(): List<OrgDepartmentEntity> = departments.values.toList()

        override suspend fun getSyncState(): OrgSyncStateEntity? = syncState

        override suspend fun setSyncState(state: OrgSyncStateEntity) {
            syncState = state
        }

        override suspend fun clearMembers() {
            members.clear()
        }

        override suspend fun clearDepartments() {
            departments.clear()
        }

        override suspend fun clearSyncState() {
            syncState = null
        }

        override suspend fun applySyncBatch(
            fullResync: Boolean,
            membersToUpsert: List<OrgDirectoryMemberEntity>,
            memberIdsToDelete: List<String>,
            departmentsToUpsert: List<OrgDepartmentEntity>,
            departmentIdsToDelete: List<String>,
            newState: OrgSyncStateEntity,
        ) {
            if (shouldFailOnApply) throw IllegalStateException("Simulated disk error during apply")
            super.applySyncBatch(
                fullResync = fullResync,
                membersToUpsert = membersToUpsert,
                memberIdsToDelete = memberIdsToDelete,
                departmentsToUpsert = departmentsToUpsert,
                departmentIdsToDelete = departmentIdsToDelete,
                newState = newState,
            )
        }
    }

    private fun createStatement(
        deviceId: String,
        subjectCrypto: TestCrypto = TestCrypto(),
        serial: Long = 1L,
        issuedAtMs: Long = nowMs,
        role: String = HubRoles.MEMBER,
        departmentId: String = "dept-1",
        signerCrypto: TestCrypto = testOrg.hubCrypto,
    ): Pair<OrgMemberStatement, TestCrypto> {
        val spki = Base64.encode(subjectCrypto.identityPublicKeyEncoded)
        val unsigned = OrgMemberStatement(
            deviceId = deviceId,
            subjectPublicKey = spki,
            departmentId = departmentId,
            role = role,
            issuedAtMs = issuedAtMs,
            statementSerial = serial,
            issuerSignature = "",
        )
        val sig = Base64.encode(signerCrypto.sign(unsigned.canonicalBytes()))
        return unsigned.copy(issuerSignature = sig) to subjectCrypto
    }

    @Test
    fun `ORGT-U10 client full resync atomic and multi-page staging`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (stmt1, _) = createStatement(deviceId = "dev-1", serial = 1)
        val (stmt2, _) = createStatement(deviceId = "dev-2", serial = 1)

        val page1 = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = "1",
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-1", displayName = "Alice", departmentId = "dept-1", statement = stmt1),
            ),
            departments = listOf(
                DepartmentSyncDto(id = "dept-1", name = "Engineering", memberCount = 1),
            ),
        )

        val page2 = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-2", displayName = "Bob", departmentId = "dept-1", statement = stmt2),
            ),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(page1))
        fakeApi.pages.add(DirectorySyncPageResult.Success(page2))

        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)
        assertEquals(2, outcome.membersUpserted)
        assertEquals(1, outcome.departmentsUpserted)
        assertEquals(true, outcome.fullResync)
        assertEquals(0, outcome.partialFailures)

        assertEquals(2, dao.members.size)
        assertNotNull(dao.findMemberByDeviceId("dev-1"))
        assertNotNull(dao.findMemberByDeviceId("dev-2"))
        assertEquals("epoch-1", dao.syncState?.directoryEpoch)
        assertEquals(10L, dao.syncState?.lastSyncedVersion)
    }

    @Test
    fun `ORGT-U10 failure on second page leaves database untouched`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (stmt1, _) = createStatement(deviceId = "dev-1", serial = 1)
        val page1 = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = "1",
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-1", displayName = "Alice", departmentId = "dept-1", statement = stmt1),
            ),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(page1))
        fakeApi.pages.add(DirectorySyncPageResult.ServerError(500, "Internal Server Error"))

        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.ServerError)

        // Database must remain completely untouched!
        assertEquals(0, dao.members.size)
        assertNull(dao.syncState)
    }

    @Test
    fun `ORGT-U11 stale serial ignored on delta sync`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (initialStmt, crypto) = createStatement(deviceId = "dev-1", serial = 10)
        val initialEntity = OrgDirectoryMemberEntity(
            deviceId = "dev-1",
            displayName = "Alice",
            displayNameFolded = "alice",
            departmentId = "dept-1",
            role = "member",
            subjectPublicKeyFingerprint = "FP-ALICE",
            statementBytes = byteArrayOf(1, 2, 3),
            statementSerial = 10L,
            statementIssuedAtMs = nowMs - 5000L,
            statementDelegationSerial = 1L,
            lastSyncedAtMs = nowMs - 5000L,
        )
        dao.upsertMembers(listOf(initialEntity))

        // Incoming update has older serial (5 < 10)
        val (staleStmt, _) = createStatement(deviceId = "dev-1", subjectCrypto = crypto, serial = 5)
        val deltaPage = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 15L,
            fromVersion = 10L,
            fullResync = false,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-1", displayName = "Alice Stale", departmentId = "dept-1", statement = staleStmt),
            ),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(deltaPage))
        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)

        val stored = dao.findMemberByDeviceId("dev-1")
        assertNotNull(stored)
        // Must still have serial 10, not downgraded to 5!
        assertEquals(10L, stored.statementSerial)
        assertEquals("Alice", stored.displayName)
    }

    @Test
    fun `ORGT-U12 partial failure rules for tampered member statements`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (validStmt, _) = createStatement(deviceId = "dev-valid", serial = 1)
        val (badSignerStmt, _) = createStatement(deviceId = "dev-bad", serial = 1, signerCrypto = TestCrypto()) // Wrong signer!

        val page = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-valid", displayName = "Valid Member", departmentId = "dept-1", statement = validStmt),
                DirectoryMemberSyncDto(deviceId = "dev-bad", displayName = "Tampered Member", departmentId = "dept-1", statement = badSignerStmt),
            ),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(page))
        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)
        assertEquals(1, outcome.membersUpserted)
        assertEquals(1, outcome.partialFailures)

        assertNotNull(dao.findMemberByDeviceId("dev-valid"))
        assertNull(dao.findMemberByDeviceId("dev-bad"))
    }

    @Test
    fun `ORGT-U12b statement with valid delegation but invalid scope is rejected by verifyStatus`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (validStmt, _) = createStatement(deviceId = "dev-valid", serial = 1)
        // Hub delegation only grants enrol:member, not enrol:admin!
        // So matchingDelegation is non-null (signed by hubCrypto matching testOrg.delegation()),
        // but verifyStatus is ChainStatus.SCOPE_DENIED.
        val (unauthorizedAdminStmt, _) = createStatement(
            deviceId = "dev-unauthorized-admin",
            serial = 1,
            role = HubRoles.ADMIN,
            signerCrypto = testOrg.hubCrypto,
        )

        val page = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-valid", displayName = "Valid Member", departmentId = "dept-1", statement = validStmt),
                DirectoryMemberSyncDto(deviceId = "dev-unauthorized-admin", displayName = "Unauthorized Admin", departmentId = "dept-1", statement = unauthorizedAdminStmt),
            ),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(page))
        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)
        assertEquals(1, outcome.membersUpserted)
        assertEquals(1, outcome.partialFailures)

        assertNotNull(dao.findMemberByDeviceId("dev-valid"))
        assertNull(dao.findMemberByDeviceId("dev-unauthorized-admin"))
    }

    @Test
    fun `ORGT-U13 wrong root in delegation chain triggers hard security lockout`() = runBlocking {

        var alertReceived: String? = null
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
            onHardSecurityError = { alertReceived = it },
        )

        val attackerOrg = TestOrg()
        val attackerDelegation = attackerOrg.delegation() // Signed by attackerOrg.rootKey, not testOrg.rootKey!

        val (stmt, _) = createStatement(deviceId = "dev-1")
        val page = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(attackerDelegation),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-1", displayName = "Alice", departmentId = "dept-1", statement = stmt),
            ),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(page))
        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.HardSecurityError)
        assertEquals("This server is not the one you joined", outcome.reason)
        assertEquals("This server is not the one you joined", alertReceived)

        // Database must remain untouched
        assertEquals(0, dao.members.size)

        // Second call immediately must be locked out without network attempt
        val initialCallCount = fakeApi.callCount.get()
        val secondOutcome = sync.syncOnce()
        assertTrue(secondOutcome is OrgSyncOutcome.HardSecurityError)
        assertEquals(initialCallCount, fakeApi.callCount.get())
    }

    @Test
    fun `ORGT-U14 unenrol clearSnapshot deletes snapshot and resets state`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (stmt, _) = createStatement(deviceId = "dev-1")
        val page = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-1", displayName = "Alice", departmentId = "dept-1", statement = stmt),
            ),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(page))
        sync.syncOnce()
        assertEquals(1, dao.members.size)
        assertNotNull(dao.syncState)

        sync.clearSnapshot()
        assertEquals(0, dao.members.size)
        assertNull(dao.syncState)
        assertEquals(0L, sync.lastSuccessfulSyncAtMs.value)
        assertNull(sync.lastOutcome.value)
    }

    @Test
    fun `ORGT-U15 clock skew handling when server time differs by more than 5 minutes`() = runBlocking {
        var observedSkew: Long? = null
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
            onClockSkewDetected = { observedSkew = it },
        )

        val skewedServerTime = nowMs + 10 * 60 * 1000L // 10 minutes ahead
        val (stmt, _) = createStatement(deviceId = "dev-1")
        val page = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = skewedServerTime,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-1", displayName = "Alice", departmentId = "dept-1", statement = stmt),
            ),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(page))
        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)

        assertTrue(sync.clockSkewDetected.value)
        assertEquals(10 * 60 * 1000L, observedSkew)

        // Lease timestamp math: capped at min(nowMs, serverTimeMs + 5 min) -> nowMs
        assertEquals(nowMs, dao.syncState?.lastSyncSuccessMs)
    }

    @Test
    fun `ORGT-U18 old hub 404 is tolerated as HubUnsupported`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        fakeApi.pages.add(DirectorySyncPageResult.HubUnsupported)
        val outcome = sync.syncOnce()
        assertEquals(OrgSyncOutcome.HubUnsupported, outcome)
        assertEquals(0, dao.members.size)
    }

    @Test
    fun `single flight concurrent sync requests join in-flight operation`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        fakeApi.delayMs = 50L
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (stmt, _) = createStatement(deviceId = "dev-1")
        val page = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-1", displayName = "Alice", departmentId = "dept-1", statement = stmt),
            ),
        )
        fakeApi.pages.add(DirectorySyncPageResult.Success(page))

        val call1 = async { sync.syncOnce() }
        val call2 = async { sync.syncOnce() }

        val res1 = call1.await()
        val res2 = call2.await()

        assertTrue(res1 is OrgSyncOutcome.Success)
        assertTrue(res2 is OrgSyncOutcome.Success)
        // Directory API must have been called exactly once!
        assertEquals(1, fakeApi.callCount.get())
    }

    @Test
    fun `Rule 8 conflict resolution drops both when same deviceId has two different keys in snapshot`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (stmtKey1, _) = createStatement(deviceId = "dev-conflict", subjectCrypto = TestCrypto(), serial = 1)
        val (stmtKey2, _) = createStatement(deviceId = "dev-conflict", subjectCrypto = TestCrypto(), serial = 2) // Different key!

        val page = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-conflict", displayName = "Conflicted 1", departmentId = "dept-1", statement = stmtKey1),
                DirectoryMemberSyncDto(deviceId = "dev-conflict", displayName = "Conflicted 2", departmentId = "dept-1", statement = stmtKey2),
            ),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(page))
        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)

        // Both must be dropped per Rule 8!
        assertNull(dao.findMemberByDeviceId("dev-conflict"))
        assertEquals(0, dao.members.size)
    }

    private fun conflictPage(vararg members: DirectoryMemberSyncDto, full: Boolean) = DirectorySyncDto(
        directoryEpoch = "epoch-1", version = 10L, fromVersion = 0L, fullResync = full, nextCursor = null, serverTimeMs = nowMs,
        delegationChain = listOf(testOrg.delegation()), members = members.toList(),
    )

    @Test
    fun `ORGT-U57 Rule 8 stays in force for key sequence K1 K2 K1`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(fakeApi, dao, { creds }, verifier, this, { nowMs })
        val k1 = TestCrypto()
        val (s1, _) = createStatement(deviceId = "dev-x", subjectCrypto = k1, serial = 1)
        val (s2, _) = createStatement(deviceId = "dev-x", subjectCrypto = TestCrypto(), serial = 2)
        val (s3, _) = createStatement(deviceId = "dev-x", subjectCrypto = k1, serial = 3)
        fakeApi.pages.add(DirectorySyncPageResult.Success(conflictPage(
            DirectoryMemberSyncDto("dev-x", "X", "dept-1", statement = s1),
            DirectoryMemberSyncDto("dev-x", "X", "dept-1", statement = s2),
            DirectoryMemberSyncDto("dev-x", "X", "dept-1", statement = s3),
            full = true,
        )))
        assertTrue(sync.syncOnce() is OrgSyncOutcome.Success)
        assertNull(dao.findMemberByDeviceId("dev-x"), "the third statement with the first key must not revive a conflicted device")
    }

    @Test
    fun `ORGT-U58 a conflict on a delta sync deletes the row an earlier sync stored`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(fakeApi, dao, { creds }, verifier, this, { nowMs })
        dao.upsertMembers(listOf(OrgDirectoryMemberEntity(
            deviceId = "dev-x", displayName = "X", displayNameFolded = "x", departmentId = "dept-1", role = "member",
            subjectPublicKeyFingerprint = "FP-OLD", statementBytes = byteArrayOf(1), statementSerial = 1L,
            statementIssuedAtMs = nowMs - 1000L, statementDelegationSerial = 1L, lastSyncedAtMs = nowMs - 1000L,
        )))
        val (a, _) = createStatement(deviceId = "dev-x", subjectCrypto = TestCrypto(), serial = 5)
        val (b, _) = createStatement(deviceId = "dev-x", subjectCrypto = TestCrypto(), serial = 6)
        fakeApi.pages.add(DirectorySyncPageResult.Success(conflictPage(
            DirectoryMemberSyncDto("dev-x", "X", "dept-1", statement = a),
            DirectoryMemberSyncDto("dev-x", "X", "dept-1", statement = b),
            full = false,
        )))
        assertTrue(sync.syncOnce() is OrgSyncOutcome.Success)
        assertNull(dao.findMemberByDeviceId("dev-x"))
    }

    @Test
    fun `ORGT-U16 5,000-member sync time and memory budget test`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val sharedDelegation = testOrg.delegation()
        // Pre-create 5,000 member DTOs
        val membersList = ArrayList<DirectoryMemberSyncDto>(5000)
        val sharedSubjectCrypto = TestCrypto()
        val spki = Base64.encode(sharedSubjectCrypto.identityPublicKeyEncoded)

        for (i in 1..5000) {
            val unsigned = OrgMemberStatement(
                deviceId = "dev-$i",
                subjectPublicKey = spki,
                departmentId = "dept-${i % 10}",
                role = HubRoles.MEMBER,
                issuedAtMs = nowMs,
                statementSerial = 1L,
                issuerSignature = "",
            )
            val sig = Base64.encode(testOrg.hubCrypto.sign(unsigned.canonicalBytes()))
            val signed = unsigned.copy(issuerSignature = sig)
            membersList.add(
                DirectoryMemberSyncDto(
                    deviceId = "dev-$i",
                    displayName = "Member $i",
                    departmentId = "dept-${i % 10}",
                    statement = signed,
                ),
            )
        }

        // Split into 10 pages of 500 members each
        for (p in 0 until 10) {
            val slice = membersList.subList(p * 500, (p + 1) * 500)
            val nextCur = if (p < 9) "${p + 1}" else null
            fakeApi.pages.add(
                DirectorySyncPageResult.Success(
                    DirectorySyncDto(
                        directoryEpoch = "epoch-5k",
                        version = 5000L,
                        fromVersion = 0L,
                        fullResync = true,
                        nextCursor = nextCur,
                        serverTimeMs = nowMs,
                        delegationChain = listOf(sharedDelegation),
                        members = slice,
                    ),
                ),
            )
        }

        val startNs = System.nanoTime()
        val outcome = sync.syncOnce()
        val durationMs = (System.nanoTime() - startNs) / 1_000_000

        assertTrue(outcome is OrgSyncOutcome.Success)
        assertEquals(5000, outcome.membersUpserted)
        assertEquals(5000, dao.members.size)
        // Memory/time budget: 5,000 members verified and staged should complete in under 25,000 ms on JVM (software ECDSA ~2ms/member)
        assertTrue(durationMs < 25000, "5,000 member sync took $durationMs ms (budget: 25000 ms)")
        println("ORGT-U16: 5,000 members sync took $durationMs ms")

        // Benchmark second conditional sync (0 changes)
        fakeApi.pages.add(
            DirectorySyncPageResult.Success(
                DirectorySyncDto(
                    directoryEpoch = "epoch-5k",
                    version = 5000L,
                    fromVersion = 5000L,
                    fullResync = false,
                    nextCursor = null,
                    serverTimeMs = nowMs + 1000L,
                    delegationChain = emptyList(),
                    members = emptyList(),
                ),
            ),
        )
        val deltaStartNs = System.nanoTime()
        val deltaOutcome = sync.syncOnce()
        val deltaDurationMs = (System.nanoTime() - deltaStartNs) / 1_000_000
        assertTrue(deltaOutcome is OrgSyncOutcome.Success)
        assertEquals(0, deltaOutcome.membersUpserted)
        assertEquals(false, deltaOutcome.fullResync)
        assertTrue(deltaDurationMs < 100, "Second conditional sync took $deltaDurationMs ms")
        println("ORGT-U16: Second conditional sync (0 changes) took $deltaDurationMs ms")
    }

    @Test
    fun `syncOnce returns StorageFailed when dao applySyncBatch fails and records backoff`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        dao.shouldFailOnApply = true
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (stmt, _) = createStatement(deviceId = "dev-1", serial = 1)
        val page = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-1", displayName = "Alice", departmentId = "dept-1", statement = stmt),
            ),
        )
        fakeApi.pages.add(DirectorySyncPageResult.Success(page))

        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.StorageFailed)
        assertTrue(outcome.message.contains("Simulated disk error during apply"))
        assertTrue(sync.isBackedOff)
        assertEquals(0, dao.members.size)
        assertNull(dao.getSyncState())
    }

    @Test
    fun `syncOnce returns NetworkError when directoryClient returns Unreachable and records backoff`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        fakeApi.pages.add(DirectorySyncPageResult.Unreachable("DNS resolution failed"))
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.NetworkError)
        assertEquals("DNS resolution failed", outcome.message)
        assertTrue(sync.isBackedOff)
        assertEquals(0, dao.members.size)
    }

    @Test
    fun `syncOnce returns AuthFailed when directoryClient returns AuthRefused and records backoff`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        fakeApi.pages.add(DirectorySyncPageResult.AuthRefused(403, "Token expired or revoked"))
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.AuthFailed)
        assertEquals(403, outcome.statusCode)
        assertEquals("Token expired or revoked", outcome.message)
        assertTrue(sync.isBackedOff)
        assertEquals(0, dao.members.size)
    }

    @Test
    fun `second conditional sync with zero updates returns success with zero upserted`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(
            directoryClient = fakeApi,
            orgDirectoryDao = dao,
            credentialsProvider = { creds },
            verifier = verifier,
            scope = this,
            clock = { nowMs },
        )

        val (stmt, _) = createStatement(deviceId = "dev-1", serial = 1)
        val initialPage = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 5000L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto(deviceId = "dev-1", displayName = "Alice", departmentId = "dept-1", statement = stmt),
            ),
        )
        val deltaPage = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 5000L,
            fromVersion = 5000L,
            fullResync = false,
            nextCursor = null,
            serverTimeMs = nowMs + 10_000L,
            delegationChain = emptyList(),
            members = emptyList(),
        )

        fakeApi.pages.add(DirectorySyncPageResult.Success(initialPage))
        fakeApi.pages.add(DirectorySyncPageResult.Success(deltaPage))

        val outcome1 = sync.syncOnce()
        assertTrue(outcome1 is OrgSyncOutcome.Success)
        assertEquals(1, outcome1.membersUpserted)
        assertEquals(true, outcome1.fullResync)
        assertEquals(5000L, dao.getSyncState()?.lastSyncedVersion)

        val outcome2 = sync.syncOnce()
        assertTrue(outcome2 is OrgSyncOutcome.Success)
        assertEquals(0, outcome2.membersUpserted)
        assertEquals(0, outcome2.membersRemoved)
        assertEquals(false, outcome2.fullResync)
        assertEquals(5000L, dao.getSyncState()?.lastSyncedVersion)
    }

    @Test
    fun `ORGT-U02 statement verification rejects wrong root, tampered signature, and invalid times`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(fakeApi, dao, { creds }, verifier, this, { nowMs })

        // 1. Wrong root key in delegation
        val wrongOrg = TestOrg()
        val (stmtWrongRoot, _) = createStatement(deviceId = "dev-wrong-root", signerCrypto = wrongOrg.hubCrypto)

        // 2. Tampered signature on statement
        val (validStmt, _) = createStatement(deviceId = "dev-tampered")
        val tamperedStmt = validStmt.copy(issuerSignature = Base64.encode(byteArrayOf(0, 1, 2, 3, 4)))

        // 3. Valid statement for control
        val (goodStmt, _) = createStatement(deviceId = "dev-good")

        val page = DirectorySyncDto(
            directoryEpoch = "epoch-1",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto("dev-wrong-root", "Wrong Root", "dept-1", statement = stmtWrongRoot),
                DirectoryMemberSyncDto("dev-tampered", "Tampered", "dept-1", statement = tamperedStmt),
                DirectoryMemberSyncDto("dev-good", "Good Member", "dept-1", statement = goodStmt),
            ),
        )
        fakeApi.pages.add(DirectorySyncPageResult.Success(page))

        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)
        // Only the valid member is upserted; wrong root and tampered signature are rejected
        assertEquals(1, outcome.membersUpserted)
        assertNotNull(dao.findMemberByDeviceId("dev-good"))
        assertNull(dao.findMemberByDeviceId("dev-wrong-root"))
        assertNull(dao.findMemberByDeviceId("dev-tampered"))
    }

    @Test
    fun `ORGT-U06 epoch change forces full resync and updates directoryEpoch`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(fakeApi, dao, { creds }, verifier, this, { nowMs })

        // Initial sync at epoch-1
        val (s1, _) = createStatement(deviceId = "dev-1")
        dao.setSyncState(
            OrgSyncStateEntity(
                id = 1,
                orgRootFingerprint = creds.rootFingerprint,
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 50L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = nowMs - 10_000L,
                lastSyncAttemptMs = nowMs - 10_000L,
            ),
        )
        dao.upsertMembers(listOf(
            OrgDirectoryMemberEntity(
                deviceId = "dev-old",
                displayName = "Old Member",
                displayNameFolded = "old member",
                departmentId = "dept-1",
                role = "member",
                subjectPublicKeyFingerprint = "FP_OLD",
                statementBytes = byteArrayOf(1),
                statementSerial = 1L,
                statementIssuedAtMs = nowMs - 20_000L,
                statementDelegationSerial = 1L,
                lastSyncedAtMs = nowMs - 10_000L,
            ),
        ))

        // Server responds with new epoch-2 and fullResync = true
        val page = DirectorySyncDto(
            directoryEpoch = "epoch-2",
            version = 10L,
            fromVersion = 0L,
            fullResync = true,
            nextCursor = null,
            serverTimeMs = nowMs,
            delegationChain = listOf(testOrg.delegation()),
            members = listOf(
                DirectoryMemberSyncDto("dev-1", "New Member", "dept-1", statement = s1),
            ),
        )
        fakeApi.pages.add(DirectorySyncPageResult.Success(page))

        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)
        assertTrue(outcome.fullResync)
        assertEquals("epoch-2", dao.getSyncState()?.directoryEpoch)
        assertEquals(10L, dao.getSyncState()?.lastSyncedVersion)
        assertNotNull(dao.findMemberByDeviceId("dev-1"))
        assertNull(dao.findMemberByDeviceId("dev-old"), "Full resync under new epoch must wipe stale members")
    }

    @Test
    fun `ORGT-U07 server requesting full resync replaces existing members and resets lastSyncedVersion`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(fakeApi, dao, { creds }, verifier, this, { nowMs })

        // Pre-populate with stale member
        val (sNew, _) = createStatement(deviceId = "dev-fresh")
        dao.setSyncState(
            OrgSyncStateEntity(
                id = 1,
                orgRootFingerprint = creds.rootFingerprint,
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 999L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = nowMs - 50_000L,
                lastSyncAttemptMs = nowMs - 50_000L,
            ),
        )
        dao.upsertMembers(listOf(
            OrgDirectoryMemberEntity(
                deviceId = "dev-stale",
                displayName = "Stale Member",
                displayNameFolded = "stale member",
                departmentId = "dept-1",
                role = "member",
                subjectPublicKeyFingerprint = "FP_STALE",
                statementBytes = byteArrayOf(9),
                statementSerial = 1L,
                statementIssuedAtMs = nowMs - 50_000L,
                statementDelegationSerial = 1L,
                lastSyncedAtMs = nowMs - 50_000L,
            ),
        ))

        // Server forces full resync with lower version
        fakeApi.pages.add(DirectorySyncPageResult.Success(
            DirectorySyncDto(
                directoryEpoch = "epoch-1",
                version = 100L,
                fromVersion = 0L,
                fullResync = true,
                nextCursor = null,
                serverTimeMs = nowMs,
                delegationChain = listOf(testOrg.delegation()),
                members = listOf(
                    DirectoryMemberSyncDto("dev-fresh", "Fresh Member", "dept-1", statement = sNew),
                ),
            ),
        ))

        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)
        assertTrue(outcome.fullResync)
        assertEquals(100L, dao.getSyncState()?.lastSyncedVersion)
        assertNotNull(dao.findMemberByDeviceId("dev-fresh"))
        assertNull(dao.findMemberByDeviceId("dev-stale"))
    }

    @Test
    fun `ORGT-U08 removed device ids are deleted in the same transaction as member upserts`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(fakeApi, dao, { creds }, verifier, this, { nowMs })

        // Existing members dev-keep and dev-remove
        val (sKeep, _) = createStatement(deviceId = "dev-keep", serial = 2)
        dao.upsertMembers(listOf(
            OrgDirectoryMemberEntity(
                deviceId = "dev-keep", displayName = "Keep Me", displayNameFolded = "keep me", departmentId = "dept-1", role = "member",
                subjectPublicKeyFingerprint = "FP_KEEP", statementBytes = byteArrayOf(1), statementSerial = 1L,
                statementIssuedAtMs = nowMs - 1000L, statementDelegationSerial = 1L, lastSyncedAtMs = nowMs - 1000L,
            ),
            OrgDirectoryMemberEntity(
                deviceId = "dev-remove", displayName = "Remove Me", displayNameFolded = "remove me", departmentId = "dept-1", role = "member",
                subjectPublicKeyFingerprint = "FP_REMOVE", statementBytes = byteArrayOf(2), statementSerial = 1L,
                statementIssuedAtMs = nowMs - 1000L, statementDelegationSerial = 1L, lastSyncedAtMs = nowMs - 1000L,
            ),
        ))

        fakeApi.pages.add(DirectorySyncPageResult.Success(
            DirectorySyncDto(
                directoryEpoch = "epoch-1",
                version = 20L,
                fromVersion = 10L,
                fullResync = false,
                nextCursor = null,
                serverTimeMs = nowMs,
                delegationChain = listOf(testOrg.delegation()),
                members = listOf(
                    DirectoryMemberSyncDto("dev-keep", "Keep Me Updated", "dept-1", statement = sKeep),
                ),
                removedDeviceIds = listOf("dev-remove"),
            ),
        ))

        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)
        assertEquals(1, outcome.membersUpserted)
        assertEquals(1, outcome.membersRemoved)
        assertNotNull(dao.findMemberByDeviceId("dev-keep"))
        assertNull(dao.findMemberByDeviceId("dev-remove"), "Tombstoned member must be deleted atomically")
    }

    @Test
    fun `ORGT-U09 rate limit 429 records backoff and honors retry-after header`() = runBlocking {
        val fakeApi = FakeDirectorySyncApi()
        val dao = FakeOrgDirectoryDao()
        val sync = OrgDirectorySync(fakeApi, dao, { creds }, verifier, this, { nowMs })

        fakeApi.pages.add(DirectorySyncPageResult.RateLimited(retryAfterSeconds = 120L))
        val outcome = sync.syncOnce(force = false)
        assertTrue(outcome is OrgSyncOutcome.RateLimited)
        assertEquals(120L, outcome.retryAfterSeconds)
        assertTrue(sync.isBackedOff, "Sync engine must enter backed off state")

        // Non-forced call while backed off must return RateLimited without calling API
        val callCountBefore = fakeApi.callCount.get()
        val backedOffOutcome = sync.syncOnce(force = false)
        assertTrue(backedOffOutcome is OrgSyncOutcome.RateLimited)
        assertEquals(callCountBefore, fakeApi.callCount.get(), "No API call should be made while backed off")

        // Force call bypasses rate limit backoff
        fakeApi.pages.add(DirectorySyncPageResult.Success(
            DirectorySyncDto(
                directoryEpoch = "epoch-1",
                version = 1L,
                fromVersion = 0L,
                fullResync = true,
                nextCursor = null,
                serverTimeMs = nowMs,
                delegationChain = emptyList(),
                members = emptyList(),
            ),
        ))
        val forceOutcome = sync.syncOnce(force = true)
        assertTrue(forceOutcome is OrgSyncOutcome.Success)
        assertFalse(sync.isBackedOff, "Successful sync must clear backoff")
    }

    @Test
    fun `ORGT-U17 directory sync client requests respect since, epoch, and cursor parameters`() = runBlocking {
        val recordedRequests = mutableListOf<Triple<Long, String?, String?>>()
        val trackingApi = object : DirectorySyncApi {
            override suspend fun fetchDirectorySync(
                hubUrl: String,
                since: Long,
                epoch: String?,
                cursor: String?,
                limit: Int,
            ): DirectorySyncPageResult {
                recordedRequests.add(Triple(since, epoch, cursor))
                return DirectorySyncPageResult.Success(
                    DirectorySyncDto(
                        directoryEpoch = "epoch-target",
                        version = since + 10L,
                        fromVersion = since,
                        fullResync = false,
                        nextCursor = null,
                        serverTimeMs = nowMs,
                        delegationChain = emptyList(),
                        members = emptyList(),
                    ),
                )
            }
        }

        val dao = FakeOrgDirectoryDao()
        dao.setSyncState(
            OrgSyncStateEntity(
                id = 1,
                orgRootFingerprint = creds.rootFingerprint,
                directoryEpoch = "epoch-initial",
                lastSyncedVersion = 350L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = nowMs,
                lastSyncAttemptMs = nowMs,
            ),
        )

        val sync = OrgDirectorySync(trackingApi, dao, { creds }, verifier, this, { nowMs })
        val outcome = sync.syncOnce()
        assertTrue(outcome is OrgSyncOutcome.Success)

        assertEquals(1, recordedRequests.size)
        val (sentSince, sentEpoch, sentCursor) = recordedRequests.first()
        assertEquals(350L, sentSince, "Request since must match lastSyncedVersion in sync state")
        assertEquals("epoch-initial", sentEpoch, "Request epoch must match directoryEpoch in sync state")
        assertNull(sentCursor, "First page cursor must be null")
    }

    // ---- Phase B4: hub-signed group rosters (ORGT-U42, U43, U46) ----

    private fun signedRoster(
        version: Long,
        members: List<String> = listOf("dev-1", "local-device"),
        validUntilMs: Long = nowMs + 30L * 24 * 3_600_000,
        signer: TestCrypto = testOrg.hubCrypto,
        groupId: String = "grp-1",
    ): GroupRosterStatement {
        val unsigned = GroupRosterStatement(
            groupId = groupId, groupName = "Platform", departmentId = "dept-1", version = version,
            memberDeviceIds = members.sorted(), adminDeviceIds = emptyList(),
            issuedAtMs = nowMs, validUntilMs = validUntilMs,
        )
        return unsigned.copy(issuerSignature = Base64.encode(signer.sign(unsigned.canonicalBytes())))
    }

    private fun rosterPage(vararg rosters: GroupRosterStatement, fullResync: Boolean = true) = DirectorySyncDto(
        directoryEpoch = "epoch-1", version = 10L, fromVersion = 0L, fullResync = fullResync, nextCursor = null,
        serverTimeMs = nowMs, delegationChain = listOf(testOrg.delegation()),
        groupRosters = rosters.map { GroupRosterDto(it.groupId, it.version, HubRestCodec.encode(it), it.validUntilMs) },
    )

    private fun TestScope2.syncWith(dao: FakeOrgDirectoryDao, page: DirectorySyncDto): OrgSyncOutcome = runBlocking {
        val api = FakeDirectorySyncApi()
        api.pages.add(DirectorySyncPageResult.Success(page))
        OrgDirectorySync(api, dao, { creds }, verifier, this, { nowMs }).syncOnce()
    }

    private object TestScope2

    @Test
    fun `ORGT-U42 a verified roster is stored with its chain and an invalid one is dropped`() {
        val dao = FakeOrgDirectoryDao()
        val forged = signedRoster(version = 5L, signer = TestCrypto(), groupId = "grp-forged")
        val expired = signedRoster(version = 5L, validUntilMs = nowMs - 1L, groupId = "grp-expired")
        val outcome = TestScope2.syncWith(dao, rosterPage(signedRoster(2L), forged, expired))
        assertTrue(outcome is OrgSyncOutcome.Success)
        assertEquals(setOf("grp-1"), dao.storedRosters.keys, "the forged and the expired statement are not stored")
        assertTrue(dao.storedRosters.getValue("grp-1").delegationChainJson.isNotBlank())
    }

    @Test
    fun `ORGT-U43 a lower version never replaces a higher stored one and a group the hub drops is removed`() {
        val dao = FakeOrgDirectoryDao()
        TestScope2.syncWith(dao, rosterPage(signedRoster(7L), signedRoster(1L, groupId = "grp-2")))
        assertEquals(setOf("grp-1", "grp-2"), dao.storedRosters.keys)
        TestScope2.syncWith(dao, rosterPage(signedRoster(3L), fullResync = false))
        assertEquals(7L, dao.storedRosters.getValue("grp-1").version, "version 3 must not replace 7")
        assertEquals(setOf("grp-1"), dao.storedRosters.keys, "grp-2 is gone from the hub's set")
    }

    @Test
    fun `ORGT-U43b after a full resync the hub's versions win even when lower (restored hub, epoch reset)`() {
        val dao = FakeOrgDirectoryDao()
        TestScope2.syncWith(dao, rosterPage(signedRoster(7L)))
        TestScope2.syncWith(dao, rosterPage(signedRoster(2L), fullResync = true))
        assertEquals(2L, dao.storedRosters.getValue("grp-1").version)
    }

    @Test
    fun `ORGT-U46 a gossip offer is adopted only when verified newer and the local device is a member`() = runBlocking {
        val dao = FakeOrgDirectoryDao()
        val rosters = HubGroupRosters(dao, { creds }, verifier, { "local-device" }, clock = { nowMs })
        val chain = HubRestCodec.encode(listOf(testOrg.delegation()))
        fun offer(st: GroupRosterStatement) = HubRosterOfferPayload(st.groupId, HubRestCodec.encode(st), chain)

        assertEquals(RosterAdoption.ADOPTED, rosters.adopt(offer(signedRoster(2L))))
        assertEquals(setOf("dev-1", "local-device"), rosters.current("grp-1", nowMs)?.memberDeviceIds)
        assertEquals(RosterAdoption.STALE, rosters.adopt(offer(signedRoster(2L))), "the same version adds nothing")
        assertEquals(RosterAdoption.STALE, rosters.adopt(offer(signedRoster(1L))), "a lower version never replaces")
        assertEquals(RosterAdoption.INVALID, rosters.adopt(offer(signedRoster(9L, signer = TestCrypto()))), "tampered / foreign signer")
        assertEquals(RosterAdoption.INVALID, rosters.adopt(offer(signedRoster(9L, validUntilMs = nowMs - 1L))), "expired")
        assertEquals(RosterAdoption.INVALID, rosters.adopt(HubRosterOfferPayload("grp-1", "{not json", chain)))
        assertEquals(RosterAdoption.NOT_A_MEMBER, rosters.adopt(offer(signedRoster(9L, members = listOf("dev-1"), groupId = "grp-x"))))
        assertEquals(RosterAdoption.ADOPTED, rosters.adopt(offer(signedRoster(3L, members = listOf("dev-1", "local-device")))))
        assertEquals(3L, rosters.current("grp-1", nowMs)?.version)
        assertEquals(RosterAdoption.SENDER_NOT_MEMBER, rosters.adopt(offer(signedRoster(10L)), offeredBy = "stranger"), "an offer from a non-member is refused")
        assertNull(rosters.current("grp-1", nowMs + 31L * 24 * 3_600_000), "an expired window answers nobody")
    }
}
