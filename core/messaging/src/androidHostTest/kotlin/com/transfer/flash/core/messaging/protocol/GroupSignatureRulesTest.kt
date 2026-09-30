package com.transfer.flash.core.messaging.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-044 V1, slice S1: every way a v2 charter, cert or message can be wrong, against real ECDSA.
 * "me" is the receiving device; "owner" owns the group; "bea" is a member; "mal" is a paired peer
 * that wants to forge things.
 */
class GroupSignatureRulesTest {
    private val me = TestGroupCrypto()
    private val owner = TestGroupCrypto()
    private val bea = TestGroupCrypto()
    private val mal = TestGroupCrypto()

    private val paired = mutableSetOf("owner", "bea", "mal")
    private val pins = mutableMapOf(
        "owner" to owner.fingerprintHex(),
        "bea" to bea.fingerprintHex(),
        "mal" to mal.fingerprintHex(),
    )
    private val rules = GroupSignatureRules(me, "me", { it in paired }, { pins[it] })
    private val ownerSigning = GroupSigning(owner)

    private fun nonce(seed: Int) = ByteArray(GroupPolicy.CHARTER_NONCE_BYTES) { (seed + it).toByte() }

    private fun charter(name: String = "Crew") = ownerSigning.newCharter(name, "owner", 1_000L, nonce(1))

    private fun cert(
        charter: GroupCharter,
        subject: String = "bea",
        key: TestGroupCrypto = bea,
        seq: Long = 1L,
        active: Boolean = true,
        issuer: String = "owner",
        signer: GroupSigning = ownerSigning,
        role: String = if (subject == charter.ownerId) MemberCert.ROLE_OWNER else MemberCert.ROLE_MEMBER,
        label: String = "Bea",
        opId: String = "op-$seq",
    ) = signer.issueCert(charter.groupId, subject, key.publicKey, label, role, seq, opId, active, issuer)

    // ------------------------------------------------------------------ charter

    @Test
    fun aCharterFromAPairedPinnedOwnerIsValid() {
        assertNull(rules.checkCharter(charter()))
    }

    @Test
    fun theGroupIdIsDerivedFromTheOwnerKeyAndCarriesTheV2Prefix() {
        val c = charter()
        assertTrue(c.groupId.startsWith("g2-"))
        assertEquals(35, c.groupId.length)
        assertEquals(c.groupId, GroupCanonical.deriveGroupId(owner, owner.publicKey, GroupCanonical.decode(c.nonce)!!))
    }

    @Test
    fun theGroupIdMatchesTheIndependentlyComputedVector() {
        // SHA-256 over the length-prefixed tag, key and nonce, computed with Python's hashlib.
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(16) { (0xA0 + it).toByte() }
        assertEquals("g2-6f01129f28cff84f8ef67cb9d1970499", GroupCanonical.deriveGroupId(owner, key, nonce))
    }

    @Test
    fun aCharterWhoseOwnerIsNotPairedIsRejected() {
        paired -= "owner"
        assertEquals("owner-not-paired", rules.checkCharter(charter()))
    }

    @Test
    fun aCharterWhoseOwnerKeyIsNotThePinnedKeyIsRejected() {
        pins["owner"] = mal.fingerprintHex()
        assertEquals("owner-key-pin", rules.checkCharter(charter()))
        pins.remove("owner")
        assertEquals("owner-key-pin", rules.checkCharter(charter()))
    }

    @Test
    fun aTamperedCharterFailsItsSignature() {
        val c = charter()
        assertEquals("signature", rules.checkCharter(c.copy(name = "Other")))
        assertEquals("signature", rules.checkCharter(c.copy(createdAt = c.createdAt + 1)))
        // Naming a different (paired) owner fails on the pin before the signature is even looked at.
        assertEquals("owner-key-pin", rules.checkCharter(c.copy(ownerId = "bea")))
    }

    @Test
    fun aCharterSignedByAnotherKeyIsRejected() {
        val c = charter()
        val forged = c.copy(sig = GroupCanonical.encode(mal.sign(GroupCanonical.charterBytes(c)!!)))
        assertEquals("signature", rules.checkCharter(forged))
    }

    @Test
    fun aMemberCannotSquatTheOwnersGroupId() {
        // Mal, a member who learned the id, builds a charter of their own. It cannot reuse the
        // owner's id: a charter's id must derive from its own owner key.
        val real = charter()
        val squat = GroupSigning(mal).newCharter("Crew", "mal", 2_000L, nonce(1))
        assertNotEquals(real.groupId, squat.groupId)
        val forcedId = squat.copy(groupId = real.groupId)
        assertEquals("id-derivation", rules.checkCharter(forcedId))
    }

    @Test
    fun aCharterForALegacyIdIsRejected() {
        val c = charter()
        assertEquals("id-namespace", rules.checkCharter(c.copy(groupId = "550e8400-e29b-41d4-a716-446655440000")))
    }

