@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.settings

import com.transfer.flash.core.enterprise.EnrollFailureReason
import com.transfer.flash.core.enterprise.EnterpriseDirectoryGroup
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.enterprise.HubOfflineReason
import com.transfer.flash.core.enterprise.HubSealingKeyState
import com.transfer.flash.core.enterprise.HubStatus
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.enterprise.ExpiredReason
import com.transfer.flash.core.enterprise.UnenrolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLI-07 / CLI-08 / CLI-13: what Settings says about the hub link, in plain language. */
class FlashHubStatusMathTest {

    private val now = 1_000_000_000L

    private fun status(state: HubLinkState, block: HubStatus.() -> HubStatus = { this }) =
        HubStatus(state = state, hubName = "Acme", hubHost = "hub.acme.example", certDaysLeft = 20).block()

    @Test
    fun everyStateHasADotAndATextAndOnlyTroubleStatesHaveABanner() {
        val bannerStates = setOf(
            HubLinkState.REVOKED, HubLinkState.SERVER_MISMATCH, HubLinkState.CERT_EXPIRED,
            HubLinkState.CERT_EXPIRING, HubLinkState.OFFLINE,
        )
        for (state in HubLinkState.entries) {
            val s = status(state)
            assertTrue(FlashHubStatusMath.statusText(s, now).isNotBlank(), "text for $state")
            val banner = FlashHubStatusMath.banner(s, now)
            assertEquals(state in bannerStates, banner != null, "banner presence for $state")
        }
    }

    @Test
    fun dotColoursFollowSeverity() {
        assertEquals(FlashHubDot.GOOD, FlashHubStatusMath.dot(status(HubLinkState.ONLINE)))
        assertEquals(FlashHubDot.PROGRESS, FlashHubStatusMath.dot(status(HubLinkState.CONNECTING)))
        assertEquals(FlashHubDot.WARNING, FlashHubStatusMath.dot(status(HubLinkState.OFFLINE)))
        assertEquals(FlashHubDot.BAD, FlashHubStatusMath.dot(status(HubLinkState.REVOKED)))
        assertEquals(FlashHubDot.BAD, FlashHubStatusMath.dot(status(HubLinkState.SERVER_MISMATCH)))
        assertEquals(FlashHubDot.NEUTRAL, FlashHubStatusMath.dot(status(HubLinkState.NOT_ENROLLED)))
    }

    @Test
    fun offlineTextNamesTheReasonAndTheRetryTime() {
        val s = status(HubLinkState.OFFLINE) { copy(offlineReason = HubOfflineReason.NO_NETWORK, nextRetryAtMs = now + 20_000L) }
        assertEquals("Offline, no connection. Trying again in 20 seconds", FlashHubStatusMath.statusText(s, now))
        val b = FlashHubStatusMath.banner(s, now)
        assertNotNull(b)
        assertEquals(FlashHubBannerAction.RETRY_NOW, b.primary)
        assertEquals("Retry now", b.primaryLabel)
    }

    @Test
    fun retryTextScalesFromSecondsToHours() {
        assertNull(FlashHubStatusMath.retryText(null, now))
        assertEquals("Trying again now", FlashHubStatusMath.retryText(now - 5L, now))
        assertEquals("Trying again in 45 seconds", FlashHubStatusMath.retryText(now + 45_000L, now))
        assertEquals("Trying again in 5 minutes", FlashHubStatusMath.retryText(now + 300_000L, now))
        assertEquals("Trying again in 2 hours", FlashHubStatusMath.retryText(now + 2 * 3_600_000L, now))
    }

    @Test
    fun revokedBannerOffersReEnrolAndCanBeDismissed() {
        val b = FlashHubStatusMath.banner(status(HubLinkState.REVOKED), now)
        assertNotNull(b)
        assertEquals(FlashHubBannerAction.RE_ENROL, b.primary)
        assertTrue(b.dismissible)
        assertTrue(b.body.contains("paired with directly"), "tells the user that direct chats still work")
    }

    @Test
    fun certificateWordingIsInDays() {
        assertEquals("Unknown", FlashHubStatusMath.certText(null))
        assertEquals("Ends today", FlashHubStatusMath.certText(0))
        assertEquals("1 day left", FlashHubStatusMath.certText(1))
        assertEquals("20 days left", FlashHubStatusMath.certText(20))
        val expiring = FlashHubStatusMath.banner(status(HubLinkState.CERT_EXPIRING) { copy(certDaysLeft = 3) }, now)
        assertNotNull(expiring)
        assertTrue(expiring.body.startsWith("3 days left"))
    }

