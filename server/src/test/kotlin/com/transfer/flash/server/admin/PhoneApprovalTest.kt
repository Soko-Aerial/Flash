package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubStepUp
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.sign
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.identity.DeviceStatus
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.storage.ServerDatabase
import java.security.KeyPair
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-131 (FO-14): with an approver phone bound, the most dangerous console actions need the phone as well as the browser key.
 * The phone's key is the registry key of an ACTIVE admin-role device, resolved on the server, over bytes of their own domain.
 */
class PhoneApprovalTest {

    private var now = 1_800_000_000_000L

    private class Realm(
        val admins: AdminAuthService,
        val identities: IdentityStore,
        val browserKey: KeyPair,
        val phoneKey: KeyPair,
    )

    private fun realm(bindApprover: Boolean = true): Realm {
        val db = ServerDatabase.inMemory()
        val admins = AdminAuthService(printSetupTokenToConsole = false, db = db, clock = { now }, passwordIterations = 1000)
        admins.addAdminDirectlyForTesting(AdminUser("boss", admins.hashPassword("BossPassword123!"), admins.generateTotpSecret()))
        val identities = IdentityStore.open(db)
        val issuer = FlashCrypto.generateEcKeyPair()
        fun enrol(id: String, key: KeyPair, role: String, token: String) {
            identities.createInvite(token, null, role, emptySet(), emptySet(), now + 100_000, 1, now)
            identities.enroll(token, id, key.toBase64Spki(), id, now, 100, 1L) { serial, _ ->
                DeviceCert.issue(issuer.private, issuer.toBase64Spki(), id, key.toBase64Spki(), role, null, now - 1000, now + 100_000, serial)
            }
        }
        val browser = FlashCrypto.generateEcKeyPair()
        val phone = FlashCrypto.generateEcKeyPair()
        enrol("dev-browser", browser, DeviceCert.ROLE_ADMIN, "tok-1")
        enrol("dev-phone", phone, DeviceCert.ROLE_ADMIN, "tok-2")
        admins.bindDevice("boss", "dev-browser")
        if (bindApprover) admins.bindApprover("boss", "dev-phone")
        return Realm(admins, identities, browser, phone)
    }

    private fun stepUp(r: Realm, ttl: Long = StepUpAuth.DEFAULT_CHALLENGE_TTL_MS) = StepUpAuth(r.admins, r.identities, clock = { now }, ttlMs = ttl)

    private fun create(s: StepUpAuth, action: String, target: String = "serial:7"): StepUpChallenge =
        assertIs<StepUpAuth.Created.Ok>(s.createChallenge("boss", action, target)).challenge

    private fun browserSig(key: KeyPair, c: StepUpChallenge): ByteArray = Base64.getDecoder().decode(FlashCrypto.sign(key.private, c.proofBytes()))
    private fun approvalSig(key: KeyPair, c: StepUpChallenge): ByteArray = Base64.getDecoder().decode(FlashCrypto.sign(key.private, c.approvalBytes()))

    @Test
    fun withoutAPhoneBoundNothingChanges() {
        val r = realm(bindApprover = false)
        val s = stepUp(r)
        val c = create(s, StepUpActions.DELEGATION_INSTALL)
        assertFalse(c.approvalRequired)
        assertTrue(s.verify(c.challengeId, browserSig(r.browserKey, c)))
        assertTrue(s.authorize(c.challengeId, "boss", StepUpActions.DELEGATION_INSTALL, "serial:7"))
    }

    @Test
    fun aDangerousActionNeedsTheBrowserKeyAndThePhone() {
        val r = realm()
        val s = stepUp(r)

        val browserOnly = create(s, StepUpActions.DELEGATION_INSTALL)
        assertTrue(browserOnly.approvalRequired)
        assertTrue(s.verify(browserOnly.challengeId, browserSig(r.browserKey, browserOnly)))
        assertFalse(s.authorize(browserOnly.challengeId, "boss", StepUpActions.DELEGATION_INSTALL, "serial:7"), "a stolen browser key alone is not enough")

        val phoneOnly = create(s, StepUpActions.DELEGATION_INSTALL)
        assertTrue(s.approve(phoneOnly.challengeId, "dev-phone", approvalSig(r.phoneKey, phoneOnly)))
        assertFalse(s.authorize(phoneOnly.challengeId, "boss", StepUpActions.DELEGATION_INSTALL, "serial:7"), "the phone alone is not enough either")

        val both = create(s, StepUpActions.DELEGATION_INSTALL)
        assertTrue(s.verify(both.challengeId, browserSig(r.browserKey, both)))
        assertTrue(s.approve(both.challengeId, "dev-phone", approvalSig(r.phoneKey, both)))
        assertTrue(s.authorize(both.challengeId, "boss", StepUpActions.DELEGATION_INSTALL, "serial:7"))
        assertFalse(s.authorize(both.challengeId, "boss", StepUpActions.DELEGATION_INSTALL, "serial:7"), "single use")
    }

