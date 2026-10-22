package com.transfer.flash.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.theme.FlashTheme

/** Top-level destinations of the Flash showcase app (UI-033). */
enum class FlashDestination(val title: String) {
    ChatList("Chats"),
    Conversation("Conversation"),
    Transfers("Transfers"),
    NearbyDevices("Nearby devices"),
    Settings("Settings"),

    /**
     * UI-058: the organisation tab. A tab root ONLY while the device is enrolled
     * ([FlashNavigationMath.tabOrder]); it takes Transfers' slot, and Transfers becomes a pushed
     * destination. Appended last: the saver persists names, but keep any ordinal user safe.
     */
    Hub("Hub"),
}

/**
 * Immutable snapshot of one back-stack entry (UI-033).
 *
 * [isValid] enforces that a [FlashDestination.Conversation] entry always carries
 * a non-blank [conversationId] and that other destinations carry none.
 */
data class FlashBackStackState(
    val destination: FlashDestination = FlashDestination.ChatList,
    val conversationId: String? = null,
    /**
     * UI-024 upgrade: the message to open the conversation at, set when a global search result is
     * tapped. Only meaningful on [FlashDestination.Conversation]; it is part of the state (rather
     * than a one-shot event) so it survives the rotation that can happen while the thread loads.
     */
    val targetMessageId: String? = null,
    /**
     * Bumped when the SAME search hit is tapped again while its chat is already open, so the screen jumps again (it jumps once per
     * (target, nonce)). Transient: never saved, because a restored stack must not replay a jump.
     */
    val targetNonce: Int = 0,
) {
    fun isValid(): Boolean = when (destination) {
        FlashDestination.Conversation -> !conversationId.isNullOrBlank()
        else -> conversationId == null && targetMessageId == null
    }
}

/**
 * Direction of a screen change, derived from the two back-stack entries alone
 * ([FlashNavigationMath.transitionFor]) so no direction bookkeeping is needed in the
 * state holder. Drives which UI-037 token pair [FlashAnimatedScreen] plays.
 */
enum class FlashScreenTransition { None, Push, Pop, TabForward, TabBackward }

/** Pure helpers backing the navigation logic so it can be unit tested without Compose. */
object FlashNavigationMath {

    /** Maximum retained entries including the root; older entries drop off the bottom. */
    const val MAX_STACK_DEPTH = 10

    /**
     * UI-046: bottom-nav display order. Membership defines [isTabRoot] and the index defines
     * lateral transition direction, so this list MUST stay in sync with the host's
     * `bottomNavTabs` (see `MainActivity`).
     */
    val TabOrder = listOf(
        FlashDestination.ChatList,
        FlashDestination.Transfers,
        FlashDestination.NearbyDevices,
        FlashDestination.Settings,
    )

    /** UI-058: the tab set of an enrolled device: Hub takes Transfers' slot. */
    val TabOrderEnrolled = listOf(
        FlashDestination.ChatList,
        FlashDestination.Hub,
        FlashDestination.NearbyDevices,
        FlashDestination.Settings,
    )

    /**
     * The tab set as a function of enrolment (UI-058, ADR-133). `tabOrder(false)` IS [TabOrder], so
     * an unenrolled device sees exactly the four tabs it always had.
     */
    fun tabOrder(hubEnrolled: Boolean): List<FlashDestination> =
        if (hubEnrolled) TabOrderEnrolled else TabOrder

    /** The tab system back falls through to before letting the system exit the app. */
    val HOME_TAB = FlashDestination.ChatList

    /** Position of [destination] in the shell bar, or -1 when it is not a tab root. */
    fun tabIndex(destination: FlashDestination, hubEnrolled: Boolean = false): Int =
        tabOrder(hubEnrolled).indexOf(destination)

    /**
     * UI-046: destinations that are shell tab roots (bottom-nav surfaces). When [hubEnrolled],
     * Transfers is NOT a tab root (it is pushed from the chat-list top bar) and Hub is.
     */
    fun isTabRoot(destination: FlashDestination, hubEnrolled: Boolean = false): Boolean =
        tabIndex(destination, hubEnrolled) >= 0

