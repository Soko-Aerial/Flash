@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.core.hubprotocol.certs.HubScopes
import com.transfer.flash.core.hubprotocol.certs.OrgMemberStatement
import com.transfer.flash.core.hubprotocol.rest.DepartmentSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectoryMemberSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto
import com.transfer.flash.core.persistence.db.dao.OrgDirectoryDao
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
}