    @Test
    fun everyPhoneActionIsCovered() {
        val r = realm()
        val s = stepUp(r)
        for (action in listOf(StepUpActions.DELEGATION_INSTALL, StepUpActions.ADMIN_CREATE, StepUpActions.APPROVER_CHANGE)) {
            val c = create(s, action)
            assertTrue(c.approvalRequired, action)
            assertTrue(s.verify(c.challengeId, browserSig(r.browserKey, c)))
            assertFalse(s.authorize(c.challengeId, "boss", action, "serial:7"), "$action without the phone")
        }
    }

    @Test
    fun everydayActionsStayBrowserOnly() {
        val r = realm()
        val s = stepUp(r)
        for (action in listOf(StepUpActions.REVOKE_DEVICE, StepUpActions.UNREVOKE_DEVICE, StepUpActions.SETTINGS_CHANGE, StepUpActions.ADMIN_DISABLE, StepUpActions.RECOVERY_REGENERATE)) {
            val c = create(s, action)
            assertFalse(c.approvalRequired, action)
            assertTrue(s.verify(c.challengeId, browserSig(r.browserKey, c)))
            assertTrue(s.authorize(c.challengeId, "boss", action, "serial:7"), action)
        }
    }

    @Test
    fun aBrowserProofIsNotAnApprovalAndAnApprovalIsNotABrowserProof() {
        val r = realm()
        val s = stepUp(r)
        val c = create(s, StepUpActions.DELEGATION_INSTALL)
        // The phone key signing the BROWSER bytes, and the browser key signing the APPROVAL bytes: both are wrong domains and wrong keys.
        assertFalse(s.approve(c.challengeId, "dev-phone", browserSig(r.phoneKey, c)))
        assertFalse(s.approve(c.challengeId, "dev-phone", approvalSig(r.browserKey, c)))
        assertFalse(s.verify(c.challengeId, approvalSig(r.browserKey, c)))
        assertNotEquals(HubStepUp.proofBytes(c.challengeId, c.action, "boss", c.target, c.nonce).toList(), c.approvalBytes().toList())
    }

    @Test
    fun onlyTheBoundPhoneCanApproveAndOnlyItSeesTheRequest() {
        val r = realm()
        val s = stepUp(r)
        val c = create(s, StepUpActions.ADMIN_CREATE, "newadmin")
        assertEquals(listOf(c.challengeId), s.pendingApprovals("dev-phone").map { it.challengeId })
        assertTrue(s.pendingApprovals("dev-browser").isEmpty())
        assertTrue(s.pendingApprovals("someone-else").isEmpty())
        val attacker = FlashCrypto.generateEcKeyPair()
        assertFalse(s.approve(c.challengeId, "dev-browser", approvalSig(r.browserKey, c)), "a different device, even an admin one")
        assertFalse(s.approve(c.challengeId, "dev-phone", approvalSig(attacker, c)), "the key is the registry key, not one supplied")
        assertTrue(s.approve(c.challengeId, "dev-phone", approvalSig(r.phoneKey, c)))
        assertFalse(s.approve(c.challengeId, "dev-phone", approvalSig(r.phoneKey, c)), "once")
        assertTrue(s.pendingApprovals("dev-phone").isEmpty(), "answered requests leave the list")
    }

    @Test
    fun anApprovalForOneChallengeDoesNotApproveAnother() {
        val r = realm()
        val s = stepUp(r)
        val a = create(s, StepUpActions.DELEGATION_INSTALL)
        val b = create(s, StepUpActions.DELEGATION_INSTALL)
        assertFalse(s.approve(b.challengeId, "dev-phone", approvalSig(r.phoneKey, a)))
        assertTrue(s.approve(a.challengeId, "dev-phone", approvalSig(r.phoneKey, a)))
    }