    /**
     * Whether the phone's bottom bar is on screen. The Hub is a tab only for an enrolled device, so the enrolment flag must
     * reach this decision: without it the bar vanished the moment the Hub opened (ERROR-409).
     */
    fun showsBottomBar(destination: FlashDestination, hubEnrolled: Boolean, railMode: Boolean): Boolean =
        !railMode && (isTabRoot(destination, hubEnrolled) || keepsBarWhenPushed(destination, hubEnrolled))

    /**
     * Transfers is pushed (not a tab) for an enrolled device, but it is still a place the person navigates from, so the bar stays:
     * opening it from the top-bar indicator must not take the navigation away (owner report 2026-10-16). The bar then highlights
     * the tab underneath ([shellTab]) and tapping a tab leaves Transfers.
     */
    fun keepsBarWhenPushed(destination: FlashDestination, hubEnrolled: Boolean): Boolean =
        hubEnrolled && destination == FlashDestination.Transfers

    /**
     * Stack size after an operation: pushing adds one entry capped at [MAX_STACK_DEPTH],
     * any other operation keeps the current size.
     */
    fun nextStackSize(currentSize: Int, pushing: Boolean): Int =
        if (pushing) minOf(currentSize + 1, MAX_STACK_DEPTH) else currentSize

    /**
     * True when a push must be ignored because it repeats the current entry,
     * including re-pushing the exact same conversation. A different [newTargetMessageId] is a
     * *different* entry on purpose: tapping a search result for a message in the chat that is
     * already open must still jump to that message instead of being swallowed as a duplicate.
     */
    fun shouldIgnorePush(
        currentDestination: FlashDestination,
        currentConversationId: String?,
        newDestination: FlashDestination,
        newConversationId: String?,
        currentTargetMessageId: String? = null,
        newTargetMessageId: String? = null,
    ): Boolean = currentDestination == newDestination &&
        currentConversationId == newConversationId &&
        currentTargetMessageId == newTargetMessageId

    /** Title shown for a destination; a loaded conversation name wins over the enum title. */
    fun resolveConversationTitle(
        destination: FlashDestination,
        conversationName: String? = null,
    ): String = conversationName?.takeIf { it.isNotBlank() } ?: destination.title

    /**
     * Result of pushing [next] onto [entries]. Returns [entries] **identically** (same instance)
     * when the push must be ignored, so assigning the result to snapshot state is a no-op and
     * costs no recomposition. Invalid entries are rejected, exact duplicates of the current top
     * are ignored, and overflow past [MAX_STACK_DEPTH] drops the oldest entry *above* the root —
     * the root itself always survives.
     */
    fun pushed(
        entries: List<FlashBackStackState>,
        next: FlashBackStackState,
    ): List<FlashBackStackState> {
        if (!next.isValid()) return entries
        val current = entries.lastOrNull() ?: return listOf(next)
        if (shouldIgnorePush(
                current.destination, current.conversationId, next.destination, next.conversationId,
                current.targetMessageId, next.targetMessageId,
            )
        ) {
            // The same search hit tapped again in the chat that is open: keep the entry but ask the screen to jump once more.
            if (current.destination == FlashDestination.Conversation && next.targetMessageId != null) {
                return entries.dropLast(1) + current.copy(targetNonce = current.targetNonce + 1)
            }
            return entries
        }
        // Same conversation, new target (a second search hit inside the chat already open):
        // REPLACE the top entry instead of stacking a second copy of the same screen, so back
        // does not walk through every message that was searched.
        if (current.destination == FlashDestination.Conversation &&
            next.destination == FlashDestination.Conversation &&
            current.conversationId == next.conversationId
        ) {
            return entries.dropLast(1) + next
        }
        val grown = entries + next
        if (grown.size <= MAX_STACK_DEPTH || grown.size <= 1) return grown
        return buildList(grown.size - 1) {
            add(grown.first())
            addAll(grown.subList(2, grown.size))
        }
    }

