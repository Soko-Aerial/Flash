package com.transfer.flash.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import com.transfer.flash.ui.shell.FlashShellTabs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun entry(d: FlashDestination, id: String? = null) = FlashBackStackState(d, id)

/** UI-058 (HUBT-U*): Hub tab navigation rules. Enrolment swaps Transfers for Hub in the tab set. */
class FlashHubNavigationTest {

    private fun roundTrip(nav: FlashNavigationState): FlashNavigationState {
        val saved = with(FlashNavigationStateSaver) { SaverScope { true }.save(nav) }
        return requireNotNull(FlashNavigationStateSaver.restore(requireNotNull(saved)))
    }

    @Test
    fun `unenrolled tab order is exactly the legacy four`() {
        assertEquals(FlashNavigationMath.TabOrder, FlashNavigationMath.tabOrder(false))
        assertEquals(
            listOf("Chats", "Transfers", "Nearby", "Settings"),
            FlashShellTabs.of(false).map { it.label },
        )
    }

    @Test
    fun `enrolled tab order swaps Transfers for Hub in the same slot`() {
        val order = FlashNavigationMath.tabOrder(true)
        assertEquals(1, order.indexOf(FlashDestination.Hub))
        assertFalse(FlashDestination.Transfers in order)
        assertEquals(listOf("Chats", "Hub", "Nearby", "Settings"), FlashShellTabs.of(true).map { it.label })
    }

    @Test
    fun `tab roots follow enrolment`() {
        assertTrue(FlashNavigationMath.isTabRoot(FlashDestination.Transfers))
        assertFalse(FlashNavigationMath.isTabRoot(FlashDestination.Transfers, true))
        assertTrue(FlashNavigationMath.showsBottomBar(FlashDestination.Hub, hubEnrolled = true, railMode = false), "the bar stays on the Hub")
        assertFalse(FlashNavigationMath.showsBottomBar(FlashDestination.Hub, hubEnrolled = false, railMode = false))
        assertFalse(FlashNavigationMath.showsBottomBar(FlashDestination.Hub, hubEnrolled = true, railMode = true), "rail mode has no bottom bar")
        assertFalse(FlashNavigationMath.showsBottomBar(FlashDestination.Conversation, hubEnrolled = true, railMode = false))
        assertTrue(FlashNavigationMath.isTabRoot(FlashDestination.Hub, true))
        assertFalse(FlashNavigationMath.isTabRoot(FlashDestination.Hub, false))
    }

    @Test
    fun `shell tabs carry badges only when positive`() {
        assertEquals(null, FlashShellTabs.of(true, chatsUnread = 0)[0].badgeCount)
        assertEquals(7, FlashShellTabs.of(true, chatsUnread = 7)[0].badgeCount)
        assertEquals(null, FlashShellTabs.of(true, hubBadge = -3)[1].badgeCount)
        assertEquals(2, FlashShellTabs.of(true, hubBadge = 2)[1].badgeCount)
    }

