package com.transfer.flash.ui.hub

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HUBT-U90: the no-dead-actions checklist (UI-058 section "no dead actions"). Every [FlashHubAction] names the
 * [FlashHubActions] member that serves it (or is pure UI state), and the screens must actually read that member.
 * There is no Compose UI test infrastructure in this project, so the check scans the Hub sources: it fails when an
 * action is added to the enum without an entry here, when a member stops being used by any screen, and when a
 * [FlashHubActions] member is neither mapped nor listed as infrastructure.
 */
class FlashHubNoDeadActionsTest {

    /** action -> members of [FlashHubActions] it needs; empty = pure UI state with no host involvement. */
    private val table: Map<FlashHubAction, List<String>> = mapOf(
        FlashHubAction.NEW_ANNOUNCEMENT to listOf("postAnnouncement"),
        FlashHubAction.EDIT_ANNOUNCEMENT to listOf("editAnnouncement"),
        FlashHubAction.DELETE_ANNOUNCEMENT to listOf("deleteAnnouncement"),
        FlashHubAction.PIN_ANNOUNCEMENT to listOf("pinAnnouncement"),
        FlashHubAction.COPY_ANNOUNCEMENT to emptyList(),
        FlashHubAction.OPEN_ANNOUNCEMENT to listOf("onMarkRead"),
        FlashHubAction.OPEN_LINK to listOf("onOpenLink"),
        FlashHubAction.SEARCH to emptyList(),
        FlashHubAction.SELECT_SEGMENT to emptyList(),
        FlashHubAction.PULL_REFRESH to listOf("onRefresh"),
        FlashHubAction.RETRY_REFRESH to listOf("onRefresh"),
        FlashHubAction.SHOW_WHY to emptyList(),
        FlashHubAction.SIGN_OUT to listOf("onSignOut"),
        FlashHubAction.OPEN_PERSON to emptyList(),
        FlashHubAction.MESSAGE_PERSON to listOf("startConversation", "onOpenConversation"),
        FlashHubAction.CALL_PERSON to listOf("onCall", "callAllowed"),
        FlashHubAction.PAIR_PERSON to listOf("onPairColleague"),
        FlashHubAction.OPEN_DEPARTMENT to emptyList(),
        FlashHubAction.MESSAGE_HEAD to listOf("startConversation", "onOpenConversation"),
        FlashHubAction.JOIN_GROUP to listOf("groupAction"),
        FlashHubAction.OPEN_GROUP to listOf("onOpenGroup"),
        FlashHubAction.DISMISS_TIP to listOf("onDismissTip"),
        FlashHubAction.FILTER_PEOPLE to emptyList(),
        FlashHubAction.MANAGE_GROUP_REQUESTS to listOf("onManageGroupRequests"),
        FlashHubAction.RETRY_GROUPS to listOf("onRetryGroups"),
    )

    /** Members that are plumbing, not user actions. */
    private val infrastructure = setOf("onRouteConsumed", "drafts")

    /** Constructor properties only: the compiler also adds static and synthetic fields (for example Compose's `$stable`). */
    private fun hostMembers(): Set<String> = FlashHubActions::class.java.declaredFields
        .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.name }.toSet()

    private fun hubSources(): String {
        val dir = File("src/commonMain/kotlin/com/transfer/flash/ui/hub")
        assertTrue(dir.isDirectory, "run from the module directory: ${dir.absolutePath}")
        return dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.joinToString("\n") { it.readText() }
    }

    @Test fun everyActionHasAChecklistRow() {
        assertEquals(FlashHubAction.entries.toSet(), table.keys, "add a row for every new FlashHubAction")
    }

    @Test fun everyNamedMemberExistsOnTheHostContract() {
        val members = hostMembers()
        table.values.flatten().toSet().forEach { assertTrue(it in members, "FlashHubActions has no member $it") }
    }

    @Test fun everyHostMemberIsMappedOrInfrastructure() {
        val members = hostMembers()
        val mapped = table.values.flatten().toSet() + infrastructure
        assertEquals(emptySet(), members - mapped, "a FlashHubActions member no action uses is a dead hook")
    }

    @Test fun theScreensReadEveryMappedMember() {
        val src = hubSources()
        (table.values.flatten().toSet() + infrastructure).forEach { member ->
            assertTrue(Regex("""actions\.$member\b""").containsMatchIn(src), "no screen reads actions.$member")
        }
    }
}