    /** Result of popping the top entry; the root is never popped. */
    fun popped(entries: List<FlashBackStackState>): List<FlashBackStackState> =
        if (entries.size > 1) entries.dropLast(1) else entries

    /**
     * Which transition a `from → to` screen change should play. Fully derivable because
     * [FlashDestination.Conversation] is the only non-tab-root destination: entering it is a
     * push, leaving it is a pop, and tab-root → tab-root is a lateral hop whose direction is
     * the sign of the [tabIndex] delta.
     */
    fun transitionFor(
        from: FlashBackStackState,
        to: FlashBackStackState,
        hubEnrolled: Boolean = false,
    ): FlashScreenTransition = when {
        from == to -> FlashScreenTransition.None
        // Re-targeting the SAME conversation (a search hit inside the chat already open) is not a
        // push: the screen stays put and only scrolls. Replaying the push animation would look like
        // a navigation that did not happen.
        from.destination == to.destination && from.conversationId == to.conversationId ->
            FlashScreenTransition.None
        !isTabRoot(to.destination, hubEnrolled) -> FlashScreenTransition.Push
        !isTabRoot(from.destination, hubEnrolled) -> FlashScreenTransition.Pop
        tabIndex(to.destination, hubEnrolled) > tabIndex(from.destination, hubEnrolled) ->
            FlashScreenTransition.TabForward
        else -> FlashScreenTransition.TabBackward
    }

    /**
     * True when the top of [entries] is a conversation that was reached from the Hub tab (open-items tracker H-16): walking down
     * through any conversations stacked on each other, the first other entry is [FlashDestination.Hub]. Two-pane hosts then keep
     * the Hub as the list pane next to the chat, so opening a colleague does not swap the Hub for the chat list, and Back lands on
     * the Hub exactly as it was.
     */
    fun conversationOpenedFromHub(entries: List<FlashBackStackState>, hubEnrolled: Boolean): Boolean {
        if (!hubEnrolled || entries.lastOrNull()?.destination != FlashDestination.Conversation) return false
        return entries.lastOrNull { it.destination != FlashDestination.Conversation }?.destination == FlashDestination.Hub
    }

    /**
     * Which tab the shell bar / rail / sidebar highlights for [entries]. A tab root highlights
     * itself; a conversation highlights Chats (unchanged behaviour); any other pushed destination
     * (Transfers while enrolled) highlights the nearest tab root beneath it, else Chats.
     */
    fun shellTab(entries: List<FlashBackStackState>, hubEnrolled: Boolean): FlashDestination {
        val top = entries.lastOrNull()?.destination ?: return HOME_TAB
        return when {
            top == FlashDestination.Conversation ->
                if (conversationOpenedFromHub(entries, hubEnrolled)) FlashDestination.Hub else FlashDestination.ChatList
            isTabRoot(top, hubEnrolled) -> top
            else -> entries.lastOrNull { isTabRoot(it.destination, hubEnrolled) }?.destination ?: HOME_TAB
        }
    }

    /**
     * Rewrites a stack so it is valid for the CURRENT enrolment (UI-058). Total: never throws,
     * never returns an empty list, never returns an invalid entry, never exceeds [MAX_STACK_DEPTH],
     * idempotent, and returns [entries] itself when nothing had to change.
     *
     * - Invalid entries are dropped (as the saver always did).
     * - Not enrolled: every [FlashDestination.Hub] entry is removed; if Hub was the root, Chats
     *   becomes the root so the stack still starts at a tab.
     * - Enrolled: Transfers is no longer a tab root, so a stack that STARTS with it becomes
     *   Chats + Transfers (the user stays on Transfers; back returns to Chats).
     * - Consecutive duplicates created by the above collapse into one entry.
     */
    fun normalised(
        entries: List<FlashBackStackState>,
        hubEnrolled: Boolean,
    ): List<FlashBackStackState> {
        var result = entries.filter { it.isValid() }
        val chatsRoot = FlashBackStackState(FlashDestination.ChatList)
        if (hubEnrolled) {
            if (result.firstOrNull()?.destination == FlashDestination.Transfers) {
                result = listOf(chatsRoot) + result
            }
        } else {
            val rootWasHub = result.firstOrNull()?.destination == FlashDestination.Hub
            result = result.filter { it.destination != FlashDestination.Hub }
            if (rootWasHub) result = listOf(chatsRoot) + result
        }
        result = result.filterIndexed { index, entry -> index == 0 || entry != result[index - 1] }
        if (result.isEmpty()) result = listOf(chatsRoot)
        if (result.size > MAX_STACK_DEPTH) {
            result = listOf(result.first()) + result.takeLast(MAX_STACK_DEPTH - 1)
        }
        return if (result == entries) entries else result
    }

