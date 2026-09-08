package com.transfer.flash.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for UI-033 dependency-free navigation logic (pure state holder + math helpers) and
 * the UI-046/UI-037 shell rules layered on top: three-step back, direction derivation, and
 * process-death restoration.
 */
class FlashNavigationLogicTest {

    @Test
    fun `initial state is ChatList root and cannot go back`() {
        val nav = FlashNavigationState()
        assertEquals(FlashDestination.ChatList, nav.current.destination)
        assertNull(nav.current.conversationId)
        assertEquals(1, nav.stackSize)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `back at root returns false and keeps state`() {
        val nav = FlashNavigationState()
        assertFalse(nav.back())
        assertEquals(FlashDestination.ChatList, nav.current.destination)
    }

    @Test
    fun `navigate pushes entry and back pops it`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-1")
        assertEquals(FlashDestination.Conversation, nav.current.destination)
        assertEquals("conv-1", nav.current.conversationId)
        assertTrue(nav.canGoBack)

        assertTrue(nav.back())
        assertEquals(FlashDestination.ChatList, nav.current.destination)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `duplicate consecutive push to same destination is ignored`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Transfers)
        nav.navigate(FlashDestination.Transfers)
        nav.navigate(FlashDestination.Transfers)
        assertEquals(2, nav.stackSize)
        assertEquals(FlashDestination.Transfers, nav.current.destination)
    }

    @Test
    fun `re-pushing the same conversation is ignored`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-a")
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-a")
        assertEquals(2, nav.stackSize)
        assertEquals("conv-a", nav.current.conversationId)
    }

    @Test
    fun `switching between two conversations counts as distinct pushes`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-a")
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-b")
        assertEquals(3, nav.stackSize)
        assertEquals("conv-b", nav.current.conversationId)

        assertTrue(nav.back())
        assertEquals("conv-a", nav.current.conversationId)
        assertTrue(nav.back())
        assertEquals(FlashDestination.ChatList, nav.current.destination)
    }

    @Test
    fun `same destination with different conversationId is a distinct push`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.NearbyDevices)
        nav.navigate(FlashDestination.NearbyDevices) // ignored: exact duplicate
        nav.navigate(FlashDestination.Conversation, conversationId = "peer-x")
        assertEquals(3, nav.stackSize)
    }

    @Test
    fun `stack depth is capped while the root is preserved`() {
        val nav = FlashNavigationState()
        for (i in 1..12) {
            nav.navigate(FlashDestination.Conversation, conversationId = "conv-$i")
        }
        assertEquals(FlashNavigationMath.MAX_STACK_DEPTH, nav.stackSize)
        // Root (ChatList) must survive; oldest pushed conversation dropped off.
        assertEquals(FlashBackStackState(FlashDestination.ChatList), nav.entries[0])
        assertEquals("conv-12", nav.current.conversationId)
        assertEquals(
            FlashNavigationMath.MAX_STACK_DEPTH,
            FlashNavigationMath.nextStackSize(FlashNavigationMath.MAX_STACK_DEPTH, pushing = true),
        )
    }

    @Test
    fun `navigate with invalid Conversation entry is rejected`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Conversation) // missing id
        nav.navigate(FlashDestination.Conversation, conversationId = "   ") // blank id
        assertEquals(1, nav.stackSize)
        assertEquals(FlashDestination.ChatList, nav.current.destination)
    }

    @Test
    fun `non-conversation destinations reject a conversationId`() {
        assertFalse(FlashBackStackState(FlashDestination.ChatList, "conv-1").isValid())
        assertFalse(FlashBackStackState(FlashDestination.Transfers, "conv-1").isValid())
        assertTrue(FlashBackStackState(FlashDestination.Transfers).isValid())
        assertTrue(FlashBackStackState(FlashDestination.Conversation, "conv-1").isValid())
    }

    @Test
    fun `math helpers agree with state holder behavior`() {
        assertEquals(4, FlashNavigationMath.nextStackSize(3, pushing = true))
        assertEquals(3, FlashNavigationMath.nextStackSize(3, pushing = false))
        assertTrue(
            FlashNavigationMath.shouldIgnorePush(
                FlashDestination.Conversation, "c1", FlashDestination.Conversation, "c1",
            ),
        )
        assertFalse(
            FlashNavigationMath.shouldIgnorePush(
                FlashDestination.Conversation, "c1", FlashDestination.Conversation, "c2",
            ),
        )
        assertFalse(
            FlashNavigationMath.shouldIgnorePush(
                FlashDestination.ChatList, null, FlashDestination.Transfers, null,
            ),
        )
    }

    @Test
    fun `conversation title falls back to destination title`() {
        assertEquals(
            "Ravi",
            FlashNavigationMath.resolveConversationTitle(FlashDestination.Conversation, "Ravi"),
        )
        assertEquals(
            "Conversation",
            FlashNavigationMath.resolveConversationTitle(FlashDestination.Conversation, "  "),
        )
        assertEquals(
            "Chats",
            FlashNavigationMath.resolveConversationTitle(FlashDestination.ChatList),
        )
    }

    @Test
    fun `tab root detection covers the four shell tabs only`() {
        assertTrue(FlashNavigationMath.isTabRoot(FlashDestination.ChatList))
        assertTrue(FlashNavigationMath.isTabRoot(FlashDestination.Transfers))
        assertTrue(FlashNavigationMath.isTabRoot(FlashDestination.NearbyDevices))
        assertTrue(FlashNavigationMath.isTabRoot(FlashDestination.Settings))
        assertFalse(FlashNavigationMath.isTabRoot(FlashDestination.Conversation))
    }

    @Test
    fun `selectTab replaces the stack with a single root entry`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-1")
        nav.navigate(FlashDestination.Settings)
        nav.selectTab(FlashDestination.Transfers)
        assertEquals(1, nav.stackSize)
        assertEquals(FlashDestination.Transfers, nav.current.destination)
        assertNull(nav.current.conversationId)
        // Nothing is left above the root, but back still has work to do: a non-home tab falls
        // back to Chats before the system is allowed to exit the app.
        assertTrue(nav.canGoBack)
    }

    @Test
    fun `selectTab ignores non-tab destinations and re-selecting current tab is a no-op`() {
        val nav = FlashNavigationState()
        nav.selectTab(FlashDestination.Conversation)
        assertEquals(1, nav.stackSize)
        assertEquals(FlashDestination.ChatList, nav.current.destination)

        nav.selectTab(FlashDestination.Settings)
        nav.selectTab(FlashDestination.Settings)
        assertEquals(1, nav.stackSize)
        assertEquals(FlashDestination.Settings, nav.current.destination)
    }

    // ---- UI-046 back resolution -------------------------------------------------------------

    @Test
    fun `back falls through a non-home tab to Chats before giving up`() {
        val nav = FlashNavigationState()
        nav.selectTab(FlashDestination.Settings)

        // Step 2: nothing above the root, but we are not home yet.
        assertTrue(nav.back())
        assertEquals(FlashDestination.ChatList, nav.current.destination)
        assertEquals(1, nav.stackSize)

        // Step 3: home tab with an empty stack — the host must NOT consume the press.
        assertFalse(nav.back())
        assertEquals(FlashDestination.ChatList, nav.current.destination)
    }

    @Test
    fun `back pops before it falls through to the home tab`() {
        val nav = FlashNavigationState()
        nav.selectTab(FlashDestination.Transfers)
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-1")

        // Step 1 wins over step 2: the pushed entry pops and the tab is left alone.
        assertTrue(nav.back())
        assertEquals(FlashDestination.Transfers, nav.current.destination)
        assertTrue(nav.back())
        assertEquals(FlashDestination.ChatList, nav.current.destination)
        assertFalse(nav.back())
    }

    @Test
    fun `canGoBack truth table matches what back actually consumes`() {
        // Home tab, empty stack: the only false case.
        assertFalse(FlashNavigationState().canGoBack)

        val onTab = FlashNavigationState()
        onTab.selectTab(FlashDestination.NearbyDevices)
        assertTrue(onTab.canGoBack)

        val pushed = FlashNavigationState()
        pushed.navigate(FlashDestination.Conversation, conversationId = "c1")
        assertTrue(pushed.canGoBack)
    }

    // ---- UI-037 transition direction --------------------------------------------------------

    @Test
    fun `transitionFor resolves the full direction matrix`() {
        val chats = FlashBackStackState(FlashDestination.ChatList)
        val transfers = FlashBackStackState(FlashDestination.Transfers)
        val settings = FlashBackStackState(FlashDestination.Settings)
        val conversation = FlashBackStackState(FlashDestination.Conversation, "c1")

        assertEquals(FlashScreenTransition.None, FlashNavigationMath.transitionFor(chats, chats))
        assertEquals(
            FlashScreenTransition.Push,
            FlashNavigationMath.transitionFor(chats, conversation),
        )
        assertEquals(
            FlashScreenTransition.Pop,
            FlashNavigationMath.transitionFor(conversation, chats),
        )
        assertEquals(
            FlashScreenTransition.TabForward,
            FlashNavigationMath.transitionFor(chats, transfers),
        )
        assertEquals(
            FlashScreenTransition.TabBackward,
            FlashNavigationMath.transitionFor(settings, transfers),
        )
        // Conversation → Conversation (different peer) is still a push, not a pop.
        assertEquals(
            FlashScreenTransition.Push,
            FlashNavigationMath.transitionFor(
                conversation,
                FlashBackStackState(FlashDestination.Conversation, "c2"),
            ),
        )
    }

    @Test
    fun `tab order matches the shell bar and drives isTabRoot`() {
        assertEquals(
            listOf(
                FlashDestination.ChatList,
                FlashDestination.Transfers,
                FlashDestination.NearbyDevices,
                FlashDestination.Settings,
            ),
            FlashNavigationMath.TabOrder,
        )
        assertEquals(0, FlashNavigationMath.tabIndex(FlashDestination.ChatList))
        assertEquals(3, FlashNavigationMath.tabIndex(FlashDestination.Settings))
        assertEquals(-1, FlashNavigationMath.tabIndex(FlashDestination.Conversation))
        assertEquals(FlashDestination.ChatList, FlashNavigationMath.HOME_TAB)
    }

    // ---- pure stack transforms --------------------------------------------------------------

    @Test
    fun `pushed returns the same instance when the push is ignored`() {
        val entries = listOf(FlashBackStackState(FlashDestination.ChatList))

        // Invalid entry (Conversation without an id) and an exact duplicate both no-op, and must
        // return the SAME list so assigning it to snapshot state costs no recomposition.
        assertTrue(
            entries === FlashNavigationMath.pushed(
                entries,
                FlashBackStackState(FlashDestination.Conversation),
            ),
        )
        assertTrue(
            entries === FlashNavigationMath.pushed(
                entries,
                FlashBackStackState(FlashDestination.ChatList),
            ),
        )
    }

    @Test
    fun `pushed caps depth by dropping the entry above the root`() {
        var entries = listOf(FlashBackStackState(FlashDestination.ChatList))
        for (i in 1..FlashNavigationMath.MAX_STACK_DEPTH + 3) {
            entries = FlashNavigationMath.pushed(
                entries,
                FlashBackStackState(FlashDestination.Conversation, "conv-$i"),
            )
        }
        assertEquals(FlashNavigationMath.MAX_STACK_DEPTH, entries.size)
        assertEquals(FlashDestination.ChatList, entries.first().destination)
        assertEquals("conv-${FlashNavigationMath.MAX_STACK_DEPTH + 3}", entries.last().conversationId)
        // The slot right above the root holds the oldest SURVIVING push, not the original one.
        assertEquals("conv-5", entries[1].conversationId)
    }

    @Test
    fun `popped never removes the root`() {
        val root = listOf(FlashBackStackState(FlashDestination.ChatList))
        assertTrue(root === FlashNavigationMath.popped(root))

        val two = root + FlashBackStackState(FlashDestination.Conversation, "c1")
        assertEquals(root, FlashNavigationMath.popped(two))
    }

    // ---- process-death restoration -----------------------------------------------------------

    @Test
    fun `saver round-trips the whole stack`() {
        val nav = FlashNavigationState()
        nav.selectTab(FlashDestination.Transfers)
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-42")

        val saved = with(FlashNavigationStateSaver) { SaverScope { true }.save(nav) }
        val restored = FlashNavigationStateSaver.restore(requireNotNull(saved))

        assertEquals(nav.entries, restored?.entries)
        assertEquals(FlashDestination.Conversation, restored?.current?.destination)
        assertEquals("conv-42", restored?.current?.conversationId)
    }

    @Test
    fun `decoding drops unknown and invalid entries instead of crashing`() {
        assertNull(decodeNavigationEntry("NotADestination|"))
        assertNull(decodeNavigationEntry("|conv-1"))
        assertNull(decodeNavigationEntry("ChatList"))
        // Conversation without an id fails isValid(); ChatList carrying one does too.
        assertNull(decodeNavigationEntry("Conversation|"))
        assertNull(decodeNavigationEntry("ChatList|conv-1"))
        assertEquals(
            FlashBackStackState(FlashDestination.Settings),
            decodeNavigationEntry("Settings|"),
        )
        assertEquals(
            FlashBackStackState(FlashDestination.Conversation, "conv-1"),
            decodeNavigationEntry("Conversation|conv-1"),
        )
    }

    @Test
    fun `restoring an empty or fully invalid stack falls back to the ChatList root`() {
        val restored = FlashNavigationStateSaver.restore(listOf("NotADestination|", "ChatList|c1"))
        assertEquals(1, restored?.stackSize)
        assertEquals(FlashDestination.ChatList, restored?.current?.destination)
    }
}