    @Test
    fun lastConnectedReadsNaturally() {
        assertEquals("Not yet", FlashHubStatusMath.lastConnectedText(null, now))
        assertEquals("Just now", FlashHubStatusMath.lastConnectedText(now - 10_000L, now))
        assertEquals("5 minutes ago", FlashHubStatusMath.lastConnectedText(now - 300_000L, now))
        assertEquals("3 hours ago", FlashHubStatusMath.lastConnectedText(now - 3 * 3_600_000L, now))
        assertEquals("2 days ago", FlashHubStatusMath.lastConnectedText(now - 2 * 86_400_000L, now))
    }

    @Test
    fun detailSheetCarriesTheFingerprintButTheCardTextDoesNot() {
        val s = status(HubLinkState.ONLINE) { copy(rootFingerprintShort = "AB12 CD34 EF56 7890", role = "member", department = "Ops") }
        val lines = FlashHubStatusMath.detailLines(s, now)
        assertEquals("AB12 CD34 EF56 7890", lines.first { it.label == "Organisation key" }.value)
        assertFalse(FlashHubStatusMath.statusText(s, now).contains("AB12"))
        assertTrue(lines.any { it.label == "Hub address" && it.value == "hub.acme.example" })
    }

    @Test
    fun sealingLinesNameTheKeyStateAndShowCountsOnlyWhenNotZero() {
        val s = status(HubLinkState.ONLINE)
        assertTrue(FlashHubStatusMath.detailLines(s, now).none { it.label == "Private messages" }, "no sealing section without a host seam")

        val ready = FlashHubStatusMath.detailLines(s, now, FlashHubSealingUi(HubSealingKeyState.PUBLISHED))
        assertTrue(ready.first { it.label == "Private messages" }.value.startsWith("Ready"))
        assertTrue(ready.none { it.label.startsWith("Messages not") }, "zero counts stay quiet")

        val counts = FlashHubStatusMath.detailLines(s, now, FlashHubSealingUi(HubSealingKeyState.PUBLISHING, unverified = 2, readableOff = 3))
        assertTrue(counts.first { it.label == "Messages not opened" }.value.startsWith("2"))
        assertTrue(counts.first { it.label == "Messages not accepted" }.value.startsWith("3"))
    }

    @Test
    fun everySealingStateReadsDifferentlyWithoutJargon() {
        val texts = HubSealingKeyState.entries.map { FlashHubStatusMath.sealingStateText(it) }
        assertTrue(texts.all { it.isNotBlank() })
        for (t in texts) for (b in listOf("409", "429", "HTTP", "P-256", "SPKI", "PKCS")) assertFalse(t.contains(b), "no '$b' in: $t")
    }

    @Test
    fun aPendingLeaveIsExplained() {
        val s = status(HubLinkState.NOT_ENROLLED) { copy(pendingUnenrol = true) }
        assertTrue(FlashHubStatusMath.detailLines(s, now).any { it.label == "Leaving" })
    }

    @Test
    fun everyEnrolFailureHasADistinctPlainSentenceWithoutJargon() {
        val texts = EnrollFailureReason.entries.map { FlashHubStatusMath.enrollFailureText(it) }
        assertEquals(texts.size, texts.toSet().size, "each reason reads differently")
        val banned = listOf("HTTP", "TLS", "JSON", "500", "401", "409", "certificate chain", "SPKI", "bearer", "token")
        for (t in texts) {
            assertTrue(t.length in 20..170, "sentence length: $t")
            for (b in banned) assertFalse(t.contains(b, ignoreCase = true), "no jargon '$b' in: $t")
        }
    }

    @Test
    fun anUnpinnedLinkIsExplainedAsNotKnowingTheOrganisation() {
        assertTrue(FlashHubStatusMath.enrollFailureText(EnrollFailureReason.INVITE_NOT_PINNED).contains("which organisation"))
        assertTrue(FlashHubStatusMath.enrollFailureText(EnrollFailureReason.WRONG_ORGANISATION).contains("Nothing was saved"))
    }

    @Test
    fun leavingTheOrganisationIsReportedHonestly() {
        assertTrue(FlashHubStatusMath.unenrolText(UnenrolResult.DONE_HUB_NOT_TOLD).contains("will be told"))
        assertTrue(FlashHubStatusMath.unenrolText(UnenrolResult.STORAGE_FAILED).contains("Try again"))
    }

