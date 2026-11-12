@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import androidx.compose.ui.unit.dp
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubReachability
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.core.messaging.model.HubUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun hubContentPaddingAppliesAndClampsBottomInset() {
        assertEquals(16.dp, hubContentPadding(0.dp).calculateBottomPadding())
        assertEquals(100.dp, hubContentPadding(84.dp).calculateBottomPadding())
        assertEquals(16.dp, hubContentPadding((-12).dp).calculateBottomPadding())
    }

    @Test fun resolveHubPeopleDoesNotFlashUnfilteredRosterWhileSearchIsLoading() {
        val alice = HubPersonUi(
            deviceId = "d1",
            displayName = "Alice",
            departmentId = "dept-1",
            departmentName = "Finance",
            role = HubPersonRole.PERSON,
            online = true,
            reachability = HubReachability.HUB,
            paired = false,
            orgVerified = true,
            isSelf = false,
        )
        val bob = alice.copy(deviceId = "d2", displayName = "Bob")
        val all = listOf(alice, bob)

        assertEquals(emptyList(), resolveHubPeopleForScreen(searching = true, searchHits = null, allPeople = all))
        assertEquals(listOf(alice), resolveHubPeopleForScreen(searching = true, searchHits = listOf(alice), allPeople = all))
        assertEquals(all, resolveHubPeopleForScreen(searching = false, searchHits = null, allPeople = all))
    }

    @Test fun announcementTruncationDetectsWrappedParagraphOverflowWithoutNewlines() {
        val singleParagraph = "One single long paragraph with no newline characters that wraps past three visual lines."
        assertTrue(isAnnouncementBodyTruncated(hasVisualOverflow = true, body = singleParagraph))
        assertFalse(isAnnouncementBodyTruncated(hasVisualOverflow = false, body = "Short summary."))
        assertTrue(isAnnouncementBodyTruncated(hasVisualOverflow = false, body = "One\nTwo\nThree\nFour"))
    }

    @Test fun hubCopyUsesConsistentNewsAndOrganisationTerms() {
        assertEquals("News", FlashHubCopy.WORKSPACE_NEWS)
        assertEquals(FlashHubCopy.SEGMENT_NEWS, FlashHubCopy.WORKSPACE_NEWS)
        assertEquals("Back", FlashHubCopy.ANN_READER_BACK)
        assertEquals("Searching…", FlashHubCopy.SEARCHING)
        assertTrue(FlashHubCopy.WORKSPACE_UNENROLLED.contains("organisation"))
        assertTrue(FlashHubCopy.WORKSPACE_NEWS_HELP.contains("organisation"))
    }
}
