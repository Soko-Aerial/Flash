@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.enterprise.ExpiredReason
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.enterprise.RealOrgPeerResolver
import com.transfer.flash.core.messaging.PeerTrustPolicy
import com.transfer.flash.core.persistence.db.dao.OrgDirectoryDao
import com.transfer.flash.core.persistence.db.entity.OrgDepartmentEntity
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Desktop JVM Parity Test Suite for Organization Trust (Plan B, ADR-134, ORGT-U31).
 * Verifies that DesktopEngine correctly wires [RealOrgPeerResolver], [PeerTrustPolicy],
 * and DesktopTrustStore on the Desktop JVM runtime.
 */
class DesktopOrgTrustParityTest {

    private val tempDirs = mutableListOf<File>()
    private val engines = mutableListOf<DesktopEngine>()

    @AfterTest
    fun tearDown() {
        engines.forEach { runCatching { it.stop() } }
        engines.clear()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun createEngine(): DesktopEngine {
        val state = Files.createTempDirectory("flash-desktop-org-state").toFile()
        val received = Files.createTempDirectory("flash-desktop-org-received").toFile()
        tempDirs += state
        tempDirs += received
        return testDesktopEngine(receivedRoot = received, stateDir = state).also { engines += it }
    }

    private class DesktopCountingDao : OrgDirectoryDao {
        val members = LinkedHashMap<String, OrgDirectoryMemberEntity>()
        var syncState: OrgSyncStateEntity? = null

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
            members.values.take(limit)
        override suspend fun listMembersByDepartment(departmentId: String): List<OrgDirectoryMemberEntity> =
            members.values.filter { it.departmentId == departmentId }
        override suspend fun listAllMembers(): List<OrgDirectoryMemberEntity> = members.values.toList()
        override suspend fun upsertDepartments(departments: List<OrgDepartmentEntity>) {}
        override suspend fun deleteDepartments(ids: List<String>) {}
        override suspend fun listDepartments(): List<OrgDepartmentEntity> = emptyList()
        override suspend fun getSyncState(): OrgSyncStateEntity? = syncState
        override suspend fun setSyncState(state: OrgSyncStateEntity) { syncState = state }
        override suspend fun clearMembers() { members.clear() }
        override suspend fun clearDepartments() {}
        override suspend fun clearSyncState() { syncState = null }
    }

    @Test
    fun ORGT_U31_desktopEngine_boots_with_orgPeerResolver_wired() = runBlocking {
        val engine = createEngine()
        engine.start()

        val booted = withTimeoutOrNull(5000L) {
            while (!engine.ready.value && engine.startError.value == null) {
                delay(20)
            }
            engine.ready.value
        }
        assertTrue(booted == true, "Desktop engine must reach ready state")

        // Unenrolled host: enterpriseTrustStore.isEnrolled is false
        assertFalse(engine.enterpriseTrustStore.isEnrolled, "Fresh desktop engine must not be enrolled")

        // Resolver must be wired and instantiated
        val resolver = engine.orgPeerResolver
        assertNotNull(resolver, "DesktopEngine must wire non-null orgPeerResolver")
        assertEquals(OrgLease.None, resolver.lease.value, "Unenrolled desktop engine resolver lease must be None")
    }

    @Test
    fun ORGT_U31_desktopEngine_peerTrustPolicy_matrix_parity_on_desktop_jvm() {
        // High-security assertion on Desktop JVM:
        // Push-to-Talk audio injection requires pairing ONLY
        assertFalse(
            PeerTrustPolicy.mayPushToTalk(isPaired = false, isOrg = true),
            "Desktop JVM must enforce PTT strictly requires paired trust",
        )
        // Chat, reactions, ring permitted for ORG
        assertTrue(
            PeerTrustPolicy.mayChat(isPaired = false, isOrg = true),
            "Desktop JVM must permit 1:1 chat for ORG colleague",
        )
        assertTrue(
            PeerTrustPolicy.mayReact(isPaired = false, isOrg = true),
            "Desktop JVM must permit reactions for ORG colleague",
        )
        assertTrue(
            PeerTrustPolicy.mayRing(isPaired = false, isOrg = true),
            "Desktop JVM must permit incoming call ringing for ORG colleague",
        )
        // Group creation and admin require pairing
        assertFalse(
            PeerTrustPolicy.mayCreateGroupWith(isPaired = false, isOrg = true),
            "Desktop JVM must require pairing to create legacy/v2 groups",
        )
        assertFalse(
            PeerTrustPolicy.mayAddGroupMember(isPaired = false, isOrg = true),
            "Desktop JVM must require pairing to add group members",
        )
        assertFalse(
            PeerTrustPolicy.mayAdministerGroup(isPaired = false, isOrg = true),
            "Desktop JVM must require pairing to administer groups",
        )
        assertFalse(
            PeerTrustPolicy.mayServeSwarm(isPaired = false, isOrg = true),
            "Desktop JVM must require pairing to serve swarm",
        )
        assertFalse(
            PeerTrustPolicy.mayVouch(isPaired = false, isOrg = true),
            "Desktop JVM must require pairing to vouch",
        )
    }

    @Test
    fun ORGT_U31_desktop_realOrgPeerResolver_with_desktop_trust_store() = runBlocking {
        val stateDir = Files.createTempDirectory("flash-desktop-resolver-test").toFile()
        tempDirs += stateDir

        val desktopTrustStore = DesktopTrustStore(stateDir)
        val dao = DesktopCountingDao()
        val now = 1_000_000_000L
        val colleagueId = "colleague-alice"
        val statementFp = "STATEMENT_FP_ALICE_DESKTOP"

        dao.upsertMembers(
            listOf(
                OrgDirectoryMemberEntity(
                    deviceId = colleagueId,
                    displayName = "Alice Desktop",
                    displayNameFolded = "alice desktop",
                    departmentId = "engineering",
                    role = "member",
                    subjectPublicKeyFingerprint = statementFp,
                    statementBytes = ByteArray(32),
                    statementSerial = 1L,
                    statementIssuedAtMs = now,
                    statementDelegationSerial = 1L,
                    lastSyncedAtMs = now,
                )
            )
        )
        dao.setSyncState(
            OrgSyncStateEntity(
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 1L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = now,
                lastSyncAttemptMs = now,
            )
        )

        var time = now + 10_000L
        val resolver = RealOrgPeerResolver(
            dao = dao,
            trustStore = desktopTrustStore,
            clock = { time },
        )
        resolver.refreshFromSnapshot()

        // 1. Lease is valid
        assertTrue(resolver.lease.value is OrgLease.Valid)
        assertEquals("Alice Desktop", resolver.displayName(colleagueId))
        assertTrue(resolver.isKnownColleague(colleagueId))

        // 2. No pin -> bindingOk == false
        assertFalse(resolver.isOrgPeer(colleagueId))

        // 3. TOFU pin mismatch replacement under Rule 3.2:
        desktopTrustStore.savePin(FlashDeviceId(colleagueId), "ATTACKER_FAKE_PIN")
        // Initial mismatched call fails closed
        val resolvedMismatched = resolver.resolve(colleagueId)
        assertNotNull(resolvedMismatched)
        assertFalse(resolvedMismatched.bindingOk, "Live mismatched session must fail closed on Desktop JVM")
        // But DesktopTrustStore pin was updated to statement fingerprint
        assertEquals(statementFp, desktopTrustStore.getPin(FlashDeviceId(colleagueId)))

        // Subsequent session matches replaced pin and succeeds
        val resolvedSubsequent = resolver.resolve(colleagueId)
        assertNotNull(resolvedSubsequent)
        assertTrue(resolvedSubsequent.bindingOk, "Subsequent session must pass binding on Desktop JVM")
        assertTrue(resolver.isOrgPeer(colleagueId))

        // 4. Calling gate on desktop JVM allows ringing for colleague
        val canRing = PeerTrustPolicy.mayRing(
            isPaired = desktopTrustStore.isTrusted(FlashDeviceId(colleagueId)),
            isOrg = resolver.isOrgPeer(colleagueId),
        )
        assertTrue(canRing, "Calling ring gate must allow ORG colleague on Desktop JVM")

        // 5. Expiration handling on desktop JVM
        time = now + (31 * 86_400_000L) // Past 30 day grace period
        assertTrue(resolver.lease.value is OrgLease.Expired)
        assertFalse(resolver.isOrgPeer(colleagueId), "Expired lease must deny ORG trust on Desktop JVM")
        assertNull(resolver.resolve(colleagueId))
    }

    @Test
    fun `ORGT_U65_desktopEngine_requestOrgSync_callable`() = runBlocking {
        val engine = createEngine()
        // requestOrgSync must be callable for both tab open and pull-to-refresh without error even before enrolled
        engine.requestOrgSync("hub_tab_opened")
        engine.requestOrgSync("pull_to_refresh", force = true)
    }
}
