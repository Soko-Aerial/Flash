package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR-129 (FO-13): the public revocation list carries `SHA-256(audience | deviceId)`, not device ids. */
@OptIn(FlashInternalApi::class)
class HashedRevocationListTest {
    private val rootKey = FakeSigs.keyOf("root")
    private val audience = RevocationList.audienceOf(rootKey)

    private fun hashed(
        ids: Set<String> = setOf("dev-1"),
        serials: Set<Long> = emptySet(),
        audience: String = this.audience,
        issuer: String = rootKey,
        version: Long = 1,
        delegations: Set<Long> = emptySet(),
    ): RevocationList {
        val unsigned = RevocationList(
            version = version,
            revokedSerials = serials,
            revokedDelegationSerials = delegations,
            issuerPublicKey = issuer,
            signature = "",
            audience = audience,
            revokedDeviceHashes = ids.map { RevocationList.deviceHash(audience, it) }.toSet(),
        )
        return unsigned.copy(signature = FakeSigs.sign(issuer, unsigned.canonicalBytes()))
    }

    // Values computed with an independent implementation (Python hashlib over the same length-prefixed bytes).
    @Test
    fun deviceHashMatchesTheIndependentGoldenVector() {
        assertEquals("5889627c138eca7ec222fd5c804016c52ff16157fd656c8a228ccf65a4d268c0", RevocationList.deviceHash("TEST-AUDIENCE", "dev-1"))
        assertEquals("d6d50b8f17681ad45ea95cb1209332e37b09be79035f89633fce2bdfa64eaa24", RevocationList.deviceHash("TEST-AUDIENCE", "dev-2"))
        assertEquals("33bda4d01975e5426ffb8e2f51474989c04f417a15b99135bbf78f0bcb13d409", RevocationList.deviceHash("OTHER-AUDIENCE", "dev-1"))
    }

    @Test
    fun audienceIsTheUpperCaseSha256OfTheRootKeyBytes() {
        assertEquals("EA41B1BBE40C422C2ABD1FC466CF76107B4A848363CEE8B44D0C66CBB2451E4B", audience)
    }

    @Test
    fun aHashedListVerifiesAndRevokesByHashWithoutNamingAnyId() {
        val list = hashed(ids = setOf("dev-1", "dev-2"), serials = setOf(7L))
        assertTrue(list.verifyTrusted(rootKey, FakeSigs, FakeSigs.T0))
        assertTrue(list.revokesDevice("dev-1"))
        assertTrue(list.revokesDevice("dev-2"))
        assertFalse(list.revokesDevice("dev-3"))
        assertTrue(list.isRevoked(7L, "someone-else"))
        assertTrue(list.revokedDeviceIds.isEmpty())
        val body = HubRestCodec.encode(list)
        assertFalse(body.contains("dev-1"), "the public body must not contain the device id")
        assertFalse(body.contains("dev-2"))
    }

    @Test
    fun theSameIdHashesDifferentlyForAnotherOrganisation() {
        assertNotEquals(RevocationList.deviceHash("A", "dev-1"), RevocationList.deviceHash("B", "dev-1"))
        assertEquals(64, RevocationList.deviceHash("A", "dev-1").length)
    }

    @Test
    fun aListForAnotherOrganisationIsNotTrusted() {
        val foreign = hashed(audience = "SOME-OTHER-ORGANISATION")
        assertTrue(foreign.verifySignature(FakeSigs), "signature alone is fine")
        assertFalse(foreign.verifyTrusted(rootKey, FakeSigs, FakeSigs.T0), "but it is not this organisation's list")
    }

    @Test
    fun aTamperedHashBreaksTheSignature() {
        val list = hashed()
        val tampered = list.copy(revokedDeviceHashes = list.revokedDeviceHashes + RevocationList.deviceHash(audience, "dev-9"))
        assertFalse(tampered.verifySignature(FakeSigs))
    }

    @Test
    fun theTwoFormatsNeverMix() {
        val list = hashed()
        assertFalse(list.copy(revokedDeviceIds = setOf("dev-1")).verifySignature(FakeSigs), "hashed list with plain ids")
        val plain = FakeSigs.revocations(ids = setOf("dev-1"))
        assertTrue(plain.verifySignature(FakeSigs))
        assertFalse(plain.copy(revokedDeviceHashes = setOf("x")).verifySignature(FakeSigs), "plain list with hashes")
    }

    @Test
    fun aPlainV2ListStillWorksForOlderRootLists() {
        val plain = FakeSigs.revocations(ids = setOf("dev-1"), serials = setOf(3L))
        assertFalse(plain.isHashed)
        assertTrue(plain.verifyTrusted(rootKey, FakeSigs, FakeSigs.T0))
        assertTrue(plain.revokesDevice("dev-1"))
        assertFalse(plain.revokesDevice("dev-2"))
    }

    @Test
    fun theV3SignatureDoesNotCoverTheV2Bytes() {
        val list = hashed()
        assertNotEquals(RevocationList.TAG, RevocationList.TAG_V3)
        // A v3 list stripped of its audience falls back to the v2 canonical form and must not verify.
        assertFalse(list.copy(audience = "", revokedDeviceHashes = emptySet()).verifySignature(FakeSigs))
    }

    @Test
    fun aHubSignedHashedListCannotRevokeDelegations() {
        val hub = FakeSigs.keyOf("hub")
        val cert = FakeSigs.delegation(delegate = hub)
        val ok = hashed(issuer = hub)
        assertTrue(ok.verifyTrusted(rootKey, FakeSigs, FakeSigs.T0, cert))
        val bad = hashed(issuer = hub, delegations = setOf(1L))
        assertFalse(bad.verifyTrusted(rootKey, FakeSigs, FakeSigs.T0, cert))
    }

    @Test
    fun theHashedListSurvivesTheJsonRoundTrip() {
        val list = hashed(ids = setOf("dev-1"), serials = setOf(5L))
        val back = HubRestCodec.decodeOrNull<RevocationList>(HubRestCodec.encode(list))
        assertEquals(list, back)
        assertTrue(back!!.verifyTrusted(rootKey, FakeSigs, FakeSigs.T0))
    }
}
