package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.model.FlashMessageInfoUi
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.core.messaging.model.FlashRecipientState
import com.transfer.flash.core.messaging.model.FlashWaitingReason
import com.transfer.flash.core.persistence.db.entity.GroupDeliveryEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.persistence.db.entity.ReadCursorEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message Info mapping (UI-051, chat/group sync audit step 6): the rule that decides which section a recipient of a
 * group message is shown in. Pure, so it is pinned here without a database; `SignedGroupsTest` covers the live wiring
 * and `FlashDatabaseInvariantTest` the query behind it.
 */
class MessageInfoBuilderTest {

    private val sentAt = 1_000L

    private fun delivery(member: String, state: String = "PENDING", deliveredAt: Long? = null) =
        GroupDeliveryEntity(messageId = "m1", memberId = member, state = state, nextAttemptAt = 0L, deliveredAt = deliveredAt)

    private fun member(id: String, name: String, active: Boolean = true) = GroupMemberEntity(
        groupId = "g1",
        deviceId = id,
        displayName = name,
        joinedAt = 0L,
        membershipVersion = 1L,
        operationId = "op",
        isActive = active,
    )

    private fun cursor(member: String, upToSentAt: Long) =
        ReadCursorEntity(conversationId = "g1", memberId = member, upToMessageId = "x", upToSentAt = upToSentAt)

    private fun build(
        deliveries: List<GroupDeliveryEntity>,
        members: List<GroupMemberEntity> = emptyList(),
        cursors: List<ReadCursorEntity> = emptyList(),
    ): FlashMessageInfoUi = buildGroupMessageInfo(
        messageId = "m1",
        preview = "hello",
        sentAt = sentAt,
        deliveries = deliveries,
        members = members,
        cursors = cursors,
        formatTime = { "t$it" },
        initialsOf = { it.take(2).uppercase() },
    )

    @Test
    fun `a member whose cursor reached the message is read even if its receipt never arrived`() {
        val info = build(
            deliveries = listOf(delivery("a", state = "PENDING")),
            members = listOf(member("a", "Ada")),
            cursors = listOf(cursor("a", upToSentAt = sentAt + 50)),
        )

        assertEquals(listOf("a"), info.readBy.map { it.id })
        assertTrue(info.deliveredTo.isEmpty())
        assertTrue(info.waiting.isEmpty())
    }

    @Test
    fun `the read test is the same sentAt comparison that turns a message read, boundary included`() {
        val info = build(
            deliveries = listOf(
                delivery("exact", state = "DELIVERED", deliveredAt = 5),
                delivery("behind", state = "DELIVERED", deliveredAt = 6),
            ),
            members = listOf(member("exact", "Exact"), member("behind", "Behind")),
            cursors = listOf(cursor("exact", upToSentAt = sentAt), cursor("behind", upToSentAt = sentAt - 1)),
        )

        assertEquals("a cursor exactly at the message has read it", listOf("exact"), info.readBy.map { it.id })
        assertEquals("one just before it has not", listOf("behind"), info.deliveredTo.map { it.id })
    }

    @Test
    fun `a delivered member carries its confirmation time and the earliest comes first`() {
        val info = build(
            deliveries = listOf(
                delivery("late", state = "DELIVERED", deliveredAt = 300),
                delivery("early", state = "DELIVERED", deliveredAt = 100),
                delivery("noTime", state = "DELIVERED", deliveredAt = null),
            ),
            members = listOf(member("late", "Zed"), member("early", "Yan"), member("noTime", "Abe")),
        )

        assertEquals(listOf("early", "late", "noTime"), info.deliveredTo.map { it.id })
        assertEquals(listOf("t100", "t300", null), info.deliveredTo.map { it.deliveredAtLabel })
    }

    @Test
    fun `a member that has not got it is waiting, and a departed one says so`() {
        val info = build(
            deliveries = listOf(
                delivery("here", state = "SENT"),
                delivery("gone", state = "PENDING"),
                delivery("goneButGotIt", state = "DELIVERED", deliveredAt = 9),
            ),
            members = listOf(
                member("here", "Here"),
                member("gone", "Gone", active = false),
                member("goneButGotIt", "Got", active = false),
            ),
        )

        assertEquals(mapOf("gone" to FlashWaitingReason.LeftGroup, "here" to FlashWaitingReason.DeviceNotReached),
            info.waiting.associate { it.id to it.waitingReason })
        assertEquals("having received it counts even after leaving", listOf("goneButGotIt"), info.deliveredTo.map { it.id })
    }

