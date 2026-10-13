package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubStepUp
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.SqliteRevocationService
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
import kotlin.test.assertTrue

/** SEC-12: dangerous console actions need a proof from the administrator's enrolled device; the key comes from the server. */
class StepUpAuthTest {

    private var now = 1_800_000_000_000L

    private class Realm(val admins: AdminAuthService, val identities: IdentityStore, val deviceKey: KeyPair, val db: ServerDatabase)

    private fun realm(role: String = DeviceCert.ROLE_ADMIN, bind: Boolean = true): Realm {
        val db = ServerDatabase.inMemory()
        val admins = AdminAuthService(printSetupTokenToConsole = false, db = db, clock = { now }, passwordIterations = 1000)
        admins.addAdminDirectlyForTesting(AdminUser("boss", admins.hashPassword("BossPassword123!"), admins.generateTotpSecret()))
        val identities = IdentityStore.open(db)
        val deviceKey = FlashCrypto.generateEcKeyPair()
        val issuer = FlashCrypto.generateEcKeyPair()
        identities.createInvite("tok-1", null, role, emptySet(), emptySet(), now + 100_000, 1, now)
        identities.enroll("tok-1", "dev-boss", deviceKey.toBase64Spki(), "Boss phone", now, 100, 1L) { serial, _ ->
            DeviceCert.issue(issuer.private, issuer.toBase64Spki(), "dev-boss", deviceKey.toBase64Spki(), role, null, now - 1000, now + 100_000, serial)
        }
        if (bind) admins.bindDevice("boss", "dev-boss")
        return Realm(admins, identities, deviceKey, db)
    }

    private fun stepUp(r: Realm, revocations: com.transfer.flash.server.auth.RevocationService = com.transfer.flash.server.auth.RevocationService.None, ttl: Long = StepUpAuth.DEFAULT_CHALLENGE_TTL_MS) =
        StepUpAuth(r.admins, r.identities, revocations, clock = { now }, ttlMs = ttl)

    private fun create(s: StepUpAuth, action: String = StepUpActions.REVOKE_DEVICE, target: String = "dev-victim"): StepUpChallenge =
        assertIs<StepUpAuth.Created.Ok>(s.createChallenge("boss", action, target)).challenge

    private fun sign(key: KeyPair, c: StepUpChallenge): ByteArray =
        Base64.getDecoder().decode(FlashCrypto.sign(key.private, c.proofBytes()))

    @Test
    fun aProofSignedByTheEnrolledDeviceAuthorisesExactlyOneAction() {
        val r = realm()
        val s = stepUp(r)
        val c = create(s)
        assertEquals(HubStepUp.proofBytes(c.challengeId, "device.revoke", "boss", "dev-victim", c.nonce).toList(), c.proofBytes().toList())
        assertFalse(s.authorize(c.challengeId, "boss", "device.revoke", "dev-victim"), "not verified yet")
        // the failed attempt consumed it: start over
        val c2 = create(s)
        assertTrue(s.verify(c2.challengeId, sign(r.deviceKey, c2)))
        assertTrue(s.authorize(c2.challengeId, "boss", "device.revoke", "dev-victim"))
        assertFalse(s.authorize(c2.challengeId, "boss", "device.revoke", "dev-victim"), "single use")
    }

    @Test
    fun theVerifyingKeyIsTheRegistryKeyNotAnythingTheClientSupplies() {
        val r = realm()
        val s = stepUp(r)
        val c = create(s)
        val attacker = FlashCrypto.generateEcKeyPair()
        assertFalse(s.verify(c.challengeId, sign(attacker, c)), "a signature by any other key must fail")
        assertFalse(s.verifyBase64(c.challengeId, "not base64 !!"))
        assertFalse(s.authorize(c.challengeId, "boss", "device.revoke", "dev-victim"))
    }

