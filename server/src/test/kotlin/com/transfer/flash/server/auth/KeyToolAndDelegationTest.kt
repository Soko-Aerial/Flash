package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.KeyTool
import com.transfer.flash.server.KeyToolException
import com.transfer.flash.server.PassphraseSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SEC-04: the root key lives OFF the server; the hub holds a delegate key and a root-signed delegation. */
class KeyToolAndDelegationTest {

    private val dir: Path = Files.createTempDirectory("flash-keytool-test")
    private val captured = ByteArrayOutputStream()
    private val now = 1_800_000_000_000L
    private var pass: CharArray? = "correct horse battery".toCharArray()

    private fun tool(passphrase: CharArray? = pass) =
        KeyTool(PrintStream(captured), PassphraseSource { _, _ -> passphrase?.copyOf() }, clock = { now })

    private fun delegateKey() = FlashCrypto.generateEcKeyPair().toBase64Spki()

    private fun outText(): String = captured.toString(Charsets.UTF_8)

    // ------------------------------------------------------------------------------------------ init-root

    @Test
    fun initRootCreatesAnEncryptedOwnerOnlyKeyAndNeverPrintsIt() {
        val result = tool().initRoot(dir.resolve("root"))
        val raw = Files.readString(result.privFile)
        assertTrue(result.encrypted)
        assertTrue(raw.startsWith("FLASH-ROOT-KEY-V1"), "the private key file is an encrypted envelope")
        assertTrue(SecureFiles.isOwnerOnly(result.privFile), "owner-only permissions")
        assertTrue(outText().contains(result.fingerprint), "the fingerprint is shown to the operator")
        assertFalse(outText().contains(raw.lines().maxByOrNull { it.length }!!), "key material never goes to the console")
    }

    @Test
    fun initRootRefusesToOverwriteAnExistingRootKey() {
        val root = dir.resolve("root")
        val first = tool().initRoot(root)
        val before = Files.readAllBytes(first.privFile)
        assertFailsWith<KeyToolException> { tool().initRoot(root) }
        assertTrue(before.contentEquals(Files.readAllBytes(first.privFile)), "the existing key is untouched")
    }

    @Test
    fun initRootWithoutAPassphraseNeedsTheExplicitFlag() {
        assertFailsWith<KeyToolException> { tool(passphrase = null).initRoot(dir.resolve("root")) }
        val plain = tool(passphrase = null).initRoot(dir.resolve("plain"), encrypt = false)
        assertFalse(plain.encrypted)
    }

    // ------------------------------------------------------------------------------------------ issue-delegation

    @Test
    fun issuedDelegationVerifiesAndSerialsOnlyGoUp() {
        val root = tool().initRoot(dir.resolve("root"))
        val server = delegateKey()
        val out = dir.resolve("delegation.cert")
        val first = tool().issueDelegation(root.privFile, server, setOf("enrol:member", "revoke:member"), 500, 90, outFile = out)
        assertEquals(1L, first.cert.serial)
        assertTrue(first.cert.verifySignature(JvmEcdsaVerifier), "the root signature verifies")
        assertEquals(server, first.cert.delegatePublicKey)
        assertEquals(500, first.cert.maxDevices)
        assertEquals(first.rootFingerprint, root.fingerprint)
        val decoded = HubRestCodec.decode<DelegationCert>(Files.readString(out))
        assertEquals(first.cert, decoded)

        val second = tool().issueDelegation(root.privFile, server, setOf("enrol:member"), 500, 90, outFile = out)
        assertEquals(2L, second.cert.serial, "automatic serials are monotonic")
        assertFailsWith<KeyToolException> {
            tool().issueDelegation(root.privFile, server, setOf("enrol:member"), 500, 90, serial = 2L, outFile = out)
        }
        assertFailsWith<KeyToolException> {
            tool().issueDelegation(root.privFile, server, setOf("enrol:member"), 500, 90, serial = 1L, outFile = out)
        }
        val third = tool().issueDelegation(root.privFile, server, setOf("enrol:member"), 500, 90, serial = 10L, outFile = out)
        assertEquals(10L, third.cert.serial)
    }

