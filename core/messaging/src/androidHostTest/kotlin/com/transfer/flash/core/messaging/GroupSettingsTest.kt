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
import com.transfer.flash.core.messaging.protocol.GroupHistoryCeiling
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
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
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
    fun testConcurrentSettingsUpdatesAreNotLost() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val groupId = (repoA.createGroup("Gamma", setOf("dev-b")) as FlashResult.Success).value
        settle()
        val before = repoA.getGroupSettings(groupId)

        // Four quick taps in the settings sheet: each one reads the stored row, so without a lock two of them start
        // from the same version and the later write replaces the earlier one.
        val results = listOf(
            async { repoA.updateGroupSettings(groupId, joinPolicy = GroupSettings.POLICY_OPEN) },
            async { repoA.updateGroupSettings(groupId, inviteSharers = GroupSettings.SHARERS_ADMINS) },
            async { repoA.updateGroupSettings(groupId, membersMayAdd = true) },
            async { repoA.updateGroupSettings(groupId, maxMembers = 12) },
        ).awaitAll()
        assertTrue(results.all { it is FlashResult.Success })

        val after = repoA.getGroupSettings(groupId)
        assertEquals(before.version + 4, after.version)
        assertEquals(GroupSettings.POLICY_OPEN, after.joinPolicy)
        assertEquals(GroupSettings.SHARERS_ADMINS, after.inviteSharers)
        assertTrue(after.membersMayAdd)
        assertEquals(12, after.maxMembers)
    }

    @Test
    fun testConcurrentPreferenceUpdatesAreNotLost() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val groupId = (repoA.createGroup("Delta", setOf("dev-b")) as FlashResult.Success).value
        settle()

        listOf(
            async { repoA.updateGroupLocalPreferences(groupId, serveToGroup = false) },
            async { repoA.updateGroupLocalPreferences(groupId, serveWifiOnly = true) },
            async { repoA.updateGroupLocalPreferences(groupId, batteryThresholdPercent = 20) },
            async { repoA.updateGroupLocalPreferences(groupId, keepAvailableDays = 7) },
        ).awaitAll()

        val prefs = repoA.getGroupLocalPreferences(groupId)
        assertFalse(prefs.serveToGroup)
        assertTrue(prefs.serveWifiOnly)
        assertEquals(20, prefs.batteryThresholdPercent)
        assertEquals(7, prefs.keepAvailableDays)
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

        // The added device itself must end up in the group, with the roster and the real name. A member-issued
        // cert used to be dropped there (reason=issuer) because the receiver had no roster to find the issuer's key in.
        val rowOfC = node("dev-c").memberDao.member(groupId, "dev-c")
        assertTrue("dev-c never joined the group it was added to", rowOfC?.isActive == true)
        assertEquals("Zeta", node("dev-c").conversationDao.get(groupId)?.title)
        assertEquals(3, node("dev-c").memberDao.activeMembers(groupId).size)
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

    // ---- ADR-100: the signed history ceiling

    @Test
    fun theHistoryCeilingIsSignedByTheOwnerAndReachesMembers() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo
        val groupId = (repoA.createGroup("Hist", setOf("dev-b")) as FlashResult.Success).value
        settle()
        assertEquals(GroupHistoryCeiling.D30, repoB.getGroupSettings(groupId).historyCeiling)

        assertTrue(repoA.updateGroupSettings(groupId, historyCeiling = "D7") is FlashResult.Success)
        settle()

        assertEquals(GroupHistoryCeiling.D7, repoA.getGroupSettings(groupId).historyCeiling)
        val onB = repoB.getGroupSettings(groupId)
        assertEquals(GroupHistoryCeiling.D7, onB.historyCeiling)
        assertEquals(2L, onB.version)
        // ADR-105: the settings statement stays the v1 layout and the ceiling is signed on its own.
        assertTrue(GroupCanonical.settingsBytes(onB).decodeToString().contains("flash-gset-v1"))
        assertTrue(GroupCanonical.historyCeilingBytes(onB).decodeToString().contains("flash-gsethc-v1"))
        assertTrue("the ceiling signature is stored and relayed", onB.historyCeilingSig.isNotEmpty())
    }

    @Test
    fun anOlderBuildThatDoesNotKnowTheCeilingStillAppliesEveryOtherSettingOfTheSameObject() = runBlocking(dispatcher) {
        // G2 (review 2026-10-09): the admin changes the join policy AND sets a non-default ceiling in one edit. A build from
        // before ADR-100 reads neither setHist nor setHistSig, so what reaches it is the object without them.
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo
        val groupId = (repoA.createGroup("HistOld", setOf("dev-b")) as FlashResult.Success).value
        settle()
        assertTrue(
            repoA.updateGroupSettings(groupId, joinPolicy = GroupSettings.POLICY_OPEN, historyCeiling = "D7") is FlashResult.Success,
        )
        wire.clear() // B has not heard of version 2 yet
        val full = repoA.bundleForGroup(groupId)!!
        assertTrue(GroupFrameCodec.encode(full).contains("setHist"))
        val asSeenByAnOlderBuild = full.copy(
            settings = full.settings!!.copy(historyCeiling = GroupHistoryCeiling.DEFAULT, historyCeilingSig = ""),
        )
        assertFalse(GroupFrameCodec.encode(asSeenByAnOlderBuild).contains("setHist"))

        repoB.onInboundGroupWireFrame("dev-a", asSeenByAnOlderBuild)
        val onB = repoB.getGroupSettings(groupId)
        assertEquals("the other setting is applied", GroupSettings.POLICY_OPEN, onB.joinPolicy)
        assertEquals(2L, onB.version)
        assertEquals("the ceiling it cannot read stays the default", GroupHistoryCeiling.D30, onB.historyCeiling)
    }

    @Test
    fun aTamperedCeilingIsRefusedAndTheGenuineOneIsApplied() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo
        val groupId = (repoA.createGroup("Hist2", setOf("dev-b")) as FlashResult.Success).value
        settle()
        assertTrue(repoA.updateGroupSettings(groupId, historyCeiling = "D7") is FlashResult.Success)
        wire.clear() // B has not heard of version 2 yet
        val genuine = repoA.bundleForGroup(groupId)!!

        // An attacker (or a relay) swaps the ceiling for a wider one and keeps the signatures: the ceiling statement fails.
        repoB.onInboundGroupWireFrame("dev-a", genuine.copy(settings = genuine.settings!!.copy(historyCeiling = GroupHistoryCeiling.ALL)))
        assertEquals(1L, repoB.getGroupSettings(groupId).version)
        assertEquals(GroupHistoryCeiling.D30, repoB.getGroupSettings(groupId).historyCeiling)
        // A ceiling stated without any signature is refused as well.
        repoB.onInboundGroupWireFrame(
            "dev-a",
            genuine.copy(settings = genuine.settings!!.copy(historyCeiling = GroupHistoryCeiling.NONE, historyCeilingSig = "")),
        )
        assertEquals(1L, repoB.getGroupSettings(groupId).version)

        // A relay that cannot read the ceiling hands on the object without it: the v1 signature still verifies, so the
        // rest of the settings apply and the ceiling reads as the default (the documented mixed-fleet limit) ...
        repoB.onInboundGroupWireFrame(
            "dev-a",
            genuine.copy(settings = genuine.settings!!.copy(historyCeiling = GroupHistoryCeiling.D30, historyCeilingSig = "")),
        )
        assertEquals(2L, repoB.getGroupSettings(groupId).version)
        assertEquals(GroupHistoryCeiling.D30, repoB.getGroupSettings(groupId).historyCeiling)

        // ... and the complete copy of the SAME operation, arriving later, restores the ceiling.
        repoB.onInboundGroupWireFrame("dev-a", genuine)
        assertEquals(2L, repoB.getGroupSettings(groupId).version)
        assertEquals(GroupHistoryCeiling.D7, repoB.getGroupSettings(groupId).historyCeiling)
    }

    @Test
    fun aCeilingSignatureCannotBeMovedOntoAnotherSettingsObject() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo
        val groupId = (repoA.createGroup("Hist5", setOf("dev-b")) as FlashResult.Success).value
        settle()
        assertTrue(repoA.updateGroupSettings(groupId, historyCeiling = "D7") is FlashResult.Success)
        settle()
        val v2 = repoA.getGroupSettings(groupId)
        assertEquals(GroupHistoryCeiling.D7, repoB.getGroupSettings(groupId).historyCeiling)
        // Version 3: the owner resets the ceiling to the default and changes the join policy (a genuine object).
        assertTrue(
            repoA.updateGroupSettings(groupId, joinPolicy = GroupSettings.POLICY_OPEN, historyCeiling = "D30") is FlashResult.Success,
        )
        wire.clear() // B is still at version 2
        val v3 = repoA.getGroupSettings(groupId)
        assertEquals(3L, v3.version)
        assertEquals(GroupHistoryCeiling.D30, v3.historyCeiling)
        // A member splices version 2's ceiling and its signature onto the genuine version 3.
        val spliced = v3.copy(historyCeiling = GroupHistoryCeiling.D7, historyCeilingSig = v2.historyCeilingSig)
        repoB.onInboundGroupWireFrame("dev-a", repoA.bundleForGroup(groupId)!!.copy(settings = spliced))
        assertEquals("the spliced object is refused", 2L, repoB.getGroupSettings(groupId).version)
        repoB.onInboundGroupWireFrame("dev-a", repoA.bundleForGroup(groupId)!!)
        assertEquals(3L, repoB.getGroupSettings(groupId).version)
        assertEquals(GroupHistoryCeiling.D30, repoB.getGroupSettings(groupId).historyCeiling)
    }

    @Test
    fun aPlainMemberCannotChangeTheCeilingAndAnUnknownNameIsRefused() = runBlocking(dispatcher) {
        mesh("dev-a", "dev-b")
        val repoA = node("dev-a").repo
        val repoB = node("dev-b").repo
        val groupId = (repoA.createGroup("Hist3", setOf("dev-b")) as FlashResult.Success).value
        settle()
        assertTrue(repoB.updateGroupSettings(groupId, historyCeiling = "ALL") is FlashResult.Failure)
        assertTrue(repoA.updateGroupSettings(groupId, historyCeiling = "FOREVER") is FlashResult.Failure)
        assertEquals(1L, repoA.getGroupSettings(groupId).version)
    }

    @Test
    fun theDefaultCeilingStaysOffTheWireAndOldSettingsDecodeAsTheDefault() = runBlocking(dispatcher) {
        val repoA = node("dev-a").repo
        val groupId = (repoA.createGroup("Hist4", setOf("dev-b")) as FlashResult.Success).value
        val bundle = repoA.bundleForGroup(groupId)!!
        val plain = GroupFrameCodec.encode(bundle)
        assertFalse("settings that never set the ceiling are byte-identical to before", plain.contains("setHist"))
        val decodedPlain = GroupFrameCodec.decode(plain) as GroupWireFrame.Bundle
        assertEquals(GroupHistoryCeiling.D30, decodedPlain.settings!!.historyCeiling)
        assertEquals(bundle.settings, decodedPlain.settings)

        val wide = bundle.copy(settings = bundle.settings!!.copy(historyCeiling = GroupHistoryCeiling.D7, historyCeilingSig = "c2ln"))
        val encoded = GroupFrameCodec.encode(wide)
        assertTrue(encoded.contains("setHist"))
        val decodedWide = (GroupFrameCodec.decode(encoded) as GroupWireFrame.Bundle).settings!!
        assertEquals(GroupHistoryCeiling.D7, decodedWide.historyCeiling)
        assertEquals("c2ln", decodedWide.historyCeilingSig)
        // ADR-105: a name this build does not know (a later ceiling) is not guessed and does not cost the other settings:
        // the object is read with the default ceiling and the roster still decodes.
        val unknown = encoded.replace("setHist=D7", "setHist=FOREVER")
        val unknownSettings = (GroupFrameCodec.decode(unknown) as GroupWireFrame.Bundle).settings
        assertNotNull(unknownSettings)
        assertEquals(GroupHistoryCeiling.D30, unknownSettings!!.historyCeiling)
        assertEquals("", unknownSettings.historyCeilingSig)
    }
}
