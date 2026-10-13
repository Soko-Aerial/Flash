@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.enterprise.relay.RelayFailure
import com.transfer.flash.core.enterprise.relay.RelayFailureKind
import com.transfer.flash.core.enterprise.relay.RelaySessionState
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubStatusMathTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun creds(validTo: Long = now + 30 * day, url: String = "https://hub.example.test:8443") = EnrollmentCredentials(
        hubUrl = url, rootFingerprint = "ab12cd34ef567890aabbccdd", rootPublicKey = "k",
        deviceCert = DeviceCert("dev-1", "s", "member", "Ops", now - day, validTo, 5L, "i", "sig"),
        hubName = "Acme Hub",
    )

    private fun inputs(
        credentials: EnrollmentCredentials? = creds(),
        relayState: RelaySessionState = RelaySessionState.CONNECTED,
        relayFailure: RelayFailure? = null,
        authFailure: HubAuthFailure? = null,
        renewalFailure: EnrollFailureReason? = null,
        nextRetryAtMs: Long? = null,
        revokedNotice: Boolean = false,
        pendingUnenrol: Boolean = false,
    ) = HubStatusInputs(credentials, relayState, relayFailure, authFailure, renewalFailure, nextRetryAtMs, null, revokedNotice, pendingUnenrol, null, now)

    private fun derive(i: HubStatusInputs) = HubStatusMath.derive(i)

    @Test
    fun notEnrolledAndOnlineAndTheDisplayFields() {
        assertEquals(HubLinkState.NOT_ENROLLED, derive(inputs(credentials = null)).state)
        val online = derive(inputs())
        assertEquals(HubLinkState.ONLINE, online.state)
        assertEquals("Acme Hub", online.hubName)
        assertEquals("hub.example.test:8443", online.hubHost)
        assertEquals("AB12 CD34 EF56 7890", online.rootFingerprintShort)
        assertEquals(30, online.certDaysLeft)
        assertEquals("Ops", online.department)
        assertTrue(online.isEnrolled)
        assertFalse(online.needsAttention)
    }

    @Test
    fun revokedBeatsEveryOtherCauseAndSurvivesTheWipe() {
        val failure = RelayFailure(RelayFailureKind.REVOKED)
        assertEquals(HubLinkState.REVOKED, derive(inputs(relayState = RelaySessionState.REVOKED, relayFailure = failure)).state)
        assertEquals(HubLinkState.REVOKED, derive(inputs(credentials = null, revokedNotice = true)).state)
        assertEquals(HubLinkState.REVOKED, derive(inputs(authFailure = HubAuthFailure.REVOKED)).state)
        assertNull(derive(inputs(relayState = RelaySessionState.REVOKED, nextRetryAtMs = 5L)).nextRetryAtMs)
    }

    @Test
    fun serverMismatchAndExpiryHavePrecedenceOverTheRelayState() {
        assertEquals(
            HubLinkState.SERVER_MISMATCH,
            derive(inputs(relayState = RelaySessionState.SUSPENDED, relayFailure = RelayFailure(RelayFailureKind.SERVER_MISMATCH))).state,
        )
        assertEquals(HubLinkState.SERVER_MISMATCH, derive(inputs(authFailure = HubAuthFailure.SERVER_MISMATCH)).state)
        assertEquals(HubLinkState.CERT_EXPIRED, derive(inputs(credentials = creds(validTo = now - day), relayState = RelaySessionState.DISCONNECTED)).state)
        assertEquals(0, derive(inputs(credentials = creds(validTo = now - day))).certDaysLeft)
    }

    @Test
    fun expiringIsShownOnlyWhenRenewalIsDueAndFailing() {
        val soon = creds(validTo = now + 12 * 3_600_000L)
        assertEquals(HubLinkState.ONLINE, derive(inputs(credentials = soon)).state)
        val failing = derive(inputs(credentials = soon, renewalFailure = EnrollFailureReason.UNREACHABLE))
        assertEquals(HubLinkState.CERT_EXPIRING, failing.state)
        assertTrue(failing.renewalFailed)
        // A stale failure far from the window does not alarm anyone.
        assertEquals(HubLinkState.ONLINE, derive(inputs(renewalFailure = EnrollFailureReason.UNREACHABLE)).state)
    }

    @Test
    fun offlineCarriesAPlainReason() {
        fun reason(kind: RelayFailureKind) =
            derive(inputs(relayState = RelaySessionState.SUSPENDED, relayFailure = RelayFailure(kind), nextRetryAtMs = now + 5_000L))
        assertEquals(HubOfflineReason.NO_NETWORK, reason(RelayFailureKind.NETWORK).offlineReason)
        assertEquals(HubOfflineReason.HUB_NOT_ANSWERING, reason(RelayFailureKind.KEEPALIVE_TIMEOUT).offlineReason)
        assertEquals(HubOfflineReason.REFUSED, reason(RelayFailureKind.AUTH_REJECTED).offlineReason)
        assertEquals(HubOfflineReason.UPDATE_NEEDED, reason(RelayFailureKind.UNSUPPORTED_VERSION).offlineReason)
        assertEquals(HubOfflineReason.KICKED, reason(RelayFailureKind.KICKED).offlineReason)
        assertEquals(now + 5_000L, reason(RelayFailureKind.NETWORK).nextRetryAtMs)
        assertEquals(HubLinkState.DISCONNECTED, derive(inputs(relayState = RelaySessionState.DISCONNECTED)).state)
        assertEquals(HubLinkState.CONNECTING, derive(inputs(relayState = RelaySessionState.CONNECTING)).state)
        assertEquals(HubLinkState.AUTHENTICATING, derive(inputs(relayState = RelaySessionState.AUTHENTICATING)).state)
    }

    @Test
    fun pendingUnenrolIsShownWhileNotEnrolled() {
        val s = derive(inputs(credentials = null, pendingUnenrol = true))
        assertEquals(HubLinkState.NOT_ENROLLED, s.state)
        assertTrue(s.pendingUnenrol)
    }

    @Test
    fun shortFingerprintAndDays() {
        assertEquals("AB12 CD34", HubStatusMath.shortFingerprint("ab12cd34").take(9))
        assertEquals(3, HubStatusMath.daysLeft(now + 3 * day + 5, now))
        assertEquals(0, HubStatusMath.daysLeft(now - 5, now))
    }
}
