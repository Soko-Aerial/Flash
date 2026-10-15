@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.auth

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.identity.DeviceStatus
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.storage.ServerDatabase
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SEC-05 / SEC-07 / SEC-08 / SEC-18: revocation is persisted, signed, versioned and enforced by serial AND by device
 * id; enrolment is one atomic transaction with a quota and a durable serial counter.
 *
 * Every "Hub" here is built over ONE database file so [Hub.restart] really reloads state from disk.
 */
class RevocationAndEnrolmentTest {

    private val dir = Files.createTempDirectory("flash-revocation-test").toFile()
    private val now = System.currentTimeMillis()
    private val root: KeyPair = FlashCrypto.generateEcKeyPair()
    private val delegate: KeyPair = FlashCrypto.generateEcKeyPair()
    private val opened = ArrayList<ServerDatabase>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun delegation(serial: Long = 1L, maxDevices: Int = 100, scopes: Set<String> = setOf("enrol:member", "revoke:member")) =
        DelegationCert.issue(
            rootPrivateKey = root.private,
            rootPublicKey = root.toBase64Spki(),
            delegatePublicKey = delegate.toBase64Spki(),
            scopes = scopes,
            validFrom = now - 60_000,
            validTo = now + 365L * 24 * 3600 * 1000,
            maxDevices = maxDevices,
            serial = serial,
        )

