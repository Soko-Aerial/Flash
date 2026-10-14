package com.transfer.flash.core.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerTrustPolicyTest {

    private data class PolicyScenario(
        val isPaired: Boolean,
        val isOrg: Boolean,
        val expectedMayChat: Boolean,
        val expectedMayReact: Boolean,
        val expectedMayRing: Boolean,
        val expectedMayPushToTalk: Boolean,
        val expectedMayCreateGroupWith: Boolean,
        val expectedMayAddGroupMember: Boolean,
        val expectedMayAdministerGroup: Boolean,
        val expectedMayServeSwarm: Boolean,
        val expectedMayVouch: Boolean,
    )

    private val scenarios = listOf(
        // 1. Neither paired nor org (unknown peer / outsider): all capabilities denied
        PolicyScenario(
            isPaired = false,
            isOrg = false,
            expectedMayChat = false,
            expectedMayReact = false,
            expectedMayRing = false,
            expectedMayPushToTalk = false,
            expectedMayCreateGroupWith = false,
            expectedMayAddGroupMember = false,
            expectedMayAdministerGroup = false,
            expectedMayServeSwarm = false,
            expectedMayVouch = false,
        ),
        // 2. Paired only (traditional trusted peer): all capabilities granted
        PolicyScenario(
            isPaired = true,
            isOrg = false,
            expectedMayChat = true,
            expectedMayReact = true,
            expectedMayRing = true,
            expectedMayPushToTalk = true,
            expectedMayCreateGroupWith = true,
            expectedMayAddGroupMember = true,
            expectedMayAdministerGroup = true,
            expectedMayServeSwarm = true,
            expectedMayVouch = true,
        ),
        // 3. Org only (unpaired colleague): 1:1 chat, reactions, ringing permitted; high-privilege denied
        PolicyScenario(
            isPaired = false,
            isOrg = true,
            expectedMayChat = true,
            expectedMayReact = true,
            expectedMayRing = true,
            expectedMayPushToTalk = false,
            expectedMayCreateGroupWith = false,
            expectedMayAddGroupMember = false,
            expectedMayAdministerGroup = false,
            expectedMayServeSwarm = false,
            expectedMayVouch = false,
        ),
        // 4. Both paired and org (colleague who completed pairing): all capabilities granted
        PolicyScenario(
            isPaired = true,
            isOrg = true,
            expectedMayChat = true,
            expectedMayReact = true,
            expectedMayRing = true,
            expectedMayPushToTalk = true,
            expectedMayCreateGroupWith = true,
            expectedMayAddGroupMember = true,
            expectedMayAdministerGroup = true,
            expectedMayServeSwarm = true,
            expectedMayVouch = true,
        ),
    )

    @Test
    fun ORGT_U25_parameterised_policy_matrix_across_all_capabilities() {
        for (s in scenarios) {
            val description = "Scenario(paired=${s.isPaired}, org=${s.isOrg})"

            assertEquals(s.expectedMayChat, PeerTrustPolicy.mayChat(s.isPaired, s.isOrg), "$description: mayChat")
            assertEquals(s.expectedMayReact, PeerTrustPolicy.mayReact(s.isPaired, s.isOrg), "$description: mayReact")
            assertEquals(s.expectedMayRing, PeerTrustPolicy.mayRing(s.isPaired, s.isOrg), "$description: mayRing")
            assertEquals(s.expectedMayPushToTalk, PeerTrustPolicy.mayPushToTalk(s.isPaired, s.isOrg), "$description: mayPushToTalk")
            assertEquals(s.expectedMayCreateGroupWith, PeerTrustPolicy.mayCreateGroupWith(s.isPaired, s.isOrg), "$description: mayCreateGroupWith")
            assertEquals(s.expectedMayAddGroupMember, PeerTrustPolicy.mayAddGroupMember(s.isPaired, s.isOrg), "$description: mayAddGroupMember")
            assertEquals(s.expectedMayAdministerGroup, PeerTrustPolicy.mayAdministerGroup(s.isPaired, s.isOrg), "$description: mayAdministerGroup")
            assertEquals(s.expectedMayServeSwarm, PeerTrustPolicy.mayServeSwarm(s.isPaired, s.isOrg), "$description: mayServeSwarm")
            assertEquals(s.expectedMayVouch, PeerTrustPolicy.mayVouch(s.isPaired, s.isOrg), "$description: mayVouch")
        }
    }

    @Test
    fun ORGT_U25_push_to_talk_strictly_requires_paired() {
        // High-security assertion: PTT plays audio immediately on receipt, so ORG trust alone must NEVER grant PTT
        assertFalse(PeerTrustPolicy.mayPushToTalk(isPaired = false, isOrg = true), "ORG trust alone must never grant Push-To-Talk")
        assertTrue(PeerTrustPolicy.mayPushToTalk(isPaired = true, isOrg = false))
    }

    @Test
    fun ORGT_U25_group_creation_and_admin_strictly_requires_paired() {
        assertFalse(PeerTrustPolicy.mayCreateGroupWith(isPaired = false, isOrg = true))
        assertFalse(PeerTrustPolicy.mayAddGroupMember(isPaired = false, isOrg = true))
        assertFalse(PeerTrustPolicy.mayAdministerGroup(isPaired = false, isOrg = true))
    }

    @Test
    fun ORGT_MUTATION_3_mayPushToTalk_failsIfWidenedToPermitOrg() {
        // Mutation check (3): widen mayPushToTalk (must fail if mayPushToTalk permits ORG)
        // PTT plays audio immediately without user confirmation; ORG colleagues MUST NOT be granted PTT.
        val permitsOrgAlone = PeerTrustPolicy.mayPushToTalk(isPaired = false, isOrg = true)
        assertFalse(
            permitsOrgAlone,
            "MUTATION CHECK 3: mayPushToTalk must fail if widened to permit unpaired ORG colleagues",
        )
    }
}
