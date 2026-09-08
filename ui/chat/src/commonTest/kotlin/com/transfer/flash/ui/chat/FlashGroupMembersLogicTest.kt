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
        assertEquals("Owner", FlashGroupMembersMath.roleBadgeLabel(FlashMemberRole.Owner))
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
}
