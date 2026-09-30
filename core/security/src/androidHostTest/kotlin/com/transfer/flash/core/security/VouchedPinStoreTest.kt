package com.transfer.flash.core.security

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.security.testutil.FakeSharedPreferences
import com.transfer.flash.core.security.testutil.SoftwareSecretSealer
import com.transfer.flash.core.security.trust.AndroidPreferencesTrustStore
import com.transfer.flash.core.security.trust.PinSource
import com.transfer.flash.core.security.trust.VouchVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** ADR-044 V2, `docs/group/v2-vouched-trust-plan.md` E1, on the Android store and its preferences file. */
class VouchedPinStoreTest {
    private val prefs = FakeSharedPreferences()
    private val store = AndroidPreferencesTrustStore(prefs, SoftwareSecretSealer())
    private val member = FlashDeviceId("member-1")
    private val g1 = "g2-aaaa"
    private val g2 = "g2-bbbb"

    @Test
    fun `a vouch installs a pin the trust manager will find`() {
        assertEquals(VouchVerdict.ACCEPT, store.applyVouch(member, "aa:bb", g1))
        assertEquals("AABB", store.getPin(member))
        assertEquals(PinSource.VOUCHED, store.pinSource(member))
        assertEquals(setOf(g1), store.vouchingGroups(member))
        assertFalse("a vouch is not pairing", store.isTrusted(member))
    }

    @Test
    fun `a vouch replaces a first-use pin`() {
        store.savePin(member, "0102")
        assertEquals(PinSource.TOFU, store.pinSource(member))
        assertEquals(VouchVerdict.ACCEPT, store.applyVouch(member, "AABB", g1))
        assertEquals("AABB", store.getPin(member))
        assertEquals(PinSource.VOUCHED, store.pinSource(member))
    }

    @Test
    fun `pairing wins and a refused vouch changes nothing`() {
        store.trustPeer(member, "Phone")
        store.savePin(member, "0102")
        assertEquals(VouchVerdict.CONFLICT_PAIRED, store.applyVouch(member, "AABB", g1))
        assertEquals("0102", store.getPin(member))
        assertEquals(emptySet<String>(), store.vouchingGroups(member))
        assertEquals(PinSource.PAIRED, store.pinSource(member))
    }

    @Test
    fun `a vouch for the paired key keeps the pairing and records the group`() {
        store.trustPeer(member, "Phone")
        store.savePin(member, "AABB")
        assertEquals(VouchVerdict.ACCEPT, store.applyVouch(member, "AABB", g1))
        assertEquals(PinSource.PAIRED, store.pinSource(member))
        assertEquals(setOf(g1), store.vouchingGroups(member))
        store.revokeVouch(member, g1)
        assertEquals("a group operation never touches a paired pin", "AABB", store.getPin(member))
        assertEquals(emptySet<String>(), store.vouchingGroups(member))
        assertEquals(true, store.isTrusted(member))
    }

    @Test
    fun `two groups that disagree keep the first vouch`() {
        store.applyVouch(member, "AABB", g1)
        assertEquals(VouchVerdict.CONFLICT_VOUCHED, store.applyVouch(member, "CCDD", g2))
        assertEquals("AABB", store.getPin(member))
        assertEquals(setOf(g1), store.vouchingGroups(member))
    }

    @Test
    fun `the pin lives until the last vouching group is gone`() {
        store.applyVouch(member, "AABB", g1)
        store.applyVouch(member, "AABB", g2)
        store.revokeVouch(member, g1)
        assertEquals("AABB", store.getPin(member))
        assertEquals(setOf(g2), store.vouchingGroups(member))
        store.revokeVouch(member, g2)
        assertNull(store.getPin(member))
        assertNull(store.pinSource(member))
    }

    @Test
    fun `revoking a group that never vouched leaves a first-use pin alone`() {
        store.savePin(member, "0102")
        store.revokeVouch(member, g1)
        assertEquals("0102", store.getPin(member))
    }

    @Test
    fun `unpairing keeps the pin while a group still vouches for it`() {
        store.trustPeer(member, "Phone")
        store.savePin(member, "AABB")
        store.applyVouch(member, "AABB", g1)
        store.revokeTrust(member)
        assertFalse(store.isTrusted(member))
        assertEquals("AABB", store.getPin(member))
        assertEquals(PinSource.VOUCHED, store.pinSource(member))

        // Nothing vouching: unpairing clears the pin as it always did.
        val other = FlashDeviceId("member-2")
        store.trustPeer(other, "Tablet")
        store.savePin(other, "EEFF")
        store.revokeTrust(other)
        assertNull(store.getPin(other))
    }

    @Test
    fun `vouches survive a restart`() {
        store.applyVouch(member, "AABB", g1)
        store.applyVouch(member, "AABB", g2)
        val reopened = AndroidPreferencesTrustStore(prefs, SoftwareSecretSealer())
        assertEquals(setOf(g1, g2), reopened.vouchingGroups(member))
        assertEquals("AABB", reopened.getPin(member))
        assertEquals(PinSource.VOUCHED, reopened.pinSource(member))
    }

    @Test
    fun `a vouch is not listed as a paired peer`() {
        store.applyVouch(member, "AABB", g1)
        assertEquals(emptyMap<FlashDeviceId, String>(), store.getTrustedPeers())
    }
}