    private inner class Hub(val cert: DelegationCert = delegation(), val requireHttps: Boolean = false, signing: Boolean = true) {
        val db = ServerDatabase("jdbc:sqlite:${File(dir, "hub.db").absolutePath}").also { opened += it }
        val identities = IdentityStore.open(db)
        val revoked = CopyOnWriteArrayList<String>()
        val revocations = SqliteRevocationService(
            db = db,
            rootPublicKey = root.toBase64Spki(),
            signer = if (signing) RevocationSigner(delegate.private, delegate.toBase64Spki()) { cert } else null,
        ).also { service -> service.onRevoked { id, _, _ -> revoked += id } }
        val enrollment = EnrollmentService(
            rootPublicKey = root.toBase64Spki(),
            serverPrivateKey = delegate.private,
            delegationCert = cert,
            store = identities,
            revocations = revocations,
            requireHttpsInvites = requireHttps,
        )

        fun restart(): Hub {
            db.close()
            return Hub(cert, requireHttps)
        }

        fun invite(): String = enrollment.createInvite(
            "https://hub.test",
            InviteScope(department = "eng", expiry = now + 3_600_000),
            now,
        ).link.substringAfter("token=").substringBefore("&")

        fun enrol(deviceId: String, key: KeyPair = FlashCrypto.generateEcKeyPair(), token: String = invite()): Result<EnrollmentResult> {
            val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(token, deviceId, key.toBase64Spki(), enrollment.serverId))
            return enrollment.enrollDevice(token, deviceId, key.toBase64Spki(), proof, currentTimeMs = now)
        }
    }

    // ------------------------------------------------------------------------------------------ revocation

    @Test
    fun revokingADeviceRevokesItsIdAndEveryCertificateSerialAndSurvivesARestart() {
        val hub = Hub()
        val device = hub.enrol("dev-a").getOrThrow()
        val serial = device.deviceCert.serial
        assertFalse(hub.revocations.isRevoked(serial, "dev-a"))

        val result = hub.revocations.revokeDevice("dev-a", "lost phone", by = "boss")
        assertTrue(result.changed)
        assertTrue(serial in result.serials)
        assertTrue(hub.revocations.isRevoked(serial, "other-id"), "by serial alone")
        assertTrue(hub.revocations.isRevoked(null, "dev-a"), "by device id alone")
        assertEquals(DeviceStatus.REVOKED, hub.identities.device("dev-a")?.status)
        assertEquals(listOf("dev-a"), hub.revoked, "the relay is told to kick the device")

        val restarted = hub.restart()
        assertTrue(restarted.revocations.isRevoked(serial, "x"), "persisted: a restart does not forget a revocation")
        assertTrue(restarted.revocations.isRevoked(null, "dev-a"))
    }

    @Test
    fun revokingTwiceChangesNothingAndDoesNotBumpTheVersion() {
        val hub = Hub()
        hub.enrol("dev-a")
        val first = hub.revocations.revokeDevice("dev-a", "x", "boss")
        val version = hub.revocations.current()?.version
        val second = hub.revocations.revokeDevice("dev-a", "x", "boss")
        assertTrue(first.changed)
        assertFalse(second.changed)
        assertEquals(version, hub.revocations.current()?.version)
        assertEquals(1, hub.revoked.size, "listeners fire once")
    }

    @Test
    fun theSignedListIsVersionedMonotonicallyAndVerifies() {
        val hub = Hub()
        hub.enrol("dev-a")
        hub.enrol("dev-b")
        assertNull(hub.revocations.current(), "no revocation yet, no list")
        val r1 = hub.revocations.revokeDevice("dev-a", "x", "boss")
        val v1 = assertNotNull(hub.revocations.current())
        val r2 = hub.revocations.revokeDevice("dev-b", "x", "boss")
        val v2 = assertNotNull(hub.revocations.current())
        assertTrue(v2.version > v1.version, "version only goes up")
        assertEquals(r1.publishedVersion, v1.version)
        assertEquals(r2.publishedVersion, v2.version)
        assertTrue(v2.verifySignature(), "the list is signed by the hub's delegate key")
        // ADR-129: the public list names no device id, only the hashes for this organisation.
        assertTrue(v2.isHashed)
        assertTrue(v2.revokedDeviceIds.isEmpty())
        assertEquals(
            setOf("dev-a", "dev-b").map { RevocationList.deviceHash(v2.audience, it) }.toSet(),
            v2.revokedDeviceHashes,
        )
        assertTrue(v2.revokesDevice("dev-a") && v2.revokesDevice("dev-b") && !v2.revokesDevice("dev-c"))
        assertTrue(hub.revocations.listAt(v1.version)?.verifySignature() == true, "older versions stay retrievable")
        assertEquals(v2.version, hub.restart().revocations.current()?.version, "the version survives a restart")
    }

    @Test
    fun withoutASigningRightRevocationIsStillEnforcedButFlagged() {
        val hub = Hub(cert = delegation(scopes = setOf("enrol:member")))
        hub.enrol("dev-a")
        val result = hub.revocations.revokeDevice("dev-a", "x", "boss")
        assertTrue(result.changed)
        assertTrue(result.needsSignedList, "the console must say clients can not learn this yet")
        assertTrue(hub.revocations.isRevoked(null, "dev-a"), "enforced at the hub immediately anyway")
    }

    @Test
    fun aRevokedDeviceIdCanNotEnrolAgainUntilAnAdministratorLiftsTheRevocation() {
        val hub = Hub()
        hub.enrol("dev-a")
        val old = hub.identities.device("dev-a")!!
        hub.revocations.revokeDevice("dev-a", "x", "boss")

        val refused = hub.enrol("dev-a")
        assertTrue(refused.isFailure, "a fresh invite does not bring a revoked id back")
        assertTrue(refused.exceptionOrNull() is SecurityException)

        assertTrue(hub.revocations.unrevokeDevice("dev-a", "boss").changed)
        assertTrue(hub.enrol("dev-a").isSuccess, "after the administrator lifts it, a NEW invite works")
        assertTrue(hub.revocations.isRevoked(old.serial, "dev-a"), "the old certificate stays dead")
        assertTrue(hub.revocations.unrevokeDevice("dev-a", "boss").let { !it.changed }, "unrevoke is idempotent")
    }

    @Test
    fun unenrollingRevokesTheCertificatesButFreesTheId() {
        val hub = Hub()
        val device = hub.enrol("dev-a").getOrThrow()
        assertTrue(hub.revocations.revokeSelf("dev-a", "dev-a").changed)
        assertTrue(hub.revocations.isRevoked(device.deviceCert.serial, "dev-a"))
        assertEquals(DeviceStatus.UNENROLLED, hub.identities.device("dev-a")?.status)
        assertTrue(hub.enrol("dev-a").isSuccess, "the id may enrol again with a fresh invite")
    }

    @Test
    fun anOfflineRootSignedListIsAcceptedOnlyWhenSignedByTheRootAndNewer() {
        val hub = Hub()
        val ok = RevocationList.issue(root.private, root.toBase64Spki(), 100L, emptySet(), setOf("dev-z"), revokedDelegationSerials = setOf(7L))
        assertTrue(hub.revocations.installRootSigned(ok))
        assertTrue(hub.revocations.isRevoked(null, "dev-z"))
        assertFalse(hub.revocations.installRootSigned(ok), "the same version again is not newer")
        val forged = RevocationList.issue(delegate.private, delegate.toBase64Spki(), 200L, emptySet(), setOf("dev-y"))
        assertFalse(hub.revocations.installRootSigned(forged), "a list signed by another key is refused")
        assertFalse(hub.revocations.isRevoked(null, "dev-y"))
    }

    // ------------------------------------------------------------------------------------------ REV-04 delegation revocation

    @Test
    fun aRootRevocationOfTheDelegationKillsEveryCertificateUnderItAndStopsTheHubIssuing() {
        val hub = Hub()
        val a = hub.enrol("dev-a").getOrThrow()
        val b = hub.enrol("dev-b").getOrThrow()
        assertFalse(hub.revocations.isRevoked(a.deviceCert.serial, "dev-a"))
        assertFalse(hub.revocations.isDelegationRevoked(1L))

        val list = RevocationList.issue(root.private, root.toBase64Spki(), 10L, emptySet(), emptySet(), revokedDelegationSerials = setOf(1L))
        assertTrue(hub.revocations.installRootSigned(list))

        // The devices' certificates are dead at once, by serial, in every place that asks (bearer, per frame, handshake).
        assertTrue(hub.revocations.isRevoked(a.deviceCert.serial, "dev-a"))
        assertTrue(hub.revocations.isRevoked(b.deviceCert.serial, "dev-b"))
        assertTrue(hub.revocations.isDelegationRevoked(1L))
        assertTrue(hub.revocations.status().revokedDelegations.contains(1L))

        // The hub refuses to enrol or renew under its own revoked delegation.
        val refused = hub.enrol("dev-c")
        assertTrue(refused.isFailure)
        assertTrue(refused.exceptionOrNull()!!.message!!.contains("revoked"), refused.exceptionOrNull()?.message)
        assertTrue(hub.enrollment.renew(a.deviceCert, now).isFailure)

        // It survives a restart (the revocation is a persisted row, not memory).
        val again = hub.restart()
        assertTrue(again.revocations.isDelegationRevoked(1L))
        assertTrue(again.revocations.isRevoked(a.deviceCert.serial, "dev-a"))
    }

    @Test
    fun aDeviceUnderTheRevokedDelegationEnrolsAgainUnderTheNewOneWithAFreshInvite() {
        val hub = Hub()
        val deviceKey = FlashCrypto.generateEcKeyPair()
        hub.enrol("dev-a", deviceKey).getOrThrow()
        val list = RevocationList.issue(root.private, root.toBase64Spki(), 10L, emptySet(), emptySet(), revokedDelegationSerials = setOf(1L))
        assertTrue(hub.revocations.installRootSigned(list))

        val second = Hub(cert = delegation(serial = 2L))
        val again = second.enrol("dev-a", deviceKey)
        assertTrue(again.isSuccess, "the id itself is not revoked, only the old certificates: ${again.exceptionOrNull()?.message}")
        assertFalse(second.revocations.isRevoked(again.getOrThrow().deviceCert.serial, "dev-a"))
    }

    @Test
    fun theKeytoolSignsARootRevocationThatTheHubInstallsAndThatKeepsTheEarlierRevocations() {
        val hub = Hub()
        hub.enrol("dev-a").getOrThrow()
        hub.revocations.revokeDevice("dev-a", "lost", "boss")
        // An OLD plain (v2) list is the base here: its ids are carried over and hashed for the tool's organisation (ADR-129).
        val baseList = RevocationList.issue(root.private, root.toBase64Spki(), 1L, emptySet(), setOf("dev-a"))

        val rootDir = Files.createTempDirectory(dir.toPath(), "root")
        val tool = com.transfer.flash.server.KeyTool(out = java.io.PrintStream(java.io.ByteArrayOutputStream()), kdfIterations = 1000)
        tool.initRoot(rootDir, encrypt = false)
        val baseFile = rootDir.resolve("base.json")
        Files.writeString(baseFile, com.transfer.flash.core.hubprotocol.rest.HubRestCodec.encode(baseList))
        val out = rootDir.resolve("revocations.signed")
        val result = tool.revoke(rootDir.resolve("root-identity.pk8"), delegationSerials = setOf(1L), baseFile = baseFile, outFile = out)

        assertEquals(baseList.version + 1, result.list.version)
        assertTrue(result.list.isHashed)
        assertTrue(result.list.revokedDeviceIds.isEmpty(), "the signed list names no device id")
        assertTrue(result.list.revokesDevice("dev-a"), "the earlier revocations are carried over, now hashed")
        assertTrue(1L in result.list.revokedDelegationSerials)
        // Signed by the tool's root: it verifies against that root's public key, and a hub pinned to ANOTHER root refuses it.
        assertEquals(Files.readString(rootDir.resolve("root-identity.pub")).trim(), result.list.issuerPublicKey)
        assertTrue(result.list.verifySignature())
        assertTrue(Files.exists(out))
        assertFalse(hub.revocations.installRootSigned(result.list))
    }

    @Test
    fun theKeytoolRefusesABaseListOfAnotherOrganisation() {
        val rootDir = Files.createTempDirectory(dir.toPath(), "root3")
        val tool = com.transfer.flash.server.KeyTool(out = java.io.PrintStream(java.io.ByteArrayOutputStream()), kdfIterations = 1000)
        tool.initRoot(rootDir, encrypt = false)
        val foreign = RevocationList.issue(
            root.private, root.toBase64Spki(), 1L, emptySet(), setOf("dev-a"),
            audience = RevocationList.audienceOf(root.toBase64Spki()),
        )
        val baseFile = rootDir.resolve("base.json")
        Files.writeString(baseFile, com.transfer.flash.core.hubprotocol.rest.HubRestCodec.encode(foreign))
        assertFailsWith<com.transfer.flash.server.KeyToolException> {
            tool.revoke(rootDir.resolve("root-identity.pk8"), delegationSerials = setOf(1L), baseFile = baseFile, outFile = rootDir.resolve("out"))
        }
    }

    @Test
    fun aHashedRootListIsInstalledByMatchingTheHubsOwnDevicesAndKeepsUnknownHashes() {
        val hub = Hub()
        hub.enrol("dev-a").getOrThrow()
        val audience = RevocationList.audienceOf(root.toBase64Spki())
        val list = RevocationList.issue(
            root.private, root.toBase64Spki(), 10L, emptySet(), setOf("dev-a", "dev-never-seen"), audience = audience,
        )
        assertTrue(hub.revocations.installRootSigned(list))
        assertTrue(hub.revocations.isRevoked(null, "dev-a"), "a device this hub knows is revoked by id")
        assertTrue(hub.revocations.isRevoked(null, "dev-never-seen"), "an id the hub has never seen is still blocked by its hash")
        assertFalse(hub.revocations.isRevoked(null, "dev-other"))
        assertEquals(2, hub.revocations.status().revokedDeviceIds)
        assertEquals(list.version, hub.restart().revocations.current()?.version, "and it survives a restart")
        assertTrue(hub.restart().revocations.isRevoked(null, "dev-never-seen"))
    }

    @Test
    fun aHubWithAnOldPlainListRepublishesItHashedAtStartup() {
        val hub = Hub()
        hub.enrol("dev-a").getOrThrow()
        hub.revocations.revokeDevice("dev-a", "lost", "boss")
        // Replace the stored list by a plain v2 list of the same content, as a hub from before ADR-129 would have published.
        val plain = RevocationList.issue(delegate.private, delegate.toBase64Spki(), 99L, emptySet(), setOf("dev-a"))
        hub.db.withConnection { conn ->
            conn.prepareStatement("INSERT INTO hub_revocation_lists(version, body, signer, created_at) VALUES(?,?,?,?)").use { st ->
                st.setLong(1, 99L)
                st.setString(2, com.transfer.flash.core.hubprotocol.rest.HubRestCodec.encode(plain))
                st.setString(3, "delegate")
                st.setLong(4, now)
                st.executeUpdate()
            }
        }
        val restarted = hub.restart()
        val current = assertNotNull(restarted.revocations.current())
        assertTrue(current.isHashed, "the plain list is replaced")
        assertTrue(current.version > 99L)
        assertTrue(current.revokedDeviceIds.isEmpty())
        assertTrue(current.revokesDevice("dev-a"))
    }

    @Test
    fun theKeytoolRefusesToSignAnEmptyRevocation() {
        val rootDir = Files.createTempDirectory(dir.toPath(), "root2")
        val tool = com.transfer.flash.server.KeyTool(out = java.io.PrintStream(java.io.ByteArrayOutputStream()), kdfIterations = 1000)
        tool.initRoot(rootDir, encrypt = false)
        val key = rootDir.resolve("root-identity.pk8")
        assertFailsWith<com.transfer.flash.server.KeyToolException> { tool.revoke(key, delegationSerials = emptySet()) }
    }

    // ------------------------------------------------------------------------------------------ bearer

    @Test
    fun aBearerTokenDiesTheMomentItsDeviceIsRevokedEvenWithoutAKick() {
        val hub = Hub()
        val device = hub.enrol("dev-a").getOrThrow()
        val tokens = BearerTokenStore(revocations = hub.revocations)
        val (token, _) = tokens.issue(HubPrincipal("dev-a", "member", "eng", device.deviceCert.serial), now)
        assertNotNull(tokens.resolve(token, now))
        hub.revocations.revokeDevice("dev-a", "x", "boss")
        // Nobody called tokens.revokeDevice: the store itself re-checks revocation on every resolve (SEC-05/SEC-08).
        assertNull(tokens.resolve(token, now))
    }

    // ------------------------------------------------------------------------------------------ enrolment

    @Test
    fun anInviteCanBeUsedByExactlyOneOfManyConcurrentEnrolments() {
        val hub = Hub()
        val token = hub.invite()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (0 until 8).map { i ->
            pool.submit<Result<EnrollmentResult>> {
                start.await()
                hub.enrol("racer-$i", token = token)
            }
        }
        start.countDown()
        val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, outcomes.count { it.isSuccess }, "single-use means single-use under a race")
        assertEquals(1L, hub.identities.activeDeviceCount())
    }

    @Test
    fun theDeviceQuotaIsEnforcedAndSerialsNeverRepeatAcrossARestart() {
        val hub = Hub(cert = delegation(maxDevices = 2))
        val a = hub.enrol("dev-a").getOrThrow().deviceCert.serial
        val b = hub.enrol("dev-b").getOrThrow().deviceCert.serial
        assertTrue(hub.enrol("dev-c").isFailure, "the third device is over the quota")
        assertEquals(2L, hub.identities.activeDeviceCount())
        assertNotNull(a)
        val restarted = hub.restart()
        restarted.revocations.revokeDevice("dev-a", "x", "boss")
        val fresh = restarted.enrol("dev-c").getOrThrow().deviceCert.serial
        assertTrue(fresh > maxOf(a, b), "the serial counter is durable: $fresh must exceed $a and $b")
    }

    @Test
    fun aDeviceIdAlreadyEnrolledWithAnotherKeyIsRefusedAndTheInviteSurvives() {
        val hub = Hub()
        hub.enrol("dev-a")
        val token = hub.invite()
        val clash = hub.enrol("dev-a", token = token)
        assertTrue(clash.isFailure)
        assertTrue(hub.enrol("dev-b", token = token).isSuccess, "a refused enrolment does not burn the invite (atomic)")
    }

    @Test
    fun aMalformedKeyOrDeviceIdIsRefusedBeforeAnythingIsSpent() {
        val hub = Hub()
        val token = hub.invite()
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rsaSpki = java.util.Base64.getEncoder().encodeToString(rsa.public.encoded)
        val rsaProof = java.util.Base64.getEncoder().encodeToString(ByteArray(64))
        for (badKey in listOf(rsaSpki, "not-base64!!", "")) {
            assertTrue(hub.enrollment.enrollDevice(token, "dev-a", badKey, rsaProof, currentTimeMs = now).isFailure, "key `$badKey` is refused")
        }
        for (badId in listOf("", "a b", "x".repeat(200), "../etc")) {
            assertTrue(hub.enrol(badId, token = token).isFailure, "device id `$badId` is refused")
        }
        assertTrue(hub.enrol("dev-ok", token = token).isSuccess, "none of the refusals burned the invite")
    }

    @Test
    fun renewalIssuesANewSerialInsideTheWindowAndNeverForARevokedOrUnenrolledDevice() {
        val hub = Hub()
        val device = hub.enrol("dev-a").getOrThrow()
        val cert = device.deviceCert
        val nearExpiry = cert.validTo - 3_600_000
        val renewed = hub.enrollment.renew(cert, nearExpiry).getOrThrow()
        assertTrue(renewed.deviceCert.serial > cert.serial)

        assertTrue(hub.enrollment.renew(cert, cert.validFrom + 1_000).isFailure, "too early: outside the renewal window")

        hub.revocations.revokeDevice("dev-a", "x", "boss")
        assertTrue(hub.enrollment.renew(renewed.deviceCert, nearExpiry).isFailure, "a revoked device can not renew")
    }

    @Test
    fun anInviteLinkForAPlainHttpHubIsRefusedUnlessInsecureIsAllowed() {
        val strict = Hub(requireHttps = true)
        assertFailsWith<IllegalStateException> {
            strict.enrollment.createInvite("http://hub.test", InviteScope(expiry = now + 60_000), now)
        }
        assertTrue(strict.enrollment.createInvite("https://hub.test", InviteScope(expiry = now + 60_000), now).link.isNotBlank())
        val lax = Hub(requireHttps = false)
        assertTrue(lax.enrollment.createInvite("http://hub.test", InviteScope(expiry = now + 60_000), now).link.isNotBlank())
    }

    @Test
    fun anInviteForARoleTheDelegationCannotIssueIsRefusedAtCreationInWords() {
        // DEFECT-017: the demo hub could create a dept_admin invite that enrolment then refused as "forbidden".
        val hub = Hub()   // default delegation: enrol:member + revoke:member only
        val e = assertFailsWith<IllegalArgumentException> {
            hub.enrollment.createInvite("https://hub.test", InviteScope(role = "dept_admin", expiry = now + 60_000), now)
        }
        assertTrue(e.message.orEmpty().contains("enrol:dept_admin"), e.message)
        assertFailsWith<IllegalArgumentException> {
            hub.enrollment.createInvite("https://hub.test", InviteScope(role = "admin", expiry = now + 60_000), now)
        }
        assertTrue(hub.enrollment.createInvite("https://hub.test", InviteScope(role = "member", expiry = now + 60_000), now).link.isNotBlank())
        val withHeads = Hub(cert = delegation(scopes = setOf("enrol:member", "enrol:dept_admin", "revoke:member")))
        assertTrue(withHeads.enrollment.createInvite("https://hub.test", InviteScope(role = "dept_admin", expiry = now + 60_000), now).link.isNotBlank())
        // enrol:admin is never implied by a wildcard-free scope list
        assertFailsWith<IllegalArgumentException> {
            withHeads.enrollment.createInvite("https://hub.test", InviteScope(role = "admin", expiry = now + 60_000), now)
        }
    }

    @Test
    fun inviteSecretsAreStoredHashedOnly() {
        val hub = Hub()
        val token = hub.invite()
        val stored = hub.db.withConnection { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT * FROM hub_invites").use { rs ->
                    val meta = rs.metaData
                    buildString { while (rs.next()) for (i in 1..meta.columnCount) append(rs.getString(i)).append('|') }
                }
            }
        }
        assertFalse(stored.contains(token), "a leaked database must not contain a usable invite")
    }
}
