package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashAttachmentProgress
import com.transfer.flash.core.messaging.model.FlashCallEventKind
import com.transfer.flash.core.messaging.model.FlashCallEventUi
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.messaging.model.FlashQuotedReplyUi
import com.transfer.flash.core.messaging.model.FlashReaction
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.messaging.util.computeMessageGroupPositions
import com.transfer.flash.core.messaging.util.sortedChatListItems
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.DraftDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReactionDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.persistence.db.entity.ReactionEntity
import com.transfer.flash.core.persistence.db.entity.ReceiptEntity
import com.transfer.flash.core.persistence.db.entity.RecentSearchEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Functional wire transport provider for sending frames to a target conversation / peer.
 */
public fun interface MessageTransportSink {
    public suspend fun send(conversationId: String, frame: MessageWireFrame): Boolean
}

/**
 * Production Room-backed implementation of [FlashChatRepository] (C6.0 - C6.13).
 *
 * Key guarantees:
 * - **Durable Outbox (C6.1):** Outgoing messages are written directly to Room `messages` + `outbox`
 *   and instantly emitted to the UI before network dispatch. Process death does not lose un-sent messages.
 * - **Idempotent Ingestion (C6.2):** Message insertions use `OnConflictStrategy.IGNORE` on client-generated UUIDs.
 * - **Delivery & Read Receipts (C6.3):** Emits and absorbs delivery receipts to update status flags.
 * - **Ephemeral Typing & Presence (C6.6):** Memory-only TTL state for live typing indicators.
 */