    @Test
    fun aProofIsBoundToTheActionTheTargetAndTheAdministrator() {
        val r = realm()
        val s = stepUp(r)
        for ((action, target, admin) in listOf(
            Triple("device.unrevoke", "dev-victim", "boss"),
            Triple("device.revoke", "dev-other", "boss"),
            Triple("device.revoke", "dev-victim", "someone-else"),
        )) {
            val c = create(s)
            assertTrue(s.verify(c.challengeId, sign(r.deviceKey, c)))
            assertFalse(s.authorize(c.challengeId, admin, action, target), "$action/$target/$admin")
            assertFalse(s.authorize(c.challengeId, "boss", "device.revoke", "dev-victim"), "a failed attempt burns the challenge")
        }
    }

    @Test
    fun aSignatureForOneChallengeDoesNotVerifyAnother() {
        val r = realm()
        val s = stepUp(r)
        val a = create(s)
        val b = create(s)
        assertFalse(s.verify(b.challengeId, sign(r.deviceKey, a)))
        assertTrue(s.verify(a.challengeId, sign(r.deviceKey, a)))
        assertFalse(s.verify(a.challengeId, sign(r.deviceKey, a)), "an already verified challenge is not verified twice")
    }

    @Test
    fun challengesExpireAndAreEvicted() {
        val r = realm()
        val s = stepUp(r, ttl = 1000)
        val c = create(s)
        now += 1001
        assertFalse(s.verify(c.challengeId, sign(r.deviceKey, c)))
        assertEquals(0, s.outstanding())
    }

    @Test
    fun noUsableDeviceMeansNoStepUp() {
        val unbound = realm(bind = false)
        assertFalse(stepUp(unbound).hasUsableDevice("boss"))
        assertEquals("ERR_NO_DEVICE", assertIs<StepUpAuth.Created.Refused>(stepUp(unbound).createChallenge("boss", StepUpActions.REVOKE_DEVICE, "x")).code)

        val member = realm(role = DeviceCert.ROLE_MEMBER)
        assertIs<StepUpAuth.Created.Refused>(stepUp(member).createChallenge("boss", StepUpActions.REVOKE_DEVICE, "x"), "a member device may not confirm")
    }

    @Test
    fun aRevokedOrUnenrolledDeviceStopsWorkingImmediately() {
        val r = realm()
        val s = stepUp(r)
        val c = create(s)
        assertTrue(r.identities.setStatus("dev-boss", DeviceStatus.REVOKED, now))
        assertFalse(s.verify(c.challengeId, sign(r.deviceKey, c)))
        assertIs<StepUpAuth.Created.Refused>(s.createChallenge("boss", StepUpActions.REVOKE_DEVICE, "x"))
    }

    @Test
    fun aDisabledAdministratorCannotOpenChallenges() {
        val r = realm()
        r.admins.addAdminDirectlyForTesting(r.admins.getAdmin("boss")!!.copy(disabled = true))
        assertIs<StepUpAuth.Created.Refused>(stepUp(r).createChallenge("boss", StepUpActions.REVOKE_DEVICE, "x"))
    }

    @Test
    fun unknownActionsAndFloodsAreRefused() {
        val r = realm()
        val s = stepUp(r)
        assertEquals("ERR_BAD_ACTION", assertIs<StepUpAuth.Created.Refused>(s.createChallenge("boss", "format.disk", "x")).code)
        repeat(StepUpAuth.MAX_PER_ADMIN) { create(s) }
        assertEquals("ERR_TOO_MANY", assertIs<StepUpAuth.Created.Refused>(s.createChallenge("boss", StepUpActions.REVOKE_DEVICE, "x")).code)
    }

    @Test
    fun aRevokedAdminDeviceSerialIsRefusedThroughTheRevocationService() {
        val r = realm()
        val revocations = SqliteRevocationService(r.db, "", null, { now })
        val serial = r.identities.device("dev-boss")!!.serial
        revocations.revokeSerial(serial, "lost", "test")
        assertIs<StepUpAuth.Created.Refused>(stepUp(r, revocations).createChallenge("boss", StepUpActions.REVOKE_DEVICE, "x"))
    }
}
