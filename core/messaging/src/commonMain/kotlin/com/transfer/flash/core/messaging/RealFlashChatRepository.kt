@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.id.UuidIdGenerator
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.messaging.model.FlashAttachmentProgress
import com.transfer.flash.core.messaging.model.FlashCallEventKind
import com.transfer.flash.core.messaging.model.FlashCallEventUi
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMemberRole
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.messaging.model.FlashQuotedReplyUi
import com.transfer.flash.core.messaging.model.FlashReaction
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import com.transfer.flash.core.messaging.protocol.ChatWireFrame
import com.transfer.flash.core.messaging.protocol.GroupMembershipVersion
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupSyncCursor
import com.transfer.flash.core.messaging.protocol.GroupSyncPolicy
import com.transfer.flash.core.messaging.protocol.GroupSyncRoundState
import com.transfer.flash.core.messaging.protocol.GroupSyncTier
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.messaging.protocol.membershipUpdateWins
import com.transfer.flash.core.messaging.util.assignDaySeparators
import com.transfer.flash.core.messaging.util.computeMessageGroupPositions
import com.transfer.flash.core.messaging.util.FlashMimeTypes
import com.transfer.flash.core.messaging.util.platformFormatMonthDay
import com.transfer.flash.core.messaging.util.platformFormatTimeOfDay
import com.transfer.flash.core.common.concurrent.SyncMap
import com.transfer.flash.core.common.concurrent.SyncSet
import com.transfer.flash.core.messaging.util.sortedChatListItems
import com.transfer.flash.core.messaging.util.throttleLatest
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.DraftDao
import com.transfer.flash.core.persistence.db.dao.GroupDeliveryDao
import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReactionDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.GroupDeliveryEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.persistence.db.entity.ReactionEntity
import com.transfer.flash.core.persistence.db.entity.ReceiptEntity
import com.transfer.flash.core.persistence.db.entity.RecentSearchEntity
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Functional wire transport provider for sending frames to a target conversation / peer.
 */
public fun interface MessageTransportSink {
    public suspend fun send(targetDeviceId: String, frame: MessageWireFrame): Boolean
}