    @Test
    fun aWrongPassphraseCannotSign() {
        val root = tool().initRoot(dir.resolve("root"))
        assertFailsWith<KeyToolException> {
            tool("not the passphrase".toCharArray()).issueDelegation(root.privFile, delegateKey(), setOf("enrol:member"), 10, 30, outFile = dir.resolve("x.cert"))
        }
        assertFalse(Files.exists(dir.resolve("x.cert")), "nothing is written for a refused signing")
    }

    @Test
    fun scopesAndLimitsAreValidatedBeforeAnythingIsSigned() {
        val root = tool().initRoot(dir.resolve("root"))
        val server = delegateKey()
        fun issue(scopes: Set<String> = setOf("enrol:member"), devices: Int = 10, days: Long = 30) =
            tool().issueDelegation(root.privFile, server, scopes, devices, days, outFile = dir.resolve("y.cert"))
        assertFailsWith<KeyToolException> { issue(scopes = setOf("enrol:member", "root:everything")) }
        assertFailsWith<KeyToolException> { issue(devices = 0) }
        assertFailsWith<KeyToolException> { issue(devices = 100_001) }
        assertFailsWith<KeyToolException> { issue(days = 0) }
        assertFailsWith<KeyToolException> { issue(days = 400) }
        assertFailsWith<KeyToolException> { tool().issueDelegation(root.privFile, "not-a-key", setOf("enrol:member"), 10, 30, outFile = dir.resolve("y.cert")) }
        assertFalse(Files.exists(dir.resolve("y.cert")))
    }

    @Test
    fun aPublicKeyThatDoesNotBelongToThePrivateKeyIsRefused() {
        val root = tool().initRoot(dir.resolve("root"), encrypt = false)
        val otherPub = Base64Spki(FlashCrypto.generateEcKeyPair())
        Files.writeString(root.pubFile, otherPub)
        assertFailsWith<KeyToolException> {
            tool(null).issueDelegation(root.privFile, delegateKey(), setOf("enrol:member"), 10, 30, outFile = dir.resolve("z.cert"))
        }
    }

    private fun Base64Spki(pair: java.security.KeyPair): String = java.util.Base64.getEncoder().encodeToString(pair.public.encoded)

    @Test
    fun runReturnsExitCodesAndRejectsUnknownCommands() {
        val err = PrintStream(ByteArrayOutputStream())
        assertEquals(2, KeyTool.run(listOf("frobnicate"), tool(), err))
        assertEquals(2, KeyTool.run(emptyList(), tool(), err))
        assertEquals(2, KeyTool.run(listOf("issue-delegation"), tool(), err), "missing required options")
        assertEquals(0, KeyTool.run(listOf("init-root", "--out", dir.resolve("r").toString()), tool(), err))
        assertEquals(2, KeyTool.run(listOf("init-root", "--out", dir.resolve("r").toString()), tool(), err), "second init refuses")
    }

    // ------------------------------------------------------------------------------------------ hub key files

    @Test
    fun theDelegateKeyIsCreatedOnceAndReloadedIdentically() {
        val store = HubKeyStore(dir.resolve("data"))
        val first = store.loadOrCreateDelegateKey()
        assertTrue(first.created)
        val second = HubKeyStore(dir.resolve("data")).loadOrCreateDelegateKey()
        assertFalse(second.created)
        assertEquals(first.publicKey, second.publicKey)
        assertTrue(SecureFiles.isOwnerOnly(store.delegatePrivFile))
    }

    @Test
    fun aCorruptOrMismatchedDelegateKeyFailsClosedAndIsNeverRegenerated() {
        val store = HubKeyStore(dir.resolve("data"))
        val created = store.loadOrCreateDelegateKey()
        val goodPriv = Files.readAllBytes(store.delegatePrivFile)

        Files.writeString(store.delegatePrivFile, "garbage")
        assertFailsWith<KeyFileException> { store.loadOrCreateDelegateKey() }
        assertEquals("garbage", Files.readString(store.delegatePrivFile), "a corrupt key is left alone for the operator")

        Files.write(store.delegatePrivFile, goodPriv)
        Files.writeString(store.delegatePubFile, Base64Spki(FlashCrypto.generateEcKeyPair()))
        assertFailsWith<KeyFileException> { store.loadOrCreateDelegateKey() }

        Files.delete(store.delegatePubFile)
        assertFailsWith<KeyFileException> { store.loadOrCreateDelegateKey() }
        assertNotEquals("", created.publicKey)
    }

