@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.enterprise.files.HubFileText
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.messaging.protocol.HubFileFrame
import com.transfer.flash.core.persistence.db.dao.HubFileDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.entity.HubFileEntity
import com.transfer.flash.core.persistence.db.entity.HubFileStates
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.security.enterprise.HubFileKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The chat half of a file sent through the hub (ADR-216, HUB-FILES P3): the ticket message on both ends.
 *
 * Outgoing, a file has two parts that start together and finish in either order: the chat message the user sees and
 * the upload the hub-file engine runs. The ticket (the message that carries the file's key) is queued exactly once,
 * when both exist ([attachMessage], [queue], under one lock and a conditional update). Incoming, a ticket becomes a
 * chat message and a `hub_file` row in one transaction ([accept]); the download itself belongs to the engine.
 *
 * Group files do not take this path (deviation D14): there is no per-member sealed key yet.
 */
internal class HubFileMessages(
    private val dao: HubFileDao,
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val runInTransaction: suspend (block: suspend () -> Unit) -> Unit,
    private val timeSource: FlashTimeSource,
    /** The hub this device is enrolled with, or null when it is not enrolled (then no ticket is taken). */
    private val hubUrl: () -> String?,
    /** Creates or refreshes the direct chat's row; called inside the insert's transaction (ERROR-087). */
    private val upsertConversation: suspend (conversationId: String, title: String?, sortOrder: Long) -> Unit,
    private val peerName: (String) -> String?,
    private val wakeOutbox: () -> Unit,
) {
    private val queueLock = Mutex()

    /** What [accept] did with a ticket. */
    sealed interface Accepted {
        /** Stored (or already stored by an earlier delivery of the same ticket). [fresh] is true only the first time. */
        data class Stored(val fresh: Boolean, val fileName: String, val mime: String) : Accepted

        /** Not taken: the caller rejects it (and the hub is told, so it does not redeliver). */
        data object Refused : Accepted
    }

    /**
     * The status the chat message of outgoing file [transferId] starts with, or null when it is not a hub file. A hub file
     * stays PENDING until the recipient's receipt (not SENT like a direct transfer); one the hub could not take (no sealing
     * key, too large) or that was cancelled before its message existed starts FAILED.
     */
    suspend fun initialStatus(transferId: String): String? {
        val row = dao.get(transferId)?.takeIf { it.direction == HubFileStates.OUT } ?: return null
        return if (row.state == HubFileStates.FAILED || row.state == HubFileStates.CANCELLED) "FAILED" else "PENDING"
    }

    /**
     * Outgoing file [transferId] was cancelled or failed for good: its message, unless already acknowledged, is Failed and
     * its ticket leaves the outbox. A message not linked yet takes FAILED from [initialStatus] instead.
     */
    suspend fun ended(transferId: String) {
        val row = dao.get(transferId)?.takeIf { it.direction == HubFileStates.OUT } ?: return
        val messageId = row.messageId ?: return
        giveUp(messageId, row, row.state.lowercase())
    }

    /** Ends ticket [messageId] in one transaction, so no reader sees a Failed message that still has an outbox row. */
    suspend fun giveUp(messageId: String, row: HubFileEntity, reason: String) {
        runInTransaction {
            messageDao.updateStatusIfUnacknowledged(messageId, "FAILED")
            outboxDao.delete(messageId)
        }
        FlashProbe.emit("hubfile.ticket.dropped", "id" to FlashProbe.short(row.fileId), "reason" to reason)
    }

    /** The chat message [messageId] shows outgoing file [transferId]; queues the ticket if the upload already finished. */
    suspend fun attachMessage(transferId: String, messageId: String) {
        if (dao.attachMessage(transferId, messageId, timeSource.nowMs()) == 1) queue(transferId)
    }

    /**
     * Puts the ticket for outgoing file [transferId] into the outbox once its upload finished (READY) and its message
     * exists. Safe to call any number of times from either side: only the call that moves READY to QUEUED enqueues.
     */
    suspend fun queue(transferId: String): Boolean = queueLock.withLock {
        val row = dao.get(transferId) ?: return@withLock false
        val messageId = row.messageId
        if (row.direction != HubFileStates.OUT || row.state != HubFileStates.READY || messageId == null) return@withLock false
        val now = timeSource.nowMs()
        var queued = false
        runInTransaction {
            if (dao.markQueued(transferId, messageId, now) == 1) {
                outboxDao.enqueue(OutboxEntity(localId = messageId, nextAttemptAt = now, payloadJson = "", createdAt = now))
                queued = true
            }
        }
        if (queued) {
            FlashProbe.emit("hubfile.ticket.queued", "id" to FlashProbe.short(row.fileId))
            wakeOutbox()
        }
        queued
    }

    /** The OUT row whose ticket [message] carries, or null when [message] is not a hub file ticket. */
    suspend fun outgoingFor(message: MessageEntity): HubFileEntity? {
        val transferId = message.attachmentTransferId ?: return null
        val row = dao.get(transferId) ?: return null
        return row.takeIf { it.direction == HubFileStates.OUT && it.messageId == message.localId }
    }

    /** The ticket to send for [row], or null when the row has no usable ticket (the caller then gives the message up). */
    fun frameFor(row: HubFileEntity, message: MessageEntity, senderId: String, senderName: String): HubFileFrame? {
        val key = row.fileKey?.let(HubFileKey::decode) ?: return null
        val sha = row.cipherSha256 ?: return null
        val mime = message.attachmentMime?.takeIf { it.isNotBlank() } ?: row.mime
        return HubFileFrame(
            localId = message.localId,
            senderId = senderId,
            senderName = senderName,
            sentAt = message.sentAt,
            ticket = HubFileTicket(
                fileId = row.fileId,
                key = key,
                cipherSha256 = sha,
                plainBytes = row.plainBytes,
                cipherChunkBytes = row.cipherChunkBytes,
                chunkCount = row.chunkCount,
                expiresAtMs = row.expiresAtMs,
            ),
            fileName = row.fileName,
            // The send path knows the type only through the chat message; the row keeps a neutral one.
            mime = mime,
            meta = if (mime.startsWith("audio/")) message.text else "",
        )
    }

    /** True when the queued ticket can no longer help: the upload was cancelled or failed, or the file left the hub. */
    fun isDead(row: HubFileEntity, now: Long): Boolean =
        row.state == HubFileStates.CANCELLED || row.state == HubFileStates.FAILED ||
            (row.expiresAtMs > 0L && row.expiresAtMs <= now)

    /**
     * Stores a ticket from [from] as a chat message plus a `hub_file` row, in one transaction. A replay of the same
     * ticket is [Accepted.Stored] with `fresh = false` (the sender still deserves its receipt). A ticket that reuses a
     * known file id or message id with other details is refused: it is either a forgery or a sender bug, and a
     * received file must never be swapped under an existing bubble.
     */
    suspend fun accept(frame: HubFileFrame, from: String): Accepted {
        if (frame.senderId != from) return Accepted.Refused
        val hub = hubUrl()?.takeIf { it.isNotBlank() } ?: return Accepted.Refused
        val ticket = frame.ticket
        val transferId = HubFileStates.TRANSFER_PREFIX_IN + ticket.fileId
        val now = timeSource.nowMs()
        val sentAt = minOf(frame.sentAt, now)
        val expired = ticket.expiresAtMs in 1..now
        val senderName = frame.senderName.ifBlank { null }

        var result: Accepted = Accepted.Refused
        runInTransaction {
            val known = dao.byFileId(HubFileStates.IN, ticket.fileId)
            if (known != null) {
                // The same ticket again (the receipt was lost). The message may have been deleted here since; the
                // sender still gets its receipt, and nothing is recreated.
                val same = known.peerId == from && known.fileKey == ticket.key.encode() && known.messageId == frame.localId
                result = if (same) Accepted.Stored(false, known.fileName, known.mime) else Accepted.Refused
                return@runInTransaction
            }
            // A message id already in use by another message: never put a file under someone else's bubble.
            if (messageDao.getByLocalId(frame.localId) != null) return@runInTransaction
            messageDao.insert(
                MessageEntity(
                    localId = frame.localId,
                    conversationId = from,
                    senderId = from,
                    senderName = senderName,
                    text = if (frame.mime.startsWith("audio/")) frame.meta else "",
                    sentAt = sentAt,
                    status = "DELIVERED",
                    attachmentTransferId = transferId,
                    attachmentName = frame.fileName,
                    attachmentMime = frame.mime,
                    attachmentSize = ticket.plainBytes,
                    attachmentPath = null,
                ),
            )
            dao.insert(
                HubFileEntity(
                    transferId = transferId,
                    direction = HubFileStates.IN,
                    fileId = ticket.fileId,
                    peerId = from,
                    hubUrl = hub,
                    fileName = frame.fileName,
                    mime = frame.mime,
                    plainBytes = ticket.plainBytes,
                    sourceUri = null,
                    fileKey = ticket.key.encode(),
                    cipherSha256 = ticket.cipherSha256,
                    cipherChunkBytes = ticket.cipherChunkBytes,
                    chunkCount = ticket.chunkCount,
                    expiresAtMs = ticket.expiresAtMs,
                    messageId = frame.localId,
                    state = if (expired) HubFileStates.FAILED else HubFileStates.OFFERED,
                    paused = false,
                    errorText = if (expired) HubFileText.expired(peerName(from) ?: frame.senderName) else null,
                    bytesDone = 0L,
                    localPath = null,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            )
            upsertConversation(from, peerName(from)?.ifBlank { null } ?: senderName ?: from, sentAt)
            result = Accepted.Stored(true, frame.fileName, frame.mime)
        }
        when (val r = result) {
            is Accepted.Stored -> FlashProbe.emit(
                "hubfile.ticket.in",
                "id" to FlashProbe.short(ticket.fileId),
                "from" to FlashProbe.short(from),
                "fresh" to r.fresh,
                "expired" to expired,
            )
            Accepted.Refused -> FlashLog.w("HUB", "file ticket ${ticket.fileId.take(8)} from ${from.take(8)} refused: it contradicts stored data")
        }
        return result
    }
}