    /**
     * UI-034 / AD-3: In two-pane mode, system back / Escape clears the detail selection
     * (the list stays, the detail returns to placeholder) rather than popping the whole navigation stack.
     * In single-pane mode, back pops to the chat list.
     */
    fun shouldClearSelectionOnBack(
        currentDestination: FlashDestination,
        isTwoPane: Boolean,
    ): Boolean = isTwoPane && currentDestination == FlashDestination.Conversation
}

/**
 * Navigation state holder (UI-033).
 *
 * The stack is ONE immutable list held in [mutableStateOf], so every operation is a single
 * atomic snapshot write that Compose can observe. This is load-bearing: while the stack lived
 * in a plain `mutableListOf`, `navigate`/`back`/`selectTab` mutated invisibly — chat rows and
 * bottom-nav taps changed the stack but never recomposed, and `BackHandler(enabled = canGoBack)`
 * latched its first value forever. All decision logic still lives in the pure
 * [FlashNavigationMath] helpers, so it stays unit-testable without Compose.
 */
class FlashNavigationState internal constructor(
    initialEntries: List<FlashBackStackState>,
    initialHubEnrolled: Boolean = false,
) {

    constructor(initial: FlashDestination = FlashDestination.ChatList) :
        this(listOf(FlashBackStackState(initial)), false)

    /**
     * UI-058: whether the shell shows the Hub tab (in Transfers' slot). Only the host sets it, and
     * only once enrolment is KNOWN (the engine is ready); see [updateHubEnrolled].
     */
    var hubEnrolled: Boolean by mutableStateOf(initialHubEnrolled)
        private set

    /** The whole stack, root first. Read by tests and by the saver; mutated only in here. */
    var entries: List<FlashBackStackState> by mutableStateOf(
        FlashNavigationMath.normalised(initialEntries, initialHubEnrolled),
    )
        private set

    /** Current top-of-stack entry; never empty (root survives all operations). */
    val current: FlashBackStackState
        get() = entries.last()

    /** Number of retained entries, root included. */
    val stackSize: Int
        get() = entries.size

    /** The tab the shell bar / rail / sidebar highlights now (see [FlashNavigationMath.shellTab]). */
    val shellTab: FlashDestination
        get() = FlashNavigationMath.shellTab(entries, hubEnrolled)

    /** The open conversation was reached from the Hub (see [FlashNavigationMath.conversationOpenedFromHub]). */
    val conversationOpenedFromHub: Boolean
        get() = FlashNavigationMath.conversationOpenedFromHub(entries, hubEnrolled)

    /**
     * True when [back] will consume the press: either an entry sits above the root, or we are
     * parked on a non-home tab and back should return to [FlashNavigationMath.HOME_TAB].
     */
    val canGoBack: Boolean
        get() = entries.size > 1 || current.destination != FlashNavigationMath.HOME_TAB

    /** Pushes [destination]; see [FlashNavigationMath.pushed] for the guards. Hub is ignored when not enrolled. */
    fun navigate(
        destination: FlashDestination,
        conversationId: String? = null,
        targetMessageId: String? = null,
    ) {
        if (destination == FlashDestination.Hub && !hubEnrolled) return
        val target = targetMessageId?.takeIf {
            destination == FlashDestination.Conversation && it.isNotBlank()
        }
        entries = FlashNavigationMath.pushed(
            entries,
            FlashBackStackState(destination, conversationId, target),
        )
    }

    /**
     * Opens the transfers list from any entry point (notification, shortcut, share, top-bar icon).
     * Not enrolled: Transfers is a tab, so this selects it. Enrolled: it is pushed on the current
     * stack (a repeat while already open is ignored by the push guard).
     */
    fun openTransfers() {
        if (hubEnrolled) navigate(FlashDestination.Transfers) else selectTab(FlashDestination.Transfers)
    }

    /**
     * Applies a changed enrolment: rewrites the stack so it is valid for the new tab set (Hub
     * entries vanish on un-enrol, a Transfers root becomes Chats + Transfers on enrol). No-op when
     * unchanged. Hosts call this only once enrolment is known, never from a not-yet-ready default.
     */
    fun updateHubEnrolled(enrolled: Boolean) {
        if (enrolled == hubEnrolled) return
        hubEnrolled = enrolled
        entries = FlashNavigationMath.normalised(entries, enrolled)
    }

    /**
     * Resolves back in three steps: pop a pushed entry, else fall back to the home tab, else
     * return false so the host does not consume the press and the system exits the app.
     */
    fun back(): Boolean {
        if (entries.size > 1) {
            entries = FlashNavigationMath.popped(entries)
            return true
        }
        if (current.destination != FlashNavigationMath.HOME_TAB) {
            selectTab(FlashNavigationMath.HOME_TAB)
            return true
        }
        return false
    }

    /**
     * UI-046: switches the active shell tab by REPLACING the whole stack with a single
     * root entry — tabs are shell-level state, not pushed destinations (docs/ui-page-plan.md).
     * Non-tab destinations are ignored; re-selecting the current tab writes a structurally equal
     * list, which snapshot state treats as no change.
     */
    fun selectTab(destination: FlashDestination) {
        if (!FlashNavigationMath.isTabRoot(destination, hubEnrolled)) return
        entries = listOf(FlashBackStackState(destination))
    }

    /**
     * Two-pane hosts: taps on the Chats tab, optionally restoring the chat that was open in the detail pane. The stack is
     * REPLACED with Chats (+ that conversation), never pushed onto the current tab: pushing a conversation on top of the Hub
     * made [conversationOpenedFromHub] true, so the Hub stayed in the list pane and highlighted while the user was asking for
     * Chats.
     */
    fun selectChatsTab(restoreConversationId: String? = null) {
        val root = FlashBackStackState(FlashDestination.ChatList)
        entries = if (restoreConversationId.isNullOrBlank()) {
            listOf(root)
        } else {
            listOf(root, FlashBackStackState(FlashDestination.Conversation, restoreConversationId))
        }
    }
}