    @Test
    fun aRootKeyInTheDataDirectoryIsReportedAsMisplaced() {
        val store = HubKeyStore(dir.resolve("data"))
        Files.createDirectories(dir.resolve("data"))
        assertNull(store.misplacedRootKey())
        Files.writeString(store.legacyRootPrivFile, "x")
        assertEquals(store.legacyRootPrivFile, store.misplacedRootKey())
    }

    // ------------------------------------------------------------------------------------------ DelegationManager

    private inner class Realm {
        val root = FlashCrypto.generateEcKeyPair()
        val delegate = FlashCrypto.generateEcKeyPair()
        val file: Path = dir.resolve("delegation.cert")
        var clockMs = now
        var pin: String? = null
        var adopted: String? = null
        val manager = DelegationManager(file, delegate.toBase64Spki(), { pin }, { adopted = it }, { clockMs })

        fun cert(serial: Long, days: Long = 30, root: java.security.KeyPair = this.root, delegateKey: String = delegate.toBase64Spki()) =
            DelegationCert.issue(
                rootPrivateKey = root.private,
                rootPublicKey = root.toBase64Spki(),
                delegatePublicKey = delegateKey,
                scopes = setOf("enrol:member", "revoke:member"),
                validFrom = now - 60_000,
                validTo = now + days * DelegationManager.DAY_MS,
                maxDevices = 100,
                serial = serial,
            )

        fun drop(cert: DelegationCert) = Files.writeString(file, HubRestCodec.encode(cert))
    }

    @Test
    fun withoutAFileTheHubIsAwaitingAndCannotIssue() {
        val r = Realm()
        assertIs<DelegationLoad.Missing>(r.manager.load())
        assertEquals(DelegationStatus.AWAITING, r.manager.status)
        assertFalse(r.manager.canIssue)
        assertNull(r.manager.daysLeft())
    }

    @Test
    fun aValidDelegationActivatesAndFixesTheRootPin() {
        val r = Realm()
        r.drop(r.cert(1))
        assertIs<DelegationLoad.Loaded>(r.manager.load())
        assertEquals(DelegationStatus.ACTIVE, r.manager.status)
        assertEquals(DelegationManager.rootFingerprint(r.cert(1)), r.adopted, "the first delegation fixes the root (TOFU at provisioning)")
        assertEquals(30L, r.manager.daysLeft())
    }

    @Test
    fun aDelegationForAnotherServerKeyOrWithABadSignatureIsRejected() {
        val r = Realm()
        r.drop(r.cert(1, delegateKey = FlashCrypto.generateEcKeyPair().toBase64Spki()))
        assertIs<DelegationLoad.Rejected>(r.manager.load())
        assertEquals(DelegationStatus.INVALID, r.manager.status)

        val good = r.cert(2)
        r.drop(good.copy(maxDevices = 100_000))
        assertIs<DelegationLoad.Rejected>(r.manager.load(), "a tampered field breaks the root signature")
        assertFalse(r.manager.canIssue)
    }

    @Test
    fun aPinnedRootRefusesADelegationFromAnotherRoot() {
        val r = Realm()
        r.pin = DelegationManager.rootFingerprint(r.cert(1))
        val otherRoot = FlashCrypto.generateEcKeyPair()
        r.drop(r.cert(5, root = otherRoot))
        val result = r.manager.load()
        assertIs<DelegationLoad.Rejected>(result)
        assertTrue(result.reason.contains("pinned root"))
    }

    @Test
    fun aReplacementNeedsAHigherSerialUnderTheSameRoot() {
        val r = Realm()
        r.drop(r.cert(3))
        r.manager.load()
        r.drop(r.cert(3, days = 60))
        assertIs<DelegationLoad.Rejected>(r.manager.reload(), "same serial is not a replacement")
        r.drop(r.cert(2, days = 60))
        assertIs<DelegationLoad.Rejected>(r.manager.reload(), "no rollback")
        r.drop(r.cert(9, root = FlashCrypto.generateEcKeyPair()))
        assertIs<DelegationLoad.Rejected>(r.manager.reload(), "no move to another root")
        assertEquals(3L, r.manager.current?.serial, "the active delegation survives every refused replacement")
        r.drop(r.cert(4, days = 60))
        val ok = r.manager.reload()
        assertIs<DelegationLoad.Loaded>(ok)
        assertTrue(ok.replaced)
        assertEquals(4L, r.manager.current?.serial)
    }

