package com.transfer.flash.ui.hub

import com.transfer.flash.core.enterprise.ExpiredReason
import com.transfer.flash.core.enterprise.OrgLease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** HUBV-U01..: the numbers behind the redesigned header (lease ring, share bars, count-up). */
class FlashHubVisualMathTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    @Test fun aLeaseRoundsUpToWholeDays() {
        assertEquals(24, FlashHubVisualMath.leaseDaysLeft(OrgLease.Valid(now + 23 * day + 1), now))
        assertEquals(23, FlashHubVisualMath.leaseDaysLeft(OrgLease.Valid(now + 23 * day), now))
        assertEquals(1, FlashHubVisualMath.leaseDaysLeft(OrgLease.Valid(now + 1), now))
    }

    @Test fun aLeaseThatEndedOrNeverStartedHasNoDays() {
        assertEquals(0, FlashHubVisualMath.leaseDaysLeft(OrgLease.Valid(now), now))
        assertEquals(0, FlashHubVisualMath.leaseDaysLeft(OrgLease.Valid(now - day), now))
        assertEquals(0, FlashHubVisualMath.leaseDaysLeft(OrgLease.Expired(now - day, ExpiredReason.TIME), now))
        assertNull(FlashHubVisualMath.leaseDaysLeft(OrgLease.None, now))
        assertNull(FlashHubVisualMath.leaseFraction(OrgLease.None, now))
    }

    @Test fun theRingIsFullAtThirtyDaysAndNeverOverflows() {
        assertEquals(1f, FlashHubVisualMath.leaseFraction(OrgLease.Valid(now + 30 * day), now))
        assertEquals(1f, FlashHubVisualMath.leaseFraction(OrgLease.Valid(now + 90 * day), now))
        assertEquals(0.5f, FlashHubVisualMath.leaseFraction(OrgLease.Valid(now + 15 * day), now))
        assertEquals(0f, FlashHubVisualMath.leaseFraction(OrgLease.Expired(now - 1, ExpiredReason.TIME), now))
    }

    @Test fun theToneFollowsTheDaysLeft() {
        assertEquals(FlashHubLeaseTone.HEALTHY, FlashHubVisualMath.leaseTone(OrgLease.Valid(now + 8 * day), now))
        assertEquals(FlashHubLeaseTone.SOON, FlashHubVisualMath.leaseTone(OrgLease.Valid(now + 7 * day), now))
        assertEquals(FlashHubLeaseTone.SOON, FlashHubVisualMath.leaseTone(OrgLease.Valid(now + 1), now))
        assertEquals(FlashHubLeaseTone.EXPIRED, FlashHubVisualMath.leaseTone(OrgLease.Valid(now), now))
        assertEquals(FlashHubLeaseTone.UNKNOWN, FlashHubVisualMath.leaseTone(OrgLease.None, now))
    }

    @Test fun theBiggestDepartmentFillsItsBar() {
        assertEquals(1f, FlashHubVisualMath.share(12, 12))
        assertEquals(0.5f, FlashHubVisualMath.share(6, 12))
        assertEquals(0f, FlashHubVisualMath.share(0, 12))
        assertEquals(0f, FlashHubVisualMath.share(5, 0))
        assertEquals(1f, FlashHubVisualMath.share(20, 12))
    }

    @Test fun theCountUpNeverOvershoots() {
        assertEquals(0, FlashHubVisualMath.countAt(42, 0f))
        assertEquals(21, FlashHubVisualMath.countAt(42, 0.5f))
        assertEquals(42, FlashHubVisualMath.countAt(42, 1f))
        assertEquals(42, FlashHubVisualMath.countAt(42, 3f))
        assertEquals(0, FlashHubVisualMath.countAt(42, -1f))
        assertEquals(0, FlashHubVisualMath.countAt(-5, 1f))
    }

    @Test fun theSweepIsAFullCircleAtOne() {
        assertEquals(360f, FlashHubVisualMath.sweepDegrees(1f))
        assertEquals(90f, FlashHubVisualMath.sweepDegrees(0.25f))
        assertEquals(0f, FlashHubVisualMath.sweepDegrees(-1f))
    }

    @Test fun theSpokenLeaseNamesTheState() {
        assertEquals("Offline trust: 1 day left", FlashHubCopy.statusLeaseSpoken(1))
        assertEquals("Offline trust: 23 days left", FlashHubCopy.statusLeaseSpoken(23))
        assertEquals("Offline trust: Ended. Connect to renew", FlashHubCopy.statusLeaseSpoken(0))
        assertEquals("Offline trust: Not started yet", FlashHubCopy.statusLeaseSpoken(null))
    }
}
