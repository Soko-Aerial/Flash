@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.persistence.db.dao.OrgDirectoryDao
import com.transfer.flash.core.persistence.db.entity.OrgDepartmentEntity
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.security.trust.PinSource
import com.transfer.flash.core.security.trust.VouchVerdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrgPeerResolverTest {

    private class CountingOrgDirectoryDao : OrgDirectoryDao {
        val members = LinkedHashMap<String, OrgDirectoryMemberEntity>()
        var syncState: OrgSyncStateEntity? = null
        var listAllMembersCount = 0
        var getSyncStateCount = 0
        var onListAllMembers: suspend () -> Unit = {}

        override suspend fun upsertMembers(members: List<OrgDirectoryMemberEntity>) {
            members.forEach { this.members[it.deviceId] = it }
        }

        override suspend fun deleteMembers(deviceIds: List<String>) {
            deviceIds.forEach { members.remove(it) }
        }

        override suspend fun findMemberByDeviceId(deviceId: String): OrgDirectoryMemberEntity? =
            members[deviceId]

        override suspend fun findMemberByFingerprint(fingerprint: String): OrgDirectoryMemberEntity? =
            members.values.firstOrNull { it.subjectPublicKeyFingerprint == fingerprint }

        override suspend fun searchMembers(prefix: String, limit: Int): List<OrgDirectoryMemberEntity> =
            members.values.take(limit)

        override suspend fun listMembersByDepartment(departmentId: String): List<OrgDirectoryMemberEntity> =
            members.values.filter { it.departmentId == departmentId }

        override suspend fun listAllMembers(): List<OrgDirectoryMemberEntity> {
            listAllMembersCount++
            onListAllMembers()
            return members.values.toList()
        }

        override suspend fun upsertDepartments(departments: List<OrgDepartmentEntity>) {}
        override suspend fun deleteDepartments(ids: List<String>) {}
        override suspend fun listDepartments(): List<OrgDepartmentEntity> = emptyList()

        override suspend fun getSyncState(): OrgSyncStateEntity? {
            getSyncStateCount++
            return syncState
        }

        override suspend fun setSyncState(state: OrgSyncStateEntity) {
            syncState = state
        }

        override suspend fun clearMembers() { members.clear() }
        override suspend fun clearDepartments() {}
        override suspend fun clearSyncState() { syncState = null }
    }

    private class TestTrustStore : FlashTrustStore {
        val pins = mutableMapOf<String, String>()
        val trusted = mutableSetOf<String>()
        val vouched = mutableMapOf<String, MutableSet<String>>()

        override fun isTrusted(deviceId: FlashDeviceId): Boolean = deviceId.value in trusted

        override fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit> {
            trusted.add(deviceId.value)
            return FlashResult.Success(Unit)
        }

        override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> {
            trusted.remove(deviceId.value)
            return FlashResult.Success(Unit)
        }

        override fun getTrustedPeers(): Map<FlashDeviceId, String> =
            trusted.associate { FlashDeviceId(it) to it }

        override fun savePin(deviceId: FlashDeviceId, fingerprintHex: String): FlashResult<Unit> {
            pins[deviceId.value] = fingerprintHex
            return FlashResult.Success(Unit)
        }

        override fun getPin(deviceId: FlashDeviceId): String? = pins[deviceId.value]

        override fun vouchingGroups(deviceId: FlashDeviceId): Set<String> =
            vouched[deviceId.value].orEmpty()

        override fun applyVouch(deviceId: FlashDeviceId, fingerprintHex: String, groupId: String): VouchVerdict {
            val verdict = vouchVerdict(deviceId, fingerprintHex, groupId)
            if (verdict == VouchVerdict.ACCEPT) {
                pins[deviceId.value] = fingerprintHex
                vouched.getOrPut(deviceId.value) { mutableSetOf() }.add(groupId)
            }
            return verdict
        }

        override fun revokeVouch(deviceId: FlashDeviceId, groupId: String) {
            vouched[deviceId.value]?.remove(groupId)
        }
    }

    private fun sampleMember(
        deviceId: String = "alice-dev",
        displayName: String = "Alice Smith",
        departmentId: String = "engineering",
        role: String = "member",
        fingerprint: String = "A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5F6A1B2",
    ) = OrgDirectoryMemberEntity(
        deviceId = deviceId,
        displayName = displayName,
        displayNameFolded = displayName.lowercase(),
        departmentId = departmentId,
        role = role,
        subjectPublicKeyFingerprint = fingerprint,
        statementBytes = ByteArray(32),
        statementSerial = 1L,
        statementIssuedAtMs = 1_000_000L,
        statementDelegationSerial = 1L,
        lastSyncedAtMs = 1_000_000L,
    )

    @Test
    fun ORGT_U20_resolver_is_O1_in_memory_without_db_access_during_resolve() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        var currentClock = 1_500_000L
        val resolver = RealOrgPeerResolver(
            dao = dao,
            trustStore = trustStore,
            clock = { currentClock },
        )

        dao.upsertMembers(listOf(sampleMember("alice"), sampleMember("bob")))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 10L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 1_000_000L,
                lastSyncAttemptMs = 1_000_000L,
            )
        )

        resolver.refreshFromSnapshot()

        val dbReadsAfterInit = dao.listAllMembersCount + dao.getSyncStateCount

        // 100 consecutive resolve calls must NOT hit the database
        repeat(100) {
            val resolved = resolver.resolve("alice")
            assertNotNull(resolved)
            assertEquals("alice", resolved.deviceId)
        }

        val dbReadsAfterResolves = dao.listAllMembersCount + dao.getSyncStateCount
        assertEquals(dbReadsAfterInit, dbReadsAfterResolves, "resolve() must be strictly in-memory O(1) without database queries")
    }

    @Test
    fun ORGT_U21_binding_check_validates_pin_matching_statement_fingerprint() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        val member = sampleMember(deviceId = "alice", fingerprint = "AA11BB22")
        dao.upsertMembers(listOf(member))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 1_000_000L,
                lastSyncAttemptMs = 1_000_000L,
            )
        )

        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { 1_100_000L })
        resolver.refreshFromSnapshot()

        // 1. No pin stored -> bindingOk == false
        val resolvedNoPin = resolver.resolve("alice")
        assertNotNull(resolvedNoPin)
        assertFalse(resolvedNoPin.bindingOk, "Binding must fail when no pin is stored")

        // 2. Matching pin (case-insensitive) -> bindingOk == true
        trustStore.savePin("alice", "aa11bb22")
        val resolvedMatchingPin = resolver.resolve("alice")
        assertNotNull(resolvedMatchingPin)
        assertTrue(resolvedMatchingPin.bindingOk, "Binding must succeed when stored pin matches statement fingerprint")
        assertTrue(resolver.isOrgPeer("alice"))

        // 3. Mismatched pin without TOFU replacement (e.g. PAIRED) -> bindingOk == false
        trustStore.savePin("bob", "CORRECT_BOB_PIN")
        trustStore.trustPeer(FlashDeviceId("bob"), "Bob")
        dao.upsertMembers(listOf(sampleMember(deviceId = "bob", fingerprint = "STATEMENT_BOB_FP")))
        resolver.refreshFromSnapshot()
        val resolvedMismatchedPin = resolver.resolve("bob")
        assertNotNull(resolvedMismatchedPin)
        assertFalse(resolvedMismatchedPin.bindingOk, "Binding must fail when stored pin does not match statement fingerprint")
        assertFalse(resolver.isOrgPeer("bob"))
    }

    @Test
    fun ORGT_U22_tofu_pin_replacement_under_rule_3_2_fails_closed_then_binds_matching_session() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        val statementFp = "STATEMENT_FINGERPRINT_1234567890"
        val member = sampleMember(deviceId = "alice", fingerprint = statementFp)
        dao.upsertMembers(listOf(member))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 1_000_000L,
                lastSyncAttemptMs = 1_000_000L,
            )
        )

        // Attacker connected first, creating a TOFU pin
        val attackerPin = "ATTACKER_FAKE_PIN_0000000000"
        trustStore.savePin("alice", attackerPin)
        assertEquals(PinSource.TOFU, trustStore.pinSource(FlashDeviceId("alice")))

        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { 1_100_000L })
        resolver.refreshFromSnapshot()

        // 1. Mismatch occurs on live session:
        // Rule 1: Mismatch -> NOT ORG (live session binding must FAIL CLOSED)
        // Rule 2: TOFU pin in TrustStore is replaced with verified statement fingerprint
        val resolvedMismatchedSession = resolver.resolve("alice")
        assertNotNull(resolvedMismatchedSession)
        assertFalse(
            resolvedMismatchedSession.bindingOk,
            "Live session binding for mismatched TOFU session must fail closed",
        )
        assertEquals(
            statementFp,
            trustStore.getPin("alice"),
            "TrustStore pin must be overwritten with verified statement fingerprint",
        )

        // 2. On subsequent resolve with the matching pin now in TrustStore, bindingOk is true
        val resolvedSubsequentSession = resolver.resolve("alice")
        assertNotNull(resolvedSubsequentSession)
        assertTrue(
            resolvedSubsequentSession.bindingOk,
            "Subsequent session authenticated under replaced statement pin must pass binding",
        )
        assertTrue(resolver.isOrgPeer("alice"))

        // 3. Also verify isOrgPeer() directly fails closed on first call with mismatched TOFU peer Bob
        val bobFp = "STATEMENT_BOB_FINGERPRINT"
        dao.upsertMembers(listOf(sampleMember(deviceId = "bob", fingerprint = bobFp)))
        trustStore.savePin("bob", "ATTACKER_FAKE_BOB_PIN")
        resolver.refreshFromSnapshot()

        // First call fails closed on mismatch
        assertFalse(
            resolver.isOrgPeer("bob"),
            "isOrgPeer() must fail closed on initial mismatched TOFU session",
        )
        // Trust store pin was updated
        assertEquals(bobFp, trustStore.getPin("bob"))
        // Subsequent call passes
        assertTrue(
            resolver.isOrgPeer("bob"),
            "Subsequent isOrgPeer() must pass once pin is updated to statement fingerprint",
        )
    }

    @Test
    fun ORGT_U23_paired_or_vouched_pins_are_never_replaced() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        val statementFp = "STATEMENT_FINGERPRINT_1234567890"
        val member = sampleMember(deviceId = "alice", fingerprint = statementFp)
        dao.upsertMembers(listOf(member))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 1_000_000L,
                lastSyncAttemptMs = 1_000_000L,
            )
        )

        // 1. Device is PAIRED under a different key
        val pairedPin = "USER_VERIFIED_PAIRED_PIN_9999"
        trustStore.savePin("alice", pairedPin)
        trustStore.trustPeer(FlashDeviceId("alice"), "Alice")
        assertEquals(PinSource.PAIRED, trustStore.pinSource(FlashDeviceId("alice")))

        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { 1_100_000L })
        resolver.refreshFromSnapshot()

        val resolved = resolver.resolve("alice")
        assertNotNull(resolved)
        assertFalse(resolved.bindingOk, "Binding must fail when statement disagrees with PAIRED pin")
        assertEquals(pairedPin, trustStore.getPin("alice"), "PAIRED pin must never be replaced by directory statement")

        // 2. Device is VOUCHED under another group's key
        trustStore.revokeTrust(FlashDeviceId("bob"))
        val vouchedPin = "VOUCHED_GROUP_PIN_7777"
        trustStore.savePin("bob", vouchedPin)
        dao.upsertMembers(listOf(sampleMember(deviceId = "bob", fingerprint = "STATEMENT_BOB")))
        trustStore.applyVouch(FlashDeviceId("bob"), vouchedPin, "group-1")
        assertEquals(PinSource.VOUCHED, trustStore.pinSource(FlashDeviceId("bob")))

        resolver.refreshFromSnapshot()
        val resolvedBob = resolver.resolve("bob")
        assertNotNull(resolvedBob)
        assertFalse(resolvedBob.bindingOk, "Binding must fail when statement disagrees with VOUCHED pin")
        assertEquals(vouchedPin, trustStore.getPin("bob"), "VOUCHED pin must never be replaced by directory statement")
    }

    @Test
    fun ORGT_U24_lease_evaluation_handles_valid_expiring_expired_and_clock_rollback() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        val lastSync = 1_000_000_000L
        val graceDays = 30
        val graceMs = 30 * 86_400_000L // 2,592,000,000 ms
        val validUntilMs = lastSync + graceMs

        dao.upsertMembers(listOf(sampleMember("alice")))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = graceDays,
                lastSyncSuccessMs = lastSync,
                lastSyncAttemptMs = lastSync,
            )
        )

        var simulatedTime = lastSync + 10_000L
        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { simulatedTime })
        resolver.refreshFromSnapshot()

        // 1. Valid lease
        val lease1 = resolver.lease.value
        assertTrue(lease1 is OrgLease.Valid)
        assertEquals(validUntilMs, lease1.untilMs)
        assertNotNull(resolver.resolve("alice"))

        // 2. Exact boundary tests
        simulatedTime = validUntilMs - 1L
        assertTrue(resolver.lease.value is OrgLease.Valid)

        simulatedTime = validUntilMs
        assertTrue(resolver.lease.value is OrgLease.Valid)

        // 3. 1 ms past expiry -> Expired by TIME
        simulatedTime = validUntilMs + 1L
        val expiredTimeLease = resolver.lease.value
        assertTrue(expiredTimeLease is OrgLease.Expired)
        assertEquals(ExpiredReason.TIME, expiredTimeLease.reason)
        assertNull(resolver.resolve("alice"), "Expired lease must cause resolve() to return null")

        // 4. Clock rollback past 5-minute tolerance
        simulatedTime = lastSync - (5 * 60 * 1000L) - 1L
        val clockRolledLease = resolver.lease.value
        assertTrue(clockRolledLease is OrgLease.Expired)
        assertEquals(ExpiredReason.CLOCK, clockRolledLease.reason)
        assertNull(resolver.resolve("alice"), "Clock rollback must expire lease and cause resolve() to return null")

        // 5. Clock within 5-minute skew tolerance is valid
        simulatedTime = lastSync - (4 * 60 * 1000L)
        resolver.refreshFromSnapshot()
        assertTrue(resolver.lease.value is OrgLease.Valid)
    }

    @Test
    fun ORGT_U32_clear_and_unenrol_clears_resolver_memory_index_immediately() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        dao.upsertMembers(listOf(sampleMember("alice"), sampleMember("bob")))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 1_000_000L,
                lastSyncAttemptMs = 1_000_000L,
            )
        )

        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { 1_100_000L })
        resolver.refreshFromSnapshot()

        assertTrue(resolver.isKnownColleague("alice"))
        assertNotNull(resolver.resolve("alice"))
        assertTrue(resolver.lease.value is OrgLease.Valid)

        // Clear snapshot on un-enrollment
        resolver.clear()

        assertFalse(resolver.isKnownColleague("alice"), "Alice must no longer be in memory index")
        assertFalse(resolver.isKnownColleague("bob"), "Bob must no longer be in memory index")
        assertNull(resolver.resolve("alice"))
        assertEquals(OrgLease.None, resolver.lease.value, "Lease must revert to None on un-enrollment")
    }

    @Test
    fun ORGT_U32_race_frame_arriving_while_unenrolling() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        val member = sampleMember(deviceId = "alice", fingerprint = "AA11BB22")
        dao.upsertMembers(listOf(member))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 1_000_000L,
                lastSyncAttemptMs = 1_000_000L,
            )
        )
        trustStore.savePin("alice", "AA11BB22")

        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { 1_100_000L })
        resolver.refreshFromSnapshot()

        // Concurrently query resolver from 10 coroutines while unenrolling (clear) runs concurrently
        val lookupJobs = (1..10).map {
            launch(Dispatchers.Default) {
                repeat(200) {
                    // Safe execution: must never crash or throw ConcurrentModificationException
                    resolver.resolve("alice")
                    resolver.isOrgPeer("alice")
                    resolver.isKnownColleague("alice")
                }
            }
        }

        val clearJob = launch(Dispatchers.Default) {
            resolver.clear()
        }

        (lookupJobs + clearJob).joinAll()

        // Post-condition: strictly cleared
        assertNull(resolver.resolve("alice"), "resolve() must return null after unenrol")
        assertFalse(resolver.isOrgPeer("alice"), "isOrgPeer must be false after unenrol")
        assertFalse(resolver.isKnownColleague("alice"), "isKnownColleague must be false after unenrol")
        assertEquals(OrgLease.None, resolver.lease.value, "Lease must be None after unenrol")
    }

    @Test
    fun ORGT_MUTATION_1_isOrgPeer_failsIfFingerprintIgnored() = runBlocking {
        // Mutation check (1): ignore fingerprint (must fail if isOrgPeer ignores fingerprint)
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        val member = sampleMember(deviceId = "alice", fingerprint = "STATEMENT_FP_EXPECTED")
        dao.upsertMembers(listOf(member))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 1_000_000L,
                lastSyncAttemptMs = 1_000_000L,
            )
        )

        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { 1_100_000L })
        resolver.refreshFromSnapshot()

        // Case A: No pin in TrustStore
        assertFalse(
            resolver.isOrgPeer("alice"),
            "MUTATION CHECK 1: isOrgPeer MUST NOT return true when TrustStore has no pin",
        )

        // Case B: Mismatched PAIRED pin (does not equal statement fingerprint)
        trustStore.savePin("alice", "DIFFERENT_PAIRED_FP")
        trustStore.trustPeer(FlashDeviceId("alice"), "Alice")
        assertFalse(
            resolver.isOrgPeer("alice"),
            "MUTATION CHECK 1: isOrgPeer MUST NOT return true when pin mismatches statement fingerprint",
        )
    }

    @Test
    fun ORGT_MUTATION_2_isOrgPeer_failsIfLeaseIgnored() = runBlocking {
        // Mutation check (2): ignore lease (must fail if isOrgPeer ignores lease)
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        val fp = "MATCHING_FP_12345"
        val member = sampleMember(deviceId = "alice", fingerprint = fp)
        dao.upsertMembers(listOf(member))
        trustStore.savePin("alice", fp)

        val lastSync = 1_000_000_000L
        val graceDays = 30
        val graceMs = graceDays * 86_400_000L

        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = graceDays,
                lastSyncSuccessMs = lastSync,
                lastSyncAttemptMs = lastSync,
            )
        )

        var time = lastSync + graceMs + 10_000L // Past grace period: lease is EXPIRED by TIME
        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { time })
        resolver.refreshFromSnapshot()

        assertTrue(resolver.lease.value is OrgLease.Expired)
        assertFalse(
            resolver.isOrgPeer("alice"),
            "MUTATION CHECK 2: isOrgPeer MUST return false when lease is expired by TIME",
        )
        assertFalse(
            resolver.isKnownColleague("alice"),
            "isKnownColleague MUST return false when lease is expired by TIME",
        )
        assertNull(
            resolver.resolve("alice"),
            "MUTATION CHECK 2: resolve() MUST return null when lease is expired by TIME",
        )

        // Clock rollback past 5-min tolerance
        time = lastSync - (10 * 60 * 1000L) // 10 minutes before sync
        assertTrue(resolver.lease.value is OrgLease.Expired)
        assertFalse(
            resolver.isOrgPeer("alice"),
            "MUTATION CHECK 2: isOrgPeer MUST return false when lease is expired by CLOCK rollback",
        )
        assertFalse(
            resolver.isKnownColleague("alice"),
            "isKnownColleague MUST return false when lease is expired by CLOCK rollback",
        )
        assertNull(
            resolver.resolve("alice"),
            "MUTATION CHECK 2: resolve() MUST return null when lease is expired by CLOCK rollback",
        )
    }

    @Test
    fun ORGT_U40_searchColleagueIds_queries_snapshot_when_lease_valid() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        val member1 = sampleMember(deviceId = "alice-id", displayName = "Alice Executive", departmentId = "Finance", role = "CFO")
        val member2 = sampleMember(deviceId = "bob-id", displayName = "Bob Engineer", departmentId = "Engineering", role = "Developer")
        dao.upsertMembers(listOf(member1, member2))
        val now = 1_000_000L
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = now,
                lastSyncAttemptMs = now,
            )
        )

        var time = now
        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { time })
        resolver.refreshFromSnapshot()

        // 1. Matches by name, department, role
        assertEquals(setOf("alice-id"), resolver.searchColleagueIds("alice"))
        assertEquals(setOf("alice-id"), resolver.searchColleagueIds("finance"))
        assertEquals(setOf("alice-id"), resolver.searchColleagueIds("cfo"))
        assertEquals(setOf("bob-id"), resolver.searchColleagueIds("engineer"))

        // 2. Blank or unmatched
        assertTrue(resolver.searchColleagueIds("   ").isEmpty())
        assertTrue(resolver.searchColleagueIds("nonexistent").isEmpty())

        // 3. Expired lease -> returns empty
        time = now + (31 * 86_400_000L)
        assertTrue(resolver.searchColleagueIds("alice").isEmpty(), "searchColleagueIds must return empty set when lease expired")
    }

    @Test
    fun ORGT_U59_clear_races_with_in_flight_refresh_ensuring_snapshot_remains_cleared() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        dao.upsertMembers(listOf(sampleMember("alice"), sampleMember("bob")))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 1_000_000L,
                lastSyncAttemptMs = 1_000_000L,
            )
        )
        trustStore.savePin("alice", "STATEMENT_FINGERPRINT_1234567890")

        val refreshStarted = CompletableDeferred<Unit>()
        val clearFinished = CompletableDeferred<Unit>()

        dao.onListAllMembers = {
            refreshStarted.complete(Unit)
            // Wait until clear() has fully executed before completing listAllMembers
            clearFinished.await()
        }

        val resolver = RealOrgPeerResolver(
            dao = dao,
            trustStore = trustStore,
            clock = { 1_100_000L },
        )

        // Launch refresh which will pause inside listAllMembers()
        val refreshJob = launch(Dispatchers.Default) {
            resolver.refreshFromSnapshot()
        }

        // Wait until refresh has started and is reading DAO
        refreshStarted.await()

        // Call clear() concurrently while refresh is in flight
        resolver.clear()
        clearFinished.complete(Unit)

        refreshJob.join()

        // Post-condition: Stale in-flight refresh MUST NOT repopulate snapshot after clear()
        assertFalse(
            resolver.isKnownColleague("alice"),
            "Stale refresh must not repopulate in-memory index after clear()",
        )
        assertNull(
            resolver.resolve("alice"),
            "resolve() must return null when cleared even if refresh completed later",
        )
        assertEquals(
            OrgLease.None,
            resolver.lease.value,
            "Lease must remain None after clear()",
        )
    }

    @Test
    fun ORGT_U60_unenrolled_device_resolver_answers_not_a_colleague() = runBlocking {
        val dao = CountingOrgDirectoryDao()
        val trustStore = TestTrustStore()
        val member = sampleMember(deviceId = "alice", displayName = "Alice Smith", fingerprint = "AA11BB22")
        dao.upsertMembers(listOf(member))
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 1_000_000L,
                lastSyncAttemptMs = 1_000_000L,
            )
        )
        trustStore.savePin("alice", "AA11BB22")

        var enrolled = false
        val resolver = RealOrgPeerResolver(
            dao = dao,
            trustStore = trustStore,
            isEnrolled = { enrolled },
            clock = { 1_100_000L },
        )
        resolver.refreshFromSnapshot()

        // When not enrolled: must answer "not a colleague" unconditionally across all queries
        assertFalse(resolver.isOrgPeer("alice"), "isOrgPeer must be false when unenrolled")
        assertFalse(resolver.isKnownColleague("alice"), "isKnownColleague must be false when unenrolled")
        assertNull(resolver.resolve("alice"), "resolve() must return null when unenrolled")
        assertNull(resolver.displayName("alice"), "displayName() must return null when unenrolled")
        assertTrue(resolver.searchColleagueIds("alice").isEmpty(), "searchColleagueIds must return empty set when unenrolled")
        assertEquals(OrgLease.None, resolver.lease.value, "lease must be None when unenrolled")

        // When enrolled: returns true and valid colleague peer data
        enrolled = true
        assertTrue(resolver.isOrgPeer("alice"), "isOrgPeer must be true when enrolled and bound")
        assertTrue(resolver.isKnownColleague("alice"), "isKnownColleague must be true when enrolled")
        assertNotNull(resolver.resolve("alice"), "resolve() must return valid peer when enrolled")
        assertEquals("Alice Smith", resolver.displayName("alice"), "displayName() must return colleague name when enrolled")
        assertEquals(setOf("alice"), resolver.searchColleagueIds("alice"), "searchColleagueIds must return colleague when enrolled")
        assertTrue(resolver.lease.value is OrgLease.Valid, "lease must be Valid when enrolled with valid snapshot")
    }
}
