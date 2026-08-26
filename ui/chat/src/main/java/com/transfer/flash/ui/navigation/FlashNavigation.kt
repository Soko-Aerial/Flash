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
) {
    fun isValid(): Boolean = when (destination) {
        FlashDestination.Conversation -> !conversationId.isNullOrBlank()
        else -> conversationId == null
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

    /** The tab system back falls through to before letting the system exit the app. */
    val HOME_TAB = FlashDestination.ChatList

    /** Position of [destination] in the shell bar, or -1 when it is not a tab root. */
    fun tabIndex(destination: FlashDestination): Int = TabOrder.indexOf(destination)

    /** UI-046: destinations that are shell tab roots (bottom-nav surfaces). */
    fun isTabRoot(destination: FlashDestination): Boolean = tabIndex(destination) >= 0

    /**
     * Stack size after an operation: pushing adds one entry capped at [MAX_STACK_DEPTH],
     * any other operation keeps the current size.
     */
    fun nextStackSize(currentSize: Int, pushing: Boolean): Int =
        if (pushing) minOf(currentSize + 1, MAX_STACK_DEPTH) else currentSize

    /**
     * True when a push must be ignored because it repeats the current entry,
     * including re-pushing the exact same conversation.
     */
    fun shouldIgnorePush(
        currentDestination: FlashDestination,
        currentConversationId: String?,
        newDestination: FlashDestination,
        newConversationId: String?,
    ): Boolean = currentDestination == newDestination && currentConversationId == newConversationId

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
            )
        ) {
            return entries
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
    ): FlashScreenTransition = when {
        from == to -> FlashScreenTransition.None
        !isTabRoot(to.destination) -> FlashScreenTransition.Push
        !isTabRoot(from.destination) -> FlashScreenTransition.Pop
        tabIndex(to.destination) > tabIndex(from.destination) -> FlashScreenTransition.TabForward
        else -> FlashScreenTransition.TabBackward
    }
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
class FlashNavigationState internal constructor(initialEntries: List<FlashBackStackState>) {

    constructor(initial: FlashDestination = FlashDestination.ChatList) :
        this(listOf(FlashBackStackState(initial)))

    /** The whole stack, root first. Read by tests and by the saver; mutated only in here. */
    var entries: List<FlashBackStackState> by mutableStateOf(
        initialEntries.filter { it.isValid() }.ifEmpty { listOf(FlashBackStackState()) },
    )
        private set

    /** Current top-of-stack entry; never empty (root survives all operations). */
    val current: FlashBackStackState
        get() = entries.last()

    /** Number of retained entries, root included. */
    val stackSize: Int
        get() = entries.size

    /**
     * True when [back] will consume the press: either an entry sits above the root, or we are
     * parked on a non-home tab and back should return to [FlashNavigationMath.HOME_TAB].
     */
    val canGoBack: Boolean
        get() = entries.size > 1 || current.destination != FlashNavigationMath.HOME_TAB

    /** Pushes [destination]; see [FlashNavigationMath.pushed] for the guards. */
    fun navigate(destination: FlashDestination, conversationId: String? = null) {
        entries = FlashNavigationMath.pushed(entries, FlashBackStackState(destination, conversationId))
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
        if (!FlashNavigationMath.isTabRoot(destination)) return
        entries = listOf(FlashBackStackState(destination))
    }
}

private const val ENTRY_SEPARATOR = '|'

/**
 * Survives rotation and process death by encoding each entry as `DestinationName|conversationId`.
 * Unknown or invalid entries are dropped on restore rather than crashing the shell.
 */
val FlashNavigationStateSaver: Saver<FlashNavigationState, Any> =
    listSaver<FlashNavigationState, String>(
        save = { state ->
            state.entries.map { "${it.destination.name}$ENTRY_SEPARATOR${it.conversationId ?: ""}" }
        },
        restore = { encoded -> FlashNavigationState(encoded.mapNotNull(::decodeNavigationEntry)) },
    )

internal fun decodeNavigationEntry(raw: String): FlashBackStackState? {
    val separator = raw.indexOf(ENTRY_SEPARATOR)
    if (separator <= 0) return null
    val destination = FlashDestination.entries
        .firstOrNull { it.name == raw.substring(0, separator) }
        ?: return null
    val conversationId = raw.substring(separator + 1).takeIf { it.isNotBlank() }
    return FlashBackStackState(destination, conversationId).takeIf { it.isValid() }
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
    content: @Composable (FlashBackStackState) -> Unit,
) {
    val motion = FlashTheme.motion
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            when (FlashNavigationMath.transitionFor(initialState, targetState)) {
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
        content(page)
    }
}