public class RealFlashChatRepository(
    private val localDeviceId: String,
    private val localDisplayName: String,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val outboxDao: OutboxDao,
    private val receiptDao: ReceiptDao,
    private val draftDao: DraftDao,
    private val recentSearchDao: RecentSearchDao,
    private val reactionDao: ReactionDao,
    private val transportSink: MessageTransportSink? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Live set of peer device ids that currently have an active session (from the network layer).
     * Drives the per-conversation presence indicator, via the graced [displayedPresence].
     * Defaults to a never-online flow for tests.
     */
    private val onlinePeerIds: Flow<Set<String>> = MutableStateFlow(emptySet()),
    /**
     * Resolves a peer device id to its friendly name (prod: the trust store). A conversationId is a
     * peer device id, so this turns the raw UUID we key threads by into the human name to display.
     * Returns null when unknown; callers fall back to any stored title, then the id itself.
     */
    private val peerNameResolver: (String) -> String? = { null },
    /**
     * Live per-transfer progress keyed by transferId, published by the transfer layer. The
     * conversation mapper joins it onto attachment rows so a bubble shows the same progress as the
     * Transfers tab (B4). Defaults to an empty flow for tests / lightweight wiring.
     */
    private val attachmentProgress: Flow<Map<String, FlashAttachmentProgress>> =
        MutableStateFlow(emptyMap()),
    /**
     * Host callback fired when a brand-new inbound TEXT message row lands in Room (Room did
     * NOT dedupe it — the insert result was a real row id, not -1). Lets the app post a
     * system notification without the messaging library ever depending on Android UI.
     * Default no-op keeps every existing constructor site (tests, shared engine) compiling
     * unchanged. [conversationId] is the peer's device id (the local thread id),
     * [senderName] the wire-carried author name (nullable), [text] the message body.
     */
    private val onInboundTextMessage: (conversationId: String, senderName: String?, text: String) -> Unit =
        { _, _, _ -> },
    /**
     * Host callback fired when an inbound attachment row is newly inserted (accepted or
     * auto-accepted offers only — a pending offer mints no row, so it fires nothing).
     * Default no-op; see [onInboundTextMessage].
     */
    private val onInboundAttachment: (conversationId: String, senderName: String?, fileName: String, mimeType: String) -> Unit =
        { _, _, _, _ -> },
) : FlashChatRepository {

    private val _chatListState = MutableStateFlow(FlashChatListUiState())
    override val chatListState: StateFlow<FlashChatListUiState> = _chatListState.asStateFlow()

    private val _conversationState = MutableStateFlow(
        FlashConversationUiState(
            header = FlashChatHeaderUiState(
                title = "Messages",
                avatarInitials = "FL",
            ),
            messages = emptyList(),
        ),
    )
    override val conversationState: StateFlow<FlashConversationUiState> = _conversationState.asStateFlow()

    private var activeConversationId: String? = null
    private var activeConversationJob: Job? = null

    // Bug 6 / crash at drainOutboxOnce: MUST be declared before the init block below.
    // Kotlin runs property initializers + init blocks in source order, and the init block
    // launches a coroutine that can reach drainOutboxOnce() on Dispatchers.IO before the
    // constructor finishes — if this val sits below the init block, the coroutine sees a
    // null drainMutex and the resulting NPE is an uncaught coroutine exception that kills
    // the whole process. Observed on device 2026-08-31 10:22 (AndroidRuntime FATAL).
    private val drainMutex = Mutex()

    // Ephemeral in-memory typing state: conversationId -> (memberId -> memberName) currently typing.
    // Mirrored into [typingFlow] so the conversation UI can observe it (#11). Never persisted.
    private val typingStates = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val typingFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private fun publishTyping() {
        typingFlow.value = typingStates
            .mapValues { (_, members) -> members.values.toList() }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Presence as the UI shows it: [onlinePeerIds] split into Online / Connecting by a per-departure
     * grace window (ERROR-026, ERROR-031). MUST be declared above the init block below, for the
     * reason recorded on [drainMutex].
     *
     * The raw flow is the live WebSocket session set, so a session that drops and is redialed a
     * second later would make the peer blink Offline → Online. Screen-off / Doze does exactly that,
     * and the blink reads as "the app lost the device" while recovery is already under way. So a
     * departing peer spends [OFFLINE_HOLD_MS] as *Connecting* first: a genuine departure is still
     * honest (it merely arrives that much late) and an in-window reconnect is invisible, but the UI
     * never claims a usable link it does not have. See [withReconnectGrace] for why the previous
     * `transformLatest` hold could latch the dot Online with an empty session set.
     *
     * Presentation only. Send gating stays on the raw session set inside [MessageTransportSink] and
     * call gating in the host's call coordinator, so a held-open dot can never make us route a frame
     * into a socket that no longer exists: the send simply fails and the message waits in the outbox.
     */
    private val displayedPresence: Flow<PresenceSnapshot> =
        onlinePeerIds.withReconnectGrace(OFFLINE_HOLD_MS)

    init {
        // Observe conversation list from Room, joined with live session presence + unread counts.
        scope.launch(ioDispatcher) {
            combine(
                conversationDao.observeAll(),
                displayedPresence,
                messageDao.observeUnreadCounts(localDeviceId),
                messageDao.observeLatestPreviews(),
            ) { entities, peers, unreadRows, previewRows ->
                val unreadByConversation = unreadRows.associate { it.conversationId to it.unread }
                val previewByConversation = previewRows.associate { it.conversationId to it.previewText }
                entities.filter { !it.archived }.map { entity ->
                    // Prefer the authoritative friendly name (trust store) over whatever title the
                    // row happens to hold — a local send may have stamped it with the raw device id.
                    val displayTitle = peerNameResolver(entity.id)?.ifBlank { null }
                        ?: entity.title.ifBlank { entity.id }
                    FlashChatListItemUi(
                        id = entity.id,
                        title = displayTitle,
                        avatarInitials = computeInitials(displayTitle),
                        // Real last-message text so the row is informative AND searchable (global
                        // search matches title OR previewText). Empty threads fall back to a hint.
                        previewText = previewLabel(previewByConversation[entity.id])
                            ?: "Tap to view conversation",
                        timestamp = formatTimestamp(entity.sortOrder),
                        unreadCount = unreadByConversation[entity.id] ?: 0,
                        // Three honest states (ERROR-031): a peer whose session just dropped reads
                        // as Connecting for the grace window rather than as a link we can use.
                        presence = when {
                            entity.id in peers.online -> FlashPeerPresence.Online
                            entity.id in peers.connecting -> FlashPeerPresence.Connecting
                            else -> FlashPeerPresence.Offline
                        },
                        isGroup = entity.isGroup,
                        isPinned = entity.pinned,
                        isMuted = entity.muted,
                        sortOrder = entity.sortOrder,
                    )
                }
            }.collectLatest { items ->
                _chatListState.update { current ->
                    current.copy(
                        items = sortedChatListItems(items),
                    )
                }
            }
        }

        // Background outbox drain worker
        scope.launch(ioDispatcher) {
            drainOutboxLoop()
        }

        // Stamp the on-disk path of every finished attachment onto its row. Live progress is
        // in-memory only, so without this a restart strips the received file off the row and every
        // photo, clip and voice note in history falls back to an undecodable placeholder — the
        // attachment is on disk, but nothing remembers where.
        scope.launch(ioDispatcher) {
            // Coroutine-confined, so no synchronisation: one entry per (transfer, path) actually
            // written. Progress emits many times a second and the DAO write is a no-op after the
            // first, but a suspending DB round-trip per tick is not.
            val stamped = HashSet<String>()
            attachmentProgress.collect { byTransfer ->
                byTransfer.forEach { (transferId, live) ->
                    if (live.status != FlashFileTransferStatus.Downloaded) return@forEach
                    val path = live.localPath?.ifBlank { null } ?: return@forEach
                    if ("$transferId|$path" in stamped) return@forEach
                    // A failed write leaves the row unstamped — the placeholder outcome it already
                    // had — and must not cancel the collector for every later transfer.
                    val written = runCatching {
                        val changed = messageDao.updateAttachmentPath(transferId, path)
                        // 0 rows changed is ambiguous: the row may already hold this path, or may not
                        // exist yet (a completion that raced its own ingestion). Only the first is
                        // done, so only the first may be cached — otherwise that transfer would
                        // never be stamped at all.
                        changed > 0 || messageDao.existsAttachment(transferId)
                    }.getOrDefault(false)
                    if (written) stamped.add("$transferId|$path")
                }
            }
        }
    }

    override fun openConversation(conversationId: String) {
        activeConversationId = conversationId
        activeConversationJob?.cancel()

        // Track the newest message id we've already marked read so an unchanged head does not
        // rewrite the cursor (and needlessly re-emit the chat list) on every recomposition, plus
        // the newest INBOUND id we've already acked so we don't re-send the same read receipt.
        var lastMarkedReadId: String? = null
        var lastAckedInboundId: String? = null

        activeConversationJob = scope.launch(ioDispatcher) {
            // Inner combine (4 flows): pure message content — rows + draft + attachment progress +
            // reactions. Kept separate from presence/typing so neither combine exceeds 5 inputs.
            val contentFlow = combine(
                messageDao.observeConversation(conversationId),
                draftDao.observeDraft(conversationId),
                attachmentProgress,
                reactionDao.observeForConversation(conversationId),
            ) { entities, draftEntity, progressByTransfer, reactionRows ->
                // Index rows by local id so a reply can resolve its quoted message's author/side for
                // the in-bubble quote card (#8). Falls back to the wire-carried preview when the
                // quoted row is not in this window (e.g. paged out).
                val byLocalId = entities.associateBy { it.localId }
                // Aggregate reaction rows per message id → UI chips (#7).
                val reactionsByMessage = reactionRows.groupBy { it.messageId }
                val messages = entities.map { entity ->
                    val replyTo = entity.replyToId?.let { quotedId ->
                        val quoted = byLocalId[quotedId]
                        FlashQuotedReplyUi(
                            messageId = quotedId,
                            senderName = quoted?.senderName
                                ?: quoted?.senderId?.let { if (it == localDeviceId) "You" else it }
                                ?: "",
                            textSnippet = entity.replyToPreview ?: quoted?.text ?: "",
                            isMine = quoted?.senderId == localDeviceId,
                        )
                    }
                    val reactions = reactionsByMessage[entity.localId]?.map { row ->
                        FlashReaction(
                            emoji = row.emoji,
                            count = row.count,
                            isSelfReacted = row.selfReacted,
                            reactorIds = decodeReactorIds(row.reactorIdsJson),
                        )
                    }.orEmpty()
                    val base = FlashMessageUi(
                        id = entity.localId,
                        senderName = entity.senderName ?: if (entity.senderId == localDeviceId) "You" else "Peer",
                        senderInitials = computeInitials(entity.senderName ?: entity.senderId),
                        timeLabel = formatTime(entity.sentAt),
                        text = entity.text,
                        isMine = entity.senderId == localDeviceId,
                        deliveryStatus = mapStatus(entity.status),
                        replyTo = replyTo,
                        reactions = reactions,
                    )
                    applyCallEvent(applyAttachment(base, entity, progressByTransfer), entity)
                }
                // entities are ordered newest-first, so the head is the latest message. Opening (or
                // receiving while open) marks the thread read up to it, clearing the unread badge.
                val newestMessageId = entities.firstOrNull()?.localId
                // Newest message the PEER sent us (entities are newest-first). Read receipts ack up
                // to this; when the head is our own outbound message there is nothing to ack.
                val newestInboundId = entities.firstOrNull { it.senderId != localDeviceId }?.localId
                ConversationContent(
                    messages = computeMessageGroupPositions(messages.reversed()),
                    draftText = draftEntity?.text.orEmpty(),
                    newestMessageId = newestMessageId,
                    newestInboundId = newestInboundId,
                )
            }

            // Outer combine (3 flows): join live presence + typing onto the header (#11).
            combine(
                contentFlow,
                displayedPresence,
                typingFlow,
            ) { content, peers, typingByConversation ->
                // conversationId is the peer's device id; show the friendly name, not the UUID.
                val title = peerNameResolver(conversationId)?.ifBlank { null } ?: conversationId
                val isOnline = conversationId in peers.online
                val isConnecting = !isOnline && conversationId in peers.connecting
                val typingNames = typingByConversation[conversationId].orEmpty()
                val presence = when {
                    // A typing indicator sticks until the peer clears it, so a session that died
                    // mid-compose would otherwise leave "typing…" on screen forever. It may only
                    // outrank a peer we still believe is reachable.
                    typingNames.isNotEmpty() && (isOnline || isConnecting) -> FlashPeerPresence.Typing
                    isOnline -> FlashPeerPresence.Online
                    // ERROR-031: the reconnect window is its own state. The header renders it as
                    // "Connecting…" and the banner agrees, because resolveHealth tests Connecting
                    // ahead of the (necessarily) Unknown transport below.
                    isConnecting -> FlashPeerPresence.Connecting
                    else -> FlashPeerPresence.Offline
                }
                FlashConversationUiState(
                    header = FlashChatHeaderUiState(
                        title = title,
                        avatarInitials = computeInitials(title),
                        presence = presence,
                        // Without a transport the header's resolveHealth() short-circuits Unknown ->
                        // Offline and shows "Searching for devices…" even when the peer has a live
                        // session. An active session is our LAN/WS mesh link, so stamp Lan when
                        // online — and only when online: a peer mid-reconnect has no link to name.
                        transport = if (isOnline) {
                            FlashNetworkTransport.Lan
                        } else {
                            FlashNetworkTransport.Unknown
                        },
                        typingMemberNames = typingNames,
                    ),
                    messages = content.messages,
                    // Restore any unsent composer text (#9); blank when there is no saved draft.
                    draftText = content.draftText,
                ) to Pair(content.newestMessageId, content.newestInboundId)
            }.collectLatest { (state, cursors) ->
                val (newestMessageId, newestInboundId) = cursors
                _conversationState.value = state
                if (newestMessageId != null && newestMessageId != lastMarkedReadId) {
                    conversationDao.updateLastReadCursor(conversationId, newestMessageId)
                    lastMarkedReadId = newestMessageId
                    // Tell the peer we've read up to its newest message so its sent bubbles flip
                    // Delivered → Read (C6.3). Only fires when the peer has actually sent us
                    // something (never for our own outbound head) and never re-acks the same id.
                    // Routed to the peer (conversationId is its device id); memberId is our id, which
                    // the peer uses to locate its thread for us. Idempotent via `markReadUpTo`.
                    if (newestInboundId != null && newestInboundId != lastAckedInboundId) {
                        lastAckedInboundId = newestInboundId
                        transportSink?.send(
                            conversationId,
                            MessageWireFrame.ReadReceipt(
                                conversationId = conversationId,
                                memberId = localDeviceId,
                                upToMessageId = newestInboundId,
                                readAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Intermediate holder for the content combine so the presence/typing combine stays ≤ 5 flows. */
    private data class ConversationContent(
        val messages: List<FlashMessageUi>,
        val draftText: String,
        val newestMessageId: String?,
        val newestInboundId: String?,
    )

    override fun closeConversation() {
        activeConversationJob?.cancel()
        activeConversationId = null
    }

    override fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val conversationId = activeConversationId ?: return
        val now = System.currentTimeMillis()
        val localId = UUID.randomUUID().toString()

        scope.launch(ioDispatcher) {
            // 1. Write message row
            val messageEntity = MessageEntity(
                localId = localId,
                conversationId = conversationId,
                senderId = localDeviceId,
                senderName = localDisplayName,
                text = trimmed,
                sentAt = now,
                status = "PENDING",
            )
            messageDao.insert(messageEntity)

            // 2. Ensure conversation exists in DB. Title uses the friendly name when known —
            // never the raw conversationId, which (via @Upsert full-row replace) would otherwise
            // clobber a good inbound-set title with the peer's device UUID.
            conversationDao.upsert(
                ConversationEntity(
                    id = conversationId,
                    title = peerNameResolver(conversationId)?.ifBlank { null } ?: conversationId,
                    isGroup = false,
                    sortOrder = now,
                ),
            )

            // 3. Clear draft
            draftDao.clear(conversationId)

            // 4. Enqueue into outbox
            outboxDao.enqueue(
                OutboxEntity(
                    localId = localId,
                    attempts = 0,
                    nextAttemptAt = now,
                    payloadJson = trimmed,
                    createdAt = now,
                ),
            )

            // 5. Trigger outbox drain immediately
            drainOutboxOnce()
        }
    }

    override fun sendReply(text: String, replyToId: String, replyToPreview: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val conversationId = activeConversationId ?: return
        val now = System.currentTimeMillis()
        val localId = UUID.randomUUID().toString()

        scope.launch(ioDispatcher) {
            // Mirrors sendText but stamps the reply columns so the row (and its outbound frame,
            // reconstructed from the row in the drain) carries the quote to the peer (#8).
            messageDao.insert(
                MessageEntity(
                    localId = localId,
                    conversationId = conversationId,
                    senderId = localDeviceId,
                    senderName = localDisplayName,
                    text = trimmed,
                    sentAt = now,
                    status = "PENDING",
                    replyToId = replyToId,
                    replyToPreview = replyToPreview,
                ),
            )
            conversationDao.upsert(
                ConversationEntity(
                    id = conversationId,
                    title = peerNameResolver(conversationId)?.ifBlank { null } ?: conversationId,
                    isGroup = false,
                    sortOrder = now,
                ),
            )
            draftDao.clear(conversationId)
            outboxDao.enqueue(
                OutboxEntity(
                    localId = localId,
                    attempts = 0,
                    nextAttemptAt = now,
                    payloadJson = trimmed,
                    createdAt = now,
                ),
            )
            drainOutboxOnce()
        }
    }

    override fun saveDraft(text: String) {
        val conversationId = activeConversationId ?: return
        scope.launch(ioDispatcher) {
            // Blank text clears the draft; otherwise persist the raw (untrimmed) composer text so
            // the user's in-progress spacing survives navigation / process death (#9).
            if (text.isBlank()) {
                draftDao.clear(conversationId)
            } else {
                draftDao.upsert(
                    DraftEntity(
                        conversationId = conversationId,
                        text = text,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    override fun sendAttachment(
        conversationId: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        localPath: String?,
        voiceDurationMs: Long,
        voiceAmplitudes: List<Int>,
    ) {
        val now = System.currentTimeMillis()
        val localId = UUID.randomUUID().toString()
        // Voice notes carry no body text; stash duration + waveform in the text column so the
        // playback card can render a real waveform without a schema change (B9). Parsed back out
        // in [applyAttachment]; see [encodeVoiceMeta]/[decodeVoiceMeta].
        val rowText = if (mimeType.startsWith("audio/")) {
            encodeVoiceMeta(voiceDurationMs, voiceAmplitudes)
        } else {
            ""
        }
        scope.launch(ioDispatcher) {
            // Local chat row referencing the live transfer. The bytes travel over the transfer
            // pipeline (not the message outbox), so this row is informational — status SENT keeps it
            // out of the Pending spinner; live progress is joined in via [attachmentProgress].
            messageDao.insert(
                MessageEntity(
                    localId = localId,
                    conversationId = conversationId,
                    senderId = localDeviceId,
                    senderName = localDisplayName,
                    text = rowText,
                    sentAt = now,
                    status = "SENT",
                    attachmentTransferId = transferId,
                    attachmentName = fileName,
                    attachmentMime = mimeType,
                    attachmentSize = sizeBytes,
                    attachmentPath = localPath,
                ),
            )
            conversationDao.upsert(
                ConversationEntity(
                    id = conversationId,
                    title = peerNameResolver(conversationId)?.ifBlank { null } ?: conversationId,
                    isGroup = false,
                    sortOrder = now,
                ),
            )
        }
    }

    /**
     * Records an inbound file transfer as a chat bubble (#1). Called by the transport the moment a
     * peer starts sending a file (`ReceiveEvent.SessionStarted`). The row references the live
     * transfer by [transferId]; [attachmentProgress] joins live progress and, on completion, the
     * received file's final path in via [applyAttachment] — so the bubble mirrors the Transfers tab
     * and becomes openable once downloaded. Idempotent per [transferId] so a replayed start (e.g.
     * reconnect) does not double-insert. Threaded under the sender's device id, like inbound text.
     */
    public fun onInboundAttachment(
        peerDeviceId: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ) {
        if (peerDeviceId.isBlank() || transferId.isBlank()) return
        scope.launch(ioDispatcher) {
            if (messageDao.existsAttachment(transferId)) return@launch
            val now = System.currentTimeMillis()
            // Same dedupe gate as text: existsAttachment guards the replay path, and a
            // real insert result is what lets the host notify (Bug 7).
            val insertedRowId = messageDao.insert(
                MessageEntity(
                    localId = UUID.randomUUID().toString(),
                    conversationId = peerDeviceId,
                    senderId = peerDeviceId,
                    senderName = peerNameResolver(peerDeviceId),
                    text = "",
                    sentAt = now,
                    status = "DELIVERED",
                    attachmentTransferId = transferId,
                    attachmentName = fileName,
                    attachmentMime = mimeType,
                    attachmentSize = sizeBytes,
                    attachmentPath = null,
                ),
            )
            conversationDao.upsert(
                ConversationEntity(
                    id = peerDeviceId,
                    title = peerNameResolver(peerDeviceId)?.ifBlank { null } ?: peerDeviceId,
                    isGroup = false,
                    sortOrder = now,
                ),
            )
            if (insertedRowId != -1L) {
                runCatching {
                    onInboundAttachment(peerDeviceId, peerNameResolver(peerDeviceId), fileName, mimeType)
                }
            }
        }
    }

    /**
     * Records a finished voice/video call as a row in the peer's thread (UI-050). The host calls
     * this once per terminated call, from `CallCoordinator.onCallLog`.
     *
     * Arguments are primitives because `core:calling` owns no messaging types and this module knows
     * nothing about WebRTC (port/adapter inversion, ADR-024); the row's kind is derived here.
     *
     * [callId] doubles as the row's `localId`, and [MessageDao.insert] is IGNORE-on-conflict, so a
     * call whose end is observed twice still produces exactly one row.
     *
     * Threaded under [peerDeviceId] like every other row. An outgoing call is attributed to this
     * device so it renders on the right; an incoming one to the peer so it renders on the left, and
     * so a missed call raises the thread's unread badge exactly like an unread message.
     */
    public fun recordCallEvent(
        peerDeviceId: String,
        callId: String,
        peerName: String?,
        outgoing: Boolean,
        video: Boolean,
        durationMs: Long,
        endedAt: Long,
    ) {
        if (peerDeviceId.isBlank() || callId.isBlank()) return
        // durationMs is zero unless media actually flowed, which is what separates a real
        // conversation from a decline or an unanswered ring.
        val connected = durationMs > 0L
        val kind = when {
            outgoing && connected -> FlashCallEventKind.Outgoing
            outgoing -> FlashCallEventKind.Unanswered
            connected -> FlashCallEventKind.Incoming
            else -> FlashCallEventKind.Missed
        }
        val at = if (endedAt > 0L) endedAt else System.currentTimeMillis()
        val resolvedPeerName = peerNameResolver(peerDeviceId)?.ifBlank { null }
            ?: peerName?.ifBlank { null }
        scope.launch(ioDispatcher) {
            messageDao.insert(
                MessageEntity(
                    localId = callId,
                    conversationId = peerDeviceId,
                    senderId = if (outgoing) localDeviceId else peerDeviceId,
                    senderName = if (outgoing) localDisplayName else resolvedPeerName,
                    text = encodeCallMeta(kind, video, durationMs),
                    sentAt = at,
                    status = "DELIVERED",
                ),
            )
            conversationDao.upsert(
                ConversationEntity(
                    id = peerDeviceId,
                    title = resolvedPeerName ?: peerDeviceId,
                    isGroup = false,
                    sortOrder = at,
                ),
            )
        }
    }

    /**
     * Ingests an inbound wire frame from the network layer.
     */
    public suspend fun onInboundWireFrame(frame: MessageWireFrame) {
        when (frame) {
            is MessageWireFrame.TextMessage -> {
                // conversationId doubles as the transport routing key (a device id). The sender
                // addressed US by OUR id, so `frame.conversationId` is the receiver's own device id
                // — threading the message under it would key our reply's routing to ourselves (the
                // "one device can't send back" bug). The correct local thread id is the AUTHOR's
                // device id (`frame.senderId`), which is the remote peer from our side and the id our
                // outbound sends must target. Mirrors the delivery-receipt routing fix below.
                val threadId = frame.senderId
                val entity = MessageEntity(
                    localId = frame.localId,
                    conversationId = threadId,
                    senderId = frame.senderId,
                    senderName = frame.senderName,
                    text = frame.text,
                    sentAt = frame.sentAt,
                    status = "DELIVERED",
                    replyToId = frame.replyToId,
                    replyToPreview = frame.replyToPreview,
                )
                // -1 == IGNORE-conflict: this localId already exists (replayed frame after a
                // reconnect). Only a fresh row notifies (Bug 7 dedupe guarantee).
                val insertedRowId = messageDao.insert(entity)
                conversationDao.upsert(
                    ConversationEntity(
                        id = threadId,
                        title = peerNameResolver(threadId)?.ifBlank { null }
                            ?: frame.senderName?.ifBlank { null }
                            ?: frame.senderId,
                        isGroup = false,
                        sortOrder = frame.sentAt,
                    ),
                )
                if (insertedRowId != -1L) {
                    runCatching {
                        onInboundTextMessage(threadId, frame.senderName, frame.text)
                    }
                }

                // Reply with a delivery receipt routed back to the message's AUTHOR. The sink's
                // first argument is the transport routing key (a device id); `frame.senderId` is the
                // author's device id — the correct target. The receipt's conversationId is the
                // author's id for this thread too (== threadId).
                transportSink?.send(
                    frame.senderId,
                    MessageWireFrame.DeliveryReceipt(
                        messageId = frame.localId,
                        conversationId = threadId,
                        memberId = localDeviceId,
                        deliveredAt = System.currentTimeMillis(),
                    ),
                )
            }

            is MessageWireFrame.DeliveryReceipt -> {
                receiptDao.insert(
                    ReceiptEntity(
                        messageId = frame.messageId,
                        memberId = frame.memberId,
                        state = "DELIVERED",
                    ),
                )
                messageDao.updateStatus(frame.messageId, "DELIVERED")
                // ERROR-031: this is the outbox row's commit point. The drain keeps the row alive
                // through a successful socket write precisely so that a frame the kernel accepted
                // but the peer never got is resent; the receipt is the only proof the peer actually
                // has the message, so it is the only thing allowed to retire the row. Receipts are
                // keyed by the author's own `localId` (the receiver echoes `frame.localId` back),
                // which is the outbox row's primary key — no lookup needed. Idempotent: a replayed
                // receipt deletes nothing the second time.
                outboxDao.delete(frame.messageId)
            }

            is MessageWireFrame.ReadReceipt -> {
                // The peer reports it has read up to frame.upToMessageId. On OUR device the peer's
                // thread is keyed by the reader's device id (`frame.memberId`), and the messages that
                // should flip to Read are the ones WE authored (`localDeviceId`). The old code instead
                // rewrote our own read cursor with the peer's frame, which never touched delivery
                // status and left our sent bubbles stuck at Delivered.
                messageDao.markReadUpTo(
                    conversationId = frame.memberId,
                    selfId = localDeviceId,
                    upToMessageId = frame.upToMessageId,
                )
            }

            is MessageWireFrame.TypingFrame -> {
                val convTyping = typingStates.computeIfAbsent(frame.conversationId) { ConcurrentHashMap() }
                if (frame.isTyping) {
                    convTyping[frame.memberId] = frame.memberName
                } else {
                    convTyping.remove(frame.memberId)
                }
                publishTyping()
            }

            is MessageWireFrame.ReactionFrame -> {
                // Apply the peer's reaction delta to the aggregated row, attributed to its memberId
                // (#7). Self-reaction state is unaffected — that only flips for localDeviceId.
                applyReactionDelta(
                    messageId = frame.messageId,
                    emoji = frame.emoji,
                    reactorId = frame.memberId,
                    isAdded = frame.isAdded,
                )
            }
        }
    }

    private suspend fun drainOutboxLoop() {
        while (true) {
            drainOutboxOnce()
            kotlinx.coroutines.delay(1000)
        }
    }

    private suspend fun drainOutboxOnce() {
        drainMutex.withLock {
            val now = System.currentTimeMillis()
            // A null sink can never deliver — bail without touching rows so we don't spin the loop
            // or advance backoff on messages we have no way to send yet.
            val sink = transportSink ?: return
            val items = outboxDao.dueForDelivery(now, limit = 16)
            for (item in items) {
                // The outbox row stores only the payload, so recover the conversationId, sender name
                // and original sentAt from the durable message row. Reconstructing the frame from
                // `activeConversationId` instead (the old behaviour) mis-routed any message whose
                // conversation was no longer the active one when the 1s drain fired — sending it to
                // the wrong peer, to "general", or nowhere — because conversationId doubles as the
                // transport routing key (see MessageTransportSink).
                val message = messageDao.getByLocalId(item.localId)
                if (message == null) {
                    // No backing message: this row can never be delivered. Drop it so the drain
                    // loop does not re-claim it every second forever. Not reachable in normal flow —
                    // sendText inserts the message before enqueueing the outbox row.
                    outboxDao.delete(item.localId)
                    continue
                }
                if (message.deletedAt != null) {
                    // Message was deleted after enqueueing but before it drained. Never transmit a
                    // tombstoned message; drop the outbox row so the peer never sees it. (deleteMessage
                    // also deletes the row, but the drain may have already claimed this batch.)
                    outboxDao.delete(item.localId)
                    continue
                }
                val wireFrame = MessageWireFrame.TextMessage(
                    localId = item.localId,
                    conversationId = message.conversationId,
                    senderId = localDeviceId,
                    senderName = localDisplayName,
                    text = message.text,
                    sentAt = message.sentAt,
                    replyToId = message.replyToId,
                    replyToPreview = message.replyToPreview,
                )
                // ERROR-031: a row's life ends at PEER ACKNOWLEDGEMENT, not at socket write. A write
                // into a half-open socket succeeds — the kernel buffers the bytes and no error ever
                // surfaces — so deleting the row on that signal made every frame lost that way
                // permanently unrecoverable: the bubble ticked once and the message never arrived,
                // and force-stopping the app was the only way to get a working session back. The
                // give-up budget therefore has to be tested BEFORE the send, so it also bounds a row
                // whose writes keep "succeeding" into a socket nobody is reading.
                //
                // ERROR-026: that budget is WALL-CLOCK age, not attempt count. The old rule was 8
                // attempts with 1s/2s/4s…60s spacing — roughly two minutes of patience — so any
                // screen-off/Doze window longer than that (routine on Transsion/Xiaomi builds)
                // permanently FAILED every queued message even though the peer came back fine a
                // minute later. `attempts` now only picks the spacing. `createdAt` is stamped at
                // enqueue by every producer.
                val queuedForMs = now - item.createdAt
                if (queuedForMs >= OUTBOX_GIVE_UP_AFTER_MS) {
                    // Unacknowledged for the whole budget: mark the message Failed (surfaces a retry
                    // affordance in the bubble) and drop the outbox row so it stops being re-claimed.
                    messageDao.updateStatusIfUnacknowledged(item.localId, "FAILED")
                    outboxDao.delete(item.localId)
                    continue
                }
                val success = sink.send(wireFrame.conversationId, wireFrame)
                if (success) {
                    // Single tick, unchanged — the bytes are on the wire. The row itself survives
                    // until the peer's DeliveryReceipt deletes it (see the DeliveryReceipt branch of
                    // [onInboundWireFrame]), which makes the reschedule below double as the resend
                    // timer for a frame that was written but never arrived.
                    messageDao.updateStatusIfUnacknowledged(item.localId, "SENT")
                }
                // Both outcomes re-arm on the same ladder (#21): a refused send backs off instead of
                // hammering the peer every tick, and an accepted-but-unacknowledged send resends on
                // that same spacing. A redundant resend is harmless by construction — the receiver's
                // insert is idempotent (IGNORE on localId) and it re-acks every TextMessage whether
                // the row was new or a replay, so the extra frame is precisely what produces the
                // receipt that clears this row.
                outboxDao.rescheduleAttempt(item.localId, now + backoffDelayMs(item.attempts + 1))
            }
        }
    }

    /**
     * Exponential backoff (#21) for a failed outbox delivery: `BASE * 2^(attempts-1)`, capped at
     * [OUTBOX_MAX_BACKOFF_MS]. [attempts] is the number of tries already made (>= 1).
     */
    private fun backoffDelayMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 16)
        return (OUTBOX_BASE_BACKOFF_MS shl shift).coerceAtMost(OUTBOX_MAX_BACKOFF_MS)
    }

    /**
     * Bug 5: a peer session came up (first connect OR reconnect). Reset the durable outbox so
     * every queued message is retryable right now (`attempts -> 0`, `nextAttemptAt -> now`) and
     * immediately run one drain pass. Messages queued while the peer was offline therefore send
     * the instant connectivity returns, instead of sitting out a backoff window.
     *
     * Safe to call on every session-up from the network layer. Rows whose peer is still
     * unreachable simply fail once more and re-enter backoff — no message is ever lost until its
     * [OUTBOX_GIVE_UP_AFTER_MS] budget expires.
     */
    public fun notifyPeerSessionUp() {
        scope.launch(ioDispatcher) {
            outboxDao.makePendingDue(System.currentTimeMillis())
            drainOutboxOnce()
        }
    }

    override fun openAttachmentPicker() {}

    override suspend fun searchMessageBodies(query: String): Set<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptySet()
        // Case-insensitive substring match over ALL message bodies (LIKE is case-insensitive for
        // ASCII in SQLite), newest-first, capped. Collapse to the distinct set of conversations so
        // the chat list can surface a thread whose only match is deep in history (#12). Tombstoned
        // rows are already excluded by the query.
        return kotlinx.coroutines.withContext(ioDispatcher) {
            messageDao.searchMessages(trimmed, limit = SEARCH_RESULT_LIMIT)
                .asSequence()
                .map { it.conversationId }
                .toSet()
        }
    }

    override fun toggleReaction(messageId: String, emoji: String) {
        val conversationId = activeConversationId ?: return
        scope.launch(ioDispatcher) {
            // Toggle our own reaction: if we already reacted with this emoji, remove it; else add it.
            val existing = reactionDao.get(messageId, emoji)
            val currentlySelf = existing?.selfReacted == true ||
                (existing != null && localDeviceId in decodeReactorIds(existing.reactorIdsJson))
            val isAdded = !currentlySelf
            applyReactionDelta(messageId, emoji, localDeviceId, isAdded)
            // Broadcast the delta so the peer's aggregate matches (#7).
            transportSink?.send(
                conversationId,
                MessageWireFrame.ReactionFrame(
                    messageId = messageId,
                    conversationId = conversationId,
                    memberId = localDeviceId,
                    emoji = emoji,
                    isAdded = isAdded,
                ),
            )
        }
    }

    override fun setTyping(isTyping: Boolean) {
        val conversationId = activeConversationId ?: return
        scope.launch(ioDispatcher) {
            transportSink?.send(
                conversationId,
                MessageWireFrame.TypingFrame(
                    conversationId = conversationId,
                    memberId = localDeviceId,
                    memberName = localDisplayName,
                    isTyping = isTyping,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Applies a single reactor's add/remove of [emoji] on [messageId] to the aggregated row (#7).
     * Recomputes count + self-flag from the reactor set and removes the row when it empties. Shared
     * by the local toggle and inbound peer frames so both sides converge on the same aggregate.
     */
    private suspend fun applyReactionDelta(
        messageId: String,
        emoji: String,
        reactorId: String,
        isAdded: Boolean,
    ) {
        val existing = reactionDao.get(messageId, emoji)
        val reactors = decodeReactorIds(existing?.reactorIdsJson).toMutableSet()
        if (isAdded) reactors.add(reactorId) else reactors.remove(reactorId)
        if (reactors.isEmpty()) {
            reactionDao.remove(messageId, emoji)
        } else {
            reactionDao.upsert(
                ReactionEntity(
                    messageId = messageId,
                    emoji = emoji,
                    count = reactors.size,
                    selfReacted = localDeviceId in reactors,
                    reactorIdsJson = encodeReactorIds(reactors.toList()),
                ),
            )
        }
    }

    /** Encodes reactor ids as a minimal JSON string array; ids are UUIDs (no escaping needed). */
    private fun encodeReactorIds(ids: List<String>): String =
        ids.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }

    /** Extracts quoted values from a JSON string array; tolerant of null/blank/legacy rows. */
    private fun decodeReactorIds(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return Regex("\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
    }

    override fun enterListSelectionMode(conversationId: String) {
        _chatListState.update { it.copy(selectionMode = true, selectedIds = setOf(conversationId)) }
    }

    override fun toggleListSelection(conversationId: String) {
        _chatListState.update { state ->
            val updated = state.selectedIds.toMutableSet()
            if (conversationId in updated) updated.remove(conversationId) else updated.add(conversationId)
            state.copy(selectedIds = updated, selectionMode = updated.isNotEmpty())
        }
    }

    override fun clearListSelection() {
        _chatListState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    override fun archiveConversation(conversationId: String) {
        scope.launch(ioDispatcher) {
            conversationDao.setArchived(conversationId, true)
        }
    }

    override fun deleteMessage(localId: String) {
        scope.launch(ioDispatcher) {
            // Tombstone rather than hard-delete so history keyset pagination stays stable; the
            // observe/history queries filter `deletedAt IS NULL`, so it vanishes from the thread.
            messageDao.markDeleted(localId, System.currentTimeMillis())
            // Also drop any pending outbox row: a message deleted before it drained must NOT still
            // be transmitted to the peer. The drain loop additionally re-checks the tombstone, so a
            // row already claimed for this tick is discarded rather than sent.
            outboxDao.delete(localId)
        }
    }

    override fun deleteConversations(ids: Set<String>) {
        if (ids.isEmpty()) return
        val list = ids.toList()
        scope.launch(ioDispatcher) {
            // Hard-delete both tables so no orphan messages survive the thread (bulk delete is a
            // deliberate "the whole conversation is gone" action, unlike per-message tombstoning).
            messageDao.deleteByConversations(list)
            conversationDao.deleteConversations(list)
        }
        clearListSelection()
    }

    override fun setConversationsPinned(ids: Set<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        scope.launch(ioDispatcher) {
            ids.forEach { conversationDao.setPinned(it, pinned) }
        }
        clearListSelection()
    }

    override fun setConversationsMuted(ids: Set<String>, muted: Boolean) {
        if (ids.isEmpty()) return
        scope.launch(ioDispatcher) {
            ids.forEach { conversationDao.setMuted(it, muted) }
        }
        clearListSelection()
    }

    override fun markConversationsRead(ids: Set<String>) {
        if (ids.isEmpty()) return
        scope.launch(ioDispatcher) {
            // Advance each thread's read cursor to its newest message so the unread badge clears.
            ids.forEach { id ->
                messageDao.newestLocalId(id)?.let { conversationDao.updateLastReadCursor(id, it) }
            }
        }
        clearListSelection()
    }

    override fun archiveConversations(ids: Set<String>) {
        if (ids.isEmpty()) return
        scope.launch(ioDispatcher) {
            ids.forEach { conversationDao.setArchived(it, true) }
        }
        clearListSelection()
    }

    /**
     * Joins an attachment row with its live transfer progress and populates the rich UI attachment
     * fields (B4). Image/video MIME renders an inline thumbnail (video adds a play overlay) **once the
     * bytes are local**; anything else — including media still awaiting acceptance or download —
     * becomes a file card. Text-only rows return unchanged.
     */
    private fun applyAttachment(
        base: FlashMessageUi,
        entity: MessageEntity,
        progressByTransfer: Map<String, FlashAttachmentProgress>,
    ): FlashMessageUi {
        val transferId = entity.attachmentTransferId ?: return base
        val mime = entity.attachmentMime ?: "application/octet-stream"
        val name = entity.attachmentName ?: "file"
        val live = progressByTransfer[transferId]
        // Prefer the live transfer's path (the received file materialises on completion); fall back
        // to the row's stored path (the source URI stamped at send time).
        val path = live?.localPath?.ifBlank { null } ?: entity.attachmentPath
        val status = live?.status
            ?: if (path != null) FlashFileTransferStatus.Downloaded else FlashFileTransferStatus.NotDownloaded
        val progress = live?.progress ?: if (status == FlashFileTransferStatus.Downloaded) 1f else 0f
        // A media tile can only show bytes that already exist locally. An inbound offer has no path
        // and no permission to fetch one until the user accepts, so it has to fall through to the
        // file card — the only surface carrying Accept/Decline, progress and "Tap to retry". The
        // video branch already guarded this; the image branch did not, which is why a received photo
        // rendered as a dead gradient tile with no way to accept it and nothing to decode.
        val renderable = path != null && when (status) {
            FlashFileTransferStatus.Downloaded -> true
            // Outbound rows point at the sender's own picked file, so it is on disk from the start.
            FlashFileTransferStatus.Transferring -> base.isMine
            FlashFileTransferStatus.NotDownloaded,
            FlashFileTransferStatus.AwaitingAcceptance,
            FlashFileTransferStatus.Failed,
            -> false
        }
        return when {
            renderable && (mime.startsWith("image/") || mime.startsWith("video/")) -> base.copy(
                images = listOf(
                    FlashImageAttachmentUi(
                        id = transferId,
                        uri = path,
                        thumbUri = path,
                        mimeType = mime,
                        isVideo = mime.startsWith("video/"),
                    ),
                ),
            )
            mime.startsWith("audio/") -> {
                val (durationMs, amplitudes) = decodeVoiceMeta(entity.text)
                base.copy(
                    // Voice rows have no body text — clear the encoded metadata blob.
                    text = "",
                    voiceAttachments = listOf(
                        FlashVoiceAttachmentUi(
                            id = transferId,
                            uri = path,
                            durationMs = durationMs,
                            amplitudes = amplitudes,
                            mimeType = mime,
                            transferStatus = status,
                        ),
                    ),
                )
            }
            else -> base.copy(
                fileAttachments = listOf(
                    FlashFileAttachmentUi(
                        id = transferId,
                        name = name,
                        sizeBytes = entity.attachmentSize,
                        mimeType = mime,
                        transferStatus = status,
                        transferProgress = progress,
                        transferSpeedMbps = live?.speedMbps ?: 0f,
                        etaSeconds = live?.etaSeconds ?: 0,
                        localUri = path,
                    ),
                ),
            )
        }
    }

    /**
     * Turns a `cmsg:`-marked row into a call bubble; every other row passes through untouched.
     *
     * The marker lives in the `text` column (same trick as voice notes) so a call log costs no
     * schema migration. Delivery ticks are cleared: a call is not a message in flight.
     */
    private fun applyCallEvent(base: FlashMessageUi, entity: MessageEntity): FlashMessageUi {
        val event = decodeCallMeta(entity.text) ?: return base
        return base.copy(text = "", deliveryStatus = null, callEvent = event)
    }

    /** Packs a call row into the (otherwise unused) text column: `cmsg:<kind>:<0|1>:<durationMs>`. */
    private fun encodeCallMeta(
        kind: FlashCallEventKind,
        video: Boolean,
        durationMs: Long,
    ): String = "$CALL_META_PREFIX${kind.name}:${if (video) 1 else 0}:${durationMs.coerceAtLeast(0L)}"

    /** Inverse of [encodeCallMeta]. Null for any row that is not a call row. */
    private fun decodeCallMeta(text: String?): FlashCallEventUi? {
        if (text == null || !text.startsWith(CALL_META_PREFIX)) return null
        val parts = text.removePrefix(CALL_META_PREFIX).split(':')
        if (parts.size < 3) return null
        // Unknown kind name → not renderable; a future build's row must not crash this one.
        val kind = FlashCallEventKind.values().firstOrNull { it.name == parts[0] } ?: return null
        return FlashCallEventUi(
            kind = kind,
            video = parts[1] == "1",
            durationMs = parts[2].toLongOrNull() ?: 0L,
        )
    }

    /**
     * Chat-list preview line for a raw `text` column value, or null when there is nothing to show.
     *
     * Rows whose text is a metadata marker have no human-readable body, so they get a label —
     * otherwise `cmsg:`/`vmsg:` blobs leak straight into the chat list.
     */
    private fun previewLabel(raw: String?): String? {
        val text = raw?.ifBlank { null } ?: return null
        decodeCallMeta(text)?.let { event ->
            val what = if (event.video) "Video call" else "Voice call"
            return when (event.kind) {
                FlashCallEventKind.Missed -> "Missed ${what.lowercase(Locale.getDefault())}"
                FlashCallEventKind.Unanswered -> "$what, no answer"
                else -> what
            }
        }
        if (text.startsWith(VOICE_META_PREFIX)) return "Voice message"
        return text
    }

    /** Packs a voice note's duration + waveform into the (otherwise empty) message text column. */
    private fun encodeVoiceMeta(durationMs: Long, amplitudes: List<Int>): String =
        "$VOICE_META_PREFIX$durationMs:${amplitudes.joinToString(",")}"

    /** Inverse of [encodeVoiceMeta]; tolerant of empty/legacy rows (returns 0 + no waveform). */
    private fun decodeVoiceMeta(text: String?): Pair<Long, List<Int>> {
        if (text == null || !text.startsWith(VOICE_META_PREFIX)) return 0L to emptyList()
        val body = text.removePrefix(VOICE_META_PREFIX)
        val sep = body.indexOf(':')
        if (sep < 0) return 0L to emptyList()
        val durationMs = body.substring(0, sep).toLongOrNull() ?: 0L
        val amplitudes = body.substring(sep + 1)
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
        return durationMs to amplitudes
    }

    private fun mapStatus(status: String): FlashMessageStatus = when (status) {
        "PENDING" -> FlashMessageStatus.Pending
        "SENT" -> FlashMessageStatus.Sent
        "DELIVERED" -> FlashMessageStatus.Delivered
        "READ" -> FlashMessageStatus.Read
        "FAILED" -> FlashMessageStatus.Failed
        else -> FlashMessageStatus.Delivered
    }

    private fun computeInitials(name: String): String {
        val parts = name.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "FL"
            parts.size == 1 -> parts[0].take(2).uppercase(Locale.getDefault())
            else -> "${parts[0].first()}${parts[1].first()}".uppercase(Locale.getDefault())
        }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

    private fun formatTimestamp(millis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - millis
        return when {
            diff < 60_000 -> "Now"
            diff < 3600_000 -> "${diff / 60_000}m"
            diff < 86400_000 -> "${diff / 3600_000}h"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
        }
    }

    private companion object {
        // Namespaced marker stored in a voice row's text column: "vmsg:<durationMs>:<csv amplitudes>".
        const val VOICE_META_PREFIX = "vmsg:"
        // Namespaced marker stored in a call row's text column: "cmsg:<KIND>:<video 0|1>:<durationMs>".
        const val CALL_META_PREFIX = "cmsg:"
        // Upper bound on full-history search hits scanned per query (#12); collapsed to conversations.
        const val SEARCH_RESULT_LIMIT = 200
        // Outbox retry policy (#21): back off exponentially from this base, capped at the max,
        // between tries. Give-up is a wall-clock budget (ERROR-026), not an attempt count: an
        // undelivered message stays retryable for half an hour, which comfortably outlasts the
        // screen-off/Doze windows that used to burn through an 8-attempt cap in ~2 minutes.
        const val OUTBOX_GIVE_UP_AFTER_MS = 30 * 60 * 1000L
        const val OUTBOX_BASE_BACKOFF_MS = 1_000L
        const val OUTBOX_MAX_BACKOFF_MS = 60_000L

        // Falling-edge hold for the presence dot (ERROR-026). Long enough to cover the WS layer's
        // own recovery — the dialing side redials from a ~1 s base and the accepting side's backup
        // loop from ~4 s, plus a ~0.2 s handshake — so a self-healing drop never reaches the UI.
        // A peer that really left shows Offline this much later, which is fine for a LAN mesh.
        const val OFFLINE_HOLD_MS = 6_000L
    }
}