    @Test
    fun relinkOfferCountsGroups() {
        assertEquals("Restore 1 group you had linked to this organisation?", FlashHubStatusMath.relinkOfferText(1))
        assertEquals("Restore 4 groups you had linked to this organisation?", FlashHubStatusMath.relinkOfferText(4))
    }

    @Test
    fun theContentDisclosureSaysTheHubCanRead() {
        assertTrue(FlashHubStatusMath.CONTENT_VIA_HUB_DISCLOSURE.contains("read"))
        assertTrue(FlashHubStatusMath.CONTENT_VIA_HUB_SUBTITLE.contains("can read"))
    }

    @Test
    fun groupBadgesShowCountApprovalAndForeignDepartment() {
        val g = EnterpriseDirectoryGroup(
            groupId = "g1", name = "Night shift", department = "Logistics", visibility = "ORG",
            requiresApproval = true, memberCount = 12, onlineCount = 3, pendingCount = 2,
        )
        val badges = FlashHubStatusMath.groupBadges(g, myDepartment = "Ops")
        assertEquals("12 members, 3 online", badges.memberText)
        assertEquals("Needs approval", badges.approvalText)
        assertEquals("Logistics", badges.departmentText)
        assertEquals("2 requests waiting", badges.adminPendingText)
        val mine = FlashHubStatusMath.groupBadges(g.copy(department = "ops", memberCount = 1, onlineCount = null, pendingCount = null, requiresApproval = false), "Ops")
        assertEquals("1 member", mine.memberText)
        assertNull(mine.approvalText)
        assertNull(mine.departmentText)
        assertNull(mine.adminPendingText)
    }

    @Test
    fun joinButtonLabelFollowsMembershipAndApproval() {
        assertEquals("Join", FlashHubStatusMath.joinButtonLabel(requiresApproval = false, joined = false, pending = false))
        assertEquals("Ask to join", FlashHubStatusMath.joinButtonLabel(true, false, false))
        assertEquals("Waiting for approval", FlashHubStatusMath.joinButtonLabel(true, false, true))
        assertEquals("Joined", FlashHubStatusMath.joinButtonLabel(true, true, true))
    }

    @Test
    fun ORGT_U24_orgTrustState_maps_all_lease_and_clock_scenarios() {
        val nowMs = 1_000_000_000L
        val validUntil = nowMs + 10 * 86_400_000L // 10 days left

        // 1. Hub unsupported
        val unsupported = FlashHubStatusMath.orgTrustState(
            lease = OrgLease.Valid(validUntil),
            clockOk = true,
            hubSupports = false,
            nowMs = nowMs,
        )
        assertEquals(FlashHubStatusMath.OrgTrustState.NotSupported, unsupported)

        // 2. Clock wrong
        val clockWrong = FlashHubStatusMath.orgTrustState(
            lease = OrgLease.Valid(validUntil),
            clockOk = false,
            hubSupports = true,
            nowMs = nowMs,
        )
        assertEquals(FlashHubStatusMath.OrgTrustState.ClockWrong, clockWrong)

        // 3. Never synced
        val neverSynced = FlashHubStatusMath.orgTrustState(
            lease = OrgLease.None,
            clockOk = true,
            hubSupports = true,
            nowMs = nowMs,
        )
        assertEquals(FlashHubStatusMath.OrgTrustState.NeverSynced, neverSynced)

        // 4. Valid with > 3 days left
        val valid = FlashHubStatusMath.orgTrustState(
            lease = OrgLease.Valid(validUntil),
            clockOk = true,
            hubSupports = true,
            nowMs = nowMs,
        )
        assertEquals(FlashHubStatusMath.OrgTrustState.Valid(validUntil), valid)

        // 5. Expiring with <= 3 days left
        val expiringUntil = nowMs + 2 * 86_400_000L // 2 days left
        val expiring = FlashHubStatusMath.orgTrustState(
            lease = OrgLease.Valid(expiringUntil),
            clockOk = true,
            hubSupports = true,
            nowMs = nowMs,
        )
        assertEquals(FlashHubStatusMath.OrgTrustState.Expiring(expiringUntil, daysLeft = 2), expiring)

        // 6. Expired by TIME
        val expiredTime = FlashHubStatusMath.orgTrustState(
            lease = OrgLease.Expired(sinceMs = nowMs - 1000L, reason = ExpiredReason.TIME),
            clockOk = true,
            hubSupports = true,
            nowMs = nowMs,
        )
        assertEquals(FlashHubStatusMath.OrgTrustState.Expired, expiredTime)

        // 7. Expired by CLOCK rollback
        val expiredClock = FlashHubStatusMath.orgTrustState(
            lease = OrgLease.Expired(sinceMs = nowMs, reason = ExpiredReason.CLOCK),
            clockOk = true,
            hubSupports = true,
            nowMs = nowMs,
        )
        assertEquals(FlashHubStatusMath.OrgTrustState.ClockWrong, expiredClock)
    }