    @Test
    fun anExpiredDelegationStopsIssuingAndWarningsComeOncePerThreshold() {
        val r = Realm()
        r.drop(r.cert(1, days = 20))
        r.manager.load()
        assertTrue(r.manager.checkExpiryWarnings().isEmpty(), "20 days left: no warning")
        r.clockMs = now + 8 * DelegationManager.DAY_MS
        assertEquals(1, r.manager.checkExpiryWarnings().size, "12 days left: the 14-day warning")
        assertTrue(r.manager.checkExpiryWarnings().isEmpty(), "not repeated")
        r.clockMs = now + 18 * DelegationManager.DAY_MS
        assertEquals(1, r.manager.checkExpiryWarnings().size, "2 days left: the 3-day warning")
        r.clockMs = now + 21 * DelegationManager.DAY_MS
        assertEquals(DelegationStatus.EXPIRED, r.manager.status)
        assertFalse(r.manager.canIssue)
    }

    @Test
    fun aLostSerialFileDoesNotRestartTheNumberingAtOne() {
        val root = tool().initRoot(dir.resolve("root"))
        val out = dir.resolve("root").resolve("delegation.cert")
        val server = delegateKey()
        tool().issueDelegation(root.privFile, server, setOf("enrol:member"), 100, 30, outFile = out)
        val second = tool().issueDelegation(root.privFile, server, setOf("enrol:member"), 100, 30, outFile = out)
        assertEquals(2L, second.cert.serial)
        Files.deleteIfExists(dir.resolve("root").resolve("delegation-serial.txt"))
        val third = tool().issueDelegation(root.privFile, server, setOf("enrol:member"), 100, 30, outFile = out)
        assertTrue(third.cert.serial > 2L, "serial ${third.cert.serial} must not fall back below the delegations already issued")
    }

    @Test
    fun aRestartDoesNotAcceptADelegationOlderThanOneItAcceptedBefore() {
        val r = Realm()
        var highest: Long? = null
        val first = DelegationManager(r.file, r.delegate.toBase64Spki(), { r.adopted }, { r.adopted = it }, { now }, highestAcceptedSerial = { highest }, onSerialAccepted = { highest = it })
        r.drop(r.cert(7))
        assertIs<DelegationLoad.Loaded>(first.load())
        assertEquals(7L, highest)
        r.drop(r.cert(3))
        val restarted = DelegationManager(r.file, r.delegate.toBase64Spki(), { r.adopted }, {}, { now }, highestAcceptedSerial = { highest }, onSerialAccepted = { highest = it })
        val result = restarted.load()
        assertIs<DelegationLoad.Rejected>(result, "an older delegation put back on disk must stay refused after a restart")
        assertTrue(result.reason.contains("older"))
    }

    @Test
    fun withRequireRootPinTheFirstDelegationIsRefusedUntilAFingerprintIsPinned() {
        val r = Realm()
        val strict = DelegationManager(r.file, r.delegate.toBase64Spki(), { r.pin }, { r.adopted = it }, { now }, requireRootPin = true)
        r.drop(r.cert(1))
        val refused = strict.load()
        assertIs<DelegationLoad.Rejected>(refused)
        assertNull(r.adopted, "nothing was adopted")
        assertFalse(strict.canIssue)
        r.pin = DelegationManager.rootFingerprint(r.cert(1))
        assertIs<DelegationLoad.Loaded>(strict.load(), "the same file is accepted once the operator pinned the root")
    }

    @Test
    fun installWritesTheFileOwnerOnlyAndSurvivesARestart() {
        val r = Realm()
        assertIs<DelegationLoad.Loaded>(r.manager.install(r.cert(1)))
        assertTrue(SecureFiles.isOwnerOnly(r.file))
        val restarted = DelegationManager(r.file, r.delegate.toBase64Spki(), { r.adopted }, {}, { now })
        assertIs<DelegationLoad.Loaded>(restarted.load())
        assertIs<DelegationLoad.Rejected>(restarted.install(r.cert(1)), "the same serial again is not an upgrade")
    }
}
