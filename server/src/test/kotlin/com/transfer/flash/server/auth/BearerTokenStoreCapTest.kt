package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BearerTokenStoreCapTest {

    private fun principal(id: String) = HubPrincipal(deviceId = id, role = "member")

    @Test
    fun aNoisyDeviceReplacesItsOwnOldestTokensAndLeavesOthersAlone() {
        // SEC-D14-03
        val store = BearerTokenStore(maxTokens = 20)
        val quiet = store.issue(principal("quiet"), nowMs = 1_000L).first
        val noisy = (1..30).map { i -> store.issue(principal("noisy"), nowMs = 2_000L + i).first }

        assertNotNull(store.resolve(quiet, nowMs = 5_000L), "another device's token survives the noisy one")
        assertNull(store.resolve(noisy.first(), nowMs = 5_000L), "the noisy device's oldest token is gone")
        assertNotNull(store.resolve(noisy.last(), nowMs = 5_000L))
        assertEquals(9, store.size, "one quiet token plus at most eight of the noisy device")
    }
}
