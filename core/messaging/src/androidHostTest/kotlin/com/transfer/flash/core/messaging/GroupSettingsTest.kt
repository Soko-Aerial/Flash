package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.group.GroupGate
import com.transfer.flash.core.messaging.group.GroupLocalPreferences
import com.transfer.flash.core.messaging.group.GroupProofSessions
import com.transfer.flash.core.messaging.group.GroupTraffic
import com.transfer.flash.core.messaging.group.toPreferences
import com.transfer.flash.core.messaging.protocol.GroupCanonical
import com.transfer.flash.core.messaging.protocol.GroupFrameCodec
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupSettings
import com.transfer.flash.core.messaging.protocol.GroupSigning
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MemberCert
import com.transfer.flash.core.messaging.protocol.TestGroupCrypto
import com.transfer.flash.core.messaging.protocol.settingsWins
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GM-9 tests: Signed GroupSettings, local preferences, and enforcement (ADR-074).
 */
class GroupSettingsTest {

    private val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    private val names = mapOf(
        "dev-a" to "Ada",
        "dev-b" to "Bo",
        "dev-c" to "Cy",
        "dev-d" to "Di",
    )
    private val cryptos = names.keys.associateWith { TestGroupCrypto() }
    private val nodes = LinkedHashMap<String, Node>()
    private val wire = ConcurrentLinkedQueue<Envelope>()
    private val liveSessions = ConcurrentHashMap.newKeySet<String>()
    private val repoScopes = mutableListOf<CoroutineScope>()

    private class Node(
        val id: String,
        val messageDao: InMemoryMessageDao = InMemoryMessageDao(),
        val conversationDao: InMemoryConversationDao = InMemoryConversationDao(),
        val outboxDao: InMemoryOutboxDao = InMemoryOutboxDao(),
        val memberDao: InMemoryGroupMemberDao = InMemoryGroupMemberDao(),
        val deliveryDao: InMemoryGroupDeliveryDao = InMemoryGroupDeliveryDao(),
        val readCursorDao: InMemoryReadCursorDao = InMemoryReadCursorDao(),
        val secretStore: InMemoryGroupSecretStore = InMemoryGroupSecretStore(),
        val rotationDao: InMemoryGroupRotationDao = InMemoryGroupRotationDao(),
        val inviteDao: InMemoryGroupInviteDao = InMemoryGroupInviteDao(),
        val joinRequestDao: InMemoryGroupJoinRequestDao = InMemoryGroupJoinRequestDao(),
        val settingsDao: InMemoryGroupSettingsDao = InMemoryGroupSettingsDao(),
        val preferencesDao: InMemoryGroupPreferencesDao = InMemoryGroupPreferencesDao(),
    ) {
        lateinit var repo: RealFlashChatRepository
    }

    private data class Envelope(val from: String, val to: String, val frame: GroupWireFrame)

    @After
    fun tearDown() {
        repoScopes.forEach { it.cancel() }
        dispatcher.close()
    }

    private fun node(id: String): Node = nodes.getOrPut(id) {
        val created = Node(id)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        repoScopes += scope
        created.repo = RealFlashChatRepository(
            localDeviceId = id,
            localDisplayName = names.getValue(id),
            messageDao = created.messageDao,
            conversationDao = created.conversationDao,
            outboxDao = created.outboxDao,
            receiptDao = NoopReceiptDao(),
            draftDao = NoopDraftDao(),
            recentSearchDao = NoopRecentSearchDao(),
            reactionDao = NoopReactionDao(),
            groupMemberDao = created.memberDao,
            groupDeliveryDao = created.deliveryDao,
            readCursorDao = created.readCursorDao,
            groupSecretStore = created.secretStore,
            groupRotationDao = created.rotationDao,
            groupInviteDao = created.inviteDao,
            groupJoinRequestDao = created.joinRequestDao,
            groupSettingsDao = created.settingsDao,
            groupPreferencesDao = created.preferencesDao,
            peerFeatures = { setOf(GroupProofSessions.FEATURE_GS1) },
            isTrustedPeer = { true },
            groupTransportSink = GroupTransportSink { target, frame -> transmit(id, target, frame) },
            transportSink = MessageTransportSink { _, _ -> true },
            scope = scope,
            ioDispatcher = dispatcher,
            peerNameResolver = { names[it] },
            groupCrypto = cryptos.getValue(id),
            pinnedFingerprint = { peer -> cryptos[peer]?.publicKey?.let { GroupCanonical.fingerprintHex(cryptos.getValue(id), it) } },
            peerGroupProtocol = { GroupPolicy.V2_PROTOCOL },
            peerIdentityKey = { peer -> cryptos[peer]?.publicKey },
        )
        created
    }

