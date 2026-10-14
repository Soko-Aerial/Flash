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
    }

    @Test
    fun ORGT_U22_tofu_pin_replacement_overwrites_tofu_pin_with_statement_fingerprint() = runBlocking {
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
        trustStore.savePin("alice", "ATTACKER_FAKE_PIN_0000000000")
        assertEquals(PinSource.TOFU, trustStore.pinSource(FlashDeviceId("alice")))

        val resolver = RealOrgPeerResolver(dao = dao, trustStore = trustStore, clock = { 1_100_000L })
        resolver.refreshFromSnapshot()

        // Resolving Alice must detect TOFU mismatch and replace with statement fingerprint
        val resolved = resolver.resolve("alice")
        assertNotNull(resolved)
        assertTrue(resolved.bindingOk, "Binding must succeed after TOFU pin replacement")
        assertEquals(statementFp, trustStore.getPin("alice"), "TrustStore pin must be overwritten with verified statement fingerprint")
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
}
