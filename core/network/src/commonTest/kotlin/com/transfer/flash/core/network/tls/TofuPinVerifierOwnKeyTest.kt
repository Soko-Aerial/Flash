package com.transfer.flash.core.network.tls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ERROR-077: a PC dialled itself under another PC's id (the two shared an mDNS name) and pinned its OWN key
 * for that id, after which every handshake with the real PC failed "no chain certificate matches the pin".
 */
class TofuPinVerifierOwnKeyTest {

    private val own = "4B99560CC6816566537BBEA627151680CCB50C1F1A28A2B6579EED516610B04F"
    private val peer = "F886E88F18E1EE8A0000000000000000000000000000000000000000000000AA"

    @Test
    fun ownKeyIsRefusedAndNotRecordedOnFirstUse() {
        val pins = mutableMapOf<String, String>()
        val verifier = TofuPinVerifier({ pins[it] }, { id, pin -> pins[id] = pin }, ownFingerprintHex = own)

        assertFalse(verifier.isPinned("other-pc", own))
        assertNull(pins["other-pc"])
        // Nor under this device's own id (the inbound half of a self-dial).
        assertFalse(verifier.isPinned("self", own.lowercase()))
        assertNull(pins["self"])
    }

    @Test
    fun storedOwnKeyPinIsReplacedByTheRealPeer() {
        val pins = mutableMapOf("other-pc" to own)
        val verifier = TofuPinVerifier({ pins[it] }, { id, pin -> pins[id] = pin }, ownFingerprintHex = own)

        assertTrue(verifier.isPinned("other-pc", peer))
        assertEquals(peer, pins["other-pc"])
        // From then on the peer's key is the pin, and anything else is refused as before.
        assertTrue(verifier.isPinned("other-pc", peer))
        assertFalse(verifier.isPinned("other-pc", "AB".repeat(32)))
    }

    @Test
    fun realPinsAreUnchanged() {
        val pins = mutableMapOf("phone" to peer)
        val verifier = TofuPinVerifier({ pins[it] }, { id, pin -> pins[id] = pin }, ownFingerprintHex = own)

        assertTrue(verifier.isPinned("phone", peer))
        assertFalse(verifier.isPinned("phone", "AB".repeat(32)))
        assertEquals(peer, pins["phone"])
    }

    @Test
    fun withoutOwnFingerprintTheOldRuleHolds() {
        val pins = mutableMapOf<String, String>()
        val verifier = TofuPinVerifier({ pins[it] }, { id, pin -> pins[id] = pin })

        assertTrue(verifier.isPinned("other-pc", own))
        assertEquals(own, pins["other-pc"])
    }
}
