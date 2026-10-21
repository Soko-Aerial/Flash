@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.auth

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.certs.RenewalPolicy
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SRVHUB-15 (ADR-222, audit section 8.2): a device certificate lives 30 days by default, is adjustable, and renewal scales with it. */
class CertificateLifetimeTest {

    private val day = 86_400_000L
    private val now = System.currentTimeMillis()
    private val root = FlashCrypto.generateEcKeyPair()
    private val delegate = FlashCrypto.generateEcKeyPair()

    private fun delegation(days: Long = 400) = DelegationCert.issue(
        rootPrivateKey = root.private,
        rootPublicKey = root.toBase64Spki(),
        delegatePublicKey = delegate.toBase64Spki(),
        scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
        validFrom = now - 60_000,
        validTo = now + days * day,
        maxDevices = 100,
        serial = 1L,
    )

    private fun service(
        certValidityDays: Int,
        delegationDays: Long = 400,
        earlyDays: Int? = null,
        graceDays: Int? = null,
    ) = EnrollmentService(
        rootPublicKey = root.toBase64Spki(),
        serverPrivateKey = delegate.private,
        delegationCert = delegation(delegationDays),
        certValidityMs = certValidityDays * day,
        renewEarlyWindowMs = earlyDays?.let { it * day },
        renewGraceMs = graceDays?.let { it * day },
    )

    private fun enrol(service: EnrollmentService, deviceId: String = "dev-a"): DeviceCert {
        val key = FlashCrypto.generateEcKeyPair()
        val token = service.generateInviteLink("https://hub.test", InviteScope(department = "eng", expiry = now + 60_000), now)
            .substringAfter("token=").substringBefore("&")
        val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(token, deviceId, key.toBase64Spki(), service.serverId))
        return service.enrollDevice(token, deviceId, key.toBase64Spki(), proof, currentTimeMs = now).getOrThrow().deviceCert
    }

    @Test
    fun theConfiguredDefaultIsThirtyDaysAndTheBoundsAreEnforced() {
        assertEquals(30, ServerConfig().deviceCertValidityDays)
        assertEquals(30, ServerConfig.fromEnvironment(emptyArray(), emptyMap()).deviceCertValidityDays)
        assertEquals(90, ServerConfig.fromEnvironment(emptyArray(), mapOf("FLASH_DEVICE_CERT_VALIDITY_DAYS" to "90")).deviceCertValidityDays)
        assertEquals(14, ServerConfig.fromEnvironment(arrayOf("--device-cert-validity-days=14"), emptyMap()).deviceCertValidityDays)
        assertFailsWith<IllegalArgumentException> { ServerConfig(deviceCertValidityDays = 0) }
        assertFailsWith<IllegalArgumentException> { ServerConfig(deviceCertValidityDays = 366) }
        assertFailsWith<IllegalArgumentException> { ServerConfig(renewEarlyDays = 0) }
        assertFailsWith<IllegalArgumentException> { ServerConfig(renewGraceDays = 400) }
        val e = assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(emptyArray(), mapOf("FLASH_DEVICE_CERT_VALIDITY_DAYS" to "a month"))
        }
        assertTrue(e.message!!.contains("FLASH_DEVICE_CERT_VALIDITY_DAYS"), "the message names the setting: ${e.message}")
        val overrides = ServerConfig.fromEnvironment(emptyArray(), mapOf("FLASH_RENEW_EARLY_DAYS" to "5", "FLASH_RENEW_GRACE_DAYS" to "9"))
        assertEquals(5, overrides.renewEarlyDays)
        assertEquals(9, overrides.renewGraceDays)
        assertNull(ServerConfig().renewEarlyDays)
    }

    @Test
    fun aNewCertificateCarriesTheConfiguredLifetime() {
        for (days in listOf(7, 30, 365)) {
            val cert = enrol(service(days))
            assertEquals(now + days * day, cert.validTo, "lifetime of $days days")
        }
    }

    @Test
    fun theLeafIsStillCutShortAtTheEndOfTheDelegation() {
        val cert = enrol(service(certValidityDays = 30, delegationDays = 10))
        assertEquals(now + 10 * day, cert.validTo, "clamped to the 10-day delegation, not 30 days")
    }

    @Test
    fun theRenewalWindowsScaleWithTheLifetimeAndKeepTodaysValuesForSevenDays() {
        assertEquals(1 * day, RenewalPolicy.earlyWindowFor(7 * day))
        assertEquals(3 * day, RenewalPolicy.graceFor(7 * day))
        assertEquals(3 * day, RenewalPolicy.earlyWindowFor(30 * day))
        assertEquals(3 * day, RenewalPolicy.graceFor(30 * day))
        assertEquals(36 * day + 12 * 3_600_000L, RenewalPolicy.earlyWindowFor(365 * day))
        assertEquals(18 * day + 6 * 3_600_000L, RenewalPolicy.graceFor(365 * day))
    }

    @Test
    fun aThirtyDayCertificateRenewsInItsDerivedWindowOnly() {
        val service = service(certValidityDays = 30)
        val cert = enrol(service)
        assertTrue(service.renew(cert, cert.validTo - 4 * day).isFailure, "four days out is before the 3-day window")
        assertTrue(service.renew(cert, cert.validTo - 2 * day).isSuccess, "inside the window")
        assertTrue(service.renew(cert, cert.validTo + 2 * day).isSuccess, "inside the grace")
        assertTrue(service.renew(cert, cert.validTo + 4 * day).isFailure, "past the grace")
    }

    @Test
    fun anExplicitOverrideWinsOverTheDerivedWindow() {
        val service = service(certValidityDays = 30, earlyDays = 10, graceDays = 1)
        val cert = enrol(service)
        assertTrue(service.renew(cert, cert.validTo - 9 * day).isSuccess)
        assertTrue(service.renew(cert, cert.validTo + 2 * day).isFailure)
    }

    @Test
    fun aClientThatOnlyKnowsTheDefaultWindowsStillRenewsAThirtyDayCertificate() {
        val service = service(certValidityDays = 30)
        val cert = enrol(service)
        // the unchanged client starts trying one day before validTo (HubContract.RENEW_EARLY_WINDOW_MS)
        val clientStart = cert.validTo - day
        assertTrue(RenewalPolicy.shouldRenew(cert, clientStart))
        assertTrue(service.renew(cert, clientStart).isSuccess, "the hub's 3-day window contains the client's 1-day one")
    }

    @Test
    fun theStartupWarningAppearsOnlyWhenTheDelegationIsShorterThanLifetimePlusGrace() {
        assertNull(service(certValidityDays = 30, delegationDays = 90).delegationLifetimeWarning(now))
        assertNull(service(certValidityDays = 30, delegationDays = 34).delegationLifetimeWarning(now))
        val warning = assertNotNull(service(certValidityDays = 30, delegationDays = 20).delegationLifetimeWarning(now))
        assertTrue(warning.contains("33"), "names the smallest sufficient --valid-days (30 + 3 grace): $warning")
        assertTrue(warning.contains("delegate"), warning)
    }
}