    @Test
    fun `navigate to Hub is ignored when not enrolled`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Hub)
        assertEquals(1, nav.stackSize)
        assertEquals(FlashDestination.ChatList, nav.current.destination)
    }

    @Test
    fun `selectTab Hub works only when enrolled and Transfers only when not`() {
        val nav = FlashNavigationState()
        nav.selectTab(FlashDestination.Hub)
        assertEquals(FlashDestination.ChatList, nav.current.destination)
        nav.updateHubEnrolled(true)
        nav.selectTab(FlashDestination.Hub)
        assertEquals(FlashDestination.Hub, nav.current.destination)
        nav.selectTab(FlashDestination.Transfers)
        assertEquals(FlashDestination.Hub, nav.current.destination)
    }

    @Test
    fun `openTransfers selects the tab when unenrolled and pushes when enrolled`() {
        val plain = FlashNavigationState()
        plain.openTransfers()
        assertEquals(listOf(entry(FlashDestination.Transfers)), plain.entries)

        val enrolled = FlashNavigationState()
        enrolled.updateHubEnrolled(true)
        enrolled.openTransfers()
        assertEquals(listOf(entry(FlashDestination.ChatList), entry(FlashDestination.Transfers)), enrolled.entries)
        enrolled.openTransfers()
        assertEquals(2, enrolled.stackSize)
        assertTrue(enrolled.back())
        assertEquals(FlashDestination.ChatList, enrolled.current.destination)
    }

    @Test
    fun `pushed Transfers highlights the tab beneath it`() {
        val nav = FlashNavigationState()
        nav.updateHubEnrolled(true)
        nav.selectTab(FlashDestination.Hub)
        nav.openTransfers()
        assertEquals(FlashDestination.Hub, nav.shellTab)
        nav.navigate(FlashDestination.Conversation, "c1")
        assertEquals(FlashDestination.ChatList, nav.shellTab)
    }

    @Test
    fun `unenrol while on Hub falls back to Chats`() {
        val nav = FlashNavigationState()
        nav.updateHubEnrolled(true)
        nav.selectTab(FlashDestination.Hub)
        nav.updateHubEnrolled(false)
        assertEquals(listOf(entry(FlashDestination.ChatList)), nav.entries)
        assertEquals(FlashDestination.ChatList, nav.shellTab)
    }

    @Test
    fun `enrol while on Transfers tab keeps the user there with Chats beneath`() {
        val nav = FlashNavigationState()
        nav.selectTab(FlashDestination.Transfers)
        nav.updateHubEnrolled(true)
        assertEquals(listOf(entry(FlashDestination.ChatList), entry(FlashDestination.Transfers)), nav.entries)
        assertEquals(FlashDestination.ChatList, nav.shellTab)
    }

    @Test
    fun `updateHubEnrolled with the same value is a no-op`() {
        val nav = FlashNavigationState()
        val before = nav.entries
        nav.updateHubEnrolled(false)
        assertSame(before, nav.entries)
    }

    @Test
    fun `normalised returns the same instance when nothing changes`() {
        val stack = listOf(entry(FlashDestination.ChatList), entry(FlashDestination.Conversation, "c"))
        assertSame(stack, FlashNavigationMath.normalised(stack, false))
        assertSame(stack, FlashNavigationMath.normalised(stack, true))
    }

    @Test
    fun `normalised is total and idempotent over mixed stacks`() {
        val all = FlashDestination.entries.flatMap { d ->
            if (d == FlashDestination.Conversation) listOf(entry(d, "c"), entry(d)) else listOf(entry(d))
        }
        val samples = listOf(emptyList(), all, all + all, listOf(entry(FlashDestination.Hub), entry(FlashDestination.Hub)))
        for (enrolled in listOf(false, true)) {
            for (s in samples) {
                val once = FlashNavigationMath.normalised(s, enrolled)
                assertTrue(once.isNotEmpty())
                assertTrue(once.all { it.isValid() })
                assertTrue(once.size <= FlashNavigationMath.MAX_STACK_DEPTH)
                assertEquals(once, FlashNavigationMath.normalised(once, enrolled))
                if (!enrolled) assertTrue(once.none { it.destination == FlashDestination.Hub })
            }
        }
    }

    @Test
    fun `normalised depth cap keeps the root`() {
        val stack = listOf(entry(FlashDestination.ChatList)) +
            (1..15).map { entry(FlashDestination.Conversation, "c$it") }
        val out = FlashNavigationMath.normalised(stack, true)
        assertEquals(FlashNavigationMath.MAX_STACK_DEPTH, out.size)
        assertEquals(entry(FlashDestination.ChatList), out.first())
        assertEquals(entry(FlashDestination.Conversation, "c15"), out.last())
    }

    @Test
    fun `transition between Hub and neighbours is lateral when enrolled`() {
        val chats = entry(FlashDestination.ChatList)
        val hub = entry(FlashDestination.Hub)
        val nearby = entry(FlashDestination.NearbyDevices)
        assertEquals(FlashScreenTransition.TabForward, FlashNavigationMath.transitionFor(chats, hub, true))
        assertEquals(FlashScreenTransition.TabBackward, FlashNavigationMath.transitionFor(nearby, hub, true))
        assertEquals(FlashScreenTransition.Push, FlashNavigationMath.transitionFor(hub, entry(FlashDestination.Transfers), true))
        assertEquals(FlashScreenTransition.Pop, FlashNavigationMath.transitionFor(entry(FlashDestination.Transfers), hub, true))
        // unenrolled: Transfers is a tab again
        assertEquals(FlashScreenTransition.TabForward, FlashNavigationMath.transitionFor(chats, entry(FlashDestination.Transfers)))
    }

    @Test
    fun `saver round trip keeps enrolment and the Hub position`() {
        val nav = FlashNavigationState()
        nav.updateHubEnrolled(true)
        nav.selectTab(FlashDestination.Hub)
        val back = roundTrip(nav)
        assertTrue(back.hubEnrolled)
        assertEquals(listOf(entry(FlashDestination.Hub)), back.entries)
    }

    @Test
    fun `restoring an old save without the marker is unenrolled and drops Hub`() {
        val restored = FlashNavigationStateSaver.restore(listOf("Hub|", "Transfers|"))
        requireNotNull(restored)
        assertFalse(restored.hubEnrolled)
        assertEquals(listOf(entry(FlashDestination.ChatList), entry(FlashDestination.Transfers)), restored.entries)
    }

    @Test
    fun `restore ignores a malformed marker value`() {
        val restored = requireNotNull(FlashNavigationStateSaver.restore(listOf("@hubEnrolled|banana", "ChatList|")))
        assertFalse(restored.hubEnrolled)
    }

    @Test
    fun `ORGT_U64_hubPlaceholder_copy_and_refresh`() {
        assertEquals("Hub", com.transfer.flash.ui.hub.FlashHubPlaceholderCopy.TITLE)
        assertEquals("Your organisation's news, people and groups will appear here.", com.transfer.flash.ui.hub.FlashHubPlaceholderCopy.BODY)
        assertEquals("Browse department groups", com.transfer.flash.ui.hub.FlashHubPlaceholderCopy.BROWSE_GROUPS)
        assertEquals("Refresh organisation directory", com.transfer.flash.ui.hub.FlashHubPlaceholderCopy.REFRESH)
    }
}
