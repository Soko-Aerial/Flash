@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.FlashTextFraming

/**
 * One codec for every group text frame. Repeated id lists use a count plus indexed keys rather
 * than a delimiter inside an escaped value, so device identifiers remain unambiguous.
 */
public object GroupFrameCodec {
    public const val GROUP_PREFIX: String = "FLASH_GROUP"
    public const val MESSAGE_PREFIX: String = "FLASH_GMSG"
    public const val RECEIPT_PREFIX: String = "FLASH_GRCPT"
    public const val READ_PREFIX: String = "FLASH_GREAD"
    public const val ACTION_PREFIX: String = "FLASH_GACT"
    public const val DELETE_ACTION: String = "delete"
    public const val SYNC_PREFIX: String = "FLASH_GSYNC"
    public const val MEDIA_PREFIX: String = "FLASH_GMEDIA"

    public fun encode(frame: GroupWireFrame): String {
        val (prefix, fields) = when (frame) {
            is GroupWireFrame.Create -> GROUP_PREFIX to membershipFields(
                action = "create", frame = frame,
                extras = listOf("name" to frame.name) + indexed("member", frame.memberIds),
            )
            is GroupWireFrame.Add -> GROUP_PREFIX to membershipFields(
                action = "add", frame = frame,
                extras = indexed("member", frame.memberIds),
            )
            is GroupWireFrame.Leave -> GROUP_PREFIX to membershipFields(
                action = "leave", frame = frame,
                extras = listOf("memberId" to frame.memberId),
            )
            is GroupWireFrame.State -> GROUP_PREFIX to membershipFields(
                action = "state", frame = frame,
                extras = listOf(
                    "name" to frame.name,
                    "creator" to frame.creatorId,
                    "memberCount" to frame.members.size.toString(),
                ) + frame.members.flatMapIndexed { index, entry ->
                    listOf(
                        "m$index" to entry.deviceId,
                        "n$index" to entry.displayName,
                        "r$index" to entry.role,
                        "j$index" to entry.joinedAt.toString(),
                        "v$index" to entry.membershipVersion.toString(),
                        "o$index" to entry.operationId,
                        "a$index" to entry.isActive.toString(),
                    )
                },
            )
            is GroupWireFrame.Message -> MESSAGE_PREFIX to listOf(
                "groupId" to frame.groupId,
                "msgId" to frame.messageId,
                "from" to frame.from,
                "name" to frame.senderName,
                "sentAt" to frame.sentAt.toString(),
                "text" to frame.text,
                "replyTo" to (frame.replyToId ?: ""),
                "replyPreview" to (frame.replyToPreview ?: ""),
                "keyEpoch" to frame.keyEpoch.toString(),
            )
            is GroupWireFrame.Receipt -> RECEIPT_PREFIX to listOf(
                "groupId" to frame.groupId,
                "msgId" to frame.messageId,
                "from" to frame.from,
                "deliveredAt" to frame.deliveredAt.toString(),
                "keyEpoch" to frame.keyEpoch.toString(),
            )
            is GroupWireFrame.Read -> READ_PREFIX to listOf(
                "groupId" to frame.groupId,
                "from" to frame.from,
                "upTo" to frame.upToMessageId,
                "readAt" to frame.readAt.toString(),
                "keyEpoch" to frame.keyEpoch.toString(),
            )
            is GroupWireFrame.DeleteForEveryone -> ACTION_PREFIX to listOf(
                "action" to DELETE_ACTION,
                "groupId" to frame.groupId,
                "msgId" to frame.messageId,
                "from" to frame.from,
                "keyEpoch" to frame.keyEpoch.toString(),
            )
            is GroupWireFrame.SyncRequest -> SYNC_PREFIX to listOf(
                "op" to "request", "groupId" to frame.groupId, "syncId" to frame.syncId,
                "from" to frame.from, "sinceAt" to frame.sinceSentAt.toString(),
                "sinceId" to frame.sinceMessageId, "tier" to frame.tier.name.lowercase(),
                "maxPerSec" to frame.maxPerSecond.toString(), "maxTotal" to frame.maxTotal.toString(),
                "keyEpoch" to frame.keyEpoch.toString(),
            )
            is GroupWireFrame.SyncClaim -> SYNC_PREFIX to listOf(
                "op" to "claim", "groupId" to frame.groupId, "syncId" to frame.syncId,
                "from" to frame.from, "tier" to frame.tier.name.lowercase(),
                "keyEpoch" to frame.keyEpoch.toString(),
            ) + indexed("msg", frame.messageIds)
            is GroupWireFrame.SyncPush -> SYNC_PREFIX to listOf(
                "op" to "push", "groupId" to frame.groupId, "syncId" to frame.syncId,
                "from" to frame.from, "keyEpoch" to frame.keyEpoch.toString(),
                "msgId" to frame.message.messageId, "name" to frame.message.senderName,
                "sentAt" to frame.message.sentAt.toString(), "text" to frame.message.text,
                "replyTo" to (frame.message.replyToId ?: ""),
                "replyPreview" to (frame.message.replyToPreview ?: ""),
            )
            is GroupWireFrame.SyncAck -> SYNC_PREFIX to listOf(
                "op" to "ack", "groupId" to frame.groupId, "syncId" to frame.syncId,
                "from" to frame.from, "hasMore" to frame.hasMore.toString(),
                "keyEpoch" to frame.keyEpoch.toString(),
            ) + indexed("msg", frame.messageIds)
            is GroupWireFrame.GroupMedia -> MEDIA_PREFIX to listOf(
                "groupId" to frame.groupId,
                "msgId" to frame.messageId,
                "transferId" to frame.transferId,
                "wireFileId" to frame.wireFileId,
                "from" to frame.from,
                "name" to frame.senderName,
                "fileName" to frame.fileName,
                "mime" to frame.mimeType,
                "size" to frame.sizeBytes.toString(),
                "sentAt" to frame.sentAt.toString(),
            )
        }
        return FlashTextFraming.encodeFields(prefix, fields)
    }

