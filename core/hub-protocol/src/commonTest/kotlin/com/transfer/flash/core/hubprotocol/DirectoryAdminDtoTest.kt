@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.hubprotocol

class DirectoryAdminDtoTest {
    @kotlin.test.Test
    fun directoryAdminDtosRoundTripThroughTheSharedCodec() {
        val me = com.transfer.flash.core.hubprotocol.rest.MeDto(deviceId = "d1", groupIds = listOf("g1"), certSerial = 7L)
        val text = com.transfer.flash.core.hubprotocol.rest.HubRestCodec.encode(me)
        kotlin.test.assertEquals(me, com.transfer.flash.core.hubprotocol.rest.HubRestCodec.decode<com.transfer.flash.core.hubprotocol.rest.MeDto>(text))
        val body = com.transfer.flash.core.hubprotocol.rest.GroupMemberBody("g1", "d2", add = false)
        kotlin.test.assertEquals(body, com.transfer.flash.core.hubprotocol.rest.HubRestCodec.decode<com.transfer.flash.core.hubprotocol.rest.GroupMemberBody>(com.transfer.flash.core.hubprotocol.rest.HubRestCodec.encode(body)))
    }

    @kotlin.test.Test
    fun stepUpProofBytesAreBoundToEveryField() {
        val base = com.transfer.flash.core.hubprotocol.certs.HubStepUp.proofBytes("c1", "REVOKE_DEVICE", "root", "dev-1", "n1")
        val variants = listOf(
            com.transfer.flash.core.hubprotocol.certs.HubStepUp.proofBytes("c2", "REVOKE_DEVICE", "root", "dev-1", "n1"),
            com.transfer.flash.core.hubprotocol.certs.HubStepUp.proofBytes("c1", "UNREVOKE_DEVICE", "root", "dev-1", "n1"),
            com.transfer.flash.core.hubprotocol.certs.HubStepUp.proofBytes("c1", "REVOKE_DEVICE", "other", "dev-1", "n1"),
            com.transfer.flash.core.hubprotocol.certs.HubStepUp.proofBytes("c1", "REVOKE_DEVICE", "root", "dev-2", "n1"),
            com.transfer.flash.core.hubprotocol.certs.HubStepUp.proofBytes("c1", "REVOKE_DEVICE", "root", "dev-1", "n2"),
        )
        variants.forEach { kotlin.test.assertFalse(base.contentEquals(it)) }
        // Length prefixing: moving a character between adjacent fields changes the bytes.
        kotlin.test.assertFalse(
            com.transfer.flash.core.hubprotocol.certs.HubStepUp.proofBytes("ab", "c", "root", "t", "n")
                .contentEquals(com.transfer.flash.core.hubprotocol.certs.HubStepUp.proofBytes("a", "bc", "root", "t", "n")),
        )
    }
}