/** Dedicated group transport seam; direct-message ABI and byte routing stay unchanged. */
public fun interface GroupTransportSink {
    public suspend fun send(targetDeviceId: String, frame: GroupWireFrame): Boolean
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
    private val groupMemberDao: GroupMemberDao? = null,
    private val groupDeliveryDao: GroupDeliveryDao? = null,
    /** Only already-paired peers can create, join, or send group traffic. */
    private val isTrustedPeer: (String) -> Boolean = { false },
    /** Checks whether E2E encryption is established with [conversationId]. */
    private val isChannelEncrypted: (String) -> Boolean = { false },
    private val groupTransportSink: GroupTransportSink? = null,
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
     * Group-aware host seam. Kept separate and defaulted through the legacy callback so existing
     * internal hosts remain source-compatible while Android can name group notifications.
     */
    private val onInboundTextMessageWithGroupTitle: (
        conversationId: String,
        senderName: String?,
        text: String,
        groupTitle: String?,
    ) -> Unit = { conversationId, senderName, text, _ ->
        onInboundTextMessage(conversationId, senderName, text)
    },
    /**
     * Host callback fired when an inbound attachment row is newly inserted (accepted or
     * auto-accepted offers only — a pending offer mints no row, so it fires nothing).
     * Default no-op; see [onInboundTextMessage].
     */
    private val onInboundAttachment: (conversationId: String, senderName: String?, fileName: String, mimeType: String) -> Unit =
        { _, _, _, _ -> },
    /** Group-aware counterpart to [onInboundAttachment], with the same compatibility bridge. */
    private val onInboundAttachmentWithGroupTitle: (
        conversationId: String,
        senderName: String?,
        fileName: String,
        mimeType: String,
        groupTitle: String?,
    ) -> Unit = { conversationId, senderName, fileName, mimeType, _ ->
        onInboundAttachment(conversationId, senderName, fileName, mimeType)
    },
    /**
     * Wall clock. Defaulted to [SystemTimeSource] so every existing call site (production
     * wiring, all tests) compiles unchanged; tests that need determinism pass a fake.
     * A seam rather than a direct call because common code cannot reach
     * `System.currentTimeMillis()`.
     */
    private val timeSource: FlashTimeSource = SystemTimeSource,
    /**
     * Runs [block] as one database write transaction, so an outgoing message row and its outbox
     * row commit together or not at all. Production passes `FlashDatabase::runInWriteTransaction`;
     * the inline default suits the fake-DAO tests, which have no database to roll back.
     */
    private val runInTransaction: suspend (block: suspend () -> Unit) -> Unit = { it() },
) : FlashChatRepository {

    private val _chatListState = MutableStateFlow(FlashChatListUiState())

    /**
     * Group Phase A: in-memory group titles, stamped the moment a group name is written
     * (local create, inbound create). Lets [openConversation] seed a correct group header
     * synchronously WITHOUT a Room read on the caller's thread — a blocking read there
     * starved the shared test executor, and on the UI thread it would block main. A group
     * opened cold from the chat list is corrected by the combine's first Room emission.
     */
    private val groupTitleCache = SyncMap<String, String>()

    /**
     * F3: this device's performance tier as seen by the sync protocol — it paces pushes TO us
     * (LOW returners get 5 msg/s). Read per request so a tier change takes effect immediately.
     */
    private val syncTier: () -> GroupSyncTier = { GroupSyncTier.MEDIUM }
    override val chatListState: StateFlow<FlashChatListUiState> = _chatListState.asStateFlow()

    private val _conversationState = MutableStateFlow(EMPTY_CONVERSATION)
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

    /**
     * Wake signal for [drainOutboxLoop], fed by the `outboxDao.observeCount()` collector launched
     * from the init block below. That is a Room `Flow`, so it fires on any write to the `outbox`
     * table — an enqueue from any send path, our own `rescheduleAttempt`, a delete on an inbound
     * `DeliveryReceipt`.
     *
     * [Channel.CONFLATED] is load-bearing twice over: a burst of writes collapses into a single
     * wake, and a wake that arrives *while* a drain pass is already running is retained rather than
     * dropped, so the pass that could not see that row runs again immediately instead of leaving it
     * to wait out a whole idle interval. Nothing here is a lost-wakeup risk.
     *
     * MUST be declared above the init block, for the reason recorded on [drainMutex].
     */
    private val drainWake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Earliest retry deadline [drainOutboxOnce] has scheduled, or null when nothing is known to be
     * pending. This is what [drainOutboxLoop] sleeps until; see [OutboxDrainSchedule].
     *
     * `@Volatile` because the writer is whichever thread happened to run the drain — a send path,
     * `notifyPeerSessionUp`, or the loop itself — while the reader is always the loop's own thread.
     * MUST be declared above the init block, for the reason recorded on [drainMutex].
     */
    @Volatile
    private var outboxNextDueAt: Long? = null

    // Ephemeral in-memory typing state: conversationId -> (memberId -> memberName) currently typing.
    // Mirrored into [typingFlow] so the conversation UI can observe it (#11). Never persisted.
    private val typingStates = SyncMap<String, SyncMap<String, String>>()
    private val typingFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    // Active typing expiry jobs per (conversationId|memberId). Ephemeral.
    private val typingExpiryJobs = SyncMap<String, Job>()

    private fun scheduleTypingExpiry(conversationId: String, memberId: String) {
        val key = "$conversationId|$memberId"
        typingExpiryJobs[key]?.cancel()
        typingExpiryJobs[key] = scope.launch(ioDispatcher) {
            delay(TYPING_EXPIRY_MS)
            val conv = typingStates[conversationId]
            if (conv != null && conv.remove(memberId) != null) {
                if (conv.valuesSnapshot().isEmpty()) {
                    typingStates.remove(conversationId)
                }
                publishTyping()
            }
            typingExpiryJobs.remove(key)
        }
    }

    private fun cancelTypingExpiry(conversationId: String, memberId: String) {
        val key = "$conversationId|$memberId"
        typingExpiryJobs.remove(key)?.cancel()
    }

    private fun publishTyping() {
        typingFlow.value = typingStates.toMap()
            .mapValues { (_, members) -> members.valuesSnapshot() }
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

    /**
     * [attachmentProgress] at a cadence a screen can use. MUST be declared above the init block
     * below, for the reason recorded on [drainMutex] — the stamping collector it feeds is launched
     * from there.
     *
     * The transfer layer's watcher publishes every 10 ms for the whole duration of a transfer, and
     * this flow is an input to the conversation `combine`. So each of those 100 emissions a second
     * used to re-derive the entire open thread: index rows by local id, group reaction rows, build a
     * fresh `FlashMessageUi` per message (with a timestamp format and an initials derivation each),
     * reverse the list, walk it again for group positions, rebuild the header, then deep-compare the
     * result against the previous state. With a fifty-message window open that is tens of thousands
     * of short-lived objects a second, and it is the *foreground* cost the user feels while a
     * transfer runs — on the low-end hardware this is aimed at, it competes with the transfer itself.
     *
     * Every field this actually moves is coarser than the tick that produced it:
     * - `progress` is driven by `bytesDone`, which only advances when an ACK_BATCH lands — one per
     *   32 chunks, i.e. one per 2 MB at the default chunk size. Between batches it does not change
     *   at all.
     * - `speedMbps` renders as `"%.1f MB/s"`, so anything finer than 0.1 MB/s is invisible.
     * - `etaSeconds` is whole seconds, and the file card does not render it at all today.
     *
     * So the 10 ms cadence carried no information the UI could show. The window is deliberately
     * tier-independent rather than gated on the performance mode: a text label that
     * changes 10 times a second is already faster than it can be read, and the progress bar is
     * animated by `animateFloatAsState` against Compose's frame clock — its smoothness comes from
     * the animation, not from how often a new target arrives. Nothing about the HIGH-tier look
     * changes here, so there is nothing to gate.
     */
    private val pacedAttachmentProgress: Flow<Map<String, FlashAttachmentProgress>> =
        attachmentProgress.throttleLatest(ATTACHMENT_PROGRESS_THROTTLE_MS)

    init {
        // Observe conversation list from Room, joined with live session presence + unread counts.
        scope.launch(ioDispatcher) {
            combine(
                conversationDao.observeAll(),
                displayedPresence,
                messageDao.observeUnreadCounts(localDeviceId),
                messageDao.observeLatestPreviews(),
                typingFlow,
            ) { entities, peers, unreadRows, previewRows, typingByConversation ->
                val unreadByConversation = unreadRows.associate { it.conversationId to it.unread }
                val previewByConversation = previewRows.associate { it.conversationId to it.previewText }
                suspend fun toUiItem(entity: ConversationEntity): FlashChatListItemUi {
                    val displayTitle = if (entity.isGroup) {
                        entity.title.ifBlank { entity.id }
                    } else {
                        peerNameResolver(entity.id)?.ifBlank { null }
                            ?: entity.title.ifBlank { entity.id }
                    }
                    val presence: FlashPeerPresence
                    val groupOnlineCount: Int
                    if (entity.isGroup) {
                        val onlineMembers = groupMemberDao
                            ?.activeMembers(entity.id)
                            .orEmpty()
                            .count { it.deviceId in peers.online }
                        groupOnlineCount = onlineMembers
                        presence = if (onlineMembers > 0) FlashPeerPresence.Online else FlashPeerPresence.Offline
                    } else {
                        groupOnlineCount = 0
                        presence = when {
                            entity.id in peers.online -> FlashPeerPresence.Online
                            entity.id in peers.connecting -> FlashPeerPresence.Connecting
                            else -> FlashPeerPresence.Offline
                        }
                    }
                    val isDirectOnline = entity.id in peers.online
                    val isTyping = if (entity.isGroup) {
                        groupOnlineCount > 0 && typingByConversation[entity.id].orEmpty().isNotEmpty()
                    } else {
                        isDirectOnline && typingByConversation[entity.id].orEmpty().isNotEmpty()
                    }
                    return FlashChatListItemUi(
                        id = entity.id,
                        title = displayTitle,
                        avatarInitials = computeInitials(displayTitle),
                        previewText = previewLabel(previewByConversation[entity.id])
                            ?: "Tap to view conversation",
                        timestamp = formatTimestamp(entity.sortOrder, timeSource.nowMs()),
                        unreadCount = unreadByConversation[entity.id] ?: 0,
                        presence = presence,
                        isGroup = entity.isGroup,
                        groupOnlineCount = groupOnlineCount,
                        isPinned = entity.pinned,
                        isMuted = entity.muted,
                        isTyping = isTyping,
                        sortOrder = entity.sortOrder,
                        isArchived = entity.archived,
                    )
                }
                val unarchived = entities.filter { !it.archived }.map { toUiItem(it) }
                val archived = entities.filter { it.archived }.map { toUiItem(it) }
                unarchived to archived
            }.collectLatest { (items, archivedItems) ->
                _chatListState.update { current ->
                    current.copy(
                        items = sortedChatListItems(items),
                        archivedItems = sortedChatListItems(archivedItems),
                        // ERROR-034: the first emission is what turns "we don't know yet" into
                        // "this is the list". Set unconditionally — an empty list from Room is a
                        // real answer (a genuinely fresh install) and must be allowed to show the
                        // first-run panel.
                        hasLoaded = true,
                    )
                }
            }
        }

        // Prune ephemeral typing indicators the instant a peer departs/disconnects.
        scope.launch(ioDispatcher) {
            onlinePeerIds.collect { onlineSet ->
                var changed = false
                typingStates.toMap().forEach { (convId, members) ->
                    val isGroup = conversationDao.get(convId)?.isGroup == true
                    if (!isGroup) {
                        if (convId !in onlineSet) {
                            if (members.valuesSnapshot().isNotEmpty()) {
                                members.clear()
                                changed = true
                            }
                            typingStates.remove(convId)
                            cancelTypingExpiry(convId, convId)
                        }
                    } else {
                        members.toMap().keys.forEach { memberId ->
                            if (memberId !in onlineSet) {
                                members.remove(memberId)
                                cancelTypingExpiry(convId, memberId)
                                changed = true
                            }
                        }
                    }
                }
                if (changed) {
                    publishTyping()
                }
            }
        }

        // Background outbox drain worker. Event-driven, not polled: see [drainOutboxLoop].
        scope.launch(ioDispatcher) {
            drainOutboxLoop()
        }

        // The wake signal that replaces the old 1 Hz grid. Kept as a separate collector rather than
        // folded into the loop because the loop has to be free to be *asleep* while this stays
        // subscribed — a Room Flow only invalidates for as long as something is collecting it.
        scope.launch(ioDispatcher) {
            outboxDao.observeCount().collect { drainWake.trySend(Unit) }
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
            pacedAttachmentProgress.collect { byTransfer ->
                byTransfer.forEach { (transferId, live) ->
                    if (live.status != FlashFileTransferStatus.Downloaded) return@forEach
                    val path = live.localPath?.ifBlank { null } ?: return@forEach
                    if ("$transferId|$path" in stamped) return@forEach
                    // A failed write leaves the row unstamped — the placeholder outcome it already
                    // had — and must not cancel the collector for every later transfer.
                    val written = runCatching {
                        val changed = messageDao.updateAttachmentPath(transferId, path)
                        val groupMsgId = transferToGroupMessage[transferId]
                        if (groupMsgId != null) {
                            messageDao.updateAttachmentPath(groupMsgId, path)
                        }
                        // 0 rows changed is ambiguous: the row may already hold this path, or may not
                        // exist yet (a completion that raced its own ingestion). Only the first is
                        // done, so only the first may be cached — otherwise that transfer would
                        // never be stamped at all.
                        changed > 0 || messageDao.existsAttachment(transferId) || (groupMsgId != null && messageDao.existsAttachment(groupMsgId))
                    }.getOrDefault(false)
                    if (written) stamped.add("$transferId|$path")
                }
            }
        }
    }

    override fun openConversation(conversationId: String) {
        activeConversationId = conversationId
        activeConversationJob?.cancel()

        // ERROR-034: clear the previous thread *before* the new collector runs. The combine below
        // emits asynchronously (Room + IO dispatcher), so without this reset the outgoing thread's
        // header, messages and draft stay on screen under the incoming thread's route — open B
        // straight after A and you read A's name, avatar and messages for a frame or three.
        //
        // The header is pre-seeded with the same title derivation the combine performs, so what is
        // shown is correct from the first frame rather than blank-then-correct. Only the message
        // list starts empty, which is the one thing we genuinely do not know yet.
        //
        // Group Phase A: a group is NOT a peer id — `peerNameResolver(groupId)` is always null and
        // the raw UUID won. The seed cannot read Room synchronously (openConversation runs on the
        // main thread, and a runBlocking read also starved the shared test executor), so a group
        // name stamped in [groupTitleCache] seeds a correct group header instantly; anything else
        // seeds the neutral derivation and the combine stamps DB truth on its first emission.
        val cachedGroupTitle = groupTitleCache[conversationId]
        if (cachedGroupTitle != null) {
            _conversationState.value = FlashConversationUiState(
                header = FlashChatHeaderUiState(
                    title = cachedGroupTitle,
                    avatarInitials = computeInitials(cachedGroupTitle),
                    isGroup = true,
                    showCallActions = true,
                ),
                messages = emptyList(),
            )
        } else {
            val seedTitle = peerNameResolver(conversationId)?.ifBlank { null } ?: conversationId
            _conversationState.value = FlashConversationUiState(
                header = FlashChatHeaderUiState(
                    title = seedTitle,
                    avatarInitials = computeInitials(seedTitle),
                    isEncrypted = isChannelEncrypted(conversationId),
                ),
                messages = emptyList(),
            )
        }

        // Track the newest message id we've already marked read so an unchanged head does not
        // rewrite the cursor (and needlessly re-emit the chat list) on every recomposition, plus
        // the newest INBOUND id we've already acked so we don't re-send the same read receipt.
        var lastMarkedReadId: String? = null
        var lastAckedInboundId: String? = null

        activeConversationJob = scope.launch(ioDispatcher) {
            val isGroupConversation = conversationDao.get(conversationId)?.isGroup == true
            val deliveryCountsFlow = if (isGroupConversation) {
                groupDeliveryDao?.observeDeliveryCounts(conversationId, localDeviceId) ?: flowOf(emptyList())
            } else {
                flowOf(emptyList())
            }
            // Inner combine (5 flows): pure message content — rows + draft + attachment progress +
            // reactions + observable group-delivery aggregates.
            val contentFlow = combine(
                messageDao.observeConversation(conversationId),
                draftDao.observeDraft(conversationId),
                pacedAttachmentProgress,
                reactionDao.observeForConversation(conversationId),
                deliveryCountsFlow,
            ) { entities, draftEntity, progressByTransfer, reactionRows, deliveryCounts ->
                // Index rows by local id so a reply can resolve its quoted message's author/side for
                // the in-bubble quote card (#8). Falls back to the wire-carried preview when the
                // quoted row is not in this window (e.g. paged out).
                val byLocalId = entities.associateBy { it.localId }
                // Aggregate reaction rows per message id → UI chips (#7).
                val reactionsByMessage = reactionRows.groupBy { it.messageId }
                val deliveryCountsByMessage = if (isGroupConversation) {
                    deliveryCounts.associateBy { it.messageId }
                } else {
                    emptyMap()
                }
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
                    val deliveryCount = deliveryCountsByMessage[entity.localId]
                        ?.takeIf { entity.senderId == localDeviceId }
                    val base = FlashMessageUi(
                        id = entity.localId,
                        senderName = entity.senderName ?: if (entity.senderId == localDeviceId) "You" else "Peer",
                        senderInitials = computeInitials(entity.senderName ?: entity.senderId),
                        timeLabel = formatTime(entity.sentAt),
                        text = entity.text,
                        isMine = entity.senderId == localDeviceId,
                        deliveryStatus = mapStatus(entity.status),
                        deliveredTo = deliveryCount?.deliveredTo,
                        deliveredTotal = deliveryCount?.deliveredTotal,
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
                val chronologicalEntities = entities.asReversed()
                val messagesWithSeparators = assignDaySeparators(
                    messages = chronologicalEntities.zip(messages.asReversed()),
                    nowMs = timeSource.nowMs(),
                ) { entity -> entity.sentAt }
                ConversationContent(
                    messages = computeMessageGroupPositions(messagesWithSeparators),
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
                // Group Phase A: a group thread derives its header from the member roster, not
                // from a peer-name lookup (a groupId is not a device id — the UUID used to win).
                val conversationEntity = conversationDao.get(conversationId)
                if (conversationEntity?.isGroup == true) {
                    val members = groupMemberDao?.activeMembers(conversationId).orEmpty()
                    val memberIds = members.mapTo(HashSet()) { it.deviceId }
                    val onlineMembers = members.count { it.deviceId in peers.online }
                    val onlineMemberIds = members.filter { it.deviceId in peers.online }.mapTo(HashSet()) { it.deviceId }
                    val activeTypingMembers = typingStates[conversationId]?.toMap().orEmpty()
                    val activeTypingNames = if (onlineMembers > 0) {
                        activeTypingMembers.filter { (memberId, _) ->
                            memberId in onlineMemberIds
                        }.values.toList()
                    } else {
                        emptyList()
                    }
                    val title = conversationEntity.title.ifBlank { conversationId }
                    FlashConversationUiState(
                        header = FlashChatHeaderUiState(
                            title = title,
                            avatarInitials = computeInitials(title),
                            presence = if (onlineMembers > 0) FlashPeerPresence.Online else FlashPeerPresence.Offline,
                            // Group members share our LAN/WS mesh when any of them is online.
                            transport = if (onlineMembers > 0) FlashNetworkTransport.Lan else FlashNetworkTransport.Unknown,
                            isGroup = true,
                            memberInitials = members.take(4).map { computeInitials(it.displayName) },
                            memberCount = members.size,
                            onlineCount = onlineMembers,
                            typingMemberNames = activeTypingNames,
                            // Group voice & video calls supported via multi-leg full-mesh CallCoordinator
                            showCallActions = true,
                        ),
                        messages = content.messages,
                        draftText = content.draftText,
                        members = members.map { member ->
                            member.toMemberUi(isOnline = member.deviceId in peers.online)
                        },
                    ) to Pair(content.newestMessageId, content.newestInboundId)
                } else {
                    directHeaderState(content, peers, typingByConversation, conversationId)
                        .let { it to Pair(content.newestMessageId, content.newestInboundId) }
                }
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
                                readAt = timeSource.nowMs(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * The direct-chat header derivation, byte-identical to the pre-group combine body (group
     * Phase A only branched it out). conversationId is the peer's device id; show the friendly
     * name, not the UUID.
     */
    private fun directHeaderState(
        content: ConversationContent,
        peers: PresenceSnapshot,
        typingByConversation: Map<String, List<String>>,
        conversationId: String,
    ): FlashConversationUiState {
        val title = peerNameResolver(conversationId)?.ifBlank { null } ?: conversationId
        val isOnline = conversationId in peers.online
        val isConnecting = !isOnline && conversationId in peers.connecting
        // Only reachable peers can be typing; if offline, typing indicator is strictly empty.
        val typingNames = if (isOnline || isConnecting) {
            typingByConversation[conversationId].orEmpty()
        } else {
            emptyList()
        }
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
        return FlashConversationUiState(
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
                isEncrypted = isChannelEncrypted(conversationId),
            ),
            messages = content.messages,
            // Restore any unsent composer text (#9); blank when there is no saved draft.
            draftText = content.draftText,
        )
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

    override suspend fun createGroup(name: String, memberIds: Set<String>): FlashResult<String> =
        // Group mutations do Room writes AND blocking socket writes (WsConnection.sendText), so
        // they must never run on the caller's dispatcher: the UI calls this from the main thread
        // and a blocking send there is a NetworkOnMainThreadException that also tears the
        // session down (observed on device 2026-09-08). Same rule as the drain loop's IO home.
        withContext(ioDispatcher) { createGroupLocked(name, memberIds) }

    private suspend fun createGroupLocked(
        name: String,
        memberIds: Set<String>,
    ): FlashResult<String> {
        val groupName = GroupPolicy.normalizedName(name)
            ?: return FlashResult.Failure(FlashError.Unknown("Group name must be 1-${GroupPolicy.MAX_GROUP_NAME_LENGTH} characters"))
        val allMembers = memberIds + localDeviceId
        if (!GroupPolicy.validMemberIds(allMembers, localDeviceId)) {
            return FlashResult.Failure(FlashError.Unknown("Groups support 2-${GroupPolicy.MAX_MEMBERS} unique members"))
        }
        if (memberIds.any { !isTrustedPeer(it) }) {
            return FlashResult.Failure(FlashError.Unknown("Every group member must be trusted"))
        }
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        val now = timeSource.nowMs()
        val groupId = UuidIdGenerator.newId()
        val operationId = UuidIdGenerator.newId()
        // Stamp before any suspend point that could race a fast openConversation().
        groupTitleCache[groupId] = groupName
        conversationDao.upsert(
            ConversationEntity(
                id = groupId,
                title = groupName,
                isGroup = true,
                sortOrder = now,
                groupCreatedBy = localDeviceId,
                groupCreatedAt = now,
            ),
        )
        allMembers.forEach { memberId ->
            members.upsert(
                GroupMemberEntity(
                    groupId = groupId,
                    deviceId = memberId,
                    displayName = if (memberId == localDeviceId) localDisplayName else peerNameResolver(memberId) ?: memberId,
                    role = if (memberId == localDeviceId) "owner" else "member",
                    joinedAt = now,
                    membershipVersion = now,
                    operationId = operationId,
                ),
            )
        }
        val frame = GroupWireFrame.Create(
            groupId = groupId,
            from = localDeviceId,
            operationId = operationId,
            membershipVersion = now,
            name = groupName,
            memberIds = allMembers.sorted(),
        )
        memberIds.forEach { groupTransportSink?.send(it, frame) }
        return FlashResult.Success(groupId)
    }

    override suspend fun addGroupMembers(groupId: String, memberIds: Set<String>): FlashResult<Unit> =
        withContext(ioDispatcher) { addGroupMembersLocked(groupId, memberIds) }

    private suspend fun addGroupMembersLocked(
        groupId: String,
        memberIds: Set<String>,
    ): FlashResult<Unit> {
        if (memberIds.isEmpty()) return FlashResult.Success(Unit)
        if (memberIds.any { !isTrustedPeer(it) }) {
            return FlashResult.Failure(FlashError.Unknown("Every group member must be trusted"))
        }
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        val existing = members.activeMembers(groupId)
        if (existing.none { it.deviceId == localDeviceId }) {
            return FlashResult.Failure(FlashError.Unknown("You are not an active group member"))
        }
        if ((existing.map { it.deviceId }.toSet() + memberIds).size > GroupPolicy.MAX_MEMBERS) {
            return FlashResult.Failure(FlashError.Unknown("Groups support at most ${GroupPolicy.MAX_MEMBERS} members"))
        }
        val now = timeSource.nowMs()
        val operationId = UuidIdGenerator.newId()
        memberIds.forEach { memberId ->
            val current = members.member(groupId, memberId)
            val candidate = GroupMembershipVersion(now, operationId)
            val existingVersion = current?.let { GroupMembershipVersion(it.membershipVersion, it.operationId) }
            if (membershipUpdateWins(candidate, existingVersion)) {
                members.upsert(
                    GroupMemberEntity(
                        groupId, memberId, peerNameResolver(memberId) ?: memberId, "member", now,
                        now, operationId, true,
                    ),
                )
            }
        }
        val frame = GroupWireFrame.Add(groupId, localDeviceId, operationId, now, memberIds.sorted())
        // F2: existing members learn the Add; the NEWCOMERS instead receive a full State
        // bootstrap — they have no local membership rows, so a bare Add would be dropped by
        // their member gate (the owner's "added device never got the group" report).
        val rosterAfter = members.activeMembers(groupId)
        val stateFrame = GroupWireFrame.State(
            groupId = groupId,
            from = localDeviceId,
            operationId = operationId,
            membershipVersion = now,
            name = conversationDao.get(groupId)?.title ?: groupId,
            creatorId = conversationDao.get(groupId)?.groupCreatedBy ?: localDeviceId,
            members = rosterAfter.map { member ->
                GroupWireFrame.RosterEntry(
                    deviceId = member.deviceId,
                    displayName = member.displayName,
                    role = member.role,
                    joinedAt = member.joinedAt,
                    membershipVersion = member.membershipVersion,
                    operationId = member.operationId,
                    isActive = member.isActive,
                )
            },
        )
        (rosterAfter.map { it.deviceId } - localDeviceId).forEach { target ->
            if (target in memberIds) {
                groupTransportSink?.send(target, stateFrame)
            } else {
                groupTransportSink?.send(target, frame)
            }
        }
        return FlashResult.Success(Unit)
    }

    override suspend fun leaveGroup(groupId: String): FlashResult<Unit> =
        withContext(ioDispatcher) { leaveGroupLocked(groupId) }

    private suspend fun leaveGroupLocked(groupId: String): FlashResult<Unit> {
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        val current = members.member(groupId, localDeviceId)
            ?: return FlashResult.Failure(FlashError.Unknown("Unknown group"))
        val now = timeSource.nowMs()
        val operationId = UuidIdGenerator.newId()
        members.upsert(current.copy(membershipVersion = now, operationId = operationId, isActive = false))
        val frame = GroupWireFrame.Leave(groupId, localDeviceId, operationId, now, localDeviceId)
        members.activeMembers(groupId).filter { it.deviceId != localDeviceId }.forEach { target ->
            groupTransportSink?.send(target.deviceId, frame)
        }
        return FlashResult.Success(Unit)
    }

    override suspend fun groupMembers(groupId: String): List<FlashGroupMemberUi> =
        withContext(ioDispatcher) {
            groupMemberDao?.activeMembers(groupId)?.map { it.toMemberUi() }.orEmpty()
        }

    /** Phase B: one shared mapping so the sheet and the state carry identical rows. */
    private fun GroupMemberEntity.toMemberUi(isOnline: Boolean = false): FlashGroupMemberUi =
        FlashGroupMemberUi(
            id = deviceId,
            name = displayName,
            initials = computeInitials(displayName),
            isOnline = isOnline,
            role = if (role == "owner") FlashMemberRole.Owner else FlashMemberRole.Member,
            // Online members share our LAN/WS mesh; offline ones have no known transport.
            transport = if (isOnline) FlashNetworkTransport.Lan else FlashNetworkTransport.Unknown,
        )

    private suspend fun sendGroupText(
        conversation: ConversationEntity,
        text: String,
        now: Long,
        localId: String,
        replyToId: String?,
        replyToPreview: String?,
    ) {
        val members = groupMemberDao ?: return
        val deliveries = groupDeliveryDao ?: return
        val recipients = members.activeMembers(conversation.id)
            .filter { it.deviceId != localDeviceId }
        if (recipients.isEmpty()) return
        messageDao.insert(
            MessageEntity(
                localId = localId,
                conversationId = conversation.id,
                senderId = localDeviceId,
                senderName = localDisplayName,
                text = text,
                sentAt = now,
                status = "PENDING",
                replyToId = replyToId,
                replyToPreview = replyToPreview,
            ),
        )
        deliveries.insertAll(
            recipients.map { recipient ->
                GroupDeliveryEntity(
                    messageId = localId,
                    memberId = recipient.deviceId,
                    nextAttemptAt = now,
                )
            },
        )
        draftDao.clear(conversation.id)
        outboxDao.enqueue(OutboxEntity(localId, nextAttemptAt = now, payloadJson = text, createdAt = now))
        drainOutboxOnce()
    }

    override fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val conversationId = activeConversationId ?: return
        val now = timeSource.nowMs()
        val localId = UuidIdGenerator.newId()

        scope.launch(ioDispatcher) {
            val conversation = conversationDao.get(conversationId)
            if (conversation?.isGroup == true) {
                sendGroupText(conversation, trimmed, now, localId, replyToId = null, replyToPreview = null)
                return@launch
            }
            enqueueDirectText(conversationId, trimmed, now, localId, replyToId = null, replyToPreview = null)
            drainOutboxOnce()
        }
    }

    override fun sendReply(text: String, replyToId: String, replyToPreview: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val conversationId = activeConversationId ?: return
        val now = timeSource.nowMs()
        val localId = UuidIdGenerator.newId()

        scope.launch(ioDispatcher) {
            val conversation = conversationDao.get(conversationId)
            if (conversation?.isGroup == true) {
                sendGroupText(conversation, trimmed, now, localId, replyToId, replyToPreview)
                return@launch
            }
            enqueueDirectText(conversationId, trimmed, now, localId, replyToId, replyToPreview)
            drainOutboxOnce()
        }
    }

    /**
     * Writes a direct (1:1) outgoing text: message row, conversation upsert, draft clear and outbox
     * row in one transaction, so a process kill can never leave a PENDING row the drain cannot see.
     * Reply columns are stamped when present so the drain-rebuilt frame carries the quote (#8).
     */
    private suspend fun enqueueDirectText(
        conversationId: String,
        text: String,
        now: Long,
        localId: String,
        replyToId: String?,
        replyToPreview: String?,
    ) {
        runInTransaction {
            messageDao.insert(
                MessageEntity(
                    localId = localId,
                    conversationId = conversationId,
                    senderId = localDeviceId,
                    senderName = localDisplayName,
                    text = text,
                    sentAt = now,
                    status = "PENDING",
                    replyToId = replyToId,
                    replyToPreview = replyToPreview,
                ),
            )
            // Title uses the friendly name when known — never the raw conversationId, which (via
            // @Upsert full-row replace) would otherwise clobber a good inbound-set title with the
            // peer's device UUID.
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
                    payloadJson = text,
                    createdAt = now,
                ),
            )
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
                        updatedAt = timeSource.nowMs(),
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
        val now = timeSource.nowMs()
        val localId = UuidIdGenerator.newId()
        // Voice notes carry no body text; stash duration + waveform in the text column so the
        // playback card can render a real waveform without a schema change (B9). Parsed back out
        // in [applyAttachment]; see [encodeVoiceMeta]/[decodeVoiceMeta].
        val rowText = if (mimeType.startsWith("audio/")) {
            encodeVoiceMeta(voiceDurationMs, voiceAmplitudes)
        } else {
            ""
        }
        scope.launch(ioDispatcher) {
            // F1 interim gate (group Phase 1 plan): group attachments are unsupported until the
            // F4 media path lands. Rejected with a log — never silently threaded into a group
            // row, which used to clobber the conversation's identity (see [touchConversation]).
            if (conversationDao.get(conversationId)?.isGroup == true) {
                FlashLog.w("CHAT", "Group attachment rejected as unsupported (F4 pending): $fileName")
                return@launch
            }
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
            touchConversation(conversationId, now)
        }
    }

    override fun sendGroupAttachment(
        conversationId: String,
        messageId: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        localPath: String?,
        voiceDurationMs: Long,
        voiceAmplitudes: List<Int>,
    ) {
        val now = timeSource.nowMs()
        val rowText = if (mimeType.startsWith("audio/")) {
            encodeVoiceMeta(voiceDurationMs, voiceAmplitudes)
        } else {
            ""
        }
        val members = groupMemberDao
        val deliveries = groupDeliveryDao
        scope.launch(ioDispatcher) {
            if (conversationDao.get(conversationId)?.isGroup != true) return@launch
            val recipients = members?.activeMembers(conversationId)
                ?.filter { it.deviceId != localDeviceId }
                .orEmpty()
            messageDao.insert(
                MessageEntity(
                    localId = messageId,
                    conversationId = conversationId,
                    senderId = localDeviceId,
                    senderName = localDisplayName,
                    text = rowText,
                    sentAt = now,
                    status = if (recipients.isEmpty()) "SENT" else "PENDING",
                    attachmentTransferId = transferId,
                    attachmentName = fileName,
                    attachmentMime = mimeType,
                    attachmentSize = sizeBytes,
                    attachmentPath = localPath,
                ),
            )
            if (recipients.isNotEmpty() && deliveries != null) {
                deliveries.insertAll(
                    recipients.map { recipient ->
                        GroupDeliveryEntity(
                            messageId = messageId,
                            memberId = recipient.deviceId,
                            nextAttemptAt = now,
                        )
                    },
                )
            }
            touchConversation(conversationId, now)
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
            val now = timeSource.nowMs()
            // F4: a parked GroupMedia intro promotes this transfer to a GROUP attachment —
            // threaded under the groupId with the sender's own messageId (re-pull dedup) and
            // the wire-carried sender name. Consumed once.
            val media = pendingGroupMedia.remove(transferId)
            if (media != null) {
                // F7d: this IS a group transfer either way - the parked intro is drained above, and
                // `return@launch` below keeps it out of the 1-to-1 fallback. The atomic claim only
                // decides who mints the bubble, so the loser must not re-insert (that is what the
                // DB's IGNORE rule used to paper over).
                if (claimedGroupMedia.add(transferId)) {
                    val insertedRowId = messageDao.insert(
                        MessageEntity(
                            localId = media.messageId,
                            conversationId = media.groupId,
                            senderId = media.from,
                            senderName = media.senderName,
                            text = "",
                            sentAt = media.sentAt.takeIf { it > 0 } ?: now,
                            status = "DELIVERED",
                            attachmentTransferId = transferId,
                            attachmentName = media.fileName.ifBlank { fileName },
                            attachmentMime = media.mimeType.ifBlank { mimeType },
                            attachmentSize = if (media.sizeBytes > 0) media.sizeBytes else sizeBytes,
                            attachmentPath = null,
                        ),
                    )
                    touchConversation(media.groupId, now)
                    if (insertedRowId != -1L) {
                        runCatching {
                            onInboundAttachmentWithGroupTitle(
                                media.groupId,
                                media.senderName,
                                media.fileName.ifBlank { fileName },
                                media.mimeType.ifBlank { mimeType },
                                conversationDao.get(media.groupId)?.title?.ifBlank { null },
                            )
                        }
                    }
                }
                return@launch
            }
            // Same dedupe gate as text: existsAttachment guards the replay path, and a
            // real insert result is what lets the host notify (Bug 7).
            val insertedRowId = messageDao.insert(
                MessageEntity(
                    localId = UuidIdGenerator.newId(),
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
            touchConversation(peerDeviceId, now)
            if (insertedRowId != -1L) {
                runCatching {
                    onInboundAttachmentWithGroupTitle(
                        peerDeviceId,
                        peerNameResolver(peerDeviceId),
                        fileName,
                        mimeType,
                        null,
                    )
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
        val at = if (endedAt > 0L) endedAt else timeSource.nowMs()
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
            touchConversation(peerDeviceId, at, directFallbackTitle = resolvedPeerName ?: peerDeviceId)
        }
    }

    /**
     * Ingests a validated group frame. The host supplies the actual transport peer so a forged
     * `from` field cannot claim another trusted member's identity.
     */
    public suspend fun onInboundGroupWireFrame(peerDeviceId: String, frame: GroupWireFrame) {
        if (peerDeviceId != frame.from || !isTrustedPeer(peerDeviceId)) return
        val members = groupMemberDao ?: return
        when (frame) {
            is GroupWireFrame.Create -> {
                if (localDeviceId !in frame.memberIds ||
                    !GroupPolicy.validMemberIds(frame.memberIds, frame.from) ||
                    frame.memberIds.any { it != localDeviceId && !isTrustedPeer(it) }
                ) return
                val name = GroupPolicy.normalizedName(frame.name) ?: return
                groupTitleCache[frame.groupId] = name
                conversationDao.upsert(
                    ConversationEntity(
                        id = frame.groupId,
                        title = name,
                        isGroup = true,
                        sortOrder = frame.membershipVersion,
                        groupCreatedBy = frame.from,
                        groupCreatedAt = frame.membershipVersion,
                    ),
                )
                frame.memberIds.forEach { memberId ->
                    applyMembership(
                        members,
                        GroupMemberEntity(
                            frame.groupId, memberId,
                            if (memberId == localDeviceId) localDisplayName else peerNameResolver(memberId) ?: memberId,
                            if (memberId == frame.from) "owner" else "member",
                            frame.membershipVersion, frame.membershipVersion, frame.operationId, true,
                        ),
                    )
                }
            }
            is GroupWireFrame.Add -> {
                if (!isActiveTrustedMember(members, frame.groupId, frame.from) ||
                    frame.memberIds.any { !isTrustedPeer(it) && it != localDeviceId }
                ) return
                if (members.activeCount(frame.groupId) + frame.memberIds.filter { members.member(frame.groupId, it)?.isActive != true }.size > GroupPolicy.MAX_MEMBERS) return
                frame.memberIds.forEach { memberId ->
                    applyMembership(
                        members,
                        GroupMemberEntity(
                            frame.groupId, memberId,
                            if (memberId == localDeviceId) localDisplayName else peerNameResolver(memberId) ?: memberId,
                            "member", frame.membershipVersion, frame.membershipVersion, frame.operationId, true,
                        ),
                    )
                }
            }
            is GroupWireFrame.Leave -> {
                if (frame.memberId != frame.from) return
                val current = members.member(frame.groupId, frame.memberId) ?: return
                applyMembership(
                    members,
                    current.copy(
                        membershipVersion = frame.membershipVersion,
                        operationId = frame.operationId,
                        isActive = false,
                    ),
                )
            }
            is GroupWireFrame.State -> {
                // F2 bootstrap: the joiner has no local record, so it cannot validate the
                // sender via membership — the contract is instead (a) the transport peer is
                // trusted (checked at the top), (b) the sender appears in the roster it
                // claims, and (c) THIS device is in the roster. Anything else is a fabricated
                // group and is dropped.
                val roster = frame.members
                if (localDeviceId !in roster.map { it.deviceId } ||
                    roster.none { it.deviceId == frame.from && it.isActive } ||
                    roster.map { it.deviceId }.size != roster.size
                ) return
                if (roster.size > GroupPolicy.MAX_MEMBERS) return
                val name = GroupPolicy.normalizedName(frame.name) ?: return
                groupTitleCache[frame.groupId] = name
                // F7: keep an existing row's provenance and list position. A `State` is no longer
                // a one-shot bootstrap - [reconcileGroupMembership] re-sends it on every
                // session-up - so re-stamping sortOrder/groupCreatedAt here would shuffle the chat
                // list and rewrite the group's creation time on every reconnect.
                val existingGroupConversation = conversationDao.get(frame.groupId)
                conversationDao.upsert(
                    ConversationEntity(
                        id = frame.groupId,
                        title = name,
                        isGroup = true,
                        sortOrder = existingGroupConversation?.sortOrder ?: frame.membershipVersion,
                        groupCreatedBy = existingGroupConversation?.groupCreatedBy ?: frame.creatorId,
                        groupCreatedAt = existingGroupConversation?.groupCreatedAt ?: frame.membershipVersion,
                    ),
                )
                roster.forEach { entry ->
                    applyMembership(
                        members,
                        GroupMemberEntity(
                            groupId = frame.groupId,
                            deviceId = entry.deviceId,
                            displayName = if (entry.deviceId == localDeviceId) localDisplayName else entry.displayName,
                            role = if (entry.deviceId == frame.creatorId) "owner" else entry.role,
                            joinedAt = entry.joinedAt,
                            membershipVersion = entry.membershipVersion,
                            operationId = entry.operationId,
                            isActive = entry.isActive,
                        ),
                    )
                }
                // F7: the group is new to this device, so it holds no message rows at all and its
                // catch-up cursor is empty - ask the other members for the history now, because
                // nothing else will (F3 sync only ever fires on a session-up edge, and this device
                // is already connected to the peer that just bootstrapped it).
                requestGroupCatchUp(frame.groupId)
            }
            is GroupWireFrame.Message -> {
                if (!isActiveTrustedMember(members, frame.groupId, frame.from) ||
                    frame.text.length > GroupPolicy.MAX_MESSAGE_TEXT_LENGTH
                ) return
                val inserted = messageDao.insert(
                    MessageEntity(
                        localId = frame.messageId,
                        conversationId = frame.groupId,
                        senderId = frame.from,
                        senderName = frame.senderName,
                        text = frame.text,
                        sentAt = frame.sentAt,
                        status = "DELIVERED",
                        replyToId = frame.replyToId,
                        replyToPreview = frame.replyToPreview,
                    ),
                )
                if (inserted != -1L) {
                    onInboundTextMessageWithGroupTitle(
                        frame.groupId,
                        frame.senderName,
                        frame.text,
                        conversationDao.get(frame.groupId)?.title?.ifBlank { null },
                    )
                }
                groupTransportSink?.send(
                    frame.from,
                    GroupWireFrame.Receipt(
                        groupId = frame.groupId,
                        messageId = frame.messageId,
                        from = localDeviceId,
                        deliveredAt = timeSource.nowMs(),
                    ),
                )
            }
            is GroupWireFrame.Receipt -> {
                if (!isActiveTrustedMember(members, frame.groupId, frame.from)) return
                val deliveries = groupDeliveryDao ?: return
                deliveries.markDelivered(frame.messageId, frame.from, frame.deliveredAt)
                if (deliveries.pendingForMessage(frame.messageId).isEmpty()) {
                    messageDao.updateStatusIfUnacknowledged(frame.messageId, "DELIVERED")
                    outboxDao.delete(frame.messageId)
                }
            }
            is GroupWireFrame.Read -> {
                if (!isActiveTrustedMember(members, frame.groupId, frame.from)) return
                // The existing cursor DAO is already per (conversation, member); UI read aggregation
                // remains a Phase 1 UI follow-up while the durable monotonic record lands now.
            }
            is GroupWireFrame.DeleteForEveryone -> {
                if (!isActiveTrustedMember(members, frame.groupId, frame.from)) return
                val message = messageDao.getByLocalId(frame.messageId) ?: return
                if (message.conversationId != frame.groupId || message.senderId != frame.from) return
                messageDao.markDeleted(frame.messageId, timeSource.nowMs())
                outboxDao.delete(frame.messageId)
            }
            is GroupWireFrame.Sync -> {
                if (!isActiveTrustedMember(members, frame.groupId, frame.from)) return
                when (frame) {
                    is GroupWireFrame.SyncRequest -> handleSyncRequest(frame)
                    is GroupWireFrame.SyncClaim -> handleSyncClaim(frame)
                    is GroupWireFrame.SyncPush -> handleSyncPush(frame)
                    is GroupWireFrame.SyncAck -> handleSyncAck(frame)
                }
            }
            is GroupWireFrame.GroupMedia -> {
                if (!isActiveTrustedMember(members, frame.groupId, frame.from)) return
                pendingGroupMedia[frame.transferId] = frame
                val now = timeSource.nowMs()
                groupTransportSink?.send(
                    frame.from,
                    GroupWireFrame.Receipt(
                        groupId = frame.groupId,
                        messageId = frame.messageId,
                        from = localDeviceId,
                        deliveredAt = now,
                    ),
                )
                scope.launch(ioDispatcher) {
                    if (messageDao.existsAttachment(frame.transferId)) {
                        // If FILE_START beat GroupMedia, the row was provisionally inserted under
                        // the peer's 1-to-1 conversation. Move it to the group conversation!
                        messageDao.updateGroupContext(
                            transferId = frame.transferId,
                            groupId = frame.groupId,
                            messageId = frame.messageId,
                            senderId = frame.from,
                            senderName = frame.senderName,
                        )
                        touchConversation(frame.groupId, now)
                    } else if (claimedGroupMedia.add(frame.transferId)) {
                        // Mint the group attachment offer bubble now so it appears immediately!
                        // F7d: the atomic claim above makes this branch the single owner; a losing
                        // race leaves the mint to the accept path instead (see [claimedGroupMedia]).
                        val insertedRowId = messageDao.insert(
                            MessageEntity(
                                localId = frame.messageId,
                                conversationId = frame.groupId,
                                senderId = frame.from,
                                senderName = frame.senderName,
                                text = "",
                                sentAt = frame.sentAt.takeIf { it > 0 } ?: now,
                                status = "DELIVERED",
                                attachmentTransferId = frame.transferId,
                                attachmentName = frame.fileName,
                                attachmentMime = frame.mimeType,
                                attachmentSize = frame.sizeBytes,
                                attachmentPath = null,
                            ),
                        )
                        touchConversation(frame.groupId, now)
                        if (insertedRowId != -1L) {
                            runCatching {
                                onInboundAttachmentWithGroupTitle(
                                    frame.groupId,
                                    frame.senderName,
                                    frame.fileName,
                                    frame.mimeType,
                                    conversationDao.get(frame.groupId)?.title?.ifBlank { null },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * F4: group-media offers parked by inbound [GroupWireFrame.GroupMedia] frames, keyed by the
     * per-member transferId. Consulted (and consumed) when the receiver accepts the transfer,
     * so the attachment row threads into the GROUP conversation.
     */
    private val pendingGroupMedia = SyncMap<String, GroupWireFrame.GroupMedia>()

    /**
     * F7d: single-owner claim for a group-media bubble.
     *
     * Two paths can mint the bubble for one `GroupWireFrame.GroupMedia.transferId` - the GMEDIA
     * early-mint branch (so the offer appears immediately) and the accept path that consumes
     * [pendingGroupMedia]. Both are guarded by `existsAttachment`, which is advisory only: these
     * run on a thread pool, so both checks can pass before either inserts. Only one of them may
     * insert, fire the host callback and touch the conversation; this set decides that atomically
     * instead of letting `MessageDao.insert`'s IGNORE rule be the tiebreaker (which would still
     * leave the loser having run `touchConversation`).
     */
    private val claimedGroupMedia = SyncSet<String>()

    /** Maps an outbound group message id to all per-recipient transfer ids. */
    private val groupMessageTransfers = SyncMap<String, SyncSet<String>>()

    /** Maps a per-recipient transfer id back to its outbound group message id. */
    private val transferToGroupMessage = SyncMap<String, String>()

    override fun getRecipientTransferIds(messageId: String): Set<String> =
        groupMessageTransfers[messageId]?.toSet().orEmpty()

    /**
     * F4 sender side: announces the host-supplied group media identity to one active recipient.
     * The host then passes the same transferId/wireFileId to the transfer repository, preserving
     * one shared group message id while keeping transfer ids recipient-specific.
     */
    override suspend fun beginGroupAttachment(
        groupId: String,
        recipientDeviceId: String,
        messageId: String,
        transferId: String,
        wireFileId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): Boolean {
        if (messageId.isBlank() || transferId.isBlank() || wireFileId.isBlank()) return false
        val members = groupMemberDao ?: return false
        if (!isActiveTrustedMember(members, groupId, recipientDeviceId)) return false
        groupMessageTransfers.getOrPut(messageId) { SyncSet() }.add(transferId)
        transferToGroupMessage[transferId] = messageId
        return groupTransportSink?.send(
            recipientDeviceId,
            GroupWireFrame.GroupMedia(
                groupId = groupId,
                messageId = messageId,
                transferId = transferId,
                wireFileId = wireFileId,
                from = localDeviceId,
                senderName = localDisplayName,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                sentAt = timeSource.nowMs(),
            ),
        ) == true
    }

    // ------------------------------------------------------------------ F7: late-join heal

    /**
     * F7: ask every other active member of [groupId] to push the history this device lacks.
     *
     * Fires when this device has just learned it is a member of a group it did not know (the F2
     * `State` bootstrap). Its catch-up cursor is empty, so the request asks for the whole history;
     * each holder answers with the messages IT owns, which is why the request goes to every member
     * and not just to the peer that bootstrapped us.
     */
    internal fun requestGroupCatchUp(groupId: String) {
        scope.launch(ioDispatcher) {
            val members = groupMemberDao ?: return@launch
            members.activeMembers(groupId)
                .map { it.deviceId }
                .filter { it != localDeviceId }
                .forEach { memberId -> sendSyncRequestFor(memberId, groupId) }
        }
    }

    /**
     * F7: hand [peerDeviceId] a fresh `State` for every group we both belong to.
     *
     * Membership frames have no delivery table: `Add`/`State` are handed to the transport once and
     * dropped when the target has no live session, which leaves that target permanently unaware of
     * a member who joined while it was offline - and, because the inbound message gate requires
     * active membership, permanently unable to receive that member's messages either. Re-sending
     * the roster on every session-up edge makes the group converge without a durable queue.
     */
    public fun reconcileGroupMembership(peerDeviceId: String) {
        scope.launch(ioDispatcher) {
            val members = groupMemberDao ?: return@launch
            val groupIds = members.activeGroupIdsFor(localDeviceId)
            for (groupId in groupIds) {
                val peer = members.member(groupId, peerDeviceId) ?: continue
                if (!peer.isActive) continue
                val state = buildStateFrame(groupId) ?: continue
                groupTransportSink?.send(peerDeviceId, state)
            }
        }
    }

    /**
     * The F2 `State` frame for [groupId] as this device currently knows it. The frame's version and
     * creator are only a fallback: the receiver keeps its own conversation provenance.
     */
    private suspend fun buildStateFrame(groupId: String): GroupWireFrame.State? {
        val members = groupMemberDao ?: return null
        val conversation = conversationDao.get(groupId) ?: return null
        val roster = members.activeMembers(groupId)
        if (roster.isEmpty()) return null
        return GroupWireFrame.State(
            groupId = groupId,
            from = localDeviceId,
            operationId = UuidIdGenerator.newId(),
            membershipVersion = timeSource.nowMs(),
            name = conversation.title.ifBlank { groupId },
            creatorId = conversation.groupCreatedBy ?: localDeviceId,
            members = roster.map { member ->
                GroupWireFrame.RosterEntry(
                    deviceId = member.deviceId,
                    displayName = member.displayName,
                    role = member.role,
                    joinedAt = member.joinedAt,
                    membershipVersion = member.membershipVersion,
                    operationId = member.operationId,
                    isActive = member.isActive,
                )
            },
        )
    }

    /** One F3 catch-up request for a single (group, peer) pair - the existing wire shape. */
    private suspend fun sendSyncRequestFor(peerDeviceId: String, groupId: String) {
        val newest = messageDao.historyBefore(groupId, Long.MAX_VALUE, "\uFFFF", limit = 1).firstOrNull()
        val tier = syncTier()
        val (maxPerSecond, maxTotal) = GroupPolicy.syncLimits(tier)
        groupTransportSink?.send(
            peerDeviceId,
            GroupWireFrame.SyncRequest(
                groupId = groupId,
                syncId = UuidIdGenerator.newId(),
                from = localDeviceId,
                sinceSentAt = newest?.sentAt ?: 0L,
                sinceMessageId = newest?.localId ?: "",
                tier = tier,
                maxPerSecond = maxPerSecond,
                maxTotal = maxTotal,
            ),
        )
    }

    // ------------------------------------------------------------------ F3: FLASH_GSYNC

    /** One outstanding catch-up round this device participates in (as holder or requester). */
    private class SyncRound(
        val requestedAtMs: Long,
        val requesterIsLow: Boolean,
        val requesterMaxPerSecond: Int,
        val messageIds: SyncSet<String>,
        val acknowledgedMessageIds: SyncSet<String>,
        val claimants: SyncMap<String, GroupSyncTier>,
    )

    /** syncId → round. Bounded by [GroupPolicy.MAX_PENDING_SYNC_MESSAGES] semantics via ack/TTL. */
    private val syncRounds = SyncMap<String, SyncRound>()

    /** Test-observable protocol state without exposing the mutable round itself. */
    internal fun pendingSyncMessageIds(syncId: String): Set<String> =
        syncRounds[syncId]?.let { it.messageIds.toSet() - it.acknowledgedMessageIds.toSet() }.orEmpty()

    /**
     * Requests catch-up for every group this device is an active member of. Called by the host
     * on a session-up edge (and available for a manual re-sync): each known-but-offline member
     * may hold messages this device missed while it was away.
     *
     * The request is unicast to the freshly connected peer — it is one holder among several,
     * and the claim/elect protocol below fans the push out deterministically.
     */
    public fun sendGroupSyncRequests(peerDeviceId: String) {
        scope.launch(ioDispatcher) {
            val groupIds = groupMemberDao?.activeGroupIdsFor(localDeviceId).orEmpty()
            for (groupId in groupIds) {
                val newest = messageDao.historyBefore(groupId, Long.MAX_VALUE, "￿", limit = 1).firstOrNull()
                val tier = syncTier()
                val (maxPerSecond, maxTotal) = GroupPolicy.syncLimits(tier)
                groupTransportSink?.send(
                    peerDeviceId,
                    GroupWireFrame.SyncRequest(
                        groupId = groupId,
                        syncId = UuidIdGenerator.newId(),
                        from = localDeviceId,
                        sinceSentAt = newest?.sentAt ?: 0L,
                        sinceMessageId = newest?.localId ?: "",
                        tier = tier,
                        maxPerSecond = maxPerSecond,
                        maxTotal = maxTotal,
                    ),
                )
            }
        }
    }

    /**
     * Holder side of a SyncRequest: compute the messages this device owns that are newer than
     * the requester's cursor (capped, TTL-bounded), record the round, and broadcast a claim so
     * the co-holders can elect a single pusher deterministically. Rank 0 pushes after the
     * claim window; rank 1 arms the backup timer; the rest stand down.
     */
    private suspend fun handleSyncRequest(frame: GroupWireFrame.SyncRequest) {
        val (maxPerSecond, maxTotal) = GroupPolicy.syncLimits(frame.tier)
        val owned = GroupSyncPolicy.ownedMessages(
            messages = messageDao.historyAfter(
                frame.groupId, frame.sinceSentAt, frame.sinceMessageId,
                maxTotal.coerceAtMost(GroupPolicy.MAX_PENDING_SYNC_MESSAGES),
            ),
            cursor = GroupSyncCursor(frame.sinceSentAt, frame.sinceMessageId),
            maxTotal = maxTotal,
            nowMs = timeSource.nowMs(),
            sentAt = { it.sentAt },
            messageId = { it.localId },
            deletedAt = { it.deletedAt },
        )
        if (owned.isEmpty()) return
        // The requester is who the pushes and the ack go back to.
        syncRequesters[frame.syncId] = frame.from
        val round = syncRounds.getOrPut(frame.syncId) {
            SyncRound(
                requestedAtMs = timeSource.nowMs(),
                requesterIsLow = frame.tier == GroupSyncTier.LOW,
                requesterMaxPerSecond = frame.maxPerSecond,
                messageIds = SyncSet(),
                acknowledgedMessageIds = SyncSet(),
                claimants = SyncMap(),
            )
        }
        owned.forEach { round.messageIds.add(it.localId) }
        round.claimants[localDeviceId] = syncTier()
        val claim = GroupWireFrame.SyncClaim(
            groupId = frame.groupId,
            syncId = frame.syncId,
            from = localDeviceId,
            messageIds = owned.map { it.localId },
        )
        groupMemberDao?.activeMembers(frame.groupId)
            ?.filter { it.deviceId != localDeviceId }
            ?.forEach { member -> groupTransportSink?.send(member.deviceId, claim) }
        armBackupPush(frame.groupId, frame.syncId)
    }

    /**
     * Co-holder claim for a round this device also holds: merge the claimant so the backup
     * election below sees every contender, and record any message ids it claims that this
     * device also owns.
     */
    private fun handleSyncClaim(frame: GroupWireFrame.SyncClaim) {
        val round = syncRounds[frame.syncId] ?: return
        round.claimants[frame.from] = frame.tier
        frame.messageIds.forEach { if (it in round.messageIds) return@forEach }
    }

    /**
     * The deterministic fallback: after the claim window, if no batch ack arrived, elect one
     * pusher per message over the observed claimants and push if this device wins rank 0.
     * Paced to the requester's [SyncRound.requesterMaxPerSecond].
     */
    private fun armBackupPush(groupId: String, syncId: String) {
        scope.launch(ioDispatcher) {
            delay(GroupPolicy.BACKUP_DELAY_MS)
            val round = syncRounds[syncId] ?: return@launch
            if (GroupSyncRoundState(round.messageIds.toSet(), round.acknowledgedMessageIds.toSet()).isComplete) {
                return@launch
            }
            val claimsWithSelf = round.claimants.toMap()
            val ids = round.messageIds.toList()
            val winners = ids.associateWith { msgId ->
                GroupSyncPolicy.electRank(claimsWithSelf, msgId).firstOrNull()
            }
            val mine = winners.filterValues { it == localDeviceId }.keys
            if (mine.isEmpty()) return@launch
            val interval = GroupSyncPolicy.pushIntervalMs(round.requesterMaxPerSecond)
            for (msgId in mine.sorted()) {
                val ackState = GroupSyncRoundState(round.messageIds.toSet(), round.acknowledgedMessageIds.toSet())
                if (!ackState.shouldPush(msgId)) continue
                val message = messageDao.getByLocalId(msgId) ?: continue
                groupTransportSink?.send(
                    // The requester is the only non-claimant target the round knows.
                    winners.entries.firstOrNull()?.let { _ -> syncRequesters[syncId] } ?: continue,
                    GroupWireFrame.SyncPush(
                        groupId = groupId,
                        syncId = syncId,
                        from = localDeviceId,
                        message = message.toSyncMessage(),
                    ),
                )
                delay(interval)
            }
        }
    }

    /** syncId → the device that requested the round (pushes and acks are unicast to it). */
    private val syncRequesters = SyncMap<String, String>()

    private fun MessageEntity.toSyncMessage() = GroupWireFrame.Message(
        groupId = conversationId,
        messageId = localId,
        from = senderId,
        senderName = senderName ?: senderId,
        sentAt = sentAt,
        text = text,
        replyToId = replyToId,
        replyToPreview = replyToPreview,
    )

    /** Requester side: an elected holder pushed a message — ingest idempotently by msgId. */
    private suspend fun handleSyncPush(frame: GroupWireFrame.SyncPush) {
        val message = frame.message
        val inserted = messageDao.insert(
            MessageEntity(
                localId = message.messageId,
                conversationId = frame.groupId,
                senderId = message.from,
                senderName = message.senderName,
                text = message.text,
                sentAt = message.sentAt,
                status = "DELIVERED",
                replyToId = message.replyToId,
                replyToPreview = message.replyToPreview,
            ),
        )
        if (inserted != -1L) {
            onInboundTextMessageWithGroupTitle(
                frame.groupId,
                message.senderName,
                message.text,
                conversationDao.get(frame.groupId)?.title?.ifBlank { null },
            )
        }
        groupTransportSink?.send(
            frame.from,
            GroupWireFrame.SyncAck(
                groupId = frame.groupId,
                syncId = frame.syncId,
                from = localDeviceId,
                messageIds = listOf(message.messageId),
                hasMore = false,
                keyEpoch = frame.keyEpoch,
            ),
        )
    }

    /** Holder side: retire only acknowledged ids and end the round after its full batch is acked. */
    private fun handleSyncAck(frame: GroupWireFrame.SyncAck) {
        val round = syncRounds[frame.syncId] ?: return
        round.acknowledgedMessageIds.addAll(frame.messageIds.filter { it in round.messageIds })
        val ackState = GroupSyncRoundState(round.messageIds.toSet(), round.acknowledgedMessageIds.toSet())
        if (ackState.isComplete) {
            syncRounds.remove(frame.syncId, round)
            syncRequesters.remove(frame.syncId)
        }
    }

    private suspend fun isActiveTrustedMember(
        members: GroupMemberDao,
        groupId: String,
        deviceId: String,
    ): Boolean = isTrustedPeer(deviceId) && members.member(groupId, deviceId)?.isActive == true

    private suspend fun applyMembership(members: GroupMemberDao, candidate: GroupMemberEntity) {
        val current = members.member(candidate.groupId, candidate.deviceId)
        val candidateVersion = GroupMembershipVersion(candidate.membershipVersion, candidate.operationId)
        val currentVersion = current?.let { GroupMembershipVersion(it.membershipVersion, it.operationId) }
        if (membershipUpdateWins(candidateVersion, currentVersion)) members.upsert(candidate)
    }

    /**
     * Ingests an inbound message wire frame from the network layer. [transportPeerId] is supplied by
     * hosts when the authenticated session identity is available so group typing cannot impersonate
     * another member. It remains optional for source compatibility with non-transport callers.
     */
    public suspend fun onInboundWireFrame(frame: MessageWireFrame, transportPeerId: String? = null) {
        when (frame) {
            is MessageWireFrame.TextMessage -> {
                // SENTINEL: Fail closed on claimed-author vs transport-peer mismatch
                if (transportPeerId != null && frame.senderId != transportPeerId) return
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
                        onInboundTextMessageWithGroupTitle(threadId, frame.senderName, frame.text, null)
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
                        deliveredAt = timeSource.nowMs(),
                    ),
                )
            }

            is MessageWireFrame.DeliveryReceipt -> {
                if (transportPeerId != null && frame.memberId != transportPeerId) return
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
                if (transportPeerId != null && frame.memberId != transportPeerId) return
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
                val isGroup = conversationDao.get(frame.conversationId)?.isGroup == true
                val typingConversationId = if (isGroup) {
                    val members = groupMemberDao ?: return
                    if (transportPeerId != null && frame.memberId != transportPeerId) return
                    if (!isActiveTrustedMember(members, frame.conversationId, frame.memberId)) return
                    frame.conversationId
                } else {
                    // Direct hosts historically keyed inbound typing under the transport peer rather
                    // than the wire conversation id (which names this receiver). Preserve that behavior.
                    transportPeerId ?: frame.conversationId
                }
                val convTyping = typingStates.getOrPut(typingConversationId) { SyncMap() }
                if (frame.isTyping) {
                    convTyping[frame.memberId] = frame.memberName
                    scheduleTypingExpiry(typingConversationId, frame.memberId)
                } else {
                    convTyping.remove(frame.memberId)
                    cancelTypingExpiry(typingConversationId, frame.memberId)
                }
                publishTyping()
            }

            is MessageWireFrame.DeleteForEveryone -> {
                val peerId = transportPeerId ?: return
                if (peerId != frame.from || frame.conversationId != peerId) return
                val message = messageDao.getByLocalId(frame.messageId) ?: return
                if (message.conversationId != peerId || message.senderId != frame.from) return
                messageDao.markDeleted(frame.messageId, timeSource.nowMs())
                outboxDao.delete(frame.messageId)
            }

            is MessageWireFrame.ReactionFrame -> {
                if (transportPeerId != null && frame.memberId != transportPeerId) return
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

    /**
     * The durable outbox's retry timer.
     *
     * Not the send path: every producer enqueues and then calls [drainOutboxOnce] itself, so a
     * message's *first* delivery attempt never waits on this loop. All this loop exists to do is
     * re-attempt rows whose `nextAttemptAt` deadline has come round, and notice rows enqueued by
     * someone who did not drain.
     *
     * It used to be `while (true) { drainOutboxOnce(); delay(1000) }`. With a transport attached
     * that was a `dueForDelivery` query against a SQLCipher database every second for the life of
     * the process — on the order of 86,400 a day, almost all of them against a table that is empty
     * — and, because a fixed 1 s grid has nothing to do with the ladder [backoffDelayMs] computes,
     * it *also* left every retry up to a second late. Waking on the ladder is both cheaper and
     * tighter: work can only appear by a write to the `outbox` table, and [drainWake] fires on every
     * such write, so the only thing left for a timer to do is honour a deadline we already know.
     *
     * The one case neither signal covers is a row left future-dated by a previous process: its
     * deadline was computed before this process existed, and there is no DAO query for "earliest
     * `nextAttemptAt`" to recover it from. [OutboxDrainSchedule.IDLE_WAIT_MS] bounds that to a
     * minute — and in practice `notifyPeerSessionUp` gets there first, because a cold start has no
     * peer session yet and the `makePendingDue(now)` it runs on the first session-up makes every
     * leftover row due immediately.
     */
    private suspend fun drainOutboxLoop() {
        while (true) {
            val batchWasFull = drainOutboxOnce()
            if (batchWasFull) {
                // More rows were already due than one batch holds, so there is nothing to wait for.
                // The floor is only here to keep a long backlog from becoming a hot loop.
                delay(OutboxDrainSchedule.MIN_WAIT_MS)
                continue
            }
            val waitMs = OutboxDrainSchedule.waitMs(outboxNextDueAt, timeSource.nowMs())
            // Whichever lands first: a write to the outbox table, or the earliest deadline we hold.
            // Both resume the same next statement — another drain — so it does not matter which won,
            // and a wake that races the timeout costs nothing even if the cancellation discards it.
            withTimeoutOrNull(waitMs) { drainWake.receive() }
        }
    }

    /**
     * One drain pass. Returns true when the batch came back full — i.e. more rows are due *now* than
     * [OUTBOX_BATCH_LIMIT] holds, so the caller should come straight back rather than sleep.
     *
     * Also records the earliest deadline it scheduled in [outboxNextDueAt], which is what
     * [drainOutboxLoop] sleeps until.
     */
    private suspend fun drainOutboxOnce(): Boolean = drainMutex.withLock {
        val now = timeSource.nowMs()
        // A repository may be configured for direct chat, group chat, or both. Do not let an
        // absent direct sink suppress an otherwise deliverable group outbox.
        if (transportSink == null && groupTransportSink == null) return@withLock false
        val items = outboxDao.dueForDelivery(now, limit = OUTBOX_BATCH_LIMIT)
        var earliestScheduled: Long? = null
        for (item in items) {
            // The outbox row stores only the payload, so recover the conversationId, sender name
            // and original sentAt from the durable message row. Reconstructing the frame from
            // `activeConversationId` instead (the old behaviour) mis-routed any message whose
            // conversation was no longer the active one when the drain fired — sending it to
            // the wrong peer, to "general", or nowhere — because conversationId doubles as the
            // transport routing key (see MessageTransportSink).
            val message = messageDao.getByLocalId(item.localId)
            if (message == null) {
                // No backing message: this row can never be delivered. Drop it so the drain
                // loop does not re-claim it on every pass forever. Not reachable in normal flow —
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
            val conversation = conversationDao.get(message.conversationId)
            val queuedForMs = now - item.createdAt
            if (conversation?.isGroup == true) {
                drainGroupMessage(item, message, now, queuedForMs)
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
            if (queuedForMs >= OUTBOX_GIVE_UP_AFTER_MS) {
                // Unacknowledged for the whole budget: mark the message Failed (surfaces a retry
                // affordance in the bubble) and drop the outbox row so it stops being re-claimed.
                messageDao.updateStatusIfUnacknowledged(item.localId, "FAILED")
                outboxDao.delete(item.localId)
                continue
            }
            val success = sendWithTimeout(wireFrame.localId, wireFrame.conversationId) {
                transportSink?.send(wireFrame.conversationId, wireFrame) == true
            }
            if (success) {
                // Single tick, unchanged — the bytes are on the wire. The row itself survives
                // until the peer's DeliveryReceipt deletes it (see the DeliveryReceipt branch of
                // [onInboundWireFrame]), which makes the reschedule below double as the resend
                // timer for a frame that was written but never arrived.
                messageDao.updateStatusIfUnacknowledged(item.localId, "SENT")
            }
            // Both outcomes re-arm on the same ladder (#21): a refused send backs off instead of
            // hammering the peer on every pass, and an accepted-but-unacknowledged send resends on
            // that same spacing. A redundant resend is harmless by construction — the receiver's
            // insert is idempotent (IGNORE on localId) and it re-acks every TextMessage whether
            // the row was new or a replay, so the extra frame is precisely what produces the
            // receipt that clears this row.
            val nextAttemptAt = now + backoffDelayMs(item.attempts + 1)
            outboxDao.rescheduleAttempt(item.localId, nextAttemptAt)
            earliestScheduled = earliestScheduled?.coerceAtMost(nextAttemptAt) ?: nextAttemptAt
        }
        // Wake for the earliest deadline still ahead of us, which is not necessarily the earliest
        // this pass set: a row that was not yet due carries a deadline this pass never saw, and
        // overwriting it would sleep straight past that row. A deadline already in the past belongs
        // to a row that has since been acknowledged and deleted, so it is dropped here rather than
        // left to pin the loop at [OutboxDrainSchedule.MIN_WAIT_MS] forever.
        outboxNextDueAt = listOfNotNull(outboxNextDueAt?.takeIf { it > now }, earliestScheduled).minOrNull()
        items.size >= OUTBOX_BATCH_LIMIT
    }

    private suspend fun drainGroupMessage(
        item: OutboxEntity,
        message: MessageEntity,
        now: Long,
        queuedForMs: Long,
    ) {
        val deliveries = groupDeliveryDao ?: return
        val sink = groupTransportSink ?: return
        if (queuedForMs >= OUTBOX_GIVE_UP_AFTER_MS) {
            messageDao.updateStatusIfUnacknowledged(item.localId, "FAILED")
            outboxDao.delete(item.localId)
            return
        }
        val pending = deliveries.pendingForMessage(item.localId)
        if (pending.isEmpty()) {
            messageDao.updateStatusIfUnacknowledged(item.localId, "DELIVERED")
            outboxDao.delete(item.localId)
            return
        }
        val frame = GroupWireFrame.Message(
            groupId = message.conversationId,
            messageId = message.localId,
            from = localDeviceId,
            senderName = localDisplayName,
            sentAt = message.sentAt,
            text = message.text,
            replyToId = message.replyToId,
            replyToPreview = message.replyToPreview,
        )
        val results = coroutineScope {
            pending.map { delivery ->
                async {
                    val sent = sendWithTimeout(item.localId, delivery.memberId) {
                        sink.send(delivery.memberId, frame)
                    }
                    delivery.memberId to sent
                }
            }.awaitAll()
        }
        var anySent = false
        for ((memberId, sent) in results) {
            if (sent) anySent = true
            deliveries.reschedule(
                messageId = item.localId,
                memberId = memberId,
                state = if (sent) "SENT" else "PENDING",
                nextAttemptAt = now + backoffDelayMs(item.attempts + 1),
            )
        }
        if (anySent) messageDao.updateStatusIfUnacknowledged(item.localId, "SENT")
        outboxDao.rescheduleAttempt(item.localId, now + backoffDelayMs(item.attempts + 1))
    }

    /**
     * One outbox wire dispatch, bounded in time and never fatal to the batch.
     *
     * A peer whose TCP window is full blocks `WsConnection.sendText` in a kernel write until the
     * keepalive watchdog eventually closes the socket (~45 s), and the drain holds [drainMutex]
     * across the batch — so one stalled peer stalls delivery for every other peer. Treat a
     * [SEND_TIMEOUT_MS] overrun as "not sent": the row stays in the outbox and re-enters backoff.
     * Re-delivery is safe because ingestion is idempotent on `localId` (C6.2).
     *
     * NOT a substitute for real backpressure — `BoundedSendQueue` is still not wired into
     * `WsConnection` (investigation §1.2 D); that needs an ADR because it changes the transport's
     * write path for chat AND transfer frames.
     */
    private suspend fun sendWithTimeout(
        localId: String,
        target: String,
        send: suspend () -> Boolean,
    ): Boolean {
        // The write MUST run in its own job: `WsConnection.send` blocks inside
        // `synchronized(writeLock)` on the socket stream and never suspends, so a timeout wrapped
        // directly around it could not fire — cancellation is only observed at a suspension point.
        // `await()` is that suspension point; on timeout the orphan job stays blocked until the
        // socket closes, but the drain moves on.
        // Deliberately the scope's own context, not [ioDispatcher]: a stuck write must not occupy
        // the dispatcher the rest of the repository's IO work is serialized on.
        val dispatch = scope.async {
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FlashLog.w("CHAT", "Transport send failed: $localId -> $target: ${e.message}", e)
                false
            }
        }
        val result = withTimeoutOrNull(SEND_TIMEOUT_MS) { dispatch.await() }
        if (result == null) {
            FlashLog.w("CHAT", "Transport send timed out after ${SEND_TIMEOUT_MS}ms: $localId -> $target")
        }
        return result == true
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
    public fun notifyPeerSessionUp(peerDeviceId: String? = null) {
        scope.launch(ioDispatcher) {
            val now = timeSource.nowMs()
            // Direct rows stay globally reset (Bug 5 unchanged); a named peer additionally makes
            // that member's group deliveries retryable, so a returning member drains its backlog
            // without waking deliveries for members that are still offline.
            outboxDao.makePendingDue(now)
            peerDeviceId?.let { groupDeliveryDao?.makePendingDueForMember(it, now) }
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

    override suspend fun searchConversationMessages(
        conversationId: String,
        query: String,
        limit: Int,
    ): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return kotlinx.coroutines.withContext(ioDispatcher) {
            messageDao.searchConversationMessages(conversationId, trimmed, limit)
                .map { it.localId }
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
            val frame = MessageWireFrame.TypingFrame(
                conversationId = conversationId,
                memberId = localDeviceId,
                memberName = localDisplayName,
                isTyping = isTyping,
                timestampMs = timeSource.nowMs(),
            )
            if (conversationDao.get(conversationId)?.isGroup == true) {
                groupMemberDao?.activeMembers(conversationId)
                    ?.filter { it.deviceId != localDeviceId }
                    ?.forEach { member -> transportSink?.send(member.deviceId, frame) }
            } else {
                // Keep the direct route and MessageWireFrame bytes exactly as before F5.3.
                transportSink?.send(conversationId, frame)
            }
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

    /**
     * F1: bumps a conversation's recency WITHOUT clobbering its identity. Attachment and call
     * rows used to upsert a fresh `ConversationEntity(isGroup = false, title = <raw id>)`, and
     * `@Upsert` is a full-row replace — observed on device: sending a voice note to a group
     * rewrote the thread's title to the groupId and reset `isGroup`, which demoted the whole
     * header back to a direct chat. A group row therefore updates `sortOrder` only; a direct
     * chat keeps the exact prior derivation via [directFallbackTitle].
     */
    private suspend fun touchConversation(
        conversationId: String,
        now: Long,
        directFallbackTitle: String? = null,
    ) {
        val existing = conversationDao.get(conversationId)
        if (existing != null) {
            conversationDao.upsert(existing.copy(sortOrder = now, archived = false))
            return
        }
        conversationDao.upsert(
            ConversationEntity(
                id = conversationId,
                title = directFallbackTitle
                    ?: peerNameResolver(conversationId)?.ifBlank { null }
                    ?: conversationId,
                isGroup = false,
                sortOrder = now,
                archived = false,
            ),
        )
    }


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
            messageDao.markDeleted(localId, timeSource.nowMs())
            // Also drop any pending outbox row: a message deleted before it drained must NOT still
            // be transmitted to the peer. The drain loop additionally re-checks the tombstone, so a
            // row already claimed for this tick is discarded rather than sent.
            outboxDao.delete(localId)
        }
    }

    override fun deleteMessageForEveryone(localId: String) {
        scope.launch(ioDispatcher) {
            val message = messageDao.getByLocalId(localId) ?: return@launch
            if (message.senderId != localDeviceId || message.deletedAt != null) return@launch
            val conversation = conversationDao.get(message.conversationId) ?: return@launch

            // Apply the local tombstone before attempting the network, and always retire a queued
            // message so an older payload cannot be sent after its deletion action.
            messageDao.markDeleted(localId, timeSource.nowMs())
            outboxDao.delete(localId)

            if (conversation.isGroup) {
                val members = groupMemberDao ?: return@launch
                val frame = GroupWireFrame.DeleteForEveryone(
                    groupId = conversation.id,
                    messageId = localId,
                    from = localDeviceId,
                )
                members.activeMembers(conversation.id)
                    .filter { it.deviceId != localDeviceId && isTrustedPeer(it.deviceId) }
                    .forEach { member -> groupTransportSink?.send(member.deviceId, frame) }
            } else {
                transportSink?.send(
                    conversation.id,
                    MessageWireFrame.DeleteForEveryone(
                        messageId = localId,
                        conversationId = conversation.id,
                        from = localDeviceId,
                    ),
                )
            }
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

    override fun markConversationUnread(conversationId: String) {
        scope.launch(ioDispatcher) {
            conversationDao.clearLastReadCursor(conversationId)
        }
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

    override fun unarchiveConversation(conversationId: String) {
        scope.launch(ioDispatcher) {
            conversationDao.setArchived(conversationId, false)
        }
    }

    override fun unarchiveConversations(ids: Set<String>) {
        if (ids.isEmpty()) return
        scope.launch(ioDispatcher) {
            ids.forEach { conversationDao.setArchived(it, false) }
        }
        clearListSelection()
    }

    /**
     * Joins an attachment row with its live transfer progress and populates the rich UI attachment
     * fields (B4). Image/video MIME renders an inline thumbnail (video adds a play overlay) **once the
     * bytes are local**; anything else — including media still awaiting acceptance or download —
     * becomes a file card. Text-only rows return unchanged.
     */
    /**
     * Infers an accurate MIME type from filename/path when the stored MIME is generic or missing,
     * ensuring video and image formats (like MKV, MP4, WebM, MOV, JPEG, PNG, etc.) are correctly
     * recognized for in-bubble preview and playback.
     */
    private fun resolveEffectiveMime(storedMime: String?, name: String, path: String?): String {
        if (!storedMime.isNullOrBlank() &&
            storedMime != "application/octet-stream" &&
            storedMime != "*/*" &&
            storedMime != "application/unknown"
        ) {
            return storedMime
        }
        val ext = (name.substringAfterLast('.', "")
            .ifBlank { path?.substringAfterLast('.', "") ?: "" })
            .lowercase()
        return when (ext) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp", "3gpp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            // Shared table, not `android.webkit.MimeTypeMap`: the framework call is an Android
            // API that hard-fails a JVM compile, which blocks this file's move to `commonMain`
            // (slice 4). `FlashMimeTypes` mirrors these explicit rows one-for-one and covers the
            // common document/archive cases the framework used to answer; anything exotic falls
            // through to the stored MIME / `*/*` — a generic card, never a wrong preview.
            else -> FlashMimeTypes.fromExtension(ext)
                ?: (storedMime?.ifBlank { "*/*" } ?: "*/*")
        }
    }

    private fun applyAttachment(
        base: FlashMessageUi,
        entity: MessageEntity,
        progressByTransfer: Map<String, FlashAttachmentProgress>,
    ): FlashMessageUi {
        val transferId = entity.attachmentTransferId ?: return base
        val name = entity.attachmentName ?: "file"
        val live = progressByTransfer[transferId] ?: run {
            val recipientTransfers = groupMessageTransfers[transferId]?.toList().orEmpty().mapNotNull { progressByTransfer[it] }
            if (!recipientTransfers.isNullOrEmpty()) {
                val allDownloaded = recipientTransfers.all { it.status == FlashFileTransferStatus.Downloaded }
                val anyTransferring = recipientTransfers.any { it.status == FlashFileTransferStatus.Transferring }
                val anyPaused = recipientTransfers.any { it.status == FlashFileTransferStatus.Paused }
                val anyAwaiting = recipientTransfers.any { it.status == FlashFileTransferStatus.AwaitingAcceptance }
                val allFailed = recipientTransfers.all { it.status == FlashFileTransferStatus.Failed }
                val status = when {
                    allDownloaded -> FlashFileTransferStatus.Downloaded
                    anyTransferring -> FlashFileTransferStatus.Transferring
                    anyPaused -> FlashFileTransferStatus.Paused
                    anyAwaiting -> FlashFileTransferStatus.AwaitingAcceptance
                    allFailed -> FlashFileTransferStatus.Failed
                    else -> FlashFileTransferStatus.Transferring
                }
                val avgProgress = recipientTransfers.map { it.progress }.average().toFloat()
                val totalSpeed = recipientTransfers.sumOf { it.speedMbps.toDouble() }.toFloat()
                val maxEta = recipientTransfers.maxOfOrNull { it.etaSeconds } ?: 0
                val path = recipientTransfers.firstOrNull { !it.localPath.isNullOrBlank() }?.localPath
                FlashAttachmentProgress(
                    progress = avgProgress,
                    status = status,
                    localPath = path,
                    speedMbps = totalSpeed,
                    etaSeconds = maxEta,
                )
            } else null
        }
        // Prefer the live transfer's path (the received file materialises on completion); fall back
        // to the row's stored path (the source URI stamped at send time).
        val path = live?.localPath?.ifBlank { null } ?: entity.attachmentPath
        val mime = resolveEffectiveMime(entity.attachmentMime, name, path)
        val status = live?.status
            ?: if (path != null) FlashFileTransferStatus.Downloaded else FlashFileTransferStatus.NotDownloaded
        val progress = live?.progress ?: if (status == FlashFileTransferStatus.Downloaded) 1f else 0f
        // A media tile can only show bytes that already exist locally. An inbound offer has no path
        // and no permission to fetch one until the user accepts, so it has to fall through to the
        // file card — the only surface carrying Accept/Decline, progress and "Tap to retry". The
        // video branch already guarded this; the image branch did not, which is why a received photo
        // rendered as a dead gradient tile with no way to accept it and nothing to decode.
        val renderable = path != null && status == FlashFileTransferStatus.Downloaded
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
                FlashCallEventKind.Missed -> "Missed ${what.lowercase()}"
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
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "${parts[0].first()}${parts[1].first()}".uppercase()
        }
    }

    private fun formatTime(millis: Long): String = platformFormatTimeOfDay(millis)

    private fun formatTimestamp(millis: Long, nowMs: Long): String {
        val diff = nowMs - millis
        return when {
            diff < 60_000 -> "Now"
            diff < 3600_000 -> "${diff / 60_000}m"
            diff < 86400_000 -> "${diff / 3600_000}h"
            else -> platformFormatMonthDay(millis)
        }
    }

    private companion object {
        /**
         * Neutral conversation state: no thread is open (ERROR-034).
         *
         * This used to be a fabricated `"Messages"` / `"FL"` header. Nothing in the app owns that
         * name, so any window in which it was visible — before the first Room emission, or after
         * [closeConversation] — put an invented identity on the screen. Blank with no call actions
         * is the honest shape, and matches `EmptyFlashChatRepository.emptyConversation`.
         */
        val EMPTY_CONVERSATION = FlashConversationUiState(
            header = FlashChatHeaderUiState(
                title = "",
                avatarInitials = "",
                showCallActions = false,
            ),
            messages = emptyList(),
        )

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

        // Rows claimed per drain pass. A bound, not a target: it keeps one pass from holding
        // [drainMutex] across an unbounded number of socket writes, and a full batch is the signal
        // [drainOutboxLoop] uses to come straight back instead of waiting on a deadline that has
        // already passed.
        const val OUTBOX_BATCH_LIMIT = 16

        // Per-item wire-dispatch bound (see sendWithTimeout). Well above a healthy LAN write and
        // well below the keepalive watchdog's ~45 s socket close, so a zero-window peer costs the
        // batch seconds, not the full watchdog window.
        const val SEND_TIMEOUT_MS = 10_000L

        // Falling-edge hold for the presence dot (ERROR-026). Long enough to cover the WS layer's
        // own recovery — the dialing side redials from a ~1 s base and the accepting side's backup
        // loop from ~4 s, plus a ~0.2 s handshake — so a self-healing drop never reaches the UI.
        // A peer that really left shows Offline this much later, which is fine for a LAN mesh.
        const val OFFLINE_HOLD_MS = 6_000L

        // Minimum gap between attachment-progress values reaching the conversation mapper; see
        // [pacedAttachmentProgress] for why this is 10× the transfer layer's 10 ms watcher tick and
        // why it is not tiered. Ten updates a second is above what a text label can be read at and
        // the progress bar is animated independently, so this is a pure work reduction: it buys a
        // 10× cut in whole-thread re-derivations during a transfer with nothing given up on screen.
        const val ATTACHMENT_PROGRESS_THROTTLE_MS = 100L

        // Ephemeral typing indicator TTL. If no heartbeat arrives within this window,
        // typing automatically clears so an inactive or disconnected peer is not stuck typing.
        const val TYPING_EXPIRY_MS = 6_000L
    }
}
