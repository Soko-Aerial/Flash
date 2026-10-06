package com.transfer.flash.core.security.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupProofTest {

    private val testSecret = GroupSecret.fromBytes(ByteArray(32) { it.toByte() })
    private val testGroupId = "g2-6f01129f28cff84f8ef67cb9d1970499"
    private val testEpoch = 1L
    private val authKey = GroupSecretKdf.authKey(testSecret, testGroupId, testEpoch)

    private val fpI = ByteArray(32) { (0x10 + it).toByte() }
    private val fpR = ByteArray(32) { (0x30 + it).toByte() }

    private val fixedNonceI = ByteArray(16) { (0xa0 + it).toByte() }
    private val fixedNonceR = ByteArray(16) { (0xb0 + it).toByte() }

    @Test
    fun endToEndMutualProofSucceeds() {
        val initiator = GroupProofInitiator(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpI,
            remoteTlsFingerprint = fpR,
            nonceSource = { fixedNonceI },
        )
        val responder = GroupProofResponder(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpR,
            remoteTlsFingerprint = fpI,
            nonceSource = { fixedNonceR },
        )

        assertEquals(GroupProofInitiator.State.INITIAL, initiator.state)
        assertEquals(GroupProofResponder.State.WAITING_HELLO, responder.state)

        // Message 1: Initiator -> Responder
        val hello = initiator.createHello()
        assertEquals(GroupProofInitiator.State.WAITING_CHALLENGE, initiator.state)
        assertEquals(testGroupId, hello.groupId)
        assertEquals(testEpoch, hello.epoch)
        assertTrue(fixedNonceI.contentEquals(hello.nonce))

        // Message 2: Responder -> Initiator
        val challenge = responder.receiveHello(hello)
        assertEquals(GroupProofResponder.State.WAITING_PROOF, responder.state)
        assertEquals(testGroupId, challenge.groupId)
        assertEquals(testEpoch, challenge.epoch)
        assertTrue(fixedNonceR.contentEquals(challenge.nonce))
        assertEquals(32, challenge.mac.size)

        // Message 3: Initiator -> Responder
        val proof = initiator.receiveChallenge(challenge)
        assertEquals(GroupProofInitiator.State.VERIFIED, initiator.state)
        assertEquals(testGroupId, proof.groupId)
        assertEquals(testEpoch, proof.epoch)
        assertEquals(32, proof.mac.size)

        // Responder verifies Message 3
        val verified = responder.receiveProof(proof)
        assertTrue(verified)
        assertEquals(GroupProofResponder.State.VERIFIED, responder.state)
    }

    @Test
    fun relayMitmFailsProofDueToDifferentTlsFingerprints() {
        // MITM holds Session 1 with Initiator (fpI <-> fpM1) and Session 2 with Responder (fpM2 <-> fpR)
        val fpM1 = ByteArray(32) { 0xee.toByte() }
        val fpM2 = ByteArray(32) { 0xff.toByte() }

        val initiator = GroupProofInitiator(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpI,
            remoteTlsFingerprint = fpM1, // Initiator sees MITM on Session 1
        )
        val responder = GroupProofResponder(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpR,
            remoteTlsFingerprint = fpM2, // Responder sees MITM on Session 2
        )

        val hello = initiator.createHello()
        // MITM relays hello to responder
        val challenge = responder.receiveHello(hello)

        // MITM relays challenge to initiator.
        // Fails because responder computed MAC over (fpM2, fpR) while initiator expects (fpI, fpM1)
        assertFailsWith<GroupProofException> {
            initiator.receiveChallenge(challenge)
        }
        assertEquals(GroupProofInitiator.State.FAILED, initiator.state)
    }

    @Test
    fun reflectionAttackFails() {
        val initiator = GroupProofInitiator(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpI,
            remoteTlsFingerprint = fpR,
            nonceSource = { fixedNonceI },
        )
        val responder = GroupProofResponder(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpR,
            remoteTlsFingerprint = fpI,
            nonceSource = { fixedNonceR },
        )

        val hello = initiator.createHello()
        val challenge = responder.receiveHello(hello)

        // Attacker attempts to reflect responder's challenge MAC as initiator's proof MAC
        val reflectedProof = GroupProofMac(testGroupId, testEpoch, challenge.mac)
        val verified = responder.receiveProof(reflectedProof)

        assertFalse(verified)
        assertEquals(GroupProofResponder.State.FAILED, responder.state)
    }

    @Test
    fun replayAttackFails() {
        val initiator1 = GroupProofInitiator(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpI,
            remoteTlsFingerprint = fpR,
            nonceSource = { fixedNonceI },
        )
        val responder1 = GroupProofResponder(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpR,
            remoteTlsFingerprint = fpI,
            nonceSource = { fixedNonceR },
        )
        val hello1 = initiator1.createHello()
        val challenge1 = responder1.receiveHello(hello1)
        val proof1 = initiator1.receiveChallenge(challenge1)
        assertTrue(responder1.receiveProof(proof1))

        // Session 2 with a new nonce from responder
        val freshNonceR = ByteArray(16) { (0xc0 + it).toByte() }
        val responder2 = GroupProofResponder(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpR,
            remoteTlsFingerprint = fpI,
            nonceSource = { freshNonceR },
        )
        responder2.receiveHello(hello1)

        // Replaying old proof1 against responder2 must fail
        assertFalse(responder2.receiveProof(proof1))
        assertEquals(GroupProofResponder.State.FAILED, responder2.state)
    }

    @Test
    fun wrongEpochFails() {
        val initiator = GroupProofInitiator(
            groupId = testGroupId,
            epoch = 1L,
            authKey = authKey,
            localTlsFingerprint = fpI,
            remoteTlsFingerprint = fpR,
        )
        val responder = GroupProofResponder(
            groupId = testGroupId,
            epoch = 2L, // different epoch
            authKey = GroupSecretKdf.authKey(testSecret, testGroupId, 2L),
            localTlsFingerprint = fpR,
            remoteTlsFingerprint = fpI,
        )

        val hello = initiator.createHello()
        assertFailsWith<GroupProofException> {
            responder.receiveHello(hello)
        }
        assertEquals(GroupProofResponder.State.FAILED, responder.state)
    }

    @Test
    fun wrongGroupIdFails() {
        val initiator = GroupProofInitiator(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpI,
            remoteTlsFingerprint = fpR,
        )
        val responder = GroupProofResponder(
            groupId = "g2-othergroup",
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpR,
            remoteTlsFingerprint = fpI,
        )

        val hello = initiator.createHello()
        assertFailsWith<GroupProofException> {
            responder.receiveHello(hello)
        }
        assertEquals(GroupProofResponder.State.FAILED, responder.state)
    }

    @Test
    fun oneShotEnforcement() {
        val initiator = GroupProofInitiator(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpI,
            remoteTlsFingerprint = fpR,
            nonceSource = { fixedNonceI },
        )
        initiator.createHello()

        // Calling createHello a second time throws
        assertFailsWith<IllegalStateException> {
            initiator.createHello()
        }

        val responder = GroupProofResponder(
            groupId = testGroupId,
            epoch = testEpoch,
            authKey = authKey,
            localTlsFingerprint = fpR,
            remoteTlsFingerprint = fpI,
            nonceSource = { fixedNonceR },
        )
        val hello = GroupProofHello(testGroupId, testEpoch, fixedNonceI)
        responder.receiveHello(hello)

        // Calling receiveHello a second time throws
        assertFailsWith<IllegalStateException> {
            responder.receiveHello(hello)
        }
    }
}
