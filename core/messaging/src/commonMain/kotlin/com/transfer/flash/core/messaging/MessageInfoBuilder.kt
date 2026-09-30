package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.model.FlashMessageInfoUi
import com.transfer.flash.core.messaging.model.FlashMessageRecipientUi
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.core.messaging.model.FlashRecipientState
import com.transfer.flash.core.messaging.model.FlashWaitingReason
import com.transfer.flash.core.persistence.db.entity.GroupDeliveryEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.persistence.db.entity.ReadCursorEntity

/**
 * The Message Info mapping (UI-051, chat/group sync audit): which section each recipient of a group message belongs
 * in. Pure so the rule can be unit-tested without a database; the repository only feeds it.
 *
 * For each recipient row, first match wins:
 * 1. the member's read cursor has `upToSentAt >= sentAt`: **read**. This is the predicate `MessageDao.markReadUpTo`
 *    uses to set a message READ, so the sheet can never disagree with the bubble's own read tick. Read beats
 *    delivered because a member that read the message has received it even when its delivery receipt was lost or has
 *    not been recorded yet (receipt and read cursor travel on different frames);
 * 2. the delivery row is `DELIVERED`: **delivered**, with the time the receipt arrived;
 * 3. otherwise **waiting**, with [FlashWaitingReason.LeftGroup] when the member's roster row is inactive.
 *
 * Only members with a delivery row are listed: they are the message's recipients. Someone who joined afterwards has no
 * row and was never one. A member whose roster row is gone is named by the start of its device id.
 *
 * Order: read by name; delivered by confirmation time (earliest first) then name; waiting by name. Names compare
 * case-insensitively and ties break on the device id, so the list does not reshuffle between emissions.
 */
internal fun buildGroupMessageInfo(
    messageId: String,
    preview: String,
    sentAt: Long,
    deliveries: List<GroupDeliveryEntity>,
    members: List<GroupMemberEntity>,
    cursors: List<ReadCursorEntity>,
    formatTime: (Long) -> String,
    initialsOf: (String) -> String,
): FlashMessageInfoUi {
    val memberById = members.associateBy { it.deviceId }
    val cursorById = cursors.associateBy { it.memberId }
    val deliveredAtById = deliveries.associate { it.memberId to (it.deliveredAt ?: Long.MAX_VALUE) }
    val recipients = deliveries.map { delivery ->
        val member = memberById[delivery.memberId]
        val name = member?.displayName?.trim()?.ifEmpty { null } ?: delivery.memberId.take(ID_FALLBACK_LENGTH)
        val hasRead = cursorById[delivery.memberId]?.let { it.upToSentAt >= sentAt } == true
        when {
            hasRead -> FlashMessageRecipientUi(delivery.memberId, name, initialsOf(name), FlashRecipientState.Read)
            delivery.state == "DELIVERED" -> FlashMessageRecipientUi(
                id = delivery.memberId,
                name = name,
                initials = initialsOf(name),
                state = FlashRecipientState.Delivered,
                deliveredAtLabel = delivery.deliveredAt?.let(formatTime),
            )
            else -> FlashMessageRecipientUi(
                id = delivery.memberId,
                name = name,
                initials = initialsOf(name),
                state = FlashRecipientState.Waiting,
                waitingReason = if (member != null && !member.isActive) {
                    FlashWaitingReason.LeftGroup
                } else {
                    FlashWaitingReason.DeviceNotReached
                },
            )
        }
    }
    val byName = compareBy<FlashMessageRecipientUi>({ it.name.lowercase() }, { it.id })
    return FlashMessageInfoUi(
        messageId = messageId,
        preview = preview,
        sentLabel = formatTime(sentAt),
        recipients = recipients.filter { it.state == FlashRecipientState.Read }.sortedWith(byName) +
            recipients.filter { it.state == FlashRecipientState.Delivered }
                .sortedWith(compareBy<FlashMessageRecipientUi> { deliveredAtById[it.id] ?: Long.MAX_VALUE }.then(byName)) +
            recipients.filter { it.state == FlashRecipientState.Waiting }.sortedWith(byName),
    )
}

/**
 * The Message Info content for a one-to-one message: the single recipient is the peer and the section follows the
 * bubble's own status (read, delivered, otherwise still to reach). No time is shown: a direct message carries none.
 * Public because the screen builds it without a repository round trip.
 */
public fun buildDirectMessageInfo(
    messageId: String,
    preview: String,
    sentLabel: String,
    recipientId: String,
    recipientName: String,
    recipientInitials: String,
    status: FlashMessageStatus?,
): FlashMessageInfoUi {
    val state = when (status) {
        FlashMessageStatus.Read -> FlashRecipientState.Read
        FlashMessageStatus.Delivered -> FlashRecipientState.Delivered
        else -> FlashRecipientState.Waiting
    }
    return FlashMessageInfoUi(
        messageId = messageId,
        preview = preview,
        sentLabel = sentLabel,
        recipients = listOf(
            FlashMessageRecipientUi(
                id = recipientId,
                name = recipientName,
                initials = recipientInitials,
                state = state,
                waitingReason = if (state == FlashRecipientState.Waiting) FlashWaitingReason.DeviceNotReached else null,
            ),
        ),
    )
}

private const val ID_FALLBACK_LENGTH = 8