private const val ENTRY_SEPARATOR = '|'

/** Saver marker entry carrying the enrolment the saved stack was valid for (no destination name starts with '@'). */
internal const val HUB_MARKER_PREFIX = "@hubEnrolled|"

/**
 * Survives rotation and process death by encoding each entry as
 * `DestinationName|conversationId|targetMessageId` (the target is empty for ordinary entries).
 * Unknown or invalid entries are dropped on restore rather than crashing the shell, and a
 * two-field entry saved by an older build still restores (empty target).
 */
val FlashNavigationStateSaver: Saver<FlashNavigationState, Any> =
    listSaver<FlashNavigationState, String>(
        save = { state ->
            listOf("$HUB_MARKER_PREFIX${if (state.hubEnrolled) "1" else "0"}") +
                state.entries.map {
                    "${it.destination.name}$ENTRY_SEPARATOR${it.conversationId ?: ""}$ENTRY_SEPARATOR${it.targetMessageId ?: ""}"
                }
        },
        restore = { encoded ->
            val enrolled = encoded.firstOrNull { it.startsWith(HUB_MARKER_PREFIX) }
                ?.removePrefix(HUB_MARKER_PREFIX) == "1"
            FlashNavigationState(
                encoded.filterNot { it.startsWith(HUB_MARKER_PREFIX) }.mapNotNull(::decodeNavigationEntry),
                enrolled,
            )
        },
    )

