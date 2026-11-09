@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.core.messaging.model.HubUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FlashHubWorkspaceTest {
    @Test fun onlyAuthenticatedConnectionsClaimConnected() {
        val connected = HubLinkState.entries.filter { hubWorkspaceConnection(it) == FlashHubCopy.OVERVIEW_CONNECTED }
        assertEquals(listOf(HubLinkState.ONLINE, HubLinkState.CERT_EXPIRING), connected)
    }

    @Test fun connectionProgressDoesNotClaimOffline() {
        for (link in listOf(HubLinkState.CONNECTING, HubLinkState.AUTHENTICATING)) {
            assertNotEquals(FlashHubCopy.OVERVIEW_OFFLINE, hubWorkspaceConnection(link))
            assertEquals(hubWorkspaceConnection(link), hubStatusRows(FlashHubScreenData(link = link)).first().value)
        }
    }

    @Test fun authenticationFailuresKeepTheirMeaning() {
        val errors = listOf(HubLinkState.CERT_EXPIRED, HubLinkState.REVOKED, HubLinkState.SERVER_MISMATCH)
        assertEquals(3, errors.map(::hubWorkspaceConnection).toSet().size)
        errors.forEach { assertNotEquals(FlashHubCopy.OVERVIEW_OFFLINE, hubWorkspaceConnection(it)) }
    }

    @Test fun cachedConnectionProgressDoesNotContradictTheHeader() {
        val state = HubUiState(enrolled = true, loaded = true, peopleCount = 4,
            lastSyncedAtMs = 1000L, syncStatus = HubSyncStatus.OK, lease = OrgLease.Valid(100_000_000_000L))
        for (link in listOf(HubLinkState.CONNECTING, HubLinkState.AUTHENTICATING)) {
            val strip = FlashHubMath.strip(state, link, 2000L)!!
            assertTrue(strip.text.startsWith(hubWorkspaceConnection(link)))
            assertTrue(strip.text.contains("showing information"))
        }
        assertEquals(FlashHubStripTone.CALM, FlashHubMath.strip(state, HubLinkState.CERT_EXPIRING, 2000L)!!.tone)
    }
}
