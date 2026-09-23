@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import com.transfer.flash.core.security.identity.IdentityKeyVault
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.model.FlashDeviceId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Durable trust on the desktop: **the name has to survive the restart, not just the id.**
 *
 * Found on hardware 2026-09-14: a phone paired, the desktop was restarted, and the trusted list came
 * back holding a row with no name — because `DesktopTrustStore.persist()` wrote
 * `trusted.<deviceId> = <name>` and `load()` read the key back but hard-coded `""` as the value. The
 * trust itself round-tripped, so `isTrusted` stayed true and nothing looked broken to any id-based
 * check; only the name was gone, and the name is the entire content of the row the user taps.
 *
 * A same-process assertion cannot see this bug: the in-memory `cache` still holds the name that was
 * just written. The test therefore builds a **second store over the same directory**, which is what a
 * relaunch does.
 */
class DesktopTrustStoreTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        dirs.forEach { runCatching { it.deleteRecursively() } }
        dirs.clear()
    }

    private fun dir(): File =
        Files.createTempDirectory("flash-truststore").toFile().also { dirs += it }

    @Test
    fun `a trusted peer's name survives a restart`() {
        val stateDir = dir()
        val peer = FlashDeviceId("92d2c543-bd11-40e6-a3ac-065079a6eb7c")

        DesktopTrustStore(stateDir).trustPeer(peer, "Flash V760")

        // The relaunch: a fresh store over the same directory, exactly as DesktopEngine builds one.
        val afterRestart = DesktopTrustStore(stateDir)
        assertTrue(afterRestart.isTrusted(peer), "trust must survive a restart")
        assertEquals(
            "Flash V760",
            afterRestart.getTrustedPeers()[peer],
            "the peer's NAME must survive a restart — an empty value here is the empty labelled row " +
                "a parked human saw in the desktop's trusted list",
        )
    }

    @Test
    fun `revoking survives a restart`() {
        val stateDir = dir()
        val peer = FlashDeviceId("0a3bd2e8-b713-4aae-9eff-48bb17a901cf")
        val store = DesktopTrustStore(stateDir)
        store.trustPeer(peer, "Prince Ayaata")
        store.revokeTrust(peer)

        val afterRestart = DesktopTrustStore(stateDir)
        assertTrue(!afterRestart.isTrusted(peer), "a revoked peer must not come back trusted")
        assertTrue(afterRestart.getTrustedPeers().isEmpty(), "and must not linger in the list")
    }

    // --- Audit S4: session keys sealed at rest ------------------------------------------------------

    /** Reversible, clearly-not-identity test vault (DPAPI does not exist on Linux CI). */
    private val testVault = IdentityKeyVault(
        protectFn = { plain -> byteArrayOf(0x5A) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray() },
        unprotectFn = { blob ->
            require(blob.isNotEmpty() && blob[0] == 0x5A.toByte()) { "not sealed by this vault" }
            blob.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        },
    )
    private val failingVault = IdentityKeyVault(protectFn = { error("DPAPI unavailable") }, unprotectFn = { error("no") })
    private val key = ByteArray(32) { (it * 5 + 1).toByte() }
    private val keyedPeer = FlashDeviceId("peer-s4")

    private fun fileText(stateDir: File) = File(stateDir, "trust.properties").readText()

    @Test
    fun `session key is sealed on disk and survives a restart`() {
        val stateDir = dir()
        DesktopTrustStore(stateDir, testVault).saveSessionKey(keyedPeer, key)

        assertFalse(fileText(stateDir).contains(Base64.encode(key)), "the raw key must not be on disk")
        // java.util.Properties escapes ':' as "\:" when storing.
        assertTrue(fileText(stateDir).contains("session_key.peer-s4=s1\\:"), "stored in sealed form")
        assertContentEquals(key, DesktopTrustStore(stateDir, testVault).getSessionKey(keyedPeer))
    }

    @Test
    fun `legacy plaintext key is migrated off disk on load`() {
        val stateDir = dir()
        File(stateDir, "trust.properties").writeText("session_key.peer-s4=${Base64.encode(key)}\n")

        val store = DesktopTrustStore(stateDir, testVault)

        assertContentEquals(key, store.getSessionKey(keyedPeer))
        assertFalse(fileText(stateDir).contains(Base64.encode(key)), "legacy plaintext must be rewritten sealed")
    }

    @Test
    fun `when sealing fails the key works this run but never reaches disk`() {
        val stateDir = dir()
        val store = DesktopTrustStore(stateDir, failingVault)
        store.saveSessionKey(keyedPeer, key)

        assertContentEquals(key, store.getSessionKey(keyedPeer))
        val onDisk = File(stateDir, "trust.properties").takeIf { it.isFile }?.readText().orEmpty()
        assertFalse(onDisk.contains("session_key"), "no plaintext fallback")
    }
}
