package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.messaging.model.FlashMemberRole
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlashGroupMembersLogicTest {

    private fun member(
        id: String,
        name: String,
        isOnline: Boolean = false,
        role: FlashMemberRole = FlashMemberRole.Member,
        transport: FlashNetworkTransport = FlashNetworkTransport.Unknown,
    ) = FlashGroupMemberUi(id = id, name = name, initials = name.take(2).uppercase(), isOnline = isOnline, role = role, transport = transport)

    // --- Sorting ---

    @Test
    fun `sorting puts online members before offline`() {
        val sorted = FlashGroupMembersMath.sortMembers(
            listOf(
                member("1", "Offline Person"),
                member("2", "Online Person", isOnline = true),
            ),
        )
        assertEquals(listOf("2", "1"), sorted.map { it.id })
    }

    @Test
    fun `sorting ranks owner then admin then member within same presence`() {
        val sorted = FlashGroupMembersMath.sortMembers(
            listOf(
                member("m", "Zed Member", role = FlashMemberRole.Member),
                member("a", "Yara Admin", role = FlashMemberRole.Admin),
                member("o", "Xena Owner", role = FlashMemberRole.Owner),
            ),
        )
        assertEquals(listOf("o", "a", "m"), sorted.map { it.id })
    }

    @Test
    fun `sorting falls back to alphabetical by name`() {
        val sorted = FlashGroupMembersMath.sortMembers(
            listOf(
                member("3", "carl"),
                member("1", "Anna", isOnline = true),
                member("2", "Bea", isOnline = true),
                member("4", "Dave"),
            ),
        )
        assertEquals(listOf("1", "2", "3", "4"), sorted.map { it.id })
    }

    @Test
    fun `sorting empty input stays empty`() {
        assertEquals(emptyList<FlashGroupMemberUi>(), FlashGroupMembersMath.sortMembers(emptyList()))
    }

    // --- Summary label ---

    @Test
    fun `online summary labels are singular safe`() {
        assertEquals("0 of 0 online", FlashGroupMembersMath.onlineSummaryLabel(total = 0, online = 0))
        assertEquals("1 of 1 online", FlashGroupMembersMath.onlineSummaryLabel(total = 1, online = 1))
        assertEquals("4 of 15 online", FlashGroupMembersMath.onlineSummaryLabel(total = 15, online = 4))
        assertEquals("0 of 5 online", FlashGroupMembersMath.onlineSummaryLabel(total = 5, online = 0))
    }

    @Test
    fun `online summary clamps negative inputs`() {
        assertEquals("0 of 3 online", FlashGroupMembersMath.onlineSummaryLabel(total = 3, online = -2))
        assertEquals("1 of 0 online", FlashGroupMembersMath.onlineSummaryLabel(total = -1, online = 1))
    }

    // --- Role badge labels ---

    @Test
    fun `role badges render for owner and admin only`() {
        assertEquals("Creator", FlashGroupMembersMath.roleBadgeLabel(FlashMemberRole.Owner))
        assertEquals("Admin", FlashGroupMembersMath.roleBadgeLabel(FlashMemberRole.Admin))
        assertNull(FlashGroupMembersMath.roleBadgeLabel(FlashMemberRole.Member))
    }

    // --- Row cap ---

    @Test
    fun `visible row count caps at fifty and floors at zero`() {
        assertEquals(50, FlashGroupMembersMath.visibleRowCount(requested = 120))
        assertEquals(12, FlashGroupMembersMath.visibleRowCount(requested = 12))
        assertEquals(0, FlashGroupMembersMath.visibleRowCount(requested = 0))
        assertEquals(0, FlashGroupMembersMath.visibleRowCount(requested = -7))
    }

    @Test
    fun `visible row count respects custom max`() {
        assertEquals(5, FlashGroupMembersMath.visibleRowCount(requested = 12, max = 5))
        assertEquals(3, FlashGroupMembersMath.visibleRowCount(requested = 3, max = 5))
    }

    // --- ADR-044 V2: members the owner introduced ---

    @Test
    fun `an introduced member gets an added-by line and a spoken form, a paired member gets none`() {
        assertEquals("Added by Ada · not verified", FlashGroupMembersMath.introducedByLabel("Ada"))
        assertEquals("added by Ada, not verified", FlashGroupMembersMath.introducedByDescription("Ada"))
        assertNull(FlashGroupMembersMath.introducedByLabel(null))
        assertNull(FlashGroupMembersMath.introducedByDescription(null))
    }

    @Test
    fun `a blank owner name falls back to the group owner`() {
        assertEquals("Added by the group owner · not verified", FlashGroupMembersMath.introducedByLabel(""))
        assertEquals("added by the group owner, not verified", FlashGroupMembersMath.introducedByDescription("  "))
    }

    @Test
    fun `introducedBy defaults to null so existing rows are unchanged`() {
        assertNull(member("1", "Bo").introducedBy)
    }

    // --- ADR-044 V2 E5: the owner removes a member ---

    @Test
    fun `remove is offered on every row except the owner's own`() {
        assertEquals(false, FlashGroupMembersMath.canRemove(member("o", "Ada", role = FlashMemberRole.Owner)))
        assertEquals(true, FlashGroupMembersMath.canRemove(member("m", "Bo", role = FlashMemberRole.Member)))
        assertEquals(true, FlashGroupMembersMath.canRemove(member("a", "Cy", role = FlashMemberRole.Admin)))
    }

    @Test
    fun `the remove confirmation names the member and says what stays behind`() {
        assertEquals("Remove Bo?", FlashGroupMembersMath.removeTitle("Bo"))
        val message = FlashGroupMembersMath.removeMessage("Bo")
        assertEquals(true, message.startsWith("Bo will be removed for everyone in the group"))
        assertEquals(true, message.contains("already received stay on their device"), "a removed member keeps what they have, so the copy must not promise otherwise")
    }

    @Test
    fun `a blank member name reads as this member in the remove confirmation`() {
        assertEquals("Remove this member?", FlashGroupMembersMath.removeTitle("  "))
        assertEquals(true, FlashGroupMembersMath.removeMessage("").startsWith("This member will be removed"))
    }
}
