@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.engine.group.TrustStoreGroupVouching
import com.transfer.flash.core.messaging.protocol.GroupVouchVerdict
import com.transfer.flash.core.security.identity.IdentityKeyVault
import com.transfer.flash.core.security.trust.PinSource
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-044 V2: the adapter that hands the group layer a real trust store, exercised over the desktop store (the same
 * [com.transfer.flash.core.security.trust.VouchRules] the Android store applies).
 */
class TrustStoreGroupVouchingTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        dirs.forEach { runCatching { it.deleteRecursively() } }
        dirs.clear()
    }

    private fun store(): DesktopTrustStore {
        val dir = Files.createTempDirectory("flash-vouching").toFile().also { dirs += it }
        return DesktopTrustStore(dir, IdentityKeyVault.PassThrough)
    }

    @Test
    fun `a vouch becomes the pin the TLS layer trusts and is withdrawn with the last group`() {
        val trust = store()
        val vouching = TrustStoreGroupVouching(trust)

        assertEquals(GroupVouchVerdict.ACCEPT, vouching.vouch("member-1", "aa:bb", "g2-1"))

        assertEquals("AABB", trust.getPin(FlashDeviceId("member-1")))
        assertEquals(PinSource.VOUCHED, trust.pinSource(FlashDeviceId("member-1")))
        assertTrue(vouching.isVouched("member-1", "AABB", "g2-1"))
        assertFalse(vouching.isVouched("member-1", "AABB", "g2-2"), "another group did not vouch")
        assertFalse(vouching.isVouched("member-1", "CCDD", "g2-1"), "another key is not the vouched one")

        vouching.revoke("member-1", "g2-1")
        assertNull(trust.getPin(FlashDeviceId("member-1")))
        assertFalse(vouching.isVouched("member-1", "AABB", "g2-1"))
    }

    @Test
    fun `the verdict maps every store answer and a query changes nothing`() {
        val trust = store()
        val vouching = TrustStoreGroupVouching(trust)
        trust.trustPeer(FlashDeviceId("paired"), "Phone")
        trust.savePin(FlashDeviceId("paired"), "0102")

        assertEquals(GroupVouchVerdict.CONFLICT_PAIRED, vouching.verdict("paired", "AABB", "g2-1"))
        assertEquals(GroupVouchVerdict.ACCEPT, vouching.verdict("paired", "0102", "g2-1"))
        assertEquals(GroupVouchVerdict.ACCEPT, vouching.verdict("new", "AABB", "g2-1"))
        assertNull(trust.getPin(FlashDeviceId("new")), "a verdict is a query: nothing was installed")

        vouching.vouch("new", "AABB", "g2-1")
        assertEquals(GroupVouchVerdict.CONFLICT_VOUCHED, vouching.verdict("new", "CCDD", "g2-2"))
        assertEquals(GroupVouchVerdict.INVALID, vouching.verdict("new", "", "g2-2"))
    }

    @Test
    fun `a blank device id is invalid and never throws`() {
        val vouching = TrustStoreGroupVouching(store())

        assertEquals(GroupVouchVerdict.INVALID, vouching.verdict("", "AABB", "g2-1"))
        assertEquals(GroupVouchVerdict.INVALID, vouching.vouch("  ", "AABB", "g2-1"))
        assertFalse(vouching.isVouched("", "AABB", "g2-1"))
        vouching.revoke("", "g2-1")
    }
}
