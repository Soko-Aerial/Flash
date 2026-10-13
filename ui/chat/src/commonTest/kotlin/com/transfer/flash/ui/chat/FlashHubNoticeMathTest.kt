package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashHubNoticeKind
import com.transfer.flash.core.messaging.model.FlashHubNoticeUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR-126: the hub notice copy is calm, names the colleague, and is honest that groups are not sealed yet. */
class FlashHubNoticeMathTest {

    private fun n(kind: FlashHubNoticeKind, name: String = "Bob") =
        FlashHubNoticeUi(kind, "bob", name, newCode = "1111 2222 3333 4444", oldCode = "aaaa bbbb cccc dddd")

    @Test
    fun `a changed key names the colleague and shows both codes`() {
        val notice = n(FlashHubNoticeKind.KEY_CHANGED)
        assertEquals("Bob's security key changed", FlashHubNoticeMath.title(notice))
        val body = FlashHubNoticeMath.body(notice)
        assertTrue("1111 2222 3333 4444" in body && "aaaa bbbb cccc dddd" in body)
        assertTrue(FlashHubNoticeMath.needsDecision(notice))
    }

    @Test
    fun `only a changed key asks for a decision`() {
        FlashHubNoticeKind.entries.filter { it != FlashHubNoticeKind.KEY_CHANGED }.forEach {
            assertFalse(FlashHubNoticeMath.needsDecision(n(it)), it.name)
        }
    }

    @Test
    fun `waiting for an update uses the colleague name`() {
        assertEquals("Waiting for Bob to update the app", FlashHubNoticeMath.title(n(FlashHubNoticeKind.PEER_NEEDS_UPDATE)))
        assertEquals("Waiting for your colleague to update the app", FlashHubNoticeMath.title(n(FlashHubNoticeKind.PEER_NEEDS_UPDATE, name = "")))
    }

    @Test
    fun `group copy says truthfully that groups are not sealed`() {
        assertTrue("not sealed" in FlashHubNoticeMath.body(n(FlashHubNoticeKind.GROUP_READABLE)))
        assertTrue("not sealed" in FlashHubNoticeMath.body(n(FlashHubNoticeKind.GROUP_HUB_OFF)))
    }

    @Test
    fun `no notice copy contains protocol words`() {
        FlashHubNoticeKind.entries.forEach {
            val text = FlashHubNoticeMath.contentDescription(n(it)).lowercase()
            listOf("sealed_v1", "fingerprint", "p-256", "ecdh", "error_", "409").forEach { bad -> assertFalse(bad in text, "$it: $bad") }
        }
    }
}
