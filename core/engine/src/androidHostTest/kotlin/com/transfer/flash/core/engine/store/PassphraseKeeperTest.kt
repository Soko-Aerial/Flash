package com.transfer.flash.core.engine.store

import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Audit 2026-10-08: any exception from the unwrap used to mint a new passphrase, overwrite the stored wrapper and get the
 * database quarantined, so a transient keystore error destroyed the chat history for good.
 */
class PassphraseKeeperTest {

    /** Same simple name as android.security.keystore.KeyPermanentlyInvalidatedException (matched by name, no Android needed). */
    private class KeyPermanentlyInvalidatedException : Exception("key invalidated")

    private val original = ByteArray(32) { it.toByte() }

    private class Fixture(var stored: String?, val unwrap: (String) -> ByteArray) {
        val writes = mutableListOf<String>()
        var unwrapCalls = 0
        val keeper = PassphraseKeeper(
            readStored = { stored },
            writeStored = { writes += it; stored = it },
            unwrap = { unwrapCalls++; unwrap(it) },
            wrap = { "wrapped:" + it.joinToString(",") },
            randomBytes = { n -> ByteArray(n) { 0x7F } },
            pause = {},
        )
    }

    @Test
    fun `a transient failure on the first attempt is retried and the old passphrase is returned`() {
        var calls = 0
        val f = Fixture("old") { if (calls++ == 0) throw KeyStoreException("keystore busy") else original }
        assertArrayEquals(original, f.keeper.resolve(commit = true))
        assertFalse(f.keeper.minted)
        assertTrue("nothing may be overwritten", f.writes.isEmpty())
        assertEquals(2, f.unwrapCalls)
    }

    @Test
    fun `a transient failure that persists is thrown, not turned into a new passphrase`() {
        val f = Fixture("old") { throw KeyStoreException("keystore daemon dead") }
        try {
            f.keeper.resolve(commit = true)
            fail("must surface the error")
        } catch (e: PassphraseUnavailableException) {
            assertTrue(e.cause is KeyStoreException)
        }
        assertFalse(f.keeper.minted)
        assertTrue("the stored wrapper must survive", f.writes.isEmpty() && f.stored == "old")
        assertEquals(2, f.unwrapCalls)
    }

    @Test
    fun `a permanently invalidated key mints a new passphrase`() {
        val f = Fixture("old") { throw KeyPermanentlyInvalidatedException() }
        val fresh = f.keeper.resolve(commit = true)
        assertTrue(f.keeper.minted)
        assertEquals(32, fresh.size)
        assertEquals(1, f.unwrapCalls)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun `an authentication failure is retried once and only then believed`() {
        var calls = 0
        val glitch = Fixture("old") { if (calls++ == 0) throw AEADBadTagException("tag") else original }
        assertArrayEquals(original, glitch.keeper.resolve(commit = true))
        assertFalse(glitch.keeper.minted)

        val gone = Fixture("old") { throw AEADBadTagException("tag") }
        gone.keeper.resolve(commit = true)
        assertTrue(gone.keeper.minted)
        assertEquals(2, gone.unwrapCalls)
    }

    @Test
    fun `a corrupt wrapper that cannot be parsed mints a new passphrase`() {
        val f = Fixture("not base64") { throw IllegalArgumentException("bad base64") }
        f.keeper.resolve(commit = true)
        assertTrue(f.keeper.minted)
    }

    @Test
    fun `a deferred commit keeps the old wrapper until commitPending`() {
        val f = Fixture("old") { throw KeyPermanentlyInvalidatedException() }
        f.keeper.resolve(commit = false)
        assertTrue(f.keeper.minted)
        assertTrue("old wrapper must still be there", f.writes.isEmpty() && f.stored == "old")
        f.keeper.commitPending()
        assertEquals(1, f.writes.size)
        assertTrue(f.stored!!.startsWith("wrapped:"))
        // Committing twice writes nothing more.
        f.keeper.commitPending()
        assertEquals(1, f.writes.size)
    }

    @Test
    fun `first run with nothing stored mints and stores`() {
        val f = Fixture(null) { error("never called") }
        f.keeper.resolve(commit = true)
        assertTrue(f.keeper.minted)
        assertEquals(1, f.writes.size)
        assertEquals(0, f.unwrapCalls)
    }

    @Test
    fun `a keystore that cannot wrap makes the mint fail instead of handing out an unstorable passphrase`() {
        val keeper = PassphraseKeeper(
            readStored = { null },
            writeStored = { fail("must not write") },
            unwrap = { error("never") },
            wrap = { throw KeyStoreException("cannot wrap") },
            pause = {},
        )
        try {
            keeper.resolve(commit = true)
            fail("expected KeyStoreException")
        } catch (_: KeyStoreException) {
            assertNull(null)
        }
    }
}