    private fun transmit(from: String, to: String, frame: GroupWireFrame): Boolean {
        if (!isConnected(from, to)) return false
        wire.add(Envelope(from, to, frame))
        return true
    }

    private fun pairKey(x: String, y: String): String = listOf(x, y).sorted().joinToString("|")
    private fun isConnected(x: String, y: String): Boolean = pairKey(x, y) in liveSessions

    private suspend fun mesh(vararg ids: String) {
        ids.forEach { node(it) }
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                connect(ids[i], ids[j])
            }
        }
        settle()
    }

    private suspend fun connect(x: String, y: String) {
        liveSessions.add(pairKey(x, y))
        for ((self, peer) in listOf(x to y, y to x)) {
            val repo = nodes.getValue(self).repo
            repo.notifyPeerSessionUp(peer)
            repo.reconcileGroupMembership(peer)
        }
    }

    private suspend fun settle(maxRounds: Int = 30) {
        var rounds = 0
        while (rounds < maxRounds) {
            val drained = mutableListOf<Envelope>()
            while (true) {
                val env = wire.poll() ?: break
                drained.add(env)
            }
            if (drained.isEmpty()) break
            for (env in drained) {
                if (isConnected(env.from, env.to)) {
                    val targetRepo = nodes[env.to]?.repo
                    targetRepo?.onInboundGroupWireFrame(env.from, env.frame)
                }
            }
            delay(10)
            rounds++
        }
    }

    @Test
    fun testInitialSettingsCreatedOnGroupCreate() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo

        val res = repoA.createGroup("Alpha", setOf("dev-b"))
        assertTrue(res is FlashResult.Success)
        val groupId = (res as FlashResult.Success).value
        settle()

        // Node A should have initial settings stored
        val settingsA = repoA.getGroupSettings(groupId)
        assertEquals(groupId, settingsA.groupId)
        assertEquals(1L, settingsA.version)
        assertEquals(GroupSettings.POLICY_APPROVE, settingsA.joinPolicy)
        assertEquals(GroupSettings.SHARERS_ALL, settingsA.inviteSharers)
        assertEquals(GroupPolicy.MAX_MEMBERS_V2, settingsA.maxMembers)
        assertTrue(settingsA.swarmServing)
        assertFalse(settingsA.membersMayAdd)
        assertEquals("dev-a", settingsA.signerId)
        assertTrue(settingsA.sig.isNotBlank())

        // Node B should have received the settings via Bundle
        val settingsB = repoB.getGroupSettings(groupId)
        assertEquals(settingsA, settingsB)
    }

    @Test
    fun testSettingsVersionOrdering() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo

        val res = repoA.createGroup("Beta", setOf("dev-b"))
        val groupId = (res as FlashResult.Success).value
        settle()

        // Admin updates settings to version 2
        val updateRes = repoA.updateGroupSettings(
            groupId = groupId,
            joinPolicy = GroupSettings.POLICY_OPEN,
            maxMembers = 10,
        )
        assertTrue(updateRes is FlashResult.Success)
        settle()

        val settingsA = repoA.getGroupSettings(groupId)
        assertEquals(2L, settingsA.version)
        assertEquals(GroupSettings.POLICY_OPEN, settingsA.joinPolicy)
        assertEquals(10, settingsA.maxMembers)

        val settingsB = repoB.getGroupSettings(groupId)
        assertEquals(2L, settingsB.version)
        assertEquals(GroupSettings.POLICY_OPEN, settingsB.joinPolicy)
        assertEquals(10, settingsB.maxMembers)

        // Stale v1 settings cannot overwrite v2
        val v1 = GroupSettings.defaults(groupId).copy(version = 1L)
        assertFalse(settingsWins(v1, settingsA))
        assertTrue(settingsWins(settingsA, v1))
    }

    @Test
    fun testTieBreakingOnEqualVersion() {
        val s1 = GroupSettings(
            groupId = "group-1",
            version = 3L,
            opId = "0000000000000001",
            signerId = "dev-a",
            sig = "sig1",
        )
        val s2 = GroupSettings(
            groupId = "group-1",
            version = 3L,
            opId = "0000000000000002",
            signerId = "dev-b",
            sig = "sig2",
        )

        // Smaller opId wins
        assertTrue(settingsWins(s1, s2))
        assertFalse(settingsWins(s2, s1))
    }

    @Test
    fun testForgedUpdateRejection() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo

        val res = repoA.createGroup("Gamma", setOf("dev-b"))
        val groupId = (res as FlashResult.Success).value
        settle()

        // Non-admin dev-b attempts to update settings via repo -> rejected locally
        val updateRes = repoB.updateGroupSettings(groupId, joinPolicy = GroupSettings.POLICY_OPEN)
        assertTrue(updateRes is FlashResult.Failure)

        // Even if dev-b crafts a forged settings frame signed with dev-b's key:
        val signingB = GroupSigning(cryptos.getValue("dev-b"))
        val forgedSettings = signingB.signSettings(
            groupId = groupId,
            version = 10L,
            joinPolicy = GroupSettings.POLICY_OPEN,
            inviteSharers = GroupSettings.SHARERS_ALL,
            maxMembers = 5,
            swarmServing = true,
            membersMayAdd = true,
            opId = "forged-op-id",
            signerId = "dev-b",
        )
        // Send in bundle from B to A
        val bundleB = repoB.bundleForGroup(groupId)
        assertNotNull(bundleB)
        val forgedBundle = bundleB!!.copy(settings = forgedSettings)
        repoA.onInboundGroupWireFrame("dev-b", forgedBundle)

        // Node A should NOT apply forged settings because signer is not admin
        val settingsA = repoA.getGroupSettings(groupId)
        assertEquals(1L, settingsA.version)
        assertEquals(GroupSettings.POLICY_APPROVE, settingsA.joinPolicy)
    }

    @Test
    fun testLocalPreferencesPersistence() = runBlocking(dispatcher) {
        val repoA = node("dev-a").repo
        val groupId = "test-group-prefs"

        val initial = repoA.getGroupLocalPreferences(groupId)
        assertTrue(initial.serveToGroup)
        assertFalse(initial.serveWifiOnly)
        assertEquals(20, initial.batteryThresholdPercent)

        // Update local preferences
        val updateRes = repoA.updateGroupLocalPreferences(
            groupId = groupId,
            serveToGroup = false,
            serveWifiOnly = true,
            batteryThresholdPercent = 45,
            keepAvailableDays = 14,
            autoAcceptSizeBytes = 50L * 1024L * 1024L,
        )
        assertTrue(updateRes is FlashResult.Success)

        val updated = repoA.getGroupLocalPreferences(groupId)
        assertFalse(updated.serveToGroup)
        assertTrue(updated.serveWifiOnly)
        assertEquals(45, updated.batteryThresholdPercent)
        assertEquals(14, updated.keepAvailableDays)
        assertEquals(50L * 1024L * 1024L, updated.autoAcceptSizeBytes)

        // Verify stored in DAO
        val entity = node("dev-a").preferencesDao.getByGroupId(groupId)
        assertNotNull(entity)
        assertEquals(45, entity?.batteryThresholdPercent)
    }

    @Test
    fun testOldBuildCompatibility() = runBlocking(dispatcher) {
        val repoA = node("dev-a").repo
        val res = repoA.createGroup("Compatibility", setOf("dev-b"))
        val groupId = (res as FlashResult.Success).value
        val bundle = repoA.bundleForGroup(groupId)
        assertNotNull(bundle)

        // 1. Bundle encoded without settings decodes with settings = null
        val bundleWithoutSettings = bundle!!.copy(settings = null)
        val encoded1 = GroupFrameCodec.encode(bundleWithoutSettings)
        val decoded1 = GroupFrameCodec.decode(encoded1)
        assertTrue(decoded1 is GroupWireFrame.Bundle)
        assertNull((decoded1 as GroupWireFrame.Bundle).settings)

        // 2. Bundle with settings encodes and decodes properly
        val mySettings = GroupSettings(
            groupId = groupId,
            version = 5L,
            joinPolicy = GroupSettings.POLICY_OPEN,
            inviteSharers = GroupSettings.SHARERS_ALL,
            maxMembers = 10,
            swarmServing = true,
            membersMayAdd = false,
            opId = "0000000000000001",
            signerId = "dev-a",
            sig = "sig123",
        )
        val bundleWithSettings = bundle.copy(settings = mySettings)
        val encoded2 = GroupFrameCodec.encode(bundleWithSettings)
        val decoded2 = GroupFrameCodec.decode(encoded2)
        assertTrue(decoded2 is GroupWireFrame.Bundle)
        assertEquals(mySettings, (decoded2 as GroupWireFrame.Bundle).settings)

        // 3. Extra unknown fields on wire ignored without error (forward compatibility)
        val encodedWithExtras = "$encoded2 unknownFutureField=42 anotherUnknown=test"
        val decoded3 = GroupFrameCodec.decode(encodedWithExtras)
        assertTrue(decoded3 is GroupWireFrame.Bundle)
        assertEquals(mySettings, (decoded3 as GroupWireFrame.Bundle).settings)
    }

    @Test
    fun testGateRestrictionOnFileServe() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo

        val res = repoA.createGroup("Delta", setOf("dev-b"))
        val groupId = (res as FlashResult.Success).value
        settle()

        val gate = repoA.groupGate

        // Default: swarmServing = true, serveToGroup = true -> admitted
        val admit1 = gate.allows(groupId, "dev-b", GroupTraffic.FILE_SERVE)
        assertTrue(admit1)

        // Local preference turned off: serveToGroup = false -> refused
        repoA.updateGroupLocalPreferences(groupId, serveToGroup = false)
        val admit2 = gate.allows(groupId, "dev-b", GroupTraffic.FILE_SERVE)
        assertFalse(admit2)

        // Restore local preference, but disable swarmServing in group settings -> refused
        repoA.updateGroupLocalPreferences(groupId, serveToGroup = true)
        repoA.updateGroupSettings(groupId, swarmServing = false)
        settle()
        val admit3 = gate.allows(groupId, "dev-b", GroupTraffic.FILE_SERVE)
        assertFalse(admit3)

        // Both true again -> admitted
        repoA.updateGroupSettings(groupId, swarmServing = true)
        settle()
        val admit4 = gate.allows(groupId, "dev-b", GroupTraffic.FILE_SERVE)
        assertTrue(admit4)
    }

    @Test
    fun testInviteSharersRestriction() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo

        val res = repoA.createGroup("Epsilon", setOf("dev-b"))
        val groupId = (res as FlashResult.Success).value
        settle()

        // Seed initial secret for members so they hold an active secret
        val secret1 = com.transfer.flash.core.security.group.GroupSecret.generate()
        listOf("dev-a", "dev-b").forEach { id ->
            node(id).secretStore.put(
                com.transfer.flash.core.messaging.group.StoredGroupSecret(
                    groupId = groupId,
                    epoch = 1L,
                    secret = secret1,
                    source = com.transfer.flash.core.messaging.group.GroupSecretSource.CREATED,
                    receivedAtMs = 1000L,
                )
            )
        }

        // By default inviteSharers = ALL -> non-admin dev-b can generate invite
        val inviteRes1 = repoB.inviteFor(groupId)
        assertTrue(inviteRes1 is FlashResult.Success)

        // Admin dev-a restricts invite sharing to ADMINS
        val updateRes = repoA.updateGroupSettings(groupId, inviteSharers = GroupSettings.SHARERS_ADMINS)
        assertTrue(updateRes is FlashResult.Success)
        settle()

        // Non-admin dev-b can no longer generate invite
        val inviteRes2 = repoB.inviteFor(groupId)
        assertTrue(inviteRes2 is FlashResult.Failure)
        val err2 = (inviteRes2 as FlashResult.Failure).error
        assertTrue(err2 is FlashError.Unknown && err2.message.contains("Only an admin can share invites"))

        // Admin dev-a can still generate invite
        val inviteRes3 = repoA.inviteFor(groupId)
        assertTrue(inviteRes3 is FlashResult.Success)
    }

    @Test
    fun testMembersMayAddRestriction() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b", "dev-c")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo

        val res = repoA.createGroup("Zeta", setOf("dev-b"))
        val groupId = (res as FlashResult.Success).value
        settle()

        // By default membersMayAdd = false -> non-admin dev-b cannot add dev-c
        val addRes1 = repoB.addGroupMembers(groupId, setOf("dev-c"))
        assertTrue(addRes1 is FlashResult.Failure)
        val err1 = (addRes1 as FlashResult.Failure).error
        assertTrue(err1 is FlashError.Unknown && err1.message.contains("Only the group owner or an admin can add members"))

        // Admin dev-a enables membersMayAdd
        val updateRes = repoA.updateGroupSettings(groupId, membersMayAdd = true)
        assertTrue(updateRes is FlashResult.Success)
        settle()

        // Non-admin dev-b can now add dev-c
        val addRes2 = repoB.addGroupMembers(groupId, setOf("dev-c"))
        assertTrue(addRes2 is FlashResult.Success)
        settle()

        // Check dev-c is now in group on dev-a and dev-b
        val membersA = node("dev-a").memberDao.activeMembers(groupId)
        assertTrue(membersA.any { it.deviceId == "dev-c" })
    }

    @Test
    fun testMaxMembersEnforced() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b", "dev-c")
        val repoA = node("dev-a").repo

        val res = repoA.createGroup("Eta", setOf("dev-b"))
        val groupId = (res as FlashResult.Success).value
        settle()

        // Set maxMembers to 2 (group already has dev-a and dev-b)
        repoA.updateGroupSettings(groupId, maxMembers = 2)
        settle()

        // Adding dev-c fails because group is full (max 2 members)
        val addRes = repoA.addGroupMembers(groupId, setOf("dev-c"))
        assertTrue(addRes is FlashResult.Failure)
        val err = (addRes as FlashResult.Failure).error
        assertTrue(err is FlashError.Unknown && err.message.contains("at most 2 members"))
    }
}