    @Test
    fun orgTrustStatusTextAndDotMatchesPlanSection9Strings() {
        val validState = FlashHubStatusMath.OrgTrustState.Valid(1_700_000_000_000L)
        assertEquals("Colleague trust valid until Nov 14", FlashHubStatusMath.orgTrustStatusText(validState) { "Nov 14" })
        assertEquals(FlashHubDot.GOOD, FlashHubStatusMath.orgTrustDot(validState))

        val expiringState = FlashHubStatusMath.OrgTrustState.Expiring(1_700_000_000_000L, daysLeft = 3)
        assertEquals("Connect to the company network within 3 days to keep chatting with colleagues offline.", FlashHubStatusMath.orgTrustStatusText(expiringState))
        assertEquals(FlashHubDot.WARNING, FlashHubStatusMath.orgTrustDot(expiringState))

        val expiredState = FlashHubStatusMath.OrgTrustState.Expired
        assertEquals("Reconnect to your company network to chat with colleagues who are not paired with you.", FlashHubStatusMath.orgTrustStatusText(expiredState))
        assertEquals(FlashHubDot.BAD, FlashHubStatusMath.orgTrustDot(expiredState))

        val neverSyncedState = FlashHubStatusMath.OrgTrustState.NeverSynced
        assertEquals("Connect to the company network once to load your colleagues.", FlashHubStatusMath.orgTrustStatusText(neverSyncedState))
        assertEquals(FlashHubDot.WARNING, FlashHubStatusMath.orgTrustDot(neverSyncedState))

        val unsupportedState = FlashHubStatusMath.OrgTrustState.NotSupported
        assertEquals("This company server does not support offline colleague chat yet.", FlashHubStatusMath.orgTrustStatusText(unsupportedState))
        assertEquals(FlashHubDot.NEUTRAL, FlashHubStatusMath.orgTrustDot(unsupportedState))

        val clockWrongState = FlashHubStatusMath.OrgTrustState.ClockWrong
        assertEquals("Your device clock looks wrong. Colleague trust may fail until it is fixed.", FlashHubStatusMath.orgTrustStatusText(clockWrongState))
        assertEquals(FlashHubDot.BAD, FlashHubStatusMath.orgTrustDot(clockWrongState))
    }

    @Test
    fun ORGT_U61_orgTrustState_identityMismatch_and_banner() {
        val nowMs = 1_000_000_000L
        val validUntil = nowMs + 10 * 86_400_000L

        // 8. Identity mismatch
        val mismatch = FlashHubStatusMath.orgTrustState(
            lease = OrgLease.Valid(validUntil),
            clockOk = true,
            hubSupports = true,
            identityMismatch = true,
            nowMs = nowMs,
        )
        assertEquals(FlashHubStatusMath.OrgTrustState.IdentityMismatch, mismatch)

        assertEquals(
            "Your device identity changed. Rejoin your organisation to chat with colleagues again.",
            FlashHubStatusMath.orgTrustStatusText(FlashHubStatusMath.OrgTrustState.IdentityMismatch),
        )
        assertEquals(FlashHubDot.BAD, FlashHubStatusMath.orgTrustDot(FlashHubStatusMath.OrgTrustState.IdentityMismatch))

        val banner = FlashHubStatusMath.orgTrustBanner(FlashHubStatusMath.OrgTrustState.IdentityMismatch)
        kotlin.test.assertNotNull(banner)
        assertEquals("Your device identity changed", banner.title)
        assertEquals(
            "Your device identity changed. Rejoin your organisation to chat with colleagues again.",
            banner.body,
        )
        assertEquals(FlashHubBannerAction.RE_ENROL, banner.primary)
        assertEquals("Re-enrol", banner.primaryLabel)
    }
}