    @Test
    fun aCharterWithABadNonceOrProtoOrNameIsRejected() {
        val c = charter()
        assertEquals("proto", rules.checkCharter(c.copy(proto = 1)))
        assertEquals("nonce", rules.checkCharter(c.copy(nonce = GroupCanonical.encode(ByteArray(8)))))
        assertEquals("nonce", rules.checkCharter(c.copy(nonce = "***")))
        assertEquals("name", rules.checkCharter(c.copy(name = "  padded  ")))
        assertEquals("name", rules.checkCharter(c.copy(name = "x".repeat(GroupPolicy.MAX_GROUP_NAME_LENGTH + 1))))
        assertEquals("owner-key", rules.checkCharter(c.copy(ownerKey = "")))
    }

    @Test
    fun aLocallyOwnedCharterMustCarryTheLocalKey() {
        val mine = GroupSigning(me).newCharter("Mine", "me", 5L, nonce(9))
        assertNull(rules.checkCharter(mine))
        // A charter that names me as owner but carries someone else's key is not mine.
        val impostor = GroupSigning(mal).newCharter("Mine", "me", 5L, nonce(9))
        assertEquals("owner-key-local", rules.checkCharter(impostor))
    }

    // ------------------------------------------------------------------ cert

    @Test
    fun anOwnerIssuedCertForAPairedPinnedMemberIsValid() {
        val c = charter()
        assertNull(rules.checkCert(c, cert(c), knownKey = null))
    }

    @Test
    fun theOwnersOwnCertIsValidAndMustCarryTheOwnerKey() {
        val c = charter()
        assertNull(rules.checkCert(c, cert(c, subject = "owner", key = owner, label = "Ada"), null))
        assertEquals(
            "subject-key-binding",
            rules.checkCert(c, cert(c, subject = "owner", key = mal, label = "Ada"), null),
        )
    }

    @Test
    fun myOwnCertMustCarryMyKey() {
        val c = charter()
        assertNull(rules.checkCert(c, cert(c, subject = "me", key = me, label = "Me"), null))
        assertEquals("subject-key-binding", rules.checkCert(c, cert(c, subject = "me", key = mal, label = "Me"), null))
    }

    @Test
    fun anActiveCertForAnUnpairedOrMismatchedMemberIsDropped() {
        val c = charter()
        paired -= "bea"
        assertEquals("subject-key-binding", rules.checkCert(c, cert(c), null))
        paired += "bea"
        pins["bea"] = mal.fingerprintHex()
        assertEquals("subject-key-binding", rules.checkCert(c, cert(c), null))
        pins.remove("bea")
        assertEquals("subject-key-binding", rules.checkCert(c, cert(c), null))
    }

    @Test
    fun aMemberCannotAddAnyoneOrPromoteThemselves() {
        val c = charter()
        val malSigning = GroupSigning(mal)
        // Mal issues an active cert for bea, in their own name.
        assertEquals("issuer", rules.checkCert(c, cert(c, issuer = "mal", signer = malSigning), null))
        // Mal claims to be the owner but signs with their own key.
        assertEquals("signature", rules.checkCert(c, cert(c, issuer = "owner", signer = malSigning), null))
        // Mal makes themselves the owner-role member.
        assertEquals(
            "role",
            rules.checkCert(c, cert(c, subject = "mal", key = mal, issuer = "owner", role = "owner"), null),
        )
    }

    @Test
    fun theOwnerSubjectMustHaveTheOwnerRoleAndNobodyElseMay() {
        val c = charter()
        assertEquals("role", rules.checkCert(c, cert(c, subject = "owner", key = owner, role = "member"), null))
        assertEquals("role", rules.checkCert(c, cert(c, role = "owner"), null))
    }

    @Test
    fun aMembersOwnLeaveIsBelievedOnlyWhenSignedByAKeyWeKnowForThem() {
        val c = charter()
        val beaSigning = GroupSigning(bea)
        val leave = cert(c, seq = 2L, active = false, issuer = "bea", signer = beaSigning)
        assertNull("paired and pinned", rules.checkCert(c, leave, knownKey = null))
        assertNull(
            "recorded key",
            rules.checkCert(c, leave, knownKey = GroupCanonical.encode(bea.publicKey)),
        )

        // Mal invents a key and "signs" a leave for bea. It verifies against the invented key, so
        // only the binding stops it.
        val invented = TestGroupCrypto()
        val fake = GroupSigning(invented).issueCert(
            c.groupId, "bea", invented.publicKey, "Bea", "member", 2L, "op-x", false, "bea",
        )
        assertEquals("subject-key-binding", rules.checkCert(c, fake, knownKey = GroupCanonical.encode(bea.publicKey)))
        pins.remove("bea")
        assertEquals("subject-key-binding", rules.checkCert(c, fake, knownKey = null))
    }

    @Test
    fun aMemberCannotSignAnotherMembersLeave() {
        val c = charter()
        val forged = cert(c, subject = "bea", seq = 2L, active = false, issuer = "mal", signer = GroupSigning(mal))
        assertEquals("issuer", rules.checkCert(c, forged, null))
    }

    @Test
    fun aMemberCannotReactivateThemselvesWithASelfSignedCert() {
        val c = charter()
        val selfAdd = cert(c, seq = 5L, active = true, issuer = "bea", signer = GroupSigning(bea))
        assertEquals("issuer", rules.checkCert(c, selfAdd, null))
    }

