@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.security.identity.IdentityKeyVault
import com.transfer.flash.core.security.trust.PinSource
import com.transfer.flash.core.security.trust.VouchVerdict
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** ADR-044 V2 vouched pins on the desktop store. Every check that matters reopens the directory, as a relaunch does. */
class DesktopTrustStoreVouchTest {
    private val dirs = mutableListOf<File>()
    private val member = FlashDeviceId("member-1")
    private val g1 = "g2-aaaa"
    private val g2 = "g2-bbbb"

    @AfterTest
    fun tearDown() {
        dirs.forEach { runCatching { it.deleteRecursively() } }
        dirs.clear()
    }

    private fun dir(): File = Files.createTempDirectory("flash-vouch").toFile().also { dirs += it }

    /** The pass-through vault: these tests are about pins, not sealing. */
    private fun open(stateDir: File) = DesktopTrustStore(stateDir, IdentityKeyVault.PassThrough)

    @Test
    fun `a vouch installs a pin and survives a restart`() {
        val stateDir = dir()
        assertEquals(VouchVerdict.ACCEPT, open(stateDir).applyVouch(member, "aa:bb", g1))
        val reopened = open(stateDir)
        assertEquals("AABB", reopened.getPin(member))
        assertEquals(setOf(g1), reopened.vouchingGroups(member))
        assertEquals(PinSource.VOUCHED, reopened.pinSource(member))
        assertFalse(reopened.isTrusted(member))
    }

    @Test
    fun `a vouch replaces a first-use pin and pairing wins over a vouch`() {
        val store = open(dir())
        store.savePin(member, "0102")
        assertEquals(VouchVerdict.ACCEPT, store.applyVouch(member, "AABB", g1))
        assertEquals("AABB", store.getPin(member))

        val paired = FlashDeviceId("member-2")
        store.trustPeer(paired, "Phone")
        store.savePin(paired, "0102")
        assertEquals(VouchVerdict.CONFLICT_PAIRED, store.applyVouch(paired, "AABB", g1))
        assertEquals("0102", store.getPin(paired))
        assertEquals(emptySet(), store.vouchingGroups(paired))
        // The same key is fine: the pairing stays, and the group is recorded.
        assertEquals(VouchVerdict.ACCEPT, store.applyVouch(paired, "0102", g1))
        assertEquals(PinSource.PAIRED, store.pinSource(paired))
        assertEquals(setOf(g1), store.vouchingGroups(paired))
    }

    @Test
    fun `the pin lives until the last vouching group is revoked, across restarts`() {
        val stateDir = dir()
        val first = open(stateDir)
        first.applyVouch(member, "AABB", g1)
        first.applyVouch(member, "AABB", g2)
        assertEquals(VouchVerdict.CONFLICT_VOUCHED, first.applyVouch(member, "CCDD", "g2-cccc"))

        val second = open(stateDir)
        second.revokeVouch(member, g1)
        assertEquals("AABB", open(stateDir).getPin(member))
        second.revokeVouch(member, g2)
        val third = open(stateDir)
        assertNull(third.getPin(member))
        assertEquals(emptySet(), third.vouchingGroups(member))
    }

    @Test
    fun `unpairing keeps a vouched pin and clears an unvouched one`() {
        val store = open(dir())
        store.trustPeer(member, "Phone")
        store.savePin(member, "AABB")
        store.applyVouch(member, "AABB", g1)
        store.revokeTrust(member)
        assertEquals("AABB", store.getPin(member))
        assertEquals(PinSource.VOUCHED, store.pinSource(member))

        val other = FlashDeviceId("member-2")
        store.trustPeer(other, "Tablet")
        store.savePin(other, "EEFF")
        store.revokeTrust(other)
        assertNull(store.getPin(other))
    }
}
