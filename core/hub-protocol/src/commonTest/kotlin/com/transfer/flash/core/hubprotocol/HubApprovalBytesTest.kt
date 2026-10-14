package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.HubStepUp
import com.transfer.flash.core.hubprotocol.certs.toHubHex
import com.transfer.flash.core.hubprotocol.rest.ApprovalDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR-131 (FO-14): what the administrator's phone signs, and the six digits both screens show. */
@OptIn(FlashInternalApi::class)
class HubApprovalBytesTest {

    // Computed with an independent implementation (Python struct/hashlib over the same length-prefixed layout).
    @Test
    fun approvalBytesMatchTheIndependentGoldenVector() {
        val bytes = HubStepUp.approvalBytes("c-1", "delegation.install", "alice", "serial:7", "n0nce")
        assertEquals(
            "00000015666c6173682d6875622d617070726f76616c2d763100000003632d310000001264656c65676174696f6e2e696e7374616c6c" +
                "00000005616c6963650000000873657269616c3a37000000056e306e6365",
            bytes.toHubHex(),
        )
    }

    @Test
    fun theCodeMatchesTheIndependentGoldenVector() {
        assertEquals("396796", HubStepUp.approvalCode("c-1", "n0nce"))
        assertEquals("530814", HubStepUp.approvalCode("c-2", "n0nce"))
    }

    @Test
    fun theCodeIsAlwaysSixDigits() {
        repeat(300) { i ->
            val code = HubStepUp.approvalCode("challenge-$i", "nonce-$i")
            assertEquals(6, code.length, code)
            assertTrue(code.all { it in '0'..'9' }, code)
        }
    }

    @Test
    fun aBrowserProofIsNeverAValidApproval() {
        val proof = HubStepUp.proofBytes("c-1", "delegation.install", "alice", "serial:7", "n0nce")
        val approval = HubStepUp.approvalBytes("c-1", "delegation.install", "alice", "serial:7", "n0nce")
        assertTrue(!proof.contentEquals(approval), "different domain tags")
        assertNotEquals(HubStepUp.STEP_UP_TAG, HubStepUp.APPROVAL_TAG)
    }

    @Test
    fun everyFieldIsBoundIntoTheApproval() {
        val base = HubStepUp.approvalBytes("c-1", "delegation.install", "alice", "serial:7", "n0nce")
        assertContentEquals(base, HubStepUp.approvalBytes("c-1", "delegation.install", "alice", "serial:7", "n0nce"))
        val variants = listOf(
            HubStepUp.approvalBytes("c-2", "delegation.install", "alice", "serial:7", "n0nce"),
            HubStepUp.approvalBytes("c-1", "admin.create", "alice", "serial:7", "n0nce"),
            HubStepUp.approvalBytes("c-1", "delegation.install", "bob", "serial:7", "n0nce"),
            HubStepUp.approvalBytes("c-1", "delegation.install", "alice", "serial:8", "n0nce"),
            HubStepUp.approvalBytes("c-1", "delegation.install", "alice", "serial:7", "other"),
        )
        for (v in variants) assertTrue(!v.contentEquals(base))
    }

    @Test
    fun theApprovalDtoRoundTrips() {
        val dto = ApprovalDto("c-1", "admin.create", "alice", "bob", "n0nce", "123456", 1_800_000_000_000L)
        assertEquals(dto, HubRestCodec.decodeOrNull<ApprovalDto>(HubRestCodec.encode(dto)))
    }
}