    @Test
    fun `only members with a delivery row are listed`() {
        val info = build(
            deliveries = listOf(delivery("a", state = "DELIVERED", deliveredAt = 1)),
            members = listOf(member("a", "Ada"), member("joinedLater", "Late")),
            cursors = listOf(cursor("joinedLater", upToSentAt = sentAt + 1)),
        )

        assertEquals("someone who joined afterwards was never a recipient", listOf("a"), info.recipients.map { it.id })
    }

    @Test
    fun `a member missing from the roster is named by its id and a blank name is treated as missing`() {
        val info = build(
            deliveries = listOf(
                delivery("0123456789abcdef", state = "DELIVERED", deliveredAt = 1),
                delivery("blank-name-member", state = "DELIVERED", deliveredAt = 2),
            ),
            members = listOf(member("blank-name-member", "   ")),
        )

        assertEquals(listOf("01234567", "blank-na"), info.deliveredTo.map { it.name })
        assertEquals(listOf("01", "BL"), info.deliveredTo.map { it.initials })
    }

    @Test
    fun `sections are ordered by name without regard to case, ties broken on the id`() {
        val info = build(
            deliveries = listOf(
                delivery("3", state = "SENT"),
                delivery("2", state = "SENT"),
                delivery("1", state = "SENT"),
                delivery("4", state = "SENT"),
            ),
            members = listOf(member("3", "bob"), member("2", "Bob"), member("1", "alice"), member("4", "Zoe")),
        )

        assertEquals("alice, then the two Bobs by id, then Zoe", listOf("1", "2", "3", "4"), info.waiting.map { it.id })
    }

    @Test
    fun `the list reads read, delivered, then waiting and the message is described`() {
        val info = build(
            deliveries = listOf(
                delivery("w", state = "SENT"),
                delivery("d", state = "DELIVERED", deliveredAt = 10),
                delivery("r", state = "DELIVERED", deliveredAt = 20),
            ),
            members = listOf(member("w", "W"), member("d", "D"), member("r", "R")),
            cursors = listOf(cursor("r", upToSentAt = sentAt)),
        )

        assertEquals(listOf("r", "d", "w"), info.recipients.map { it.id })
        assertEquals(listOf(FlashRecipientState.Read, FlashRecipientState.Delivered, FlashRecipientState.Waiting), info.recipients.map { it.state })
        assertEquals("m1", info.messageId)
        assertEquals("hello", info.preview)
        assertEquals("t$sentAt", info.sentLabel)
    }

    @Test
    fun `a message with no recipient rows yields an empty but described info`() {
        val info = build(deliveries = emptyList())

        assertTrue(info.recipients.isEmpty())
        assertEquals("t$sentAt", info.sentLabel)
    }

    @Test
    fun `a direct message follows its own bubble status`() {
        fun direct(status: FlashMessageStatus?) = buildDirectMessageInfo(
            messageId = "m1", preview = "hi", sentLabel = "9:41", recipientId = "peer", recipientName = "Bo",
            recipientInitials = "BO", status = status,
        )

        assertEquals(FlashRecipientState.Read, direct(FlashMessageStatus.Read).recipients.single().state)
        assertEquals(FlashRecipientState.Delivered, direct(FlashMessageStatus.Delivered).recipients.single().state)
        for (status in listOf(FlashMessageStatus.Sent, FlashMessageStatus.Pending, FlashMessageStatus.Failed, null)) {
            val recipient = direct(status).recipients.single()
            assertEquals("$status is not delivered yet", FlashRecipientState.Waiting, recipient.state)
            assertEquals(FlashWaitingReason.DeviceNotReached, recipient.waitingReason)
        }
        assertNull("a direct message carries no confirmation time", direct(FlashMessageStatus.Delivered).recipients.single().deliveredAtLabel)
        assertEquals("9:41", direct(FlashMessageStatus.Read).sentLabel)
    }
}
