@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import com.transfer.flash.core.enterprise.ExpiredReason
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.core.messaging.model.HubUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/** HUBV-U10..: the four rows of the Hub status page say what is true and use the right tone. */
class FlashHubStatusRowsTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    private fun data(state: HubUiState, link: HubLinkState = HubLinkState.ONLINE) =
        FlashHubScreenData(state = state, link = link, nowMs = now)

    @Test fun aHealthyHubReadsConnectedUpdatedAndTrusted() {
        val rows = hubStatusRows(
            data(HubUiState(enrolled = true, loaded = true, syncStatus = HubSyncStatus.OK, lastSyncedAtMs = now - 60_000L, lease = OrgLease.Valid(now + 20 * day))),
        )
        assertEquals(listOf(FlashHubCopy.STATUS_SERVER, FlashHubCopy.STATUS_DIRECTORY, FlashHubCopy.STATUS_TRUST, FlashHubCopy.STATUS_CLOCK), rows.map { it.title })
        assertEquals(FlashHubCopy.OVERVIEW_CONNECTED, rows[0].value)
        assertEquals(HubTone.SUCCESS, rows[0].tone)
        assertEquals(HubTone.SUCCESS, rows[1].tone)
        assertEquals("20 days left", rows[2].value)
        assertEquals(HubTone.SUCCESS, rows[2].tone)
        assertEquals(FlashHubCopy.STATUS_CLOCK_OK, rows[3].value)
    }

    @Test fun anUnreachableHubWithOldDataWarnsAndSaysHowOld() {
        val rows = hubStatusRows(
            data(
                HubUiState(enrolled = true, loaded = true, syncStatus = HubSyncStatus.FAILED_OFFLINE, lastSyncedAtMs = now - 3 * 3_600_000L, lease = OrgLease.Valid(now + 2 * day)),
                link = HubLinkState.OFFLINE,
            ),
        )
        assertEquals(FlashHubCopy.OVERVIEW_OFFLINE, rows[0].value)
        assertEquals(HubTone.NEUTRAL, rows[0].tone)
        assertEquals(HubTone.WARNING, rows[1].tone)
        assertEquals(HubTone.WARNING, rows[2].tone)
    }

    @Test fun aNeverSyncedDeviceHasNoLeaseAndNothingLoaded() {
        val rows = hubStatusRows(data(HubUiState(enrolled = true, loaded = true, syncStatus = HubSyncStatus.NEVER)))
        assertEquals(FlashHubCopy.STATUS_NEVER_SYNCED, rows[1].value)
        assertEquals(FlashHubCopy.STATUS_NO_LEASE, rows[2].value)
        assertEquals(HubTone.NEUTRAL, rows[2].tone)
    }

    @Test fun anEndedLeaseIsADangerAndAWrongClockIsAWarning() {
        val rows = hubStatusRows(
            data(HubUiState(enrolled = true, loaded = true, lastSyncedAtMs = now - 1000L, lease = OrgLease.Expired(now - day, ExpiredReason.TIME), clockWrong = true)),
        )
        assertEquals(FlashHubCopy.STATUS_LEASE_ENDED, rows[2].value)
        assertEquals(HubTone.DANGER, rows[2].tone)
        assertEquals(FlashHubCopy.STRIP_CLOCK_WRONG, rows[3].value)
        assertEquals(HubTone.WARNING, rows[3].tone)
    }
}
