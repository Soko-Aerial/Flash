@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.core.hubprotocol.relay.RelayLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SEC-D15-02: the hub announces how long it keeps undelivered mail and the recipient bounds a delivery's age by it.
 *
 * The value is additive on the wire (`ignoreUnknownKeys`): a hub that predates it sends nothing, an older client
 * ignores it, and neither side breaks. These tests pin the default a silent hub gets and the round trip.
 */
class MessageFreshnessTest {

    @Test
    fun `a hub that announces no retention gets the shipped default, not zero`() {
        assertEquals(HubContract.DEFAULT_MAILBOX_TTL_MS, RelayLimits().mailboxTtlMs)
        assertEquals(7L * 24 * 60 * 60 * 1000, RelayLimits().mailboxTtlMs)
    }

    @Test
    fun `the announced retention survives the wire and a welcome without it still decodes`() {
        val withTtl = RelayFrameCodec.encode(
            RelayFrame.Welcome(
                principal = com.transfer.flash.core.hubprotocol.relay.HubPrincipal("dev-1", "member"),
                serverTime = 1_700_000_000_000L,
                limits = RelayLimits(mailboxTtlMs = 3_600_000L),
            ),
        )
        val decoded = RelayFrameCodec.decode(withTtl) as RelayFrame.Welcome
        assertEquals(3_600_000L, decoded.limits.mailboxTtlMs)

        // A WELCOME from a hub that predates the field: no `mailboxTtlMs` key at all.
        val older = withTtl.replace(",\"mailboxTtlMs\":3600000", "")
        assertTrue(!older.contains("mailboxTtlMs"), "the fixture must really lack it")
        val fallback = RelayFrameCodec.decode(older) as RelayFrame.Welcome
        assertEquals(HubContract.DEFAULT_MAILBOX_TTL_MS, fallback.limits.mailboxTtlMs)
    }
}