internal fun decodeNavigationEntry(raw: String): FlashBackStackState? {
    val separator = raw.indexOf(ENTRY_SEPARATOR)
    if (separator <= 0) return null
    val destination = FlashDestination.entries
        .firstOrNull { it.name == raw.substring(0, separator) }
        ?: return null
    val rest = raw.substring(separator + 1).split(ENTRY_SEPARATOR, limit = 2)
    val conversationId = rest.getOrNull(0)?.takeIf { it.isNotBlank() }
    val targetMessageId = rest.getOrNull(1)?.takeIf { it.isNotBlank() }
    return FlashBackStackState(destination, conversationId, targetMessageId).takeIf { it.isValid() }
}

/** Remembers a [FlashNavigationState] across recomposition, rotation and process death. */
@Composable
fun rememberFlashNavigationState(): FlashNavigationState =
    rememberSaveable(saver = FlashNavigationStateSaver) { FlashNavigationState() }

/**
 * Screen-level transition host (UI-033) over foundation `AnimatedContent`, driven exclusively by
 * UI-037 motion tokens and by [FlashNavigationMath.transitionFor]:
 *
 * - Push (→ Conversation): incoming slides in from the trailing edge, above the outgoing screen.
 * - Pop (Conversation →): mirrored, and the *outgoing* screen rides on top as it leaves.
 * - Tab hop: short lateral slide in the direction of the tab-index delta, like a pager.
 *
 * `sizeTransform = null` because every page fills the window — animating the container size would
 * only add a needless relayout per frame.
 */
@Composable
fun FlashAnimatedScreen(
    targetState: FlashBackStackState,
    modifier: Modifier = Modifier,
    hubEnrolled: Boolean = false,
    content: @Composable (FlashBackStackState) -> Unit,
) {
    val motion = FlashTheme.motion
    val saveableStateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            when (FlashNavigationMath.transitionFor(initialState, targetState, hubEnrolled)) {
                FlashScreenTransition.Push -> ContentTransform(
                    targetContentEnter = motion.screenPushEnter(),
                    initialContentExit = motion.screenPushExit(),
                    targetContentZIndex = 1f,
                    sizeTransform = null,
                )
                FlashScreenTransition.Pop -> ContentTransform(
                    targetContentEnter = motion.screenPopEnter(),
                    initialContentExit = motion.screenPopExit(),
                    targetContentZIndex = 0f,
                    sizeTransform = null,
                )
                FlashScreenTransition.TabForward -> ContentTransform(
                    targetContentEnter = motion.tabEnter(towardEnd = true),
                    initialContentExit = motion.tabExit(towardEnd = true),
                    sizeTransform = null,
                )
                FlashScreenTransition.TabBackward -> ContentTransform(
                    targetContentEnter = motion.tabEnter(towardEnd = false),
                    initialContentExit = motion.tabExit(towardEnd = false),
                    sizeTransform = null,
                )
                FlashScreenTransition.None -> ContentTransform(
                    targetContentEnter = EnterTransition.None,
                    initialContentExit = ExitTransition.None,
                    sizeTransform = null,
                )
            }
        },
        label = "flashNavigation",
    ) { page ->
        // AnimatedContent discards the page it leaves, and `rememberSaveable` state (the Hub's open announcement, search,
        // People filter, list scroll) goes with it. A tab page therefore keeps its saveable state under its destination.
        // A conversation is deliberately NOT kept: it must open fresh, not with the last visit's composer or search.
        if (page.destination == FlashDestination.Conversation) {
            content(page)
        } else {
            saveableStateHolder.SaveableStateProvider(page.destination.name) { content(page) }
        }
    }
}