    @Test
    fun theCodeOnTheChallengeIsTheOneThePhoneShows() {
        val r = realm()
        val s = stepUp(r)
        val a = create(s, StepUpActions.DELEGATION_INSTALL)
        val b = create(s, StepUpActions.DELEGATION_INSTALL)
        assertEquals(HubStepUp.approvalCode(a.challengeId, a.nonce), a.approvalCode)
        assertNotEquals(a.approvalCode, b.approvalCode)
        assertEquals(setOf(a.approvalCode, b.approvalCode), s.pendingApprovals("dev-phone").map { it.approvalCode }.toSet())
    }

    @Test
    fun aRevokedPhoneFailsClosedInsteadOfFallingBackToTheBrowserKey() {
        val r = realm()
        val s = stepUp(r)
        val open = create(s, StepUpActions.DELEGATION_INSTALL)
        assertTrue(r.identities.setStatus("dev-phone", DeviceStatus.REVOKED, now))
        assertFalse(s.approve(open.challengeId, "dev-phone", approvalSig(r.phoneKey, open)), "a revoked phone can not approve")
        assertEquals("ERR_APPROVER_UNAVAILABLE", assertIs<StepUpAuth.Created.Refused>(s.createChallenge("boss", StepUpActions.DELEGATION_INSTALL, "serial:7")).code)
        // Everyday actions are unaffected, and a recovery code (the route) unbinds the phone.
        assertIs<StepUpAuth.Created.Ok>(s.createChallenge("boss", StepUpActions.REVOKE_DEVICE, "x"))
        r.admins.clearApprover("boss")
        assertIs<StepUpAuth.Created.Ok>(s.createChallenge("boss", StepUpActions.DELEGATION_INSTALL, "serial:7"))
    }

    @Test
    fun unbindingThePhoneKillsItsOpenRequests() {
        val r = realm()
        val s = stepUp(r)
        val c = create(s, StepUpActions.DELEGATION_INSTALL)
        r.admins.clearApprover("boss")
        assertFalse(s.approve(c.challengeId, "dev-phone", approvalSig(r.phoneKey, c)))
    }

    @Test
    fun anApprovalExpiresWithItsChallenge() {
        val r = realm()
        val s = stepUp(r, ttl = 1000)
        val c = create(s, StepUpActions.DELEGATION_INSTALL)
        now += 1001
        assertFalse(s.approve(c.challengeId, "dev-phone", approvalSig(r.phoneKey, c)))
        assertTrue(s.pendingApprovals("dev-phone").isEmpty())
    }

    @Test
    fun replacingTheSignerNeedsOnlyThePhoneBecauseTheBrowserKeyMayBeTheLostOne() {
        val r = realm()
        val s = stepUp(r)
        val c = create(s, StepUpActions.SIGNER_REPLACE, "console-key")
        assertTrue(c.approvalRequired)
        assertFalse(s.authorize(c.challengeId, "boss", StepUpActions.SIGNER_REPLACE, "console-key"), "not yet approved")
        val d = create(s, StepUpActions.SIGNER_REPLACE, "console-key")
        assertTrue(s.approve(d.challengeId, "dev-phone", approvalSig(r.phoneKey, d)))
        assertTrue(s.authorize(d.challengeId, "boss", StepUpActions.SIGNER_REPLACE, "console-key"))
    }

    @Test
    fun replacingTheSignerIsRefusedWhenThereIsNoPhone() {
        val r = realm(bindApprover = false)
        assertEquals("ERR_NO_APPROVER", assertIs<StepUpAuth.Created.Refused>(stepUp(r).createChallenge("boss", StepUpActions.SIGNER_REPLACE, "console-key")).code)
    }

    @Test
    fun theApproverIsStoredPerAdministratorAndSurvivesReloads() {
        val r = realm()
        assertEquals("dev-phone", r.admins.approverOf("boss"))
        assertEquals("dev-phone", r.admins.getAdmin("boss")!!.approverDeviceId)
        r.admins.clearApprover("boss")
        assertEquals(null, r.admins.approverOf("boss"))
    }
}