    @Test
    fun anOwnerTombstoneNeedsNoKeyForTheSubject() {
        val c = charter()
        paired -= "bea"
        pins.remove("bea")
        val removal = cert(c, seq = 2L, active = false)
        assertNull(rules.checkCert(c, removal, null))
    }

    @Test
    fun anOwnerMayLeaveAndTheTombstoneVerifiesWithTheOwnerKey() {
        val c = charter()
        val ownerLeave = cert(c, subject = "owner", key = owner, seq = 2L, active = false, issuer = "owner", label = "Ada")
        assertNull(rules.checkCert(c, ownerLeave, null))
    }

    @Test
    fun aTamperedCertFailsItsSignature() {
        val c = charter()
        val good = cert(c)
        assertEquals("signature", rules.checkCert(c, good.copy(seq = 9L), null))
        assertEquals("signature", rules.checkCert(c, good.copy(label = "Boss"), null))
        assertEquals("signature", rules.checkCert(c, good.copy(active = false), null))
        assertEquals("signature", rules.checkCert(c, good.copy(opId = "other"), null))
    }

    @Test
    fun aCertOfAnotherGroupCannotBeReplayedHere() {
        val c = charter()
        val other = ownerSigning.newCharter("Other", "owner", 1_000L, nonce(2))
        assertEquals("group", rules.checkCert(c, cert(other), null))
    }

    @Test
    fun malformedCertFieldsAreRejected() {
        val c = charter()
        assertEquals("seq", rules.checkCert(c, cert(c, seq = 0L), null))
        assertEquals("seq", rules.checkCert(c, cert(c, seq = -3L), null))
        assertEquals("label", rules.checkCert(c, cert(c, label = " "), null))
        assertEquals("label", rules.checkCert(c, cert(c, label = "x".repeat(GroupPolicy.MAX_LABEL_LENGTH + 1)), null))
        assertEquals("op-id", rules.checkCert(c, cert(c, opId = ""), null))
        assertEquals("subject-key", rules.checkCert(c, cert(c).copy(subjectKey = ""), null))
        assertEquals("subject-key", rules.checkCert(c, cert(c).copy(subjectKey = "***"), null))
        assertEquals("signature", rules.checkCert(c, cert(c).copy(sig = ""), null))
        assertEquals("signature", rules.checkCert(c, cert(c).copy(sig = "***"), null))
    }

    @Test
    fun aCertKeepsItsMeaningWhenTheSubjectKeyIsRepinnedToTheSameKey() {
        val c = charter()
        pins["bea"] = bea.fingerprintHex().chunked(2).joinToString(":").lowercase()
        assertNull("colon-separated lowercase pins are normalised", rules.checkCert(c, cert(c), null))
    }

    // ------------------------------------------------------------------ message

    private fun signed(
        text: String = "hi",
        reply: String? = null,
        preview: String? = null,
    ): String = GroupSigning(bea).signMessage("g2-x", "m-1", "bea", 1_234L, reply, preview, text)

    private fun verify(
        sig: String?,
        key: TestGroupCrypto = bea,
        groupId: String = "g2-x",
        msgId: String = "m-1",
        from: String = "bea",
        sentAt: Long = 1_234L,
        reply: String? = null,
        preview: String? = null,
        text: String = "hi",
    ) = rules.verifyMessage(GroupCanonical.encode(key.publicKey), groupId, msgId, from, sentAt, reply, preview, text, sig)

    @Test
    fun aMessageVerifiesWithItsAuthorsKey() {
        assertTrue(verify(signed()))
        assertTrue(verify(signed("re", "m-0", "quoted"), reply = "m-0", preview = "quoted", text = "re"))
    }

    @Test
    fun everySignedMessageFieldIsBoundToTheSignature() {
        val sig = signed()
        assertFalse(verify(sig, text = "hi!"))
        assertFalse(verify(sig, groupId = "g2-y"))
        assertFalse(verify(sig, msgId = "m-2"))
        assertFalse(verify(sig, from = "mal"))
        assertFalse(verify(sig, sentAt = 1_235L))
        assertFalse(verify(sig, reply = "m-0"))
        assertFalse(verify(sig, preview = "p"))
    }

    @Test
    fun aMessageDoesNotVerifyWithSomeoneElsesKeyOrABrokenSignature() {
        val sig = signed()
        assertFalse("relayer's key is not the author's", verify(sig, key = mal))
        assertFalse(verify(null))
        assertFalse(verify(""))
        assertFalse(verify("***"))
        assertFalse(verify(GroupCanonical.encode(ByteArray(70))))
        assertFalse(rules.verifyMessage("", "g2-x", "m-1", "bea", 1_234L, null, null, "hi", sig))
        assertFalse(rules.verifyMessage("***", "g2-x", "m-1", "bea", 1_234L, null, null, "hi", sig))
    }

    @Test
    fun aSignedMessageCannotBeReplayedIntoAnotherGroup() {
        assertFalse(verify(signed(), groupId = "g2-other"))
    }
}