    /** Returns null for non-group text, malformed data, or intentionally ignored unknown actions. */
    public fun decode(text: String): GroupWireFrame? {
        FlashTextFraming.parseFields(text, GROUP_PREFIX)?.let { fields ->
            val groupId = fields.required("groupId") ?: return null
            val from = fields.required("from") ?: return null
            val operationId = fields.required("opId") ?: return null
            val version = fields.long("version") ?: return null
            return when (fields["action"]) {
                "create" -> GroupWireFrame.Create(
                    groupId, from, operationId, version, fields.required("name") ?: return null,
                    fields.indexed("member") ?: return null,
                )
                "add" -> GroupWireFrame.Add(groupId, from, operationId, version, fields.indexed("member") ?: return null)
                "leave" -> GroupWireFrame.Leave(
                    groupId, from, operationId, version, fields.required("memberId") ?: return null,
                )
                "state" -> {
                    val name = fields.required("name") ?: return null
                    val creator = fields.required("creator") ?: return null
                    val count = fields.int("memberCount") ?: return null
                    if (count !in 1..GroupPolicy.MAX_MEMBERS) return null
                    val roster = (0 until count).map { index ->
                        GroupWireFrame.RosterEntry(
                            deviceId = fields.required("m$index") ?: return null,
                            displayName = fields.required("n$index") ?: return null,
                            role = fields["r$index"] ?: "member",
                            joinedAt = fields.long("j$index") ?: 0L,
                            membershipVersion = fields.long("v$index") ?: 0L,
                            operationId = fields.required("o$index") ?: return null,
                            isActive = fields["a$index"]?.toBooleanStrictOrNull() ?: true,
                        )
                    }
                    GroupWireFrame.State(groupId, from, operationId, version, name, creator, roster)
                }
                else -> null
            }
        }
        FlashTextFraming.parseFields(text, MESSAGE_PREFIX)?.let { fields ->
            return GroupWireFrame.Message(
                groupId = fields.required("groupId") ?: return null,
                messageId = fields.required("msgId") ?: return null,
                from = fields.required("from") ?: return null,
                senderName = fields.required("name") ?: return null,
                sentAt = fields.long("sentAt") ?: return null,
                text = fields["text"] ?: return null,
                replyToId = fields["replyTo"]?.ifBlank { null },
                replyToPreview = fields["replyPreview"]?.ifBlank { null },
                keyEpoch = fields.long("keyEpoch") ?: 0L,
            )
        }
        FlashTextFraming.parseFields(text, RECEIPT_PREFIX)?.let { fields ->
            return GroupWireFrame.Receipt(
                fields.required("groupId") ?: return null, fields.required("msgId") ?: return null,
                fields.required("from") ?: return null, fields.long("deliveredAt") ?: return null,
                fields.long("keyEpoch") ?: 0L,
            )
        }
        FlashTextFraming.parseFields(text, READ_PREFIX)?.let { fields ->
            return GroupWireFrame.Read(
                fields.required("groupId") ?: return null, fields.required("from") ?: return null,
                fields.required("upTo") ?: return null, fields.long("readAt") ?: return null,
                fields.long("keyEpoch") ?: 0L,
            )
        }
        FlashTextFraming.parseFields(text, ACTION_PREFIX)?.let { fields ->
            return when (fields["action"]) {
                DELETE_ACTION -> GroupWireFrame.DeleteForEveryone(
                    groupId = fields.required("groupId") ?: return null,
                    messageId = fields.required("msgId") ?: return null,
                    from = fields.required("from") ?: return null,
                    keyEpoch = fields.long("keyEpoch") ?: 0L,
                )
                else -> null
            }
        }
        FlashTextFraming.parseFields(text, MEDIA_PREFIX)?.let { fields ->
            return GroupWireFrame.GroupMedia(
                groupId = fields.required("groupId") ?: return null,
                messageId = fields.required("msgId") ?: return null,
                transferId = fields.required("transferId") ?: return null,
                wireFileId = fields.required("wireFileId") ?: return null,
                from = fields.required("from") ?: return null,
                senderName = fields.required("name") ?: return null,
                fileName = fields.required("fileName") ?: return null,
                mimeType = fields["mime"] ?: "application/octet-stream",
                sizeBytes = fields.long("size") ?: 0L,
                sentAt = fields.long("sentAt") ?: 0L,
            )
        }
        FlashTextFraming.parseFields(text, SYNC_PREFIX)?.let { fields ->
            val groupId = fields.required("groupId") ?: return null
            val syncId = fields.required("syncId") ?: return null
            val from = fields.required("from") ?: return null
            val epoch = fields.long("keyEpoch") ?: 0L
            return when (fields["op"]) {
                "request" -> GroupWireFrame.SyncRequest(
                    groupId, syncId, from, fields.long("sinceAt") ?: return null,
                    fields.required("sinceId") ?: return null,
                    fields["tier"]?.uppercase()?.let { runCatching { GroupSyncTier.valueOf(it) }.getOrNull() }
                        ?: return null,
                    fields.int("maxPerSec") ?: return null, fields.int("maxTotal") ?: return null, epoch,
                )
                "claim" -> GroupWireFrame.SyncClaim(
                    groupId, syncId, from, fields.indexed("msg") ?: return null,
                    fields["tier"]?.uppercase()?.let { runCatching { GroupSyncTier.valueOf(it) }.getOrNull() }
                        ?: GroupSyncTier.MEDIUM, epoch,
                )
                "push" -> GroupWireFrame.SyncPush(
                    groupId, syncId, from, GroupWireFrame.Message(
                        groupId, fields.required("msgId") ?: return null, from,
                        fields.required("name") ?: return null, fields.long("sentAt") ?: return null,
                        fields["text"] ?: return null, fields["replyTo"]?.ifBlank { null },
                        fields["replyPreview"]?.ifBlank { null }, epoch,
                    ), epoch,
                )
                "ack" -> GroupWireFrame.SyncAck(
                    groupId, syncId, from, fields.indexed("msg") ?: return null,
                    fields["hasMore"]?.toBooleanStrictOrNull() ?: return null, epoch,
                )
                else -> null
            }
        }
        return null
    }

    private fun membershipFields(
        action: String,
        frame: GroupWireFrame.Membership,
        extras: List<Pair<String, String>>,
    ): List<Pair<String, String>> = listOf(
        "action" to action, "groupId" to frame.groupId, "from" to frame.from,
        "opId" to frame.operationId, "version" to frame.membershipVersion.toString(),
    ) + extras

    private fun indexed(prefix: String, values: List<String>): List<Pair<String, String>> =
        listOf("${prefix}Count" to values.size.toString()) + values.mapIndexed { index, value ->
            "$prefix$index" to value
        }

    private fun Map<String, String>.indexed(prefix: String): List<String>? {
        val count = int("${prefix}Count") ?: return null
        if (count !in 0..GroupPolicy.MAX_REMOTE_MEMBERS) return null
        return (0 until count).map { index -> required("$prefix$index") ?: return null }
    }

    private fun Map<String, String>.required(key: String): String? = this[key]?.takeIf { it.isNotBlank() }
    private fun Map<String, String>.long(key: String): Long? = this[key]?.toLongOrNull()
    private fun Map<String, String>.int(key: String): Int? = this[key]?.toIntOrNull()
}
