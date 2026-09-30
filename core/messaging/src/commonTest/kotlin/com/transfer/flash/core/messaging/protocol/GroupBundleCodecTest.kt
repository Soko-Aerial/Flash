@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.FlashProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-044 V1, slice S2: the v2 wire additions (plan D4). Signatures are opaque strings here. */
class GroupBundleCodecTest {
    private val gid = "g2-6f01129f28cff84f8ef67cb9d1970499"

    private val charter = GroupCharter(
        groupId = gid,
        name = "Café = 100% crew",
        ownerId = "owner-1",
        ownerKey = "AAECAwQFBgcICQoLDA0ODw+/+/==",
        createdAt = 1_700_000_000_000L,
        nonce = "oKGio6SlpqeoqaqrrK2urw==",
        sig = "c2lnbmF0dXJlKy8vPT0=",
    )

    private fun cert(index: Int, active: Boolean = true) = MemberCert(
        groupId = gid,
        subjectId = "dev-$index",
        subjectKey = "a2V5+/$index==",
        label = "Bé a $index = x%",
        role = if (index == 0) MemberCert.ROLE_OWNER else MemberCert.ROLE_MEMBER,
        seq = index + 1L,
        opId = "op-$index",
        active = active,
        issuerId = "owner-1",
        sig = "c2ln/+$index==",
    )

    private fun bundle(certs: List<MemberCert>) = GroupWireFrame.Bundle(gid, "owner-1", "op-b", charter, certs)

    @Test
    fun bundleRoundTripsCharterAndEveryCertIncludingTombstones() {
        val frame = bundle(listOf(cert(0), cert(1), cert(2, active = false)))
        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun aBundleWithNoCertsIsJustTheCharter() {
        val frame = bundle(emptyList())
        assertEquals(frame, GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun aBundleAtTheCertCapDecodesAndOneOverDoesNot() {
        val atCap = bundle((0 until GroupPolicy.MAX_BUNDLE_CERTS).map { cert(it) })
        assertEquals(atCap, GroupFrameCodec.decode(GroupFrameCodec.encode(atCap)))

        val over = bundle((0..GroupPolicy.MAX_BUNDLE_CERTS).map { cert(it) })
        assertNull(GroupFrameCodec.decode(GroupFrameCodec.encode(over)))
    }

    @Test
    fun aBundleRepeatingASubjectIsDropped() {
        val frame = bundle(listOf(cert(1), cert(1).copy(seq = 9L, opId = "op-again")))
        assertNull(GroupFrameCodec.decode(GroupFrameCodec.encode(frame)))
    }

    @Test
    fun aBundleMissingAnyRequiredFieldIsDropped() {
        val text = GroupFrameCodec.encode(bundle(listOf(cert(1))))
        assertNotNull(GroupFrameCodec.decode(text))
        for (key in listOf(
            "cName", "cOwner", "cOwnerKey", "cCreated", "cNonce", "cProto", "cSig", "certCount",
            "c0s", "c0k", "c0l", "c0r", "c0q", "c0o", "c0a", "c0i", "c0g",
        )) {
            val without = text.split(" ").filterNot { it.startsWith("$key=") }.joinToString(" ")
            assertNull(GroupFrameCodec.decode(without), "dropping $key must invalidate the bundle")
        }
    }

    @Test
    fun aBundleWithANonBooleanActiveFlagIsDropped() {
        val text = GroupFrameCodec.encode(bundle(listOf(cert(1)))).replace("c0a=true", "c0a=maybe")
        assertNull(GroupFrameCodec.decode(text))
    }

    @Test
    fun aBundleKeepsTheCommonHeaderSoOldDecodersSeeAnUnknownActionAndDropIt() {
        val text = GroupFrameCodec.encode(bundle(listOf(cert(1))))
        assertTrue(text.startsWith("FLASH_GROUP "))
        for (key in listOf("action=bundle", "groupId=$gid", "from=owner-1", "opId=op-b", "version=0")) {
            assertTrue(" $key" in text || text.contains("$key "), "missing $key in header")
        }
    }

    @Test
    fun aV2MessageCarriesItsSignatureAndALegacyOneIsUnchanged() {
        val signed = GroupWireFrame.Message(gid, "m-1", "dev-1", "Bea", 5L, "hi", signature = "c2ln/+==")
        assertEquals(signed, GroupFrameCodec.decode(GroupFrameCodec.encode(signed)))

        val legacy = GroupWireFrame.Message("legacy-group", "m-1", "dev-1", "Bea", 5L, "hi")
        val legacyText = GroupFrameCodec.encode(legacy)
        assertTrue("sig=" !in legacyText, "a legacy message must encode exactly as before")
        assertNull((GroupFrameCodec.decode(legacyText) as GroupWireFrame.Message).signature)
    }

    @Test
    fun aV2PushNamesItsAuthorSeparatelyFromThePusher() {
        val message = GroupWireFrame.Message(gid, "m-1", "dev-author", "Bea", 5L, "hi", signature = "c2ln/+==")
        val push = GroupWireFrame.SyncPush(gid, "sync-1", "dev-relay", message)
        val decoded = GroupFrameCodec.decode(GroupFrameCodec.encode(push)) as GroupWireFrame.SyncPush
        assertEquals("dev-relay", decoded.from)
        assertEquals("dev-author", decoded.message.from, "the relayed message keeps its true author (F-9)")
        assertEquals("c2ln/+==", decoded.message.signature)
    }

    @Test
    fun aLegacyPushStillAttributesTheMessageToThePusher() {
        val message = GroupWireFrame.Message("legacy-group", "m-1", "dev-relay", "Bea", 5L, "hi")
        val push = GroupWireFrame.SyncPush("legacy-group", "sync-1", "dev-relay", message)
        val text = GroupFrameCodec.encode(push)
        assertTrue("author=" !in text && "sig=" !in text)
        val decoded = GroupFrameCodec.decode(text) as GroupWireFrame.SyncPush
        assertEquals("dev-relay", decoded.message.from)
        assertNull(decoded.message.signature)
    }

    @Test
    fun aPushThatNamesAnAuthorButCarriesNoSignatureDecodesUnsigned() {
        val text = GroupFrameCodec.encode(
            GroupWireFrame.SyncPush(
                gid, "sync-1", "dev-relay",
                GroupWireFrame.Message(gid, "m-1", "dev-relay", "Bea", 5L, "hi", signature = "c2ln"),
            ),
        ).split(" ").filterNot { it.startsWith("sig=") }.joinToString(" ")
        val decoded = GroupFrameCodec.decode(text) as GroupWireFrame.SyncPush
        assertNull(decoded.message.signature, "the repository drops it: a v2 message without a signature is not accepted")
    }

    @Test
    fun aBundleMustCarryASingleGroup() {
        val other = cert(1).copy(groupId = "g2-other")
        var threw = false
        try {
            GroupWireFrame.Bundle(gid, "owner-1", "op-b", charter, listOf(other))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun theAdvertisedGroupLevelIsTheLevelV2GroupsNeed() {
        assertEquals(GroupPolicy.V2_PROTOCOL, FlashProtocol.GROUP_PROTOCOL_LEVEL)
    }
}
