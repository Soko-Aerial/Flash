@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.id.UuidIdGenerator
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashProbe
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
import com.transfer.flash.core.messaging.model.FlashGroupJoinRequestUi
import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.messaging.model.FlashGroupHistoryUi
import com.transfer.flash.core.messaging.model.FlashGroupSyncUi
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMemberRole
import com.transfer.flash.core.messaging.model.FlashMessageInfoUi
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.messaging.model.FlashQuotedReplyUi
import com.transfer.flash.core.messaging.model.FlashSelfMembership
import com.transfer.flash.core.messaging.model.FlashReaction
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import com.transfer.flash.core.messaging.group.GroupProofResult
import com.transfer.flash.core.messaging.group.GroupProofSessions
import com.transfer.flash.core.messaging.group.GroupSecretStore
import com.transfer.flash.core.messaging.group.GroupTraffic
import com.transfer.flash.core.messaging.protocol.CatchUpLane
import com.transfer.flash.core.messaging.protocol.ChatWireFrame
import com.transfer.flash.core.messaging.protocol.GroupAdminPolicy
import com.transfer.flash.core.messaging.protocol.GroupCrypto
import com.transfer.flash.core.messaging.protocol.GroupHistoryCeiling
import com.transfer.flash.core.messaging.protocol.GroupHistoryChoice
import com.transfer.flash.core.messaging.protocol.GroupHistoryPolicy
import com.transfer.flash.core.messaging.protocol.GroupHistoryWindows
import com.transfer.flash.core.messaging.protocol.GroupMembershipVersion
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupRotation
import com.transfer.flash.core.messaging.protocol.toRotation
import com.transfer.flash.core.messaging.protocol.GroupSyncCursor
import com.transfer.flash.core.messaging.protocol.GroupSyncPolicy
import com.transfer.flash.core.messaging.protocol.GroupSyncRoundState
import com.transfer.flash.core.messaging.protocol.GroupSyncTier
import com.transfer.flash.core.messaging.protocol.GroupVouching
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.messaging.protocol.OutgoingSyncRequest
import com.transfer.flash.core.messaging.protocol.VerifyBudget
import com.transfer.flash.core.messaging.protocol.membershipUpdateWins
import com.transfer.flash.core.transfer.TransferFailureText
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
import com.transfer.flash.core.persistence.db.dao.GroupHistoryDao
import com.transfer.flash.core.persistence.db.dao.GroupInviteDao
import com.transfer.flash.core.persistence.db.dao.GroupJoinRequestDao
import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import com.transfer.flash.core.persistence.db.dao.GroupPreferencesDao
import com.transfer.flash.core.persistence.db.dao.GroupRotationDao
import com.transfer.flash.core.persistence.db.dao.GroupSettingsDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.MessagePinDao
import com.transfer.flash.core.messaging.group.GroupLocalPreferences
import com.transfer.flash.core.messaging.group.toEntity
import com.transfer.flash.core.messaging.group.toPreferences
import com.transfer.flash.core.messaging.group.toSettings
import com.transfer.flash.core.messaging.protocol.GroupSettings
import com.transfer.flash.core.persistence.db.entity.GroupHistoryStateEntity
import com.transfer.flash.core.persistence.db.entity.GroupInviteEntity
import com.transfer.flash.core.persistence.db.entity.GroupSyncWatermarkEntity
import com.transfer.flash.core.persistence.db.entity.GroupJoinRequestEntity
import com.transfer.flash.core.security.group.GroupInvite
import com.transfer.flash.core.security.group.GroupInviteCodec
import com.transfer.flash.core.security.group.GroupSecret
import com.transfer.flash.core.security.group.GroupSecretCommit
import com.transfer.flash.core.messaging.group.GroupMembershipStatusText
import com.transfer.flash.core.messaging.group.GroupSecretSource
import com.transfer.flash.core.messaging.group.StoredGroupSecret
import com.transfer.flash.core.messaging.protocol.GroupCanonical
import com.transfer.flash.core.messaging.protocol.GroupCharter
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReadCursorDao
import com.transfer.flash.core.persistence.db.dao.ReactionDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.MessagePinEntity
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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    localDisplayName: String,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val outboxDao: OutboxDao,
    private val receiptDao: ReceiptDao,
    private val draftDao: DraftDao,
    private val recentSearchDao: RecentSearchDao,
    private val reactionDao: ReactionDao,
    private val groupMemberDao: GroupMemberDao? = null,
    private val groupDeliveryDao: GroupDeliveryDao? = null,
    /** Device-local pinned messages. Null (tests, previews) means pins are not stored and the state carries none. */
    private val messagePinDao: MessagePinDao? = null,
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
     * PC3: peer device ids discovery sees right now (hosts pass `discoveredEndpoints`). A peer in
     * this set without a live session is shown as [FlashPeerPresence.Reachable] ("Online", ring
     * dot). Presentation only, like [onlinePeerIds]'s grace: sends still gate on a real session.
     */
    private val reachablePeerIds: Flow<Set<String>> = MutableStateFlow(emptySet()),
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
    /**
     * ADR-044 V1: this device's signing identity. Null keeps every group legacy and makes the device
     * ignore v2 frames, which is what tests and hosts without a crypto identity get.
     */
    private val groupCrypto: GroupCrypto? = null,
    /** The trust store's pinned fingerprint (hex) for a device, or null when nothing is pinned. */
    private val pinnedFingerprint: (String) -> String? = { null },
    /** The group protocol level a peer advertised on its live session; 1 when unknown or offline. */
    private val peerGroupProtocol: (String) -> Int = { 1 },
    /** A peer's identity key (SPKI) from its live TLS session, or null; checked against the pin before use. */
    private val peerIdentityKey: (String) -> ByteArray? = { null },
    /**
     * ADR-044 V2: the trust store's vouched pins. Null keeps V1 behaviour, where every member of a v2 group
     * must be paired with this device.
     */
    private val groupVouching: GroupVouching? = null,
    /**
     * Per-member read cursors. Group "Read" ticks need them: a group message is READ only once every active remote
     * member has read past it, which no single `ReadReceipt` can say. Null keeps a group's sent bubbles at Delivered
     * (what a host without the cursor table gets), and leaves 1:1 read receipts untouched.
     */
    private val readCursorDao: ReadCursorDao? = null,
    /** How long the catch-up banner outlives the last arrival; a parameter so a test need not wait [GroupPolicy.SYNC_QUIET_MS]. */
    private val groupSyncQuietMs: Long = GroupPolicy.SYNC_QUIET_MS,
    /** GM-2 / GM-3: Secret-based group membership store. */
    private val groupSecretStore: GroupSecretStore? = null,
    /** GM-3: Advertised peer features on live session (e.g. "gs1"). */
    private val peerFeatures: (String) -> Set<String> = { emptySet() },
    /** GM-4: Group invite storage DAO. */
    private val groupInviteDao: GroupInviteDao? = null,
    /** GM-4: Group join request storage DAO. */
    private val groupJoinRequestDao: GroupJoinRequestDao? = null,
    /** GM-4: Address hints for invite creation (up to 3 addresses). */
    private val localAddressHints: () -> List<String> = { emptyList() },
    /** GM-4: Host hook to dial peer address hints upon accepting an invite. */
    private val onConnectPeerWithHints: (suspend (peerId: String, hints: List<String>) -> Unit)? = null,
    /** GM-8: Host hook to dial an individual address hint for [peerId]. Returns true if connected. */
    private val onConnectPeerHint: (suspend (peerId: String, hint: String) -> Boolean)? = null,
    /** GM-4: Group join policy lookup ("approve" or "open"). */
    private val groupJoinPolicy: (groupId: String) -> String = { "approve" },
    /** GM-6: Group rotation notice storage DAO. */
    private val groupRotationDao: GroupRotationDao? = null,
    /** GM-9: Group settings storage DAO. */
    private val groupSettingsDao: GroupSettingsDao? = null,
    /** GM-9: Group local preferences storage DAO. */
    private val groupPreferencesDao: GroupPreferencesDao? = null,
    /**
     * ADR-100: device-local group history state and catch-up watermarks. Null (tests, previews) keeps the pre-ADR-100
     * behaviour exactly: no join card, no deferral, and catch-up requests carry no window.
     */
    private val groupHistoryDao: GroupHistoryDao? = null,
) : FlashChatRepository {

    /**
     * The name this device stamps on what it sends. A var because the owner can rename the device while the
     * repository lives; see [updateLocalDisplayName].
     */
    @Volatile
    private var localDisplayName: String = localDisplayName

    private val _chatListState = MutableStateFlow(FlashChatListUiState())

    /**
     * Group Phase A: in-memory group titles, stamped the moment a group name is written
     * (local create, inbound create). Lets [openConversation] seed a correct group header
     * synchronously WITHOUT a Room read on the caller's thread — a blocking read there
     * starved the shared test executor, and on the UI thread it would block main. A group
     * opened cold from the chat list is corrected by the combine's first Room emission.
     */
    private val groupTitleCache = SyncMap<String, String>()

    /** GM-4: Active accepted invite group IDs providing charter trust roots. */
    private val acceptedInviteGroupIds = SyncSet<String>()

    /** GM-4: Per-group join policy overrides. */
    private val groupJoinPolicyOverrides = SyncMap<String, String>()

    /** GM-8: In-memory address hints for pending invites to retry on network changes. */
    private val pendingInviteHints = SyncMap<String, List<String>>()
    /** GM-8: Group name from invite for display while join is pending (M-03, M-07). */
    private val pendingInviteGroupNames = SyncMap<String, String>()
    /** GM-8: Groups whose hints have exhausted or timed out without establishing a session. */
    private val hintsExhausted = SyncSet<String>()

    /** (peer, group) pairs whose invite proof is running now: a second trigger for the same pair is skipped. */
    private val inviteProofsInFlight = SyncSet<Pair<String, String>>()

    /** Failed invite proofs retried so far per (peer, group); cleared on success. */
    private val inviteProofRetries = SyncMap<Pair<String, String>, Int>()
    /** Connected peers currently active. */
    private val currentConnectedPeers = SyncSet<String>()

    private fun isPeerOnline(peerId: String): Boolean = currentConnectedPeers.contains(peerId)

    /** GM-10: Triggered when an inbound join request arrives requiring admin approval. */
    public var onJoinRequestNotification: ((groupId: String, groupTitle: String, requesterName: String) -> Unit)? = null

    /**
     * A member added this device to a signed group that it cannot accept because it is not paired with the owner
     * (M-23). Called at most once per group per [OFFER_REFUSED_NOTICE_INTERVAL_MS]; [message] is ready to show.
     */
    public var onGroupOfferRefused: ((groupId: String, groupName: String, message: String) -> Unit)? = null
    private val offerRefusedNoticeAt = SyncMap<String, Long>()

    /** GM-10: Refresh trigger for open conversation state (settings, join requests, etc.). */
    private val conversationRefreshTrigger = MutableStateFlow(0L)

    /** Sets group join policy ("open" or "approve") for [groupId] (GM-4). */
    public fun setGroupJoinPolicy(groupId: String, policy: String) {
        groupJoinPolicyOverrides[groupId] = policy
    }

    private suspend fun isGroupJoinOpen(groupId: String): Boolean =
        groupJoinPolicyOverrides[groupId]?.equals("open", ignoreCase = true) == true ||
            signedGroups?.currentSettings(groupId)?.joinPolicy.equals(GroupSettings.POLICY_OPEN, ignoreCase = true) ||
            groupJoinPolicy(groupId).equals("open", ignoreCase = true)

    /** The v2 group engine (ADR-044 V1); null when the host gave no crypto identity or group storage. */
    private val signedGroups: SignedGroups? =
        if (groupCrypto != null && groupMemberDao != null) {
            SignedGroups(
                localDeviceId = localDeviceId,
                localDisplayName = localDisplayName,
                crypto = groupCrypto,
                conversationDao = conversationDao,
                members = groupMemberDao,
                isPaired = isTrustedPeer,
                pinnedFingerprint = pinnedFingerprint,
                vouching = groupVouching,
                nowMs = { timeSource.nowMs() },
                newId = { UuidIdGenerator.newId() },
                hasInvite = { acceptedInviteGroupIds.contains(it) },
                groupRotationDao = groupRotationDao,
                groupSecretStore = groupSecretStore,
                groupSettingsDao = groupSettingsDao,
            )
        } else {
            null
        }

    /** GM-3: Group secret proof sessions. */
    public val groupProofSessions: GroupProofSessions? =
        if (groupSecretStore != null && groupCrypto != null) {
            GroupProofSessions(
                localDeviceId = localDeviceId,
                groupCrypto = groupCrypto,
                peerIdentityKey = peerIdentityKey,
                peerFeatures = peerFeatures,
                groupSecretStore = groupSecretStore,
                sendFrame = { peerId, frame ->
                    groupTransportSink?.send(peerId, frame) ?: false
                },
                timeSource = timeSource,
                onStaleProof = { peerDeviceId, groupId ->
                    val member = groupMemberDao?.member(groupId, peerDeviceId)
                    if (member?.isActive == true) {
                        val latestRot = groupRotationDao?.getLatestForGroup(groupId)?.toRotation()
                        if (latestRot != null) {
                            groupTransportSink?.send(
                                peerDeviceId,
                                GroupWireFrame.GsStale(
                                    groupId = groupId,
                                    from = localDeviceId,
                                    epoch = latestRot.newEpoch,
                                    rotation = latestRot,
                                ),
                            )
                        }
                    }
                },
            )
        } else {
            null
        }

    override fun hasProvedGroup(peerId: String, groupId: String): Boolean =
        groupProofSessions?.hasProved(peerId, groupId) ?: false

    override suspend fun initiateGroupProof(
        peerId: String,
        groupId: String,
        epoch: Long,
    ): GroupProofResult =
        groupProofSessions?.initiateProof(peerId, groupId, epoch) ?: GroupProofResult.FAILED

    internal suspend fun bundleForGroup(groupId: String): GroupWireFrame.Bundle? =
        signedGroups?.bundleFor(groupId)

    override var swarmAnnouncementListener: GroupSwarmAnnouncementListener? = null
    override var onGroupMessageDeletedForEveryone: ((groupId: String, messageId: String) -> Unit)? = null

    public val groupGate: com.transfer.flash.core.messaging.group.GroupGate =
        com.transfer.flash.core.messaging.group.RosterGroupGate(
            localDeviceId = localDeviceId,
            members = { groupMemberDao },
            isTrustedPeer = { isTrustedPeer(it) },
            isVouchedMember = { gId, dId, sessionKey ->
                signedGroups?.isVouchedMember(gId, dId, sessionKey) == true
            },
            hasMemberKey = { gId, dId ->
                groupMemberDao?.member(gId, dId)?.subjectKey != null
            },
            peerIdentityKey = { peerIdentityKey(it) },
            swarmServingEnabled = { gId ->
                val settings = signedGroups?.currentSettings(gId) ?: GroupSettings.defaults(gId)
                val prefs = getGroupLocalPreferences(gId)
                settings.swarmServing && prefs.serveToGroup
            },
        )

    override fun isV2Group(groupId: String): Boolean =
        GroupPolicy.isV2GroupId(groupId)

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

    /** ERROR-087: what the open chat has put on screen and already acknowledged; [endOpenConversation] finishes the difference. */
    private var activeReadState: OpenConversationReadState? = null

    // Bug 6 / crash at drainOutboxOnce: MUST be declared before the init block below.
    // Kotlin runs property initializers + init blocks in source order, and the init block
    // launches a coroutine that can reach drainOutboxOnce() on Dispatchers.IO before the
    // constructor finishes — if this val sits below the init block, the coroutine sees a
    // null drainMutex and the resulting NPE is an uncaught coroutine exception that kills
    // the whole process. Observed on device 2026-08-31 10:22 (AndroidRuntime FATAL).
    private val drainMutex = Mutex()

    /** Serialises [updateGroupLocalPreferences], which reads the stored row before replacing it. */
    private val groupPreferencesLock = Mutex()

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

    private fun notifyOutboxDrain() {
        drainWake.trySend(Unit)
    }

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

    /**
     * Every retry deadline a drain pass has scheduled and not yet seen pass; [outboxNextDueAt] is the smallest.
     *
     * A single stored "earliest" was not enough: a pass that resends a row (a session-up pass does, after
     * resetting its attempts) moves that row's deadline LATER than one already pending, the older deadline then
     * fires with nothing due and is dropped, and the later one was never kept, so the loop slept its idle net
     * while the row waited. Touched only inside [drainOutboxOnce], under [drainMutex].
     * MUST be declared above the init block, for the reason recorded on [drainMutex].
     */
    private val outboxDeadlines = mutableListOf<Long>()

    /** Catch-up arrivals per group (UI-052). [generation] lets the quiet timer tell whether a newer arrival superseded it. */
    private data class SyncActivity(val received: Int, val generation: Long, val expected: Int? = null)

    /** What the catch-up banner needs: arrivals so far, and the holder's estimate of the total while a page chain continues (ADR-100). */
    private data class SyncBanner(val received: Int, val expected: Int?)

    private val groupSyncActivity = MutableStateFlow<Map<String, SyncActivity>>(emptyMap())

    /**
     * A catch-up push stored a NEW message in [groupId] (a duplicate does not count, so a redundant second holder never
     * starts the banner). Counts it and restarts the quiet timer; [groupSyncQuietMs] after the last arrival the group's
     * entry is removed and the banner goes away.
     */
    private fun recordCatchUpArrival(groupId: String) {
        var generation = 0L
        groupSyncActivity.update { current ->
            val previous = current[groupId]
            generation = (previous?.generation ?: 0L) + 1L
            current + (groupId to SyncActivity((previous?.received ?: 0) + 1, generation, previous?.expected))
        }
        val armed = generation
        scope.launch {
            delay(groupSyncQuietMs)
            groupSyncActivity.update { current ->
                if (current[groupId]?.generation == armed) current - groupId else current
            }
        }
    }

    /**
     * ADR-100: a catch-up page ended. [remaining] is the holder's count of rows still to come (null when the chain is over).
     * Only a banner that is already up is touched (a page of duplicates must not start one), and the quiet timer restarts so
     * the banner stays up between pages.
     */
    private fun recordCatchUpPage(groupId: String, remaining: Int?) {
        var armed = -1L
        groupSyncActivity.update { current ->
            val previous = current[groupId] ?: return@update current
            armed = previous.generation + 1L
            current + (groupId to previous.copy(
                generation = armed,
                expected = remaining?.let { GroupHistoryPolicy.expectedTotal(previous.received, it) },
            ))
        }
        if (armed < 0L) return
        val mine = armed
        scope.launch {
            delay(groupSyncQuietMs)
            groupSyncActivity.update { current ->
                if (current[groupId]?.generation == mine) current - groupId else current
            }
        }
    }

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
        onlinePeerIds.withReconnectGrace(OFFLINE_HOLD_MS).withReachable(reachablePeerIds)

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
    /** Device id -> display name for the members of the open group; read when a sent file's recipient list is drawn. */
    private var openGroupMemberNames: Map<String, String> = emptyMap()

    private val pacedAttachmentProgress: Flow<Map<String, FlashAttachmentProgress>> =
        attachmentProgress.throttleLatest(ATTACHMENT_PROGRESS_THROTTLE_MS)

    init {
        // GM-4: Load accepted invite group IDs to restore charter trust roots.
        scope.launch(ioDispatcher) {
            groupInviteDao?.getAll()?.forEach { entity ->
                if (entity.state != "REFUSED" && entity.state != "ABANDONED" && entity.state != "STALE") {
                    acceptedInviteGroupIds.add(entity.groupId)
                }
            }
        }

        // GM-6: Recover unrotated tombstones from crashes (GINV-4).
        scope.launch(ioDispatcher) {
            val recovered = signedGroups?.recoverUnrotatedTombstones().orEmpty()
            for (rot in recovered) {
                broadcastRotation(rot.groupId, rot)
            }
        }

        // GM-7: Upgrade existing v2 groups without secrets on startup (O-11).
        scope.launch(ioDispatcher) {
            val upgraded = signedGroups?.upgradeExistingV2Groups().orEmpty()
            for (rot in upgraded) {
                broadcastRotation(rot.groupId, rot)
            }
        }

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
                            entity.id in peers.reachable -> FlashPeerPresence.Reachable
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

        // Prune ephemeral typing indicators and clear proof sessions the instant a peer departs/disconnects.
        scope.launch(ioDispatcher) {
            var previousOnline = emptySet<String>()
            onlinePeerIds.collect { onlineSet ->
                val departed = previousOnline - onlineSet
                val arrived = onlineSet - previousOnline
                previousOnline = onlineSet
                currentConnectedPeers.clear()
                currentConnectedPeers.addAll(onlineSet)
                for (peerId in departed) {
                    groupProofSessions?.onSessionDown(peerId)
                }
                for (peerId in arrived) {
                    onPeerSessionUp(peerId)
                }
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
            outboxDao.observeCount().collect { notifyOutboxDrain() }
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
        // ERROR-087: leave the previous chat properly first, so what it displayed is acknowledged even when its
        // collector was cancelled before it got to write the cursor.
        endOpenConversation()
        activeConversationId = conversationId

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
        // Both live in [readState] (not in locals of the collector) so closing the chat can finish
        // an acknowledgement the cancelled collector never reached (ERROR-087).
        val readState = OpenConversationReadState(conversationId)
        activeReadState = readState

        activeConversationJob = scope.launch(ioDispatcher) {
            if (isV2Group(conversationId)) {
                signedGroups?.upgradeGroupSecret(conversationId)?.let { rot ->
                    broadcastRotation(conversationId, rot)
                }
            }
            val isGroupConversation = conversationDao.get(conversationId)?.isGroup == true
            readState.isGroup = isGroupConversation
            if (isGroupConversation) {
                // Names for the sender's "who has the file" list. A separate collector: the list is repainted every
                // second while a file moves, so a name that arrives a moment late shows up on the next repaint.
                launch {
                    groupMemberDao?.observeMembers(conversationId)?.collect { roster ->
                        openGroupMemberNames = roster.associate { it.deviceId to it.displayName }
                    }
                }
            }
            val deliveryCountsFlow = if (isGroupConversation) {
                groupDeliveryDao?.observeDeliveryCounts(conversationId, localDeviceId) ?: flowOf(emptyList())
            } else {
                flowOf(emptyList())
            }
            // Inner combine (5 flows): pure message content — rows + draft + attachment progress +
            // reactions + observable group-delivery aggregates.
            val messageContentFlow = combine(
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
                    pinnedMessageIds = emptyList(),
                    newestMessageId = newestMessageId,
                    newestInboundId = newestInboundId,
                )
            }

            // Pins live in their own table; joined here so the 5-flow content combine above stays within its overload.
            val contentFlow = combine(
                messageContentFlow,
                messagePinDao?.observePinnedIds(conversationId) ?: flowOf(emptyList()),
            ) { content, pinnedIds -> content.copy(pinnedMessageIds = pinnedIds) }

            // The roster is read inside the combine below, so a change to the member table (an owner removal, a leave, an
            // add from another device) has to re-run it, or an open members sheet keeps showing the old members.
            val rawRosterFlow = if (isGroupConversation) {
                groupMemberDao?.observeMembers(conversationId) ?: flowOf(emptyList())
            } else {
                flowOf(emptyList())
            }
            val rosterFlow = combine(rawRosterFlow, conversationRefreshTrigger) { roster, _ -> roster }
            // UI-052: how many catch-up messages have arrived, for the banner; nothing for a direct chat.
            val syncFlow = if (isGroupConversation) {
                groupSyncActivity.map { all -> all[conversationId]?.let { SyncBanner(it.received, it.expected) } }.distinctUntilChanged()
            } else {
                flowOf(null)
            }
            // Outer combine (5 flows): join live presence, typing, roster and catch-up changes onto the header (#11).
            combine(
                contentFlow,
                displayedPresence,
                typingFlow,
                rosterFlow,
                syncFlow,
            ) { content, peers, typingByConversation, _, syncBanner ->
                // Group Phase A: a group thread derives its header from the member roster, not
                // from a peer-name lookup (a groupId is not a device id — the UUID used to win).
                val conversationEntity = conversationDao.get(conversationId)
                if (conversationEntity?.isGroup == true) {
                    val members = groupMemberDao?.activeMembers(conversationId).orEmpty()
                    val selfMembership = selfMembershipOf(groupMemberDao?.member(conversationId, localDeviceId))
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
                    val ownerName = ownerDisplayName(conversationId, conversationEntity)
                    val selfMember = members.firstOrNull { it.deviceId == localDeviceId }
                    val isOwner = conversationEntity.groupCreatedBy == localDeviceId
                    val isMemberActive = selfMembership == FlashSelfMembership.Active
                    val isAdmin = isOwner || (isMemberActive && selfMember?.role == "admin")
                    val isV2 = conversationEntity.groupProto == GroupPolicy.V2_PROTOCOL
                    val currentSettings = if (isV2) signedGroups?.currentSettings(conversationId) ?: GroupSettings.defaults(conversationId) else null
                    val canShare = isMemberActive && isV2 && (isOwner || isAdmin || currentSettings?.inviteSharers == GroupSettings.SHARERS_ALL)
                    val canAdd = isMemberActive && (!isV2 || isOwner || isAdmin || currentSettings?.membersMayAdd == true)
                    val pendingReqs = if (isV2 && (isOwner || isAdmin)) getPendingJoinRequests(conversationId) else emptyList()
                    val localPrefs = if (isV2) getGroupLocalPreferences(conversationId) else null
                    // ADR-100: the join card shows while this member has not chosen how much history to load.
                    val historyPending = isMemberActive && groupHistoryDao?.state(conversationId)?.cardState == HISTORY_PENDING
                    val historyCeiling = currentSettings?.historyCeiling ?: historyCeilingOf(conversationId)
                    val rosterForAdmin = (groupMemberDao?.allMembers(conversationId).orEmpty())
                        .map { GroupAdminPolicy.Member(it.deviceId, it.role, it.isActive) }
                    val canChangeCeiling = isV2 && isMemberActive &&
                        GroupAdminPolicy.canChangeHistoryCeiling(localDeviceId, conversationEntity.groupCreatedBy, rosterForAdmin)
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
                            // Group voice & video calls supported via multi-leg full-mesh CallCoordinator; a device that
                            // left or was removed is out of the calls too.
                            showCallActions = selfMembership == FlashSelfMembership.Active,
                        ),
                        messages = content.messages,
                        draftText = content.draftText,
                        pinnedMessageIds = content.pinnedMessageIds,
                        members = members.map { member ->
                            member.toMemberUi(
                                isOnline = member.deviceId in peers.online,
                                introducedBy = introducedByOf(member, ownerName),
                            )
                        },
                        canRemoveMembers = isMemberActive && isV2 && (isOwner || isAdmin),
                        canAddMembers = canAdd,
                        isGroupOwner = isMemberActive && isOwner,
                        isGroupAdmin = isAdmin,
                        canPromoteAdmin = isMemberActive && isV2 && isOwner,
                        canContinueInNewGroup = isMemberActive && !isOwner,
                        selfMembership = selfMembership,
                        groupSync = syncBanner?.let { FlashGroupSyncUi(receivedCount = it.received, expectedCount = it.expected) },
                        groupHistory = if (historyPending) FlashGroupHistoryUi(ceiling = historyCeiling) else null,
                        canChangeHistoryCeiling = canChangeCeiling,
                        canShareInvite = canShare,
                        isGroupV2 = isV2,
                        pendingJoinRequests = pendingReqs,
                        groupSettings = currentSettings,
                        groupLocalPreferences = localPrefs,
                    ) to Pair(content.newestMessageId, content.newestInboundId)
                } else {
                    directHeaderState(content, peers, typingByConversation, conversationId)
                        .let { it to Pair(content.newestMessageId, content.newestInboundId) }
                }
            }.collectLatest { (state, cursors) ->
                val (newestMessageId, newestInboundId) = cursors
                _conversationState.value = state
                // Record what is now on screen BEFORE the suspending writes: collectLatest or a close can cancel
                // this block between the render and the cursor write, and a message the user was shown must not
                // stay unread because of that (ERROR-087).
                readState.shownNewestMessageId = newestMessageId
                readState.shownNewestInboundId = newestInboundId
                acknowledgeShown(readState)
            }
        }
    }

    /**
     * Message Info (UI-051). Only a message this device wrote in a group has recipient rows; anything else is null.
     * The mapping lives in [buildGroupMessageInfo]; this only gathers its inputs and keeps them live, so a receipt or
     * a read cursor that arrives while the sheet is open moves the recipient to its new section.
     */
    override fun observeMessageInfo(messageId: String): Flow<FlashMessageInfoUi?> = flow {
        val message = messageDao.getByLocalId(messageId)
        val deliveries = groupDeliveryDao
        val members = groupMemberDao
        if (message == null || message.senderId != localDeviceId || message.deletedAt != null ||
            deliveries == null || members == null ||
            conversationDao.get(message.conversationId)?.isGroup != true
        ) {
            emit(null)
            return@flow
        }
        val preview = if (message.attachmentTransferId != null) message.attachmentLabel() else message.text
        emitAll(
            combine(
                deliveries.observeForMessage(messageId),
                members.observeMembers(message.conversationId),
                readCursorDao?.observeCursors(message.conversationId) ?: flowOf(emptyList()),
            ) { rows, roster, cursors ->
                buildGroupMessageInfo(
                    messageId = messageId,
                    preview = preview,
                    sentAt = message.sentAt,
                    deliveries = rows,
                    members = roster,
                    cursors = cursors,
                    formatTime = ::formatTime,
                    initialsOf = ::computeInitials,
                )
            },
        )
    }

    /**
     * This device was renamed: what it sends from now on is stamped with [newName], and so are the groups it
     * creates. Messages already sent keep the name they carried. The device's own row in every LEGACY group is
     * rewritten too; a v2 row is left alone (`updateMemberDisplayName` skips it) because its name is the
     * owner-signed label, which only the owner can re-issue.
     */
    public override suspend fun updateLocalDisplayName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == localDisplayName) return
        localDisplayName = trimmed
        signedGroups?.updateLocalDisplayName(trimmed)
        groupMemberDao?.updateMemberDisplayName(localDeviceId, trimmed)
    }

    /**
     * Tells every other active member of [groupId] that this device has read up to [upToMessageId]. One frame per
     * member because the wire has no broadcast: each connection carries its own. Runs to completion even if the
     * state collector is cancelled by a newer emission (`collectLatest`); the caller has already recorded this id
     * as sent, so a cut-off fan-out would not be retried until the next inbound message or a re-open.
     * Nothing is sent once this device is no longer a member. Best effort and not durable, like the 1:1 receipt:
     * a member that is offline now hears it the next time this conversation is opened.
     */
    private suspend fun sendGroupRead(groupId: String, upToMessageId: String) {
        val members = groupMemberDao ?: return
        val sink = groupTransportSink ?: return
        if (isRemovedHere(members, groupId)) return
        withContext(NonCancellable) {
            val readAt = timeSource.nowMs()
            members.activeMembers(groupId).filter { it.deviceId != localDeviceId }.forEach { target ->
                sink.send(
                    target.deviceId,
                    GroupWireFrame.Read(groupId = groupId, from = localDeviceId, upToMessageId = upToMessageId, readAt = readAt),
                )
            }
        }
    }

    /**
     * Records that [reader] has read [groupId] up to [upToMessageId], then flips this device's own sent messages to
     * READ as far as EVERY active remote member has read. A cursor is stored per member, so the group tick can
     * only move when the slowest member catches up; a member that never sent a `Read` holds it back.
     *
     * The named message must be one this device holds for that group: its `sentAt` is what orders the cursor,
     * and a name this device cannot resolve (not synced here yet) moves nothing rather than guessing.
     */
    private suspend fun onGroupRead(members: GroupMemberDao, groupId: String, reader: String, upToMessageId: String) {
        val cursors = readCursorDao ?: return
        val target = messageDao.getByLocalId(upToMessageId)?.takeIf { it.conversationId == groupId } ?: return
        cursors.advanceFurthest(groupId, reader, upToMessageId, target.sentAt)
        val remote = members.activeMembers(groupId).filter { it.deviceId != localDeviceId }
        if (remote.isEmpty()) return
        // The slowest active member decides. Removed members are not in `remote`, so a stale cursor from one
        // cannot hold the tick back, and a missing cursor (never read) holds it at its current value.
        val slowest = remote.map { cursors.get(groupId, it.deviceId) ?: return }
            .minWithOrNull(compareBy({ it.upToSentAt }, { it.upToMessageId })) ?: return
        messageDao.markReadUpTo(groupId, localDeviceId, slowest.upToMessageId)
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
            // PC3: seen by discovery, no session. The header says "Online" with a ring and
            // claims no transport, so the banner explains that a send will connect.
            conversationId in peers.reachable -> FlashPeerPresence.Reachable
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
            pinnedMessageIds = content.pinnedMessageIds,
        )
    }

    /** Intermediate holder for the content combine so the presence/typing combine stays ≤ 5 flows. */
    private data class ConversationContent(
        val messages: List<FlashMessageUi>,
        val draftText: String,
        val pinnedMessageIds: List<String>,
        val newestMessageId: String?,
        val newestInboundId: String?,
    )

    override fun closeConversation() {
        endOpenConversation()
        activeConversationId = null
    }

    /**
     * ERROR-087: the read bookkeeping of ONE open chat. The collector in [openConversation] publishes a state to the
     * screen first and acknowledges it (read cursor, read receipt) second, and `collectLatest` or a close can cancel it
     * between the two. The newest ids the screen showed are therefore recorded before the writes, and
     * [endOpenConversation] writes whatever is still outstanding when the chat is left.
     */
    private class OpenConversationReadState(val conversationId: String) {
        @Volatile var isGroup: Boolean = false
        @Volatile var shownNewestMessageId: String? = null
        @Volatile var shownNewestInboundId: String? = null
        @Volatile var markedReadId: String? = null
        @Volatile var ackedInboundId: String? = null

        fun hasOutstandingAcknowledgement(): Boolean = shownNewestMessageId.let { it != null && it != markedReadId }
    }

    /**
     * Writes the read cursor up to the newest message the screen showed, then tells the author (a `Read` receipt, or
     * a `Read` frame per active member in a group). Idempotent: an unchanged head writes nothing, so it is safe to
     * call from every emission and again from the close flush.
     */
    private suspend fun acknowledgeShown(read: OpenConversationReadState) {
        val newestMessageId = read.shownNewestMessageId ?: return
        if (newestMessageId == read.markedReadId) return
        conversationDao.updateLastReadCursor(read.conversationId, newestMessageId)
        read.markedReadId = newestMessageId
        // Tell the peer we've read up to its newest message so its sent bubbles flip
        // Delivered → Read (C6.3). Only fires when the peer has actually sent us
        // something (never for our own outbound head) and never re-acks the same id.
        // Routed to the peer (conversationId is its device id); memberId is our id, which
        // the peer uses to locate its thread for us. Idempotent via `markReadUpTo`.
        val newestInboundId = read.shownNewestInboundId
        if (newestInboundId != null && newestInboundId != read.ackedInboundId) {
            read.ackedInboundId = newestInboundId
            if (read.isGroup) {
                // A group id is not a device, so the direct receipt below had no one to reach. Each
                // active member gets its own `Read` frame and keeps this device's cursor.
                sendGroupRead(read.conversationId, newestInboundId)
            } else {
                transportSink?.send(
                    read.conversationId,
                    MessageWireFrame.ReadReceipt(
                        conversationId = read.conversationId,
                        memberId = localDeviceId,
                        upToMessageId = newestInboundId,
                        readAt = timeSource.nowMs(),
                    ),
                )
            }
        }
    }

    /**
     * Stops the open chat's collector and, off the caller's thread, acknowledges anything it displayed but had not yet
     * marked read. Called by [closeConversation] and by [openConversation] (opening B straight after A leaves A).
     */
    private fun endOpenConversation() {
        activeConversationJob?.cancel()
        activeConversationJob = null
        val read = activeReadState
        activeReadState = null
        if (read != null && read.hasOutstandingAcknowledgement()) {
            scope.launch(ioDispatcher) { acknowledgeShown(read) }
        }
    }

    override suspend fun createGroupForInvite(name: String): FlashResult<String> =
        withContext(ioDispatcher) {
            val signed = signedGroups
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Signed groups are unavailable on this device"))
            val secretStore = groupSecretStore
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Group secret store is unavailable"))
            val groupName = GroupPolicy.normalizedName(name)
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Group name must be 1-${GroupPolicy.MAX_GROUP_NAME_LENGTH} characters"))
            if (!GroupPolicy.validMemberIds(listOf(localDeviceId), localDeviceId, GroupPolicy.MAX_MEMBERS_V2, minMembers = 1)) {
                return@withContext FlashResult.Failure(FlashError.Unknown("Invalid group membership"))
            }
            val created = signed.create(groupName, emptyMap()) { localDisplayName }
            val rot = signed.upgradeGroupSecret(created.groupId)
            if (rot == null && secretStore.current(created.groupId) == null) {
                val now = timeSource.nowMs()
                val secret = GroupSecret.generate()
                secretStore.put(
                    StoredGroupSecret(
                        groupId = created.groupId,
                        epoch = 1L,
                        secret = secret,
                        source = GroupSecretSource.CREATED,
                        receivedAtMs = now,
                    ),
                )
            }
            groupTitleCache[created.groupId] = groupName
            FlashLog.i("CHAT", "Group v2 created for invite: group=${created.groupId}")
            FlashResult.Success(created.groupId)
        }

    override suspend fun inviteFor(groupId: String): FlashResult<String> =
        withContext(ioDispatcher) {
            if (!isV2Group(groupId)) {
                return@withContext FlashResult.Failure(
                    FlashError.Unknown("Only groups made with the latest Flash version support invite links"),
                )
            }
            val crypto = groupCrypto
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Crypto identity unavailable"))
            val secretStore = groupSecretStore
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Group secret store unavailable"))
            val members = groupMemberDao
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
            val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive }
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("You are not an active group member"))
            val conv = conversationDao.get(groupId)
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Group not found"))
            val isOwner = conv.groupCreatedBy == localDeviceId || self.role == "owner"
            val isAdmin = isOwner || self.role == "admin"
            val settings = signedGroups?.currentSettings(groupId) ?: GroupSettings.defaults(groupId)
            if (settings.inviteSharers == GroupSettings.SHARERS_ADMINS && !isAdmin) {
                return@withContext FlashResult.Failure(
                    FlashError.Unknown("Only an admin can share invites for this group"),
                )
            }
            val signed = signedGroups
            var currentSecret = secretStore.current(groupId)
            if (signed != null && (currentSecret == null || groupRotationDao?.getLatestForGroup(groupId) == null)) {
                val rot = signed.upgradeGroupSecret(groupId)
                if (rot != null) {
                    broadcastRotation(groupId, rot)
                }
                currentSecret = secretStore.current(groupId)
            }
            if (currentSecret == null && groupRotationDao == null) {
                val newSecret = GroupSecret.generate()
                val record = StoredGroupSecret(
                    groupId = groupId,
                    epoch = 1L,
                    secret = newSecret,
                    source = GroupSecretSource.CREATED,
                    receivedAtMs = timeSource.nowMs(),
                )
                secretStore.put(record)
                currentSecret = record
            }
            if (currentSecret == null) {
                return@withContext FlashResult.Failure(
                    FlashError.Unknown("An admin needs to open this group on the new version first"),
                )
            }
            val now = timeSource.nowMs()
            val fpBytes = crypto.sha256(crypto.publicKey)
            val hints = localAddressHints().take(3)
            val invite = GroupInvite(
                version = 1,
                groupId = groupId,
                epoch = currentSecret.epoch,
                secret = currentSecret.secret,
                groupName = conv.title,
                inviterDeviceId = localDeviceId,
                inviterKeyFingerprint = fpBytes,
                addressHints = hints,
                issuedAtMs = now,
            )
            val encoded = GroupInviteCodec.encode(invite)
            FlashResult.Success(encoded)
        }

    override suspend fun acceptInvite(inviteUri: String): FlashResult<String> =
        withContext(ioDispatcher) {
            val invite = GroupInviteCodec.decode(inviteUri)
                ?: return@withContext FlashResult.Failure(FlashError.Unknown(GroupMembershipStatusText.INVALID_INVITE))
            val members = groupMemberDao
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
            val current = members.member(invite.groupId, localDeviceId)
            if (current?.isActive == true) {
                return@withContext FlashResult.Success(invite.groupId)
            }
            val now = timeSource.nowMs()
            val inviteEntity = GroupInviteEntity(
                groupId = invite.groupId,
                inviterId = invite.inviterDeviceId,
                inviterFingerprint = invite.inviterFingerprintHex.uppercase(),
                acceptedAtMs = now,
                state = "PENDING_CONTACT",
            )
            groupInviteDao?.upsert(inviteEntity)
            acceptedInviteGroupIds.add(invite.groupId)
            pendingInviteHints[invite.groupId] = invite.addressHints
            pendingInviteGroupNames[invite.groupId] = invite.groupName
            hintsExhausted.remove(invite.groupId)
            groupSecretStore?.put(
                StoredGroupSecret(
                    groupId = invite.groupId,
                    epoch = invite.epoch,
                    secret = invite.secret,
                    source = GroupSecretSource.INVITE,
                    receivedAtMs = now,
                ),
            )
            // Pre-install inviter's key as a scoped vouch
            // The verdict is not an error to the joiner: a refusal means the id is already pinned under another key
            // (paired, or vouched by another group), the dial is then checked against THAT pin, and an impostor at a
            // hinted address is turned away by the TLS layer either way.
            val vouchVerdict = groupVouching?.vouch(invite.inviterDeviceId, invite.inviterFingerprintHex.uppercase(), invite.groupId)
            FlashLog.i("GROUP", "Inviter vouch: group=${invite.groupId} inviter=${invite.inviterDeviceId} verdict=$vouchVerdict")
            // Dial address hints in order (GM-4 task 3, GM-8 task 1)
            scope.launch(ioDispatcher) {
                dialHintsInOrder(invite.groupId, invite.inviterDeviceId, invite.addressHints)
            }
            // If already connected to the inviter, start the proof now. In the background: it waits for the peer's
            // answers (up to the proof timeout), and the caller (the Join button) must not wait with it.
            if (isPeerOnline(invite.inviterDeviceId)) {
                scope.launch(ioDispatcher) { triggerProofForPendingInvites(invite.inviterDeviceId) }
            }
            FlashLog.i("GROUP", "Invite accepted: group=${invite.groupId} inviter=${invite.inviterDeviceId} hints=${invite.addressHints.size} inviterOnline=${isPeerOnline(invite.inviterDeviceId)}")
            FlashResult.Success(invite.groupId)
        }

    private suspend fun dialHintsInOrder(groupId: String, inviterId: String, hints: List<String>) {
        if (hints.isEmpty()) {
            FlashLog.i("GROUP", "No address hints in invite for $groupId; waiting for discovery (M-03)")
            hintsExhausted.add(groupId)
            return
        }
        val deadline = timeSource.nowMs() + HINT_DIAL_TIMEOUT_MS
        for (hint in hints) {
            if (timeSource.nowMs() >= deadline) {
                FlashLog.i("GROUP", "Hint dial deadline reached (30s) for $groupId; waiting for discovery (M-03)")
                break
            }
            if (isPeerOnline(inviterId)) {
                FlashLog.i("GROUP", "Session already established to inviter $inviterId in group $groupId")
                triggerProofForPendingInvites(inviterId)
                return
            }
            FlashLog.i("GROUP", "Dialing address hint $hint for inviter $inviterId in group $groupId")
            val success = if (onConnectPeerHint != null) {
                onConnectPeerHint.invoke(inviterId, hint)
            } else if (onConnectPeerWithHints != null) {
                onConnectPeerWithHints.invoke(inviterId, listOf(hint))
                isPeerOnline(inviterId)
            } else {
                false
            }
            if (success || isPeerOnline(inviterId)) {
                FlashLog.i("GROUP", "Address hint $hint succeeded for inviter $inviterId in group $groupId")
                triggerProofForPendingInvites(inviterId)
                return
            }
        }
        FlashLog.i("GROUP", "Hints exhausted for group $groupId; waiting for discovery (M-03)")
        hintsExhausted.add(groupId)
    }

    override fun inviterDisplayName(deviceId: String): String? =
        peerNameResolver(deviceId)?.ifBlank { null }

    override suspend fun inviteStatusSentence(groupId: String): String? = withContext(ioDispatcher) {
        val members = groupMemberDao
        if (members?.member(groupId, localDeviceId)?.isActive == true) {
            return@withContext "Joined"
        }
        val invite = groupInviteDao?.getByGroupId(groupId) ?: return@withContext null
        val groupName = conversationDao.get(groupId)?.title
            ?.takeIf { it.isNotBlank() }
            ?: pendingInviteGroupNames[groupId]
            ?: "the group"
        val now = timeSource.nowMs()
        val elapsedMs = now - invite.acceptedAtMs
        when (invite.state) {
            "PENDING_CONTACT" -> {
                if (elapsedMs >= HINT_DIAL_TIMEOUT_MS || hintsExhausted.contains(groupId)) {
                    GroupMembershipStatusText.waitingForMember(groupName)
                } else {
                    "Connecting to inviter…"
                }
            }
            "PENDING_APPROVAL" -> GroupMembershipStatusText.waitingForAdmin(groupName)
            "REFUSED" -> GroupMembershipStatusText.requestDeclined(groupName)
            "JOINED" -> "Joined"
            "ABANDONED" -> "Cancelled"
            "STALE" -> GroupMembershipStatusText.INVITE_REPLACED
            "INVALID" -> GroupMembershipStatusText.INVITE_NO_LONGER_VALID
            else -> null
        }
    }

    override suspend fun retryPendingInviteHints(): Unit = withContext(ioDispatcher) {
        val pending = groupInviteDao?.getByState("PENDING_CONTACT") ?: emptyList()
        for (invite in pending) {
            val hints = pendingInviteHints[invite.groupId] ?: emptyList()
            if (hints.isNotEmpty() && !isPeerOnline(invite.inviterId)) {
                hintsExhausted.remove(invite.groupId)
                scope.launch(ioDispatcher) {
                    dialHintsInOrder(invite.groupId, invite.inviterId, hints)
                }
            }
        }
    }

    public fun onNetworkChanged() {
        scope.launch(ioDispatcher) {
            retryPendingInviteHints()
        }
    }

    override suspend fun approveJoinRequest(groupId: String, subjectId: String): FlashResult<Unit> =
        withContext(ioDispatcher) {
            val signed = signedGroups
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Signed groups unavailable"))
            val members = groupMemberDao
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
            val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive }
            val isOwner = conversationDao.get(groupId)?.groupCreatedBy == localDeviceId
            val isAdmin = isOwner || self?.role == "admin"
            if (!isAdmin) {
                return@withContext FlashResult.Failure(FlashError.Unknown("Only an admin can approve join requests"))
            }
            val active = members.activeMembers(groupId)
            val maxMembers = signedGroups?.currentSettings(groupId)?.maxMembers ?: GroupPolicy.MAX_MEMBERS_V2
            if (active.size >= maxMembers) {
                return@withContext FlashResult.Failure(FlashError.Unknown("Group is full"))
            }
            val req = groupJoinRequestDao?.getAllForGroup(groupId)?.firstOrNull {
                it.subjectId == subjectId && it.state == "PENDING"
            } ?: return@withContext FlashResult.Failure(FlashError.Unknown("Join request not found"))
            val keyBytes = GroupCanonical.decode(req.subjectKey)
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Invalid subject key"))
            val added = signed.addMembers(groupId, mapOf(subjectId to keyBytes)) { req.label }
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Failed to certify member"))
            val now = timeSource.nowMs()
            groupJoinRequestDao?.updateDecision(groupId, subjectId, req.subjectKey, "APPROVED", localDeviceId, now)
            touchConversationRefresh()
            // Send full bundle to subject. If the link is down right now this is lost; the subject asks again when it
            // reconnects (its request is answered with the roster) and the roster reconcile on session-up covers it too.
            val bundleSent = groupTransportSink?.send(subjectId, added.full)
            FlashLog.i("GROUP", "Join approved: group=$groupId subject=$subjectId bundleDelivered=$bundleSent")
            // Broadcast changed bundle to existing members
            active.forEach { member ->
                if (member.deviceId != localDeviceId && member.deviceId != subjectId) {
                    groupTransportSink?.send(member.deviceId, added.changed)
                }
            }
            // Send decision
            val decBytes = GroupCanonical.joinDecisionBytes(groupId, subjectId, true, "approved", localDeviceId, now)
            val decSig = GroupCanonical.encode(groupCrypto?.sign(decBytes) ?: ByteArray(0))
            val decision = GroupWireFrame.GsJoinDecision(
                groupId = groupId,
                from = localDeviceId,
                subjectId = subjectId,
                approved = true,
                reason = "approved",
                decidedBy = localDeviceId,
                decidedAtMs = now,
                signature = decSig,
            )
            groupTransportSink?.send(subjectId, decision)
            FlashResult.Success(Unit)
        }

    override suspend fun refuseJoinRequest(
        groupId: String,
        subjectId: String,
        reason: String,
    ): FlashResult<Unit> = withContext(ioDispatcher) {
        val members = groupMemberDao
            ?: return@withContext FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive }
        val isOwner = conversationDao.get(groupId)?.groupCreatedBy == localDeviceId
        val isAdmin = isOwner || self?.role == "admin"
        if (!isAdmin) {
            return@withContext FlashResult.Failure(FlashError.Unknown("Only an admin can refuse join requests"))
        }
        val req = groupJoinRequestDao?.getAllForGroup(groupId)?.firstOrNull {
            it.subjectId == subjectId && it.state == "PENDING"
        } ?: return@withContext FlashResult.Failure(FlashError.Unknown("Join request not found"))
        val now = timeSource.nowMs()
        groupJoinRequestDao?.updateDecision(groupId, subjectId, req.subjectKey, "REFUSED", localDeviceId, now)
        touchConversationRefresh()
        val decBytes = GroupCanonical.joinDecisionBytes(groupId, subjectId, false, reason, localDeviceId, now)
        val decSig = GroupCanonical.encode(groupCrypto?.sign(decBytes) ?: ByteArray(0))
        val decision = GroupWireFrame.GsJoinDecision(
            groupId = groupId,
            from = localDeviceId,
            subjectId = subjectId,
            approved = false,
            reason = reason,
            decidedBy = localDeviceId,
            decidedAtMs = now,
            signature = decSig,
        )
        groupTransportSink?.send(subjectId, decision)
        FlashResult.Success(Unit)
    }

    override suspend fun getPendingJoinRequests(groupId: String): List<FlashGroupJoinRequestUi> =
        withContext(ioDispatcher) {
            val requests = groupJoinRequestDao?.getAllForGroup(groupId)?.filter { it.state == "PENDING" } ?: emptyList()
            val members = groupMemberDao
            val rotations = groupRotationDao?.getAllForGroup(groupId).orEmpty()
            requests.map { req ->
                val isRemoved = members?.member(groupId, req.subjectId)?.let { !it.isActive } == true ||
                    rotations.any { req.subjectId in it.toRotation().removedIds }
                val known = isTrustedPeer(req.subjectId)
                FlashGroupJoinRequestUi(
                    subjectId = req.subjectId,
                    subjectKey = req.subjectKey,
                    // A paired device is shown under the name this device gave it, not the one it asked for.
                    label = if (known) peerNameResolver(req.subjectId)?.ifBlank { null } ?: req.label else req.label,
                    requestedAtMs = req.requestedAtMs,
                    isPreviouslyRemoved = isRemoved,
                    isKnownDevice = known,
                )
            }
        }

    override suspend fun cancelPendingInvite(groupId: String): FlashResult<Unit> =
        withContext(ioDispatcher) {
            val invite = groupInviteDao?.getByGroupId(groupId)
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("No invite found for group"))
            groupInviteDao?.updateState(groupId, "ABANDONED")
            touchConversationRefresh()
            FlashResult.Success(Unit)
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
        if (!GroupPolicy.validMemberIds(allMembers, localDeviceId, GroupPolicy.MAX_MEMBERS_V2)) {
            return FlashResult.Failure(FlashError.Unknown("Groups support 2-${GroupPolicy.MAX_MEMBERS_V2} unique members"))
        }
        if (memberIds.any { !isTrustedPeer(it) }) {
            return FlashResult.Failure(FlashError.Unknown("Every group member must be trusted"))
        }
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        // ADR-044 V1: a new group is v2 when this device can sign and every invitee advertised the
        // level on its live session; otherwise it is created exactly as before (legacy).
        val signed = signedGroups
        if (signed != null) {
            // ERROR-095: peerGroupProtocol answers 1 for a device with no live session, which means "not known",
            // not "an old build". Such a group used to be created as legacy without a word, and a legacy group
            // cannot call a member the caller is not paired with, for good. Only a device seen on a live session
            // that reports the old level is a reason to create a legacy group.
            val old = memberIds.filter { peerGroupProtocol(it) < GroupPolicy.V2_PROTOCOL }
            if (old.isEmpty()) return createV2GroupLocked(signed, groupName, memberIds)
            val unknown = old.filter { peerIdentityKey(it).let { key -> key == null || key.isEmpty() } }
            if (unknown.isNotEmpty()) {
                FlashLog.w("CHAT", "Group not created: no live session to $unknown to learn whether they support signed groups")
                return FlashResult.Failure(FlashError.Unknown(membersOfflineMessage(unknown)))
            }
            FlashLog.i("CHAT", "Group created as legacy: $old are on a build without signed groups")
        }
        // Legacy groups keep their old limit: every shipped codec rejects a longer legacy roster.
        if (!GroupPolicy.validMemberIds(allMembers, localDeviceId)) {
            return FlashResult.Failure(
                FlashError.Unknown(
                    "Groups of more than ${GroupPolicy.MAX_MEMBERS} need every member on the latest Flash version",
                ),
            )
        }
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

    override suspend fun addGroupMembersAdvice(groupId: String, memberIds: Set<String>): String? =
        withContext(ioDispatcher) {
            if (!isV2Group(groupId)) return@withContext null
            val conversation = conversationDao.get(groupId) ?: return@withContext null
            if (conversation.groupCreatedBy == localDeviceId) return@withContext null
            val newcomers = memberIds.filter { id -> groupMemberDao?.member(groupId, id)?.isActive != true }
            if (newcomers.isEmpty()) return@withContext null
            GroupMembershipStatusText.newcomersNeedOwnerPairing(
                newcomers.map { peerNameResolver(it)?.ifBlank { null } ?: it.take(8) },
                ownerDisplayName(groupId, conversation),
            )
        }

    /** M-23, at most once per group per interval: tells the person why a group they were added to never appears. */
    private fun noticeOfferRefused(groupId: String, groupName: String, fromPeerId: String) {
        val callback = onGroupOfferRefused ?: return
        val now = timeSource.nowMs()
        val last = offerRefusedNoticeAt[groupId]
        if (last != null && now - last < OFFER_REFUSED_NOTICE_INTERVAL_MS) return
        offerRefusedNoticeAt[groupId] = now
        val title = groupName.trim().ifEmpty { "a group" }
        callback(groupId, title, GroupMembershipStatusText.addedButOwnerNotPaired(peerNameResolver(fromPeerId), title))
    }

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
        if (isV2Group(groupId)) return addV2MembersLocked(members, groupId, memberIds)
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
        leaveGroup(groupId, null)

    override suspend fun leaveGroup(groupId: String, successorId: String?): FlashResult<Unit> =
        withContext(ioDispatcher) { leaveGroupLocked(groupId, successorId) }

    /**
     * ADR-063: Promotes an active member to admin in a v2 group. Owner-only (or active admin if owner left).
     */
    override suspend fun promoteAdmin(groupId: String, deviceId: String): FlashResult<Unit> =
        withContext(ioDispatcher) { promoteAdminLocked(groupId, deviceId) }

    private suspend fun promoteAdminLocked(groupId: String, deviceId: String): FlashResult<Unit> {
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        val signed = signedGroups
            ?: return FlashResult.Failure(FlashError.Unknown("Signed groups are unavailable on this device"))
        if (!isV2Group(groupId)) {
            return FlashResult.Failure(FlashError.Unknown("Only groups made with the latest Flash version support admins"))
        }
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive }
        val isOwner = conversationDao.get(groupId)?.groupCreatedBy == localDeviceId
        val ownerActive = members.member(groupId, conversationDao.get(groupId)?.groupCreatedBy.orEmpty())?.isActive == true
        if (!isOwner && (ownerActive || self?.role != "admin")) {
            return FlashResult.Failure(FlashError.Unknown("Only the group owner can promote admins"))
        }
        val bundle = signed.promoteAdmin(groupId, deviceId)
            ?: return FlashResult.Failure(FlashError.Unknown("Could not promote this member to admin"))
        val targets = members.activeMembers(groupId).map { it.deviceId }.filter { it != localDeviceId }
        targets.distinct().forEach { groupTransportSink?.send(it, bundle) }
        FlashLog.i("CHAT", "Group v2 member promoted to admin: group=$groupId member=$deviceId")
        return FlashResult.Success(Unit)
    }

    /**
     * ADR-063: Demotes an active admin to regular member in a v2 group. Owner-only.
     */
    override suspend fun demoteAdmin(groupId: String, deviceId: String): FlashResult<Unit> =
        withContext(ioDispatcher) { demoteAdminLocked(groupId, deviceId) }

    private suspend fun demoteAdminLocked(groupId: String, deviceId: String): FlashResult<Unit> {
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        val signed = signedGroups
            ?: return FlashResult.Failure(FlashError.Unknown("Signed groups are unavailable on this device"))
        if (!isV2Group(groupId)) {
            return FlashResult.Failure(FlashError.Unknown("Only groups made with the latest Flash version support admins"))
        }
        if (conversationDao.get(groupId)?.groupCreatedBy != localDeviceId) {
            return FlashResult.Failure(FlashError.Unknown("Only the group owner can demote admins"))
        }
        val bundle = signed.demoteAdmin(groupId, deviceId)
            ?: return FlashResult.Failure(FlashError.Unknown("Could not demote this admin"))
        val targets = members.activeMembers(groupId).map { it.deviceId }.filter { it != localDeviceId }
        targets.distinct().forEach { groupTransportSink?.send(it, bundle) }
        FlashLog.i("CHAT", "Group v2 admin demoted to member: group=$groupId member=$deviceId")
        return FlashResult.Success(Unit)
    }

    /**
     * ADR-044 V2 (E5) / ADR-063: the owner or admin of a v2 group removes [deviceId].
     */
    override suspend fun removeGroupMember(groupId: String, deviceId: String): FlashResult<Unit> =
        withContext(ioDispatcher) { removeGroupMemberLocked(groupId, deviceId) }

    private suspend fun removeGroupMemberLocked(groupId: String, deviceId: String): FlashResult<Unit> {
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        val signed = signedGroups
        if (signed == null || !isV2Group(groupId)) {
            return FlashResult.Failure(FlashError.Unknown("Only groups made with the latest Flash version support removing members"))
        }
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive }
        val isOwner = conversationDao.get(groupId)?.groupCreatedBy == localDeviceId
        val isAdmin = self?.role == "admin"
        if (!isOwner && !isAdmin) {
            return FlashResult.Failure(FlashError.Unknown("Only the group owner or an admin can remove members"))
        }
        val removed = signed.removeMember(groupId, deviceId)
            ?: return FlashResult.Failure(FlashError.Unknown("That device is not a removable member of this group"))
        val targets = members.activeMembers(groupId).map { it.deviceId }.filter { it != localDeviceId } + deviceId
        targets.distinct().forEach { groupTransportSink?.send(it, removed.bundle) }
        FlashLog.i("CHAT", "Group v2 member removed: group=$groupId member=$deviceId")
        return FlashResult.Success(Unit)
    }

    /**
     * GM-6: Rotates the group secret ("Change group code"), invalidating prior invites while keeping
     * existing members unaffected. Owner or admin action.
     */
    override suspend fun changeGroupCode(groupId: String): FlashResult<Unit> =
        withContext(ioDispatcher) { changeGroupCodeLocked(groupId) }

    private suspend fun changeGroupCodeLocked(groupId: String): FlashResult<Unit> {
        val signed = signedGroups
            ?: return FlashResult.Failure(FlashError.Unknown("Signed groups are unavailable on this device"))
        if (!isV2Group(groupId)) {
            return FlashResult.Failure(FlashError.Unknown("Only groups made with the latest Flash version support changing group code"))
        }
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive }
        val isOwner = conversationDao.get(groupId)?.groupCreatedBy == localDeviceId
        val isAdmin = self?.role == "admin"
        if (!isOwner && !isAdmin) {
            return FlashResult.Failure(FlashError.Unknown("Only the group owner or an admin can change the group code"))
        }
        val rotation = signed.rotateGroupSecret(groupId, GroupRotation.REASON_MANUAL, emptyList())
            ?: return FlashResult.Failure(FlashError.Unknown("Failed to rotate group secret"))

        val targets = members.activeMembers(groupId).map { it.deviceId }.filter { it != localDeviceId }
        val bundle = signed.bundleFor(groupId)
        if (bundle != null) {
            targets.forEach { groupTransportSink?.send(it, bundle) }
        }
        FlashLog.i("CHAT", "Group secret rotated (change group code): group=$groupId newEpoch=${rotation.newEpoch}")
        touchConversationRefresh()
        return FlashResult.Success(Unit)
    }

    private suspend fun leaveGroupLocked(groupId: String, successorId: String? = null): FlashResult<Unit> {
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        if (isV2Group(groupId)) {
            val signed = signedGroups
                ?: return FlashResult.Failure(FlashError.Unknown("Signed groups are unavailable on this device"))
            val bundle = signed.leave(groupId, successorId)
                ?: return FlashResult.Failure(FlashError.Unknown("Unknown group"))
            members.activeMembers(groupId).filter { it.deviceId != localDeviceId }.forEach { target ->
                groupTransportSink?.send(target.deviceId, bundle)
            }
            return FlashResult.Success(Unit)
        }
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

    override suspend fun continueInNewGroup(groupId: String): FlashResult<String> =
        withContext(ioDispatcher) { continueInNewGroupLocked(groupId) }

    private suspend fun continueInNewGroupLocked(groupId: String): FlashResult<String> {
        val oldConv = conversationDao.get(groupId)
            ?: return FlashResult.Failure(FlashError.Unknown("Unknown group"))
        if (!oldConv.isGroup) {
            return FlashResult.Failure(FlashError.Unknown("Not a group conversation"))
        }
        val members = groupMemberDao
            ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        val active = members.activeMembers(groupId)
        if (active.none { it.deviceId == localDeviceId }) {
            return FlashResult.Failure(FlashError.Unknown("You are not an active member of this group"))
        }
        val invitees = active
            .map { it.deviceId }
            .filter { it != localDeviceId && it != oldConv.groupCreatedBy }
            .toSet()
        if (invitees.isEmpty()) {
            return FlashResult.Failure(FlashError.Unknown("No other active members to continue the group with"))
        }
        return createGroupLocked(oldConv.title, invitees)
    }

    private suspend fun createV2GroupLocked(
        signed: SignedGroups,
        groupName: String,
        memberIds: Set<String>,
    ): FlashResult<String> {
        val keys = inviteeKeys(signed, memberIds)
            ?: return FlashResult.Failure(FlashError.Unknown(V2_KEY_UNAVAILABLE))
        val created = signed.create(groupName, keys) { peerNameResolver(it) ?: it }
        groupTitleCache[created.groupId] = groupName
        FlashLog.i("CHAT", "Group v2 created: group=${created.groupId} members=${memberIds.size + 1}")
        memberIds.forEach { groupTransportSink?.send(it, created.bundle) }
        return FlashResult.Success(created.groupId)
    }

    /** Owner-only add for a v2 group: changed certs to existing members, the full set to newcomers. */
    private suspend fun addV2MembersLocked(
        members: GroupMemberDao,
        groupId: String,
        memberIds: Set<String>,
    ): FlashResult<Unit> {
        val signed = signedGroups
            ?: return FlashResult.Failure(FlashError.Unknown("Signed groups are unavailable on this device"))
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive }
        val isOwner = conversationDao.get(groupId)?.groupCreatedBy == localDeviceId
        val isAdmin = self?.role == "admin"
        val settings = signed.currentSettings(groupId)
        if (!isOwner && !isAdmin && !settings.membersMayAdd) {
            return FlashResult.Failure(FlashError.Unknown("Only the group owner or an admin can add members"))
        }
        val existing = members.activeMembers(groupId)
        if (existing.none { it.deviceId == localDeviceId }) {
            return FlashResult.Failure(FlashError.Unknown("You are not an active group member"))
        }
        val newcomers = memberIds.filter { id -> existing.none { it.deviceId == id } }.toSet()
        if (newcomers.isEmpty()) return FlashResult.Success(Unit)
        if (existing.size + newcomers.size > settings.maxMembers) {
            return FlashResult.Failure(FlashError.Unknown("Groups support at most ${settings.maxMembers} members"))
        }
        if (newcomers.any { peerGroupProtocol(it) < GroupPolicy.V2_PROTOCOL }) {
            return FlashResult.Failure(FlashError.Unknown("Update Flash on that device before adding it to this group"))
        }
        val keys = inviteeKeys(signed, newcomers)
            ?: return FlashResult.Failure(FlashError.Unknown(V2_KEY_UNAVAILABLE))
        val added = signed.addMembers(groupId, keys) { peerNameResolver(it) ?: it }
            ?: return FlashResult.Success(Unit)
        (members.activeMembers(groupId).map { it.deviceId } - localDeviceId).forEach { target ->
            groupTransportSink?.send(target, if (target in newcomers) added.full else added.changed)
        }
        return FlashResult.Success(Unit)
    }

    /**
     * Each invitee's key from its live session, or null unless every one is present and is the key
     * the trust store pinned for that device. The owner signs a cert over the key, so it never
     * signs one it cannot tie to a pin.
     */
    private fun inviteeKeys(signed: SignedGroups, memberIds: Collection<String>): Map<String, ByteArray>? {
        val keys = HashMap<String, ByteArray>()
        for (id in memberIds) {
            val key = peerIdentityKey(id)?.takeIf { it.isNotEmpty() && signed.keyMatchesPin(id, it) }
            if (key == null) {
                FlashLog.w("CHAT", "Group v2: no verified key for $id (offline, unencrypted session, or the key does not match its pin)")
                return null
            }
            keys[id] = key
        }
        return keys
    }

    override suspend fun groupMembers(groupId: String): List<FlashGroupMemberUi> =
        withContext(ioDispatcher) {
            val ownerName = ownerDisplayName(groupId, conversationDao.get(groupId))
            groupMemberDao?.activeMembers(groupId)
                ?.map { it.toMemberUi(introducedBy = introducedByOf(it, ownerName)) }
                .orEmpty()
        }

    override suspend fun getGroupSettings(groupId: String): GroupSettings =
        withContext(ioDispatcher) {
            signedGroups?.currentSettings(groupId)
                ?: groupSettingsDao?.getByGroupId(groupId)?.toSettings()
                ?: GroupSettings.defaults(groupId)
        }

    override suspend fun updateGroupSettings(
        groupId: String,
        joinPolicy: String?,
        inviteSharers: String?,
        maxMembers: Int?,
        swarmServing: Boolean?,
        membersMayAdd: Boolean?,
        historyCeiling: String?,
    ): FlashResult<Unit> =
        withContext(ioDispatcher) {
            val ceiling = if (historyCeiling == null) null else {
                GroupHistoryCeiling.fromName(historyCeiling)
                    ?: return@withContext FlashResult.Failure(FlashError.Unknown("Unknown history limit"))
            }
            val signed = signedGroups
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Signed groups are unavailable on this device"))
            val members = groupMemberDao
                ?: return@withContext FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
            val ownerId = conversationDao.get(groupId)?.groupCreatedBy
            // One seam decides who is an admin (ADR-100, O3): a later co-admin rule changes GroupAdminPolicy only.
            val roster = members.allMembers(groupId).map { GroupAdminPolicy.Member(it.deviceId, it.role, it.isActive) }
            val isAdmin = GroupAdminPolicy.canChangeHistoryCeiling(localDeviceId, ownerId, roster)
            if (!isAdmin) {
                return@withContext FlashResult.Failure(FlashError.Unknown("Only the group owner or an admin can update settings"))
            }
            val bundle = signed.updateSettings(
                groupId = groupId,
                joinPolicy = joinPolicy,
                inviteSharers = inviteSharers,
                maxMembers = maxMembers,
                swarmServing = swarmServing,
                membersMayAdd = membersMayAdd,
                historyCeiling = ceiling,
            ) ?: return@withContext FlashResult.Failure(FlashError.Unknown("Failed to sign or update group settings"))

            val activeOthers = members.activeMembers(groupId).map { it.deviceId } - localDeviceId
            activeOthers.forEach { target ->
                groupTransportSink?.send(target, bundle)
            }
            touchConversationRefresh()
            FlashResult.Success(Unit)
        }

    override suspend fun getGroupLocalPreferences(groupId: String): GroupLocalPreferences =
        withContext(ioDispatcher) {
            groupPreferencesDao?.getByGroupId(groupId)?.toPreferences()
                ?: GroupLocalPreferences.defaults(groupId)
        }

    override suspend fun updateGroupLocalPreferences(
        groupId: String,
        serveToGroup: Boolean?,
        serveWifiOnly: Boolean?,
        batteryThresholdPercent: Int?,
        keepAvailableDays: Int?,
        autoAcceptSizeBytes: Long?,
    ): FlashResult<Unit> =
        withContext(ioDispatcher) {
            // Read-modify-write of one row: without the lock two quick toggles of different fields both start from
            // the same stored row and the second upsert erases the first field's change.
            groupPreferencesLock.withLock {
                val current = getGroupLocalPreferences(groupId)
                val updated = current.copy(
                    serveToGroup = serveToGroup ?: current.serveToGroup,
                    serveWifiOnly = serveWifiOnly ?: current.serveWifiOnly,
                    batteryThresholdPercent = batteryThresholdPercent ?: current.batteryThresholdPercent,
                    keepAvailableDays = keepAvailableDays ?: current.keepAvailableDays,
                    autoAcceptSizeBytes = autoAcceptSizeBytes ?: current.autoAcceptSizeBytes,
                )
                groupPreferencesDao?.upsert(updated.toEntity())
            }
            touchConversationRefresh()
            FlashResult.Success(Unit)
        }

    /** The group owner's stored label, even after the owner left (their row stays as a tombstone). */
    private suspend fun ownerDisplayName(groupId: String, conversation: ConversationEntity?): String? =
        conversation?.groupCreatedBy
            ?.let { ownerId -> groupMemberDao?.member(groupId, ownerId)?.displayName }
            ?.takeIf { it.isNotBlank() }

    /**
     * ADR-044 V2: who introduced [member] to this device, or null when nobody had to. A member this device never
     * paired with is trusted in a v2 group only because the owner (whom it did pair with) certified their key.
     */
    private fun introducedByOf(member: GroupMemberEntity, ownerName: String?): String? =
        if (member.deviceId == localDeviceId || member.role == "owner" || isTrustedPeer(member.deviceId)) {
            null
        } else {
            ownerName ?: "the group owner"
        }

    /**
     * PC4 (ADR-046): the active member ids of every group this device is an active member of.
     * Presence sharing reads it to decide who counts as a fellow member.
     */
    public suspend fun activeGroupRosters(): List<Set<String>> =
        withContext(ioDispatcher) {
            val members = groupMemberDao ?: return@withContext emptyList()
            members.activeGroupIdsFor(localDeviceId).map { groupId ->
                members.activeMembers(groupId).mapTo(HashSet()) { it.deviceId }
            }
        }

    /** Phase B: one shared mapping so the sheet and the state carry identical rows. */
    private fun GroupMemberEntity.toMemberUi(isOnline: Boolean = false, introducedBy: String? = null): FlashGroupMemberUi =
        FlashGroupMemberUi(
            id = deviceId,
            name = displayName,
            initials = computeInitials(displayName),
            isOnline = isOnline,
            role = when (role) {
                "owner" -> FlashMemberRole.Owner
                "admin" -> FlashMemberRole.Admin
                else -> FlashMemberRole.Member
            },
            // Online members share our LAN/WS mesh; offline ones have no known transport.
            transport = if (isOnline) FlashNetworkTransport.Lan else FlashNetworkTransport.Unknown,
            introducedBy = introducedBy,
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
        if (isRemovedHere(members, conversation.id)) {
            // The composer is replaced by a notice, so this is only a stale draft or a race. Storing the row would leave
            // a PENDING message that every receiver drops and that this device would retry for good.
            FlashLog.w("CHAT", "Group message not sent: this device is no longer a member of ${conversation.id}")
            return
        }
        val recipients = members.activeMembers(conversation.id)
            .filter { it.deviceId != localDeviceId }
        if (recipients.isEmpty()) {
            FlashProbe.emit("group.msg.not_sent", "group" to FlashProbe.short(conversation.id), "reason" to "no_other_active_member")
            return
        }
        // v2: the signature is made once, here, and stored on the row, because the row is later
        // relayed by offline sync and the receiver has to be able to verify it.
        val signature = if (conversation.groupProto == GroupPolicy.V2_PROTOCOL) {
            val signed = signedGroups
            if (signed == null) {
                FlashLog.w("CHAT", "Group message not sent: ${conversation.id} is a signed group but this device cannot sign")
                return
            }
            signed.signMessage(conversation.id, localId, now, replyToId, replyToPreview, text)
        } else {
            null
        }
        // One transaction, like [enqueueDirectText]: a process kill between the message row and the outbox row used to
        // leave a PENDING group message the drain could not see. The conversation is bumped to the top of the list too
        // (audit 3.6): a group send used to leave the thread where it was, unlike every other send path.
        runInTransaction {
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
                    groupSig = signature,
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
            touchConversationInTransaction(conversation.id, now)
            draftDao.clear(conversation.id)
            outboxDao.enqueue(OutboxEntity(localId, nextAttemptAt = now, payloadJson = text, createdAt = now))
        }
        FlashProbe.emit(
            "group.msg.out",
            "group" to FlashProbe.short(conversation.id),
            "recipients" to recipients.size,
            "signed" to (signature != null),
        )
        notifyOutboxDrain()
        drainOutboxOnce()
    }

    override fun sendText(text: String) {
        val conversationId = activeConversationId ?: return
        sendTextTo(conversationId, text)
    }

    override fun sendTextTo(conversationId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val capped = if (trimmed.length > GroupPolicy.MAX_MESSAGE_TEXT_LENGTH) {
            FlashLog.w("CHAT", "sendText truncated from ${trimmed.length} to ${GroupPolicy.MAX_MESSAGE_TEXT_LENGTH}")
            trimmed.take(GroupPolicy.MAX_MESSAGE_TEXT_LENGTH)
        } else {
            trimmed
        }
        val now = timeSource.nowMs()
        val localId = UuidIdGenerator.newId()

        scope.launch(ioDispatcher) {
            val conversation = conversationDao.get(conversationId)
            if (conversation?.isGroup == true) {
                sendGroupText(conversation, capped, now, localId, replyToId = null, replyToPreview = null)
                return@launch
            }
            enqueueDirectText(conversationId, capped, now, localId, replyToId = null, replyToPreview = null)
            notifyOutboxDrain()
            drainOutboxOnce()
        }
    }

    override fun sendReply(text: String, replyToId: String, replyToPreview: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val capped = if (trimmed.length > GroupPolicy.MAX_MESSAGE_TEXT_LENGTH) {
            FlashLog.w("CHAT", "sendReply truncated from ${trimmed.length} to ${GroupPolicy.MAX_MESSAGE_TEXT_LENGTH}")
            trimmed.take(GroupPolicy.MAX_MESSAGE_TEXT_LENGTH)
        } else {
            trimmed
        }
        val conversationId = activeConversationId ?: return
        val now = timeSource.nowMs()
        val localId = UuidIdGenerator.newId()

        scope.launch(ioDispatcher) {
            val conversation = conversationDao.get(conversationId)
            if (conversation?.isGroup == true) {
                sendGroupText(conversation, capped, now, localId, replyToId, replyToPreview)
                return@launch
            }
            enqueueDirectText(conversationId, capped, now, localId, replyToId, replyToPreview)
            notifyOutboxDrain()
            drainOutboxOnce()
        }
    }

    override fun retryMessage(localId: String) {
        scope.launch(ioDispatcher) {
            val message = messageDao.getByLocalId(localId) ?: return@launch
            if (message.senderId != localDeviceId || message.deletedAt != null) return@launch
            val now = timeSource.nowMs()
            messageDao.updateStatusIfUnacknowledged(localId, "PENDING")
            val isGroup = conversationDao.get(message.conversationId)?.isGroup == true
            if (isGroup) {
                val deliveries = groupDeliveryDao
                if (deliveries != null) {
                    val pending = deliveries.pendingForMessage(localId)
                    for (del in pending) {
                        deliveries.reschedule(localId, del.memberId, "PENDING", now)
                    }
                }
            }
            outboxDao.enqueue(
                OutboxEntity(
                    localId = localId,
                    attempts = 0,
                    createdAt = now,
                    nextAttemptAt = now,
                    payloadJson = message.text,
                ),
            )
            notifyOutboxDrain()
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
            // peer's device UUID. The stored row's read cursor, pin and mute are kept (ERROR-087).
            upsertDirectConversation(
                conversationId = conversationId,
                title = peerNameResolver(conversationId)?.ifBlank { null },
                sortOrder = now,
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

    override fun setMessagePinned(messageId: String, pinned: Boolean) {
        val conversationId = activeConversationId ?: return
        val dao = messagePinDao ?: return
        scope.launch(ioDispatcher) {
            if (pinned) {
                dao.upsert(MessagePinEntity(conversationId, messageId, timeSource.nowMs()))
            } else {
                dao.unpin(conversationId, messageId)
            }
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
            val conv = conversationDao.get(conversationId)
            if (conv?.isGroup != true) return@launch
            if (members != null && isRemovedHere(members, conversationId)) return@launch
            val cached = pendingGroupAttachmentSignatures.remove(messageId)
            val sentAt = cached?.first ?: now
            // The offer's signature must cover THIS row's sentAt (ERROR-108): a catch-up receiver checks it against the
            // row it is given. An announcement already signed it so; an offer recorded without one (ERROR-117) carries
            // the origin's own-clock signature, so it is signed again here.
            val swarmOffer = pendingSwarmOffers.remove(messageId)?.let { (root, pieceSize, sig) ->
                Triple(
                    root,
                    pieceSize,
                    signedGroups?.signSwarmAnnouncement(conversationId, messageId, root, sizeBytes, fileName, mimeType, sentAt) ?: sig,
                )
            }
            val signature = cached?.second ?: if (conv.groupProto == GroupPolicy.V2_PROTOCOL) {
                val signed = signedGroups
                if (signed == null) {
                    FlashLog.w("CHAT", "Group attachment not sent: $conversationId is a signed group but this device cannot sign")
                    return@launch
                }
                signed.signMessage(conversationId, messageId, sentAt, null, null, "")
            } else {
                null
            }
            // ADR-044 V2 (E3): a file pushed whole is paired-only, so a vouched member is not a recipient of one and
            // cannot leave the message PENDING for a delivery that will never be attempted. A swarm offer is different
            // (ERROR-117): the swarm gate admits any active member, so a vouched member with a live, key-matching
            // session was announced to and will answer with a receipt; it is a recipient like a paired one.
            val recipients = members?.activeMembers(conversationId)
                ?.filter {
                    it.deviceId != localDeviceId &&
                        (isTrustedPeer(it.deviceId) || (swarmOffer != null && isGroupPeerTrusted(conversationId, it.deviceId)))
                }
                .orEmpty()
            val activeOthers = members?.activeMembers(conversationId).orEmpty().count { it.deviceId != localDeviceId }
            FlashProbe.emit(
                "group.media.out",
                "group" to FlashProbe.short(conversationId),
                "swarm" to (swarmOffer != null),
                "recipients" to recipients.size,
                "activeMembers" to activeOthers,
                "notAnnounced" to (activeOthers - recipients.size),
                "signed" to (signature != null),
            )
            messageDao.insert(
                MessageEntity(
                    localId = messageId,
                    conversationId = conversationId,
                    senderId = localDeviceId,
                    senderName = localDisplayName,
                    text = rowText,
                    sentAt = sentAt,
                    status = if (recipients.isEmpty()) "SENT" else "PENDING",
                    attachmentTransferId = transferId,
                    attachmentName = fileName,
                    attachmentMime = mimeType,
                    attachmentSize = sizeBytes,
                    attachmentPath = localPath,
                    groupSig = signature,
                    swarmRoot = swarmOffer?.first,
                    swarmPieceSize = swarmOffer?.second,
                    swarmRootSig = swarmOffer?.third,
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
                            // R-10: the same bound as every other inbound group row (a far-future stamp used to pin the row).
                            sentAt = storedSentAt(media.sentAt.takeIf { it > 0 } ?: now, now, signed = media.signature != null),
                            status = "DELIVERED",
                            attachmentTransferId = transferId,
                            attachmentName = media.fileName.ifBlank { fileName },
                            attachmentMime = media.mimeType.ifBlank { mimeType },
                            attachmentSize = if (media.sizeBytes > 0) media.sizeBytes else sizeBytes,
                            attachmentPath = null,
                            groupSig = media.signature,
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
        if (frame is GroupWireFrame.GsHello ||
            frame is GroupWireFrame.GsChallenge ||
            frame is GroupWireFrame.GsProof ||
            frame is GroupWireFrame.GsResult
        ) {
            if (peerDeviceId != frame.from) return
            groupProofSessions?.let { sessions ->
                when (frame) {
                    is GroupWireFrame.GsHello -> sessions.onHello(peerDeviceId, frame)
                    is GroupWireFrame.GsChallenge -> sessions.onChallenge(peerDeviceId, frame)
                    is GroupWireFrame.GsProof -> sessions.onProof(peerDeviceId, frame)
                    is GroupWireFrame.GsResult -> sessions.onResult(peerDeviceId, frame)
                    else -> Unit
                }
            }
            return
        }

        if (frame is GroupWireFrame.GsJoinRequest) {
            if (peerDeviceId != frame.from) return
            handleInboundJoinRequest(peerDeviceId, frame)
            return
        }

        if (frame is GroupWireFrame.GsJoinDecision) {
            if (peerDeviceId != frame.from) return
            handleInboundJoinDecision(peerDeviceId, frame)
            return
        }

        if (frame is GroupWireFrame.GsRosterPreview) {
            if (peerDeviceId != frame.from) return
            handleInboundRosterPreview(peerDeviceId, frame)
            return
        }

        val isTrusted = isGroupPeerTrusted(frame.groupId, peerDeviceId) ||
            (frame is GroupWireFrame.Bundle && acceptedInviteGroupIds.contains(frame.groupId))
        if (peerDeviceId != frame.from || !isTrusted) {
            if (frame is GroupWireFrame.Bundle) {
                FlashLog.w("CHAT", "Group bundle dropped at the gate: group=${frame.groupId} peer=$peerDeviceId from=${frame.from} trusted=$isTrusted")
            }
            return
        }
        val members = groupMemberDao ?: return
        if (frame is GroupWireFrame.Membership && GroupPolicy.isV2GroupId(frame.groupId)) {
            // ADR-044 V1 (D1): the `g2-` namespace belongs to signed groups. A legacy frame for it can
            // only be an attempt to pre-empt or overwrite one.
            FlashLog.w("CHAT", "SECURITY: legacy group membership frame dropped for v2 id ${frame.groupId} (from ${frame.from})")
            return
        }
        when (frame) {
            is GroupWireFrame.Create -> {
                // ADR-044 V1a (F-1): a group id is created once. A Create for an id this device
                // already knows would otherwise rename or re-own someone else's group.
                if (isKnownGroup(members, frame.groupId)) {
                    FlashLog.w("CHAT", "Group Create ignored: group ${frame.groupId} is already known (from ${frame.from})")
                    return
                }
                if (localDeviceId !in frame.memberIds ||
                    !GroupPolicy.validMemberIds(frame.memberIds, frame.from) ||
                    frame.memberIds.any { it != localDeviceId && !isTrustedPeer(it) }
                ) return
                val name = GroupPolicy.normalizedName(frame.name) ?: return
                groupTitleCache[frame.groupId] = name
                runInTransaction {
                    upsertLegacyGroupConversation(
                        groupId = frame.groupId,
                        title = name,
                        sortOrder = frame.membershipVersion,
                        createdBy = frame.from,
                        createdAt = frame.membershipVersion,
                    )
                }
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
                // A roster carries this device as an ACTIVE member (a `State` may now also carry
                // leave tombstones for others) and names each device once. The old uniqueness
                // check compared the list's size with itself, so a repeated id was accepted and
                // the later entry applied.
                val roster = frame.members
                val rosterIds = roster.map { it.deviceId }
                if (roster.none { it.deviceId == localDeviceId && it.isActive } ||
                    roster.none { it.deviceId == frame.from && it.isActive } ||
                    rosterIds.toSet().size != rosterIds.size
                ) return
                if (roster.size > GroupPolicy.MAX_MEMBERS) return
                val name = GroupPolicy.normalizedName(frame.name) ?: return
                // F7: keep an existing row's provenance and list position. A `State` is no longer
                // a one-shot bootstrap - [reconcileGroupMembership] re-sends it on every
                // session-up - so re-stamping sortOrder/groupCreatedAt here would shuffle the chat
                // list and rewrite the group's creation time on every reconnect.
                val existingGroupConversation = conversationDao.get(frame.groupId)
                // G7: only a group this device had no record of is a join; a returning member with zero rows is not.
                val wasNewHere = existingGroupConversation == null && members.member(frame.groupId, localDeviceId) == null
                // ADR-044 V1a (F-2): once this device has a record of the group, only a member it
                // already knows as active may reconcile it. No record = the bootstrap above.
                // Accepted cost: a member this device has not yet learned about cannot teach it
                // the roster; the owner's next session-up `State` does, and V1 signatures remove
                // the limit.
                if ((existingGroupConversation != null || members.member(frame.groupId, localDeviceId) != null) &&
                    !isActiveTrustedMember(members, frame.groupId, frame.from)
                ) {
                    FlashLog.w("CHAT", "Group State ignored: ${frame.from} is not an active member of ${frame.groupId}")
                    return
                }
                // The owner is whoever this device recorded at creation; a later `State` cannot
                // re-own the group, and only the owner is "owner" (the wire carries a free-form role).
                val creatorId = existingGroupConversation?.groupCreatedBy ?: frame.creatorId
                groupTitleCache[frame.groupId] = name
                // ERROR-087: this frame is re-sent on every session-up; replacing the row reset its read cursor, pin,
                // mute and archive state each time.
                runInTransaction {
                    upsertLegacyGroupConversation(
                        groupId = frame.groupId,
                        title = name,
                        sortOrder = frame.membershipVersion,
                        createdBy = creatorId,
                        createdAt = frame.membershipVersion,
                    )
                }
                roster.forEach { entry ->
                    applyMembership(
                        members,
                        GroupMemberEntity(
                            groupId = frame.groupId,
                            deviceId = entry.deviceId,
                            displayName = if (entry.deviceId == localDeviceId) localDisplayName else entry.displayName,
                            role = if (entry.deviceId == creatorId) "owner" else "member",
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
                requestGroupCatchUp(frame.groupId, newlyJoined = wasNewHere)
            }
            is GroupWireFrame.Message -> {
                if (!isActiveGroupMember(members, frame.groupId, frame.from)) {
                    probeGroupMessageDrop("group.msg.drop", frame.groupId, frame.from, "not_active_member", frame.sentAt)
                    return
                }
                if (frame.text.length > GroupPolicy.MAX_MESSAGE_TEXT_LENGTH) {
                    probeGroupMessageDrop("group.msg.drop", frame.groupId, frame.from, "too_long", frame.sentAt)
                    return
                }
                // v2: the author must sign the message, and the name shown is the roster's signed label.
                var senderName: String? = frame.senderName
                var groupSig: String? = null
                if (isV2Group(frame.groupId)) {
                    val label = signedGroups?.verifiedAuthorLabel(
                        frame.groupId, frame.from, frame.messageId, frame.sentAt,
                        frame.replyToId, frame.replyToPreview, frame.text, frame.signature,
                    )
                    if (label == null) {
                        probeGroupMessageDrop("group.msg.drop", frame.groupId, frame.from, "bad_signature", frame.sentAt)
                        FlashLog.w("CHAT", "SECURITY: group message dropped, no valid signature (group=${frame.groupId} msg=${frame.messageId} from=${frame.from})")
                        return
                    }
                    senderName = label
                    groupSig = frame.signature
                }
                val now = timeSource.nowMs()
                val boundedSentAt = storedSentAt(frame.sentAt, now, signed = groupSig != null)
                val inserted = messageDao.insert(
                    MessageEntity(
                        localId = frame.messageId,
                        conversationId = frame.groupId,
                        senderId = frame.from,
                        senderName = senderName,
                        text = frame.text,
                        sentAt = boundedSentAt,
                        status = "DELIVERED",
                        replyToId = frame.replyToId,
                        replyToPreview = frame.replyToPreview,
                        groupSig = groupSig,
                    ),
                )
                FlashProbe.emit(
                    "group.msg.in",
                    "group" to FlashProbe.short(frame.groupId),
                    "from" to FlashProbe.short(frame.from),
                    "signed" to (groupSig != null),
                    "skewMs" to (frame.sentAt - now),
                    "stored" to (inserted != -1L),
                )
                if (inserted != -1L) {
                    onInboundTextMessageWithGroupTitle(
                        frame.groupId,
                        senderName,
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
                if (!isActiveGroupMember(members, frame.groupId, frame.from)) return
                recordGroupDelivery(frame.messageId, frame.from, frame.deliveredAt)
            }
            is GroupWireFrame.Read -> {
                if (!isActiveGroupMember(members, frame.groupId, frame.from)) return
                onGroupRead(members, frame.groupId, frame.from, frame.upToMessageId)
            }
            is GroupWireFrame.DeleteForEveryone -> {
                if (!isActiveGroupMember(members, frame.groupId, frame.from)) return
                val now = timeSource.nowMs()
                val message = messageDao.getByLocalId(frame.messageId)
                if (message != null) {
                    if (message.conversationId != frame.groupId || message.senderId != frame.from) return
                    messageDao.markDeleted(frame.messageId, now)
                } else {
                    messageDao.insert(
                        MessageEntity(
                            localId = frame.messageId,
                            conversationId = frame.groupId,
                            senderId = frame.from,
                            senderName = null,
                            text = "",
                            sentAt = now,
                            status = "DELIVERED",
                            deletedAt = now,
                        ),
                    )
                }
                messageDao.clearReplyPreviews(frame.messageId)
                outboxDao.delete(frame.messageId)
            }
            is GroupWireFrame.Bundle -> {
                val signed = signedGroups
                if (signed == null) {
                    FlashLog.w("CHAT", "Group bundle ignored: signed groups are not enabled on this device (group ${frame.groupId})")
                    return
                }
                val outcome = signed.onBundle(peerDeviceId, frame)
                if (outcome is SignedGroups.BundleOutcome.Ignored && outcome.reason == "charter:owner-not-paired") {
                    noticeOfferRefused(frame.groupId, frame.charter.name, peerDeviceId)
                }
                if (outcome is SignedGroups.BundleOutcome.Applied) {
                    val selfActive = groupMemberDao?.member(frame.groupId, localDeviceId)?.isActive == true
                    if (outcome.joined || selfActive) {
                        FlashLog.i("CHAT", "Group v2 joined: group=${frame.groupId} owner=${frame.charter.ownerId} from=$peerDeviceId")
                        groupTitleCache[frame.groupId] = frame.charter.name
                        groupInviteDao?.updateState(frame.groupId, "JOINED")
                        pendingInviteHints.remove(frame.groupId)
                        hintsExhausted.remove(frame.groupId)
                        // The group is new here and holds no messages, and F3 sync only fires on a
                        // session-up edge: ask for history now, as a legacy bootstrap does.
                        requestGroupCatchUp(frame.groupId, newlyJoined = outcome.joined)
                    }
                    if (outcome.needsSecret && outcome.rotation != null) {
                        if (groupGate.allows(frame.groupId, peerDeviceId, GroupTraffic.CHAT)) {
                            groupTransportSink?.send(
                                peerDeviceId,
                                GroupWireFrame.GsSecretRequest(
                                    groupId = frame.groupId,
                                    from = localDeviceId,
                                    epoch = outcome.rotation.newEpoch,
                                ),
                            )
                        }
                    }
                    if (outcome.winningRotation != null) {
                        groupTransportSink?.send(
                            peerDeviceId,
                            GroupWireFrame.GsStale(
                                groupId = frame.groupId,
                                from = localDeviceId,
                                epoch = outcome.winningRotation.newEpoch,
                                rotation = outcome.winningRotation,
                            ),
                        )
                    }
                    if (outcome.reRotated != null) {
                        broadcastRotation(frame.groupId, outcome.reRotated)
                    }
                }
            }
            is GroupWireFrame.Sync -> {
                if (!isActiveGroupMember(members, frame.groupId, frame.from)) return
                when (frame) {
                    is GroupWireFrame.SyncRequest -> handleSyncRequest(frame)
                    is GroupWireFrame.SyncClaim -> handleSyncClaim(frame)
                    is GroupWireFrame.SyncPush -> handleSyncPush(frame)
                    is GroupWireFrame.SyncAck -> handleSyncAck(frame)
                    // G4: the marker may wait up to SYNC_PAGE_WAIT_MS for its pushes; it must not hold this session's
                    // inbound pipeline (call signalling shares it) while it does.
                    is GroupWireFrame.SyncPage -> {
                        scope.launch(ioDispatcher) { handleSyncPage(frame) }
                    }
                }
            }
            is GroupWireFrame.GroupMedia -> {
                // ERROR-117: a swarm offer may come from a vouched member (the sender need not be paired with this
                // device: the swarm gate admits any active member). A whole-file push stays paired-only (ADR-044 E3).
                val senderAllowed = if (frame.swarm == 1) {
                    isActiveGroupMember(members, frame.groupId, frame.from)
                } else {
                    isActiveTrustedMember(members, frame.groupId, frame.from)
                }
                if (isRemovedHere(members, frame.groupId) || !senderAllowed) {
                    probeGroupMessageDrop(
                        "group.media.drop", frame.groupId, frame.from,
                        if (isRemovedHere(members, frame.groupId)) "removed_here" else "sender_not_allowed", frame.sentAt,
                        "swarm" to frame.swarm,
                    )
                    return
                }
                var senderName = frame.senderName
                var groupSig: String? = null
                if (isV2Group(frame.groupId)) {
                    val label = signedGroups?.verifiedAuthorLabel(
                        frame.groupId, frame.from, frame.messageId, frame.sentAt,
                        null, null, "", frame.signature,
                    )
                    if (label == null) {
                        probeGroupMessageDrop("group.media.drop", frame.groupId, frame.from, "bad_signature", frame.sentAt, "swarm" to frame.swarm)
                        FlashLog.w("CHAT", "SECURITY: group media dropped, no valid signature (group=${frame.groupId} msg=${frame.messageId} from=${frame.from})")
                        return
                    }
                    senderName = label
                    groupSig = frame.signature
                }
                val isSwarmOffer = frame.swarm == 1 &&
                    frame.root != null &&
                    frame.pieceSize != null &&
                    frame.rootSig != null &&
                    swarmAnnouncementListener != null &&
                    (signedGroups == null || signedGroups.verifySwarmAnnouncement(
                        frame.groupId, frame.from, frame.messageId, frame.root,
                        frame.sizeBytes, frame.fileName, frame.mimeType, frame.sentAt, frame.rootSig
                    ))

                if (isSwarmOffer) {
                    val now = timeSource.nowMs()
                    FlashProbe.emit(
                        "swarm.offer.in",
                        "group" to FlashProbe.short(frame.groupId),
                        "from" to FlashProbe.short(frame.from),
                        "via" to "live",
                        "paired" to isTrustedPeer(frame.from),
                        "skewMs" to (frame.sentAt - now),
                    )
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
                        if (claimedGroupMedia.add(frame.transferId)) {
                            val insertedRowId = messageDao.insert(
                                MessageEntity(
                                    localId = frame.messageId,
                                    conversationId = frame.groupId,
                                    senderId = frame.from,
                                    senderName = senderName,
                                    text = "",
                                    sentAt = storedSentAt(frame.sentAt.takeIf { it > 0 } ?: now, now, signed = groupSig != null || frame.rootSig != null),
                                    status = "DELIVERED",
                                    // ERROR-108: the swarm's row id is the message id, not this recipient's transfer id.
                                    // A bubble keyed by the transfer id never found its row, so it showed no progress and
                                    // Accept / Pause / Cancel on it reached nothing.
                                    attachmentTransferId = frame.messageId,
                                    attachmentName = frame.fileName,
                                    attachmentMime = frame.mimeType,
                                    attachmentSize = frame.sizeBytes,
                                    attachmentPath = null,
                                    groupSig = groupSig,
                                    swarmRoot = frame.root,
                                    swarmPieceSize = frame.pieceSize,
                                    swarmRootSig = frame.rootSig,
                                ),
                            )
                            touchConversation(frame.groupId, now)
                            if (insertedRowId != -1L) {
                                runCatching {
                                    onInboundAttachmentWithGroupTitle(
                                        frame.groupId,
                                        senderName,
                                        frame.fileName,
                                        frame.mimeType,
                                        conversationDao.get(frame.groupId)?.title?.ifBlank { null },
                                    )
                                }
                            }
                        }
                    }
                    swarmAnnouncementListener?.onSwarmAnnouncement(
                        groupId = frame.groupId,
                        messageId = frame.messageId,
                        // The swarm row's id is the message id (see the row insert above), so that is what the host gets.
                        transferId = frame.messageId,
                        from = frame.from,
                        root = frame.root!!,
                        pieceSize = frame.pieceSize!!,
                        totalSize = frame.sizeBytes,
                        fileName = frame.fileName,
                        mimeType = frame.mimeType,
                        sentAt = frame.sentAt,
                        rootSig = frame.rootSig!!,
                    )
                    return
                }
                if (!isTrustedPeer(frame.from)) {
                    // A vouched sender's swarm offer this device cannot use (swarm off, or the offer did not verify): no
                    // whole-file push will ever follow from a device it is not paired with, so park nothing.
                    FlashProbe.emit(
                        "swarm.offer.ignored",
                        "group" to FlashProbe.short(frame.groupId),
                        "from" to FlashProbe.short(frame.from),
                        "via" to "live",
                        "reason" to when {
                            frame.swarm != 1 -> "not_a_swarm_offer"
                            frame.root == null || frame.pieceSize == null || frame.rootSig == null -> "incomplete_offer"
                            swarmAnnouncementListener == null -> "swarm_off_here"
                            else -> "bad_announcement_signature"
                        },
                    )
                    FlashLog.w("CHAT", "Group media from unpaired ${frame.from} ignored: not a usable swarm offer (group=${frame.groupId} msg=${frame.messageId})")
                    return
                }
                pendingGroupMedia[frame.transferId] = frame.copy(senderName = senderName)
                val now = timeSource.nowMs()
                FlashProbe.emit(
                    "group.media.in",
                    "group" to FlashProbe.short(frame.groupId),
                    "from" to FlashProbe.short(frame.from),
                    "kind" to (if (frame.swarm == 1) "swarm_offer_unusable_parked_as_file" else "whole_file"),
                    "skewMs" to (frame.sentAt - now),
                )
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
                        val moved = try {
                            messageDao.updateGroupContext(
                                transferId = frame.transferId,
                                groupId = frame.groupId,
                                messageId = frame.messageId,
                                senderId = frame.from,
                                senderName = senderName,
                            )
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            // R-08: a failing UPDATE must not take the receive coroutine down with it.
                            FlashLog.w("CHAT", "Group media re-attribution failed (group=${frame.groupId} msg=${frame.messageId}): ${e::class.simpleName}")
                            0
                        }
                        if (moved == 0) {
                            FlashLog.w("CHAT", "Group media for transfer ${frame.transferId} not re-attributed: the stored row is not ${frame.from}'s or the message id is taken (group=${frame.groupId})")
                        }
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
                                senderName = senderName,
                                text = "",
                                sentAt = storedSentAt(frame.sentAt.takeIf { it > 0 } ?: now, now, signed = groupSig != null),
                                status = "DELIVERED",
                                attachmentTransferId = frame.transferId,
                                attachmentName = frame.fileName,
                                attachmentMime = frame.mimeType,
                                attachmentSize = frame.sizeBytes,
                                attachmentPath = null,
                                groupSig = groupSig,
                            ),
                        )
                        touchConversation(frame.groupId, now)
                        if (insertedRowId != -1L) {
                            runCatching {
                                onInboundAttachmentWithGroupTitle(
                                    frame.groupId,
                                    senderName,
                                    frame.fileName,
                                    frame.mimeType,
                                    conversationDao.get(frame.groupId)?.title?.ifBlank { null },
                                )
                            }
                        }
                    }
                }
            }
            is GroupWireFrame.GsHello,
            is GroupWireFrame.GsChallenge,
            is GroupWireFrame.GsProof,
            is GroupWireFrame.GsResult,
            is GroupWireFrame.GsJoinRequest,
            is GroupWireFrame.GsJoinDecision,
            is GroupWireFrame.GsRosterPreview -> Unit
            is GroupWireFrame.GsStale -> handleInboundGsStale(peerDeviceId, frame)
            is GroupWireFrame.GsSecretRequest -> handleInboundGsSecretRequest(peerDeviceId, frame)
            is GroupWireFrame.GsSecret -> handleInboundGsSecret(peerDeviceId, frame)
        }
    }

    private suspend fun broadcastRotation(groupId: String, rotation: GroupRotation) {
        val members = groupMemberDao ?: return
        val active = members.activeMembers(groupId)
        for (m in active) {
            if (m.deviceId == localDeviceId) continue
            groupTransportSink?.send(
                m.deviceId,
                GroupWireFrame.GsStale(
                    groupId = groupId,
                    from = localDeviceId,
                    epoch = rotation.newEpoch,
                    rotation = rotation,
                ),
            )
        }
    }

    private suspend fun handleInboundGsStale(peerDeviceId: String, frame: GroupWireFrame.GsStale) {
        if (peerDeviceId != frame.from) return
        val signed = signedGroups ?: return
        val outcome = signed.handleIncomingRotation(frame.groupId, frame.rotation, peerDeviceId)
        when (outcome) {
            is SignedGroups.RotationOutcome.Applied -> {
                if (outcome.needsSecret) {
                    if (groupGate.allows(frame.groupId, peerDeviceId, GroupTraffic.CHAT)) {
                        groupTransportSink?.send(
                            peerDeviceId,
                            GroupWireFrame.GsSecretRequest(
                                groupId = frame.groupId,
                                from = localDeviceId,
                                epoch = outcome.rotation.newEpoch,
                            ),
                        )
                    }
                }
                if (outcome.reRotated != null) {
                    broadcastRotation(frame.groupId, outcome.reRotated)
                }
            }
            is SignedGroups.RotationOutcome.WonConcurrently -> {
                groupTransportSink?.send(
                    peerDeviceId,
                    GroupWireFrame.GsStale(
                        groupId = frame.groupId,
                        from = localDeviceId,
                        epoch = outcome.winningRotation.newEpoch,
                        rotation = outcome.winningRotation,
                    ),
                )
            }
            is SignedGroups.RotationOutcome.Ignored -> Unit
        }
    }

    private suspend fun handleInboundGsSecretRequest(peerDeviceId: String, frame: GroupWireFrame.GsSecretRequest) {
        if (peerDeviceId != frame.from) return
        // Check allows(CHAT) AT THAT MOMENT (task 3)
        if (!groupGate.allows(frame.groupId, peerDeviceId, GroupTraffic.CHAT)) {
            FlashLog.w("CHAT", "SECURITY: GsSecretRequest refused: not allowed CHAT at this moment (group=${frame.groupId} peer=$peerDeviceId)")
            return
        }

        // SW-0 (2026-10-04, security 10.2): never to a device id named in removedIds of any rotation notice held
        val rotations = groupRotationDao?.getAllForGroup(frame.groupId).orEmpty()
        val isRemoved = rotations.any { rot ->
            rot.removedIds.split(',').map { it.trim() }.contains(peerDeviceId)
        }
        if (isRemoved) {
            FlashLog.w("CHAT", "SECURITY: GsSecretRequest refused: peer $peerDeviceId is in removedIds for group=${frame.groupId}")
            return
        }

        val secretStore = groupSecretStore ?: return
        val record = secretStore.get(frame.groupId, frame.epoch) ?: return
        val response = GroupWireFrame.GsSecret(
            groupId = frame.groupId,
            from = localDeviceId,
            epoch = frame.epoch,
            secret = record.secret.toByteArray(),
        )
        groupTransportSink?.send(peerDeviceId, response)
    }

    private suspend fun handleInboundGsSecret(peerDeviceId: String, frame: GroupWireFrame.GsSecret) {
        if (peerDeviceId != frame.from) return
        if (!groupGate.allows(frame.groupId, peerDeviceId, GroupTraffic.CHAT)) {
            FlashLog.w("CHAT", "SECURITY: GsSecret dropped: peer $peerDeviceId not allowed CHAT at this moment (group=${frame.groupId})")
            return
        }
        if (frame.secret.size != GroupSecret.SECRET_SIZE_BYTES) {
            FlashLog.w("CHAT", "GsSecret dropped: invalid secret size (${frame.secret.size})")
            return
        }

        val rotationDao = groupRotationDao ?: return
        val rotation = rotationDao.getByGroupAndEpoch(frame.groupId, frame.epoch) ?: run {
            FlashLog.w("CHAT", "GsSecret dropped: no rotation notice for group=${frame.groupId} epoch=${frame.epoch}")
            return
        }

        val candidateSecret = GroupSecret.fromBytes(frame.secret)
        val matches = GroupSecretCommit.matchesHex(
            commitHex = rotation.commit,
            groupId = frame.groupId,
            epoch = frame.epoch,
            secret = candidateSecret,
        )
        if (!matches) {
            FlashLog.w("CHAT", "SECURITY: GsSecret commit check failed for group=${frame.groupId} epoch=${frame.epoch} from=$peerDeviceId")
            return
        }

        val secretStore = groupSecretStore ?: return
        val now = timeSource.nowMs()
        val record = StoredGroupSecret(
            groupId = frame.groupId,
            epoch = frame.epoch,
            secret = candidateSecret,
            commit = rotation.commit,
            source = GroupSecretSource.HANDOVER,
            receivedAtMs = now,
        )
        secretStore.put(record)
        FlashLog.i("CHAT", "Stored handed-over group secret: group=${frame.groupId} epoch=${frame.epoch} from=$peerDeviceId")
    }

    private suspend fun handleInboundJoinRequest(peerDeviceId: String, frame: GroupWireFrame.GsJoinRequest) {
        val crypto = groupCrypto ?: return
        val members = groupMemberDao ?: return
        val isDirect = (peerDeviceId == frame.subjectId)

        if (isDirect) {
            // Direct request from joiner
            // 1. Session must have proved knowledge of group secret (GINV-2)
            if (groupProofSessions?.hasProved(peerDeviceId, frame.groupId) != true) {
                FlashLog.w("CHAT", "SECURITY: GsJoinRequest dropped: session $peerDeviceId has not proved group ${frame.groupId}")
                return
            }
            // 2. subjectKey must be the live session key
            val liveKey = peerIdentityKey(peerDeviceId)
            if (liveKey == null || liveKey.isEmpty()) {
                FlashLog.w("CHAT", "SECURITY: GsJoinRequest dropped: no live key for $peerDeviceId")
                return
            }
            val encodedLiveKey = GroupCanonical.encode(liveKey)
            if (frame.subjectKey != encodedLiveKey) {
                FlashLog.w("CHAT", "SECURITY: GsJoinRequest dropped: subjectKey does not match live key for $peerDeviceId")
                return
            }
        } else {
            // Forwarded request from another peer - forwarder must be an active group member
            val forwarder = members.member(frame.groupId, peerDeviceId)
            if (forwarder?.isActive != true) {
                FlashLog.w("CHAT", "SECURITY: GsJoinRequest dropped: forwarder $peerDeviceId is not active member of ${frame.groupId}")
                return
            }
        }

        // Verify cryptographic signature against subjectKey (joiner's key)
        val subjectKeyBytes = GroupCanonical.decode(frame.subjectKey) ?: return
        val sigBytes = GroupCanonical.decode(frame.signature) ?: return
        val reqBytes = GroupCanonical.joinRequestBytes(
            groupId = frame.groupId,
            epoch = frame.epoch,
            subjectId = frame.subjectId,
            subjectKeyBase64 = frame.subjectKey,
            label = frame.label,
            requestedAtMs = frame.requestedAtMs,
        ) ?: return
        if (!crypto.verify(sigBytes, reqBytes, subjectKeyBytes)) {
            FlashLog.w("CHAT", "SECURITY: GsJoinRequest dropped: invalid signature from ${frame.subjectId}")
            return
        }

        // A device that is already an active member asks again: its approval never reached it (it was offline or the
        // link dropped when the admin approved). Do not turn the row back into a pending request or notify again;
        // hand it the roster, which is what completes its join.
        val alreadyMember = members.member(frame.groupId, frame.subjectId)
        if (alreadyMember?.isActive == true && alreadyMember.subjectKey == frame.subjectKey) {
            FlashLog.i("GROUP", "Join request from ${frame.subjectId} for ${frame.groupId}: already a member, resending the roster")
            if (isDirect) {
                signedGroups?.bundleFor(frame.groupId)?.let { groupTransportSink?.send(peerDeviceId, it) }
            }
            return
        }
        FlashLog.i("GROUP", "Join request accepted for review: group=${frame.groupId} subject=${frame.subjectId} direct=$isDirect via=$peerDeviceId")

        // 5. Reply GsRosterPreview to joiner if direct
        if (isDirect) {
            val conversation = conversationDao.get(frame.groupId)
            if (conversation != null && conversation.groupProto == GroupPolicy.V2_PROTOCOL) {
                val active = members.activeMembers(frame.groupId)
                val charter = GroupCharter(
                    groupId = frame.groupId,
                    name = conversation.title,
                    ownerId = conversation.groupCreatedBy ?: "",
                    ownerKey = conversation.groupOwnerKey ?: "",
                    createdAt = conversation.groupCreatedAt ?: 0L,
                    nonce = conversation.groupNonce ?: "",
                    proto = conversation.groupProto,
                    sig = conversation.groupCharterSig ?: "",
                )
                val preview = GroupWireFrame.GsRosterPreview(
                    groupId = frame.groupId,
                    from = localDeviceId,
                    charter = charter,
                    memberCount = active.size,
                    memberNames = active.map { it.displayName },
                )
                groupTransportSink?.send(peerDeviceId, preview)
            }
        }

        // 6. Store request in group_join_request
        val entity = GroupJoinRequestEntity(
            groupId = frame.groupId,
            subjectId = frame.subjectId,
            subjectKey = frame.subjectKey,
            label = frame.label,
            requestSig = frame.signature,
            viaPeerId = peerDeviceId,
            requestedAtMs = frame.requestedAtMs,
            state = "PENDING",
            decidedBy = null,
            decidedAtMs = null,
        )
        groupJoinRequestDao?.upsert(entity)

        // 7. Check if this device is admin/owner
        val self = members.member(frame.groupId, localDeviceId)?.takeIf { it.isActive }
        val isOwner = conversationDao.get(frame.groupId)?.groupCreatedBy == localDeviceId
        val isAdmin = isOwner || self?.role == "admin"
        if (isAdmin) {
            // Under policy "open", auto-approve UNLESS tombstoned (GINV-5) or group is full
            val isTombstoned = members.member(frame.groupId, frame.subjectId)?.let {
                !it.isActive && it.subjectKey == frame.subjectKey
            } == true
            val activeCount = members.activeMembers(frame.groupId).size
            val maxMembers = signedGroups?.currentSettings(frame.groupId)?.maxMembers ?: GroupPolicy.MAX_MEMBERS_V2
            if (activeCount >= maxMembers) {
                refuseJoinRequest(frame.groupId, frame.subjectId, reason = "full")
            } else if (!isTombstoned && isGroupJoinOpen(frame.groupId)) {
                approveJoinRequest(frame.groupId, frame.subjectId)
            } else {
                val groupTitle = conversationDao.get(frame.groupId)?.title ?: "Group"
                onJoinRequestNotification?.invoke(frame.groupId, groupTitle, frame.label)
                touchConversationRefresh()
            }
        } else {
            // Forward signed request to every admin this device has an active session with
            forwardJoinRequestToAdmins(frame)
        }
    }

    private suspend fun handleInboundJoinDecision(peerDeviceId: String, frame: GroupWireFrame.GsJoinDecision) {
        if (frame.subjectId != localDeviceId) return
        val invite = groupInviteDao?.getByGroupId(frame.groupId)
        if (invite == null) {
            FlashLog.w("CHAT", "Group join decision ignored: no invite stored for ${frame.groupId} (from $peerDeviceId)")
            return
        }
        if (!frame.approved) {
            groupInviteDao.updateState(frame.groupId, "REFUSED")
            acceptedInviteGroupIds.remove(frame.groupId)
            groupVouching?.revoke(invite.inviterId, frame.groupId)
            groupSecretStore?.forget(frame.groupId)
            pendingInviteHints.remove(frame.groupId)
            hintsExhausted.remove(frame.groupId)
            FlashLog.i("CHAT", "Group join request refused: ${frame.reason} for ${frame.groupId}")
        } else {
            groupInviteDao.updateState(frame.groupId, "APPROVED")
            FlashLog.i("CHAT", "Group join request approved for ${frame.groupId}")
        }
    }

    private suspend fun handleInboundRosterPreview(peerDeviceId: String, frame: GroupWireFrame.GsRosterPreview) {
        val invite = groupInviteDao?.getByGroupId(frame.groupId) ?: return
        if (invite.state == "REFUSED" || invite.state == "ABANDONED") return
        val signed = signedGroups ?: return
        val err = signed.checkCharter(frame.charter)
        if (err != null) {
            FlashLog.w("CHAT", "SECURITY: GsRosterPreview charter invalid: $err from $peerDeviceId")
            return
        }
        FlashLog.i("CHAT", "Received GsRosterPreview for ${frame.groupId}: ${frame.memberCount} members")
    }

    private suspend fun onPeerSessionUp(peerId: String) {
        triggerProofForPendingInvites(peerId)
        // Forward any pending join requests where peerId is an admin in that group
        val pendingRequests = groupJoinRequestDao?.getAll()?.filter { it.state == "PENDING" } ?: emptyList()
        val members = groupMemberDao ?: return
        for (req in pendingRequests) {
            val adminRow = members.member(req.groupId, peerId)
            val conversation = conversationDao.get(req.groupId)
            val isOwner = conversation?.groupCreatedBy == peerId
            val isAdmin = isOwner || (adminRow?.isActive == true && (adminRow.role == "owner" || adminRow.role == "admin"))
            if (isAdmin) {
                val frame = GroupWireFrame.GsJoinRequest(
                    groupId = req.groupId,
                    from = localDeviceId,
                    epoch = groupSecretStore?.current(req.groupId)?.epoch ?: 1L,
                    subjectId = req.subjectId,
                    subjectKey = req.subjectKey,
                    label = req.label,
                    requestedAtMs = req.requestedAtMs,
                    signature = req.requestSig,
                )
                groupTransportSink?.send(peerId, frame)
            }
        }
    }

    private suspend fun triggerProofForPendingInvites(peerId: String) {
        val pending = groupInviteDao?.getAll()?.filter {
            // INVALID is retried: the proof failed when last tried, which a reconnect may cure. STALE is not: the
            // secret in the link is older than the group's and only a new link helps.
            it.state == "PENDING_CONTACT" || it.state == "PENDING_APPROVAL" || it.state == "INVALID"
        } ?: emptyList()
        if (pending.isEmpty()) return
        if (!peerFeatures(peerId).contains("gs1")) {
            FlashLog.i("GROUP", "No invite proof with peer=$peerId: it does not advertise gs1 (${pending.size} invite(s) waiting)")
            return
        }
        for (invite in pending) {
            val key = peerId to invite.groupId
            // Joining triggers this from two places at once (the accept itself and the hint dialer that finds the
            // inviter already connected). One proof per pair is enough; the other trigger has nothing to add.
            if (!inviteProofsInFlight.add(key)) {
                FlashLog.i("GROUP", "Invite proof already running: group=${invite.groupId} peer=$peerId")
                continue
            }
            try {
                val secretRecord = groupSecretStore?.current(invite.groupId)
                if (secretRecord == null) {
                    FlashLog.w("GROUP", "No stored secret for pending invite group=${invite.groupId}; cannot prove to peer=$peerId")
                    continue
                }
                val result = groupProofSessions?.initiateProof(peerId, invite.groupId, secretRecord.epoch)
                FlashLog.i("GROUP", "Invite proof: group=${invite.groupId} peer=$peerId epoch=${secretRecord.epoch} result=$result")
                when (result) {
                    GroupProofResult.OK -> {
                        inviteProofRetries.remove(key)
                        sendJoinRequest(peerId, invite.groupId, secretRecord.epoch)
                    }
                    GroupProofResult.FAILED -> scheduleInviteProofRetry(peerId, invite.groupId, failed = true)
                    GroupProofResult.TIMEOUT -> scheduleInviteProofRetry(peerId, invite.groupId, failed = false)
                    GroupProofResult.STALE -> markInviteDead(invite.groupId, "STALE")
                    GroupProofResult.UNSUPPORTED, null -> Unit
                }
            } finally {
                inviteProofsInFlight.remove(key)
            }
        }
    }

    /**
     * A proof that failed or timed out while the inviter is still connected is tried again a few times: nothing else
     * would retry it until the next reconnect, and the joiner would sit on "waiting" with no request ever sent.
     */
    private fun scheduleInviteProofRetry(peerId: String, groupId: String, failed: Boolean) {
        val key = peerId to groupId
        val attempt = (inviteProofRetries[key] ?: 0) + 1
        if (attempt > INVITE_PROOF_MAX_RETRIES) {
            inviteProofRetries.remove(key)
            FlashLog.w("GROUP", "Invite proof gave up after ${attempt - 1} retries: group=$groupId peer=$peerId failed=$failed")
            // A proof the peer answered and rejected says the link no longer matches the group; a proof that only timed
            // out says nothing about the link, so the joiner keeps waiting.
            if (failed) scope.launch(ioDispatcher) { markInviteDead(groupId, "INVALID") }
            return
        }
        inviteProofRetries[key] = attempt
        scope.launch(ioDispatcher) {
            delay(INVITE_PROOF_RETRY_DELAY_MS * attempt)
            if (isPeerOnline(peerId)) {
                FlashLog.i("GROUP", "Retrying invite proof ($attempt/$INVITE_PROOF_MAX_RETRIES): group=$groupId peer=$peerId")
                triggerProofForPendingInvites(peerId)
            }
        }
    }

    /**
     * The invite can not complete as it is: [state] is `STALE` (the group's secret moved on since the link was made;
     * only a new link helps) or `INVALID` (the peer rejected the proof; retried when a peer reconnects). The joiner is
     * told in words instead of waiting for ever.
     */
    private suspend fun markInviteDead(groupId: String, state: String) {
        val invite = groupInviteDao?.getByGroupId(groupId) ?: return
        if (invite.state == "JOINED" || invite.state == "REFUSED" || invite.state == "ABANDONED") return
        groupInviteDao.updateState(groupId, state)
        pendingInviteHints.remove(groupId)
        hintsExhausted.remove(groupId)
        if (state == "STALE") {
            acceptedInviteGroupIds.remove(groupId)
            groupVouching?.revoke(invite.inviterId, groupId)
            groupSecretStore?.forget(groupId)
        }
        FlashLog.w("GROUP", "Invite can not complete: group=$groupId state=$state")
        touchConversationRefresh()
    }

    private suspend fun sendJoinRequest(peerId: String, groupId: String, epoch: Long) {
        val crypto = groupCrypto ?: return
        val now = timeSource.nowMs()
        val subjectKeyStr = GroupCanonical.encode(crypto.publicKey)
        val bytes = GroupCanonical.joinRequestBytes(
            groupId = groupId,
            epoch = epoch,
            subjectId = localDeviceId,
            subjectKeyBase64 = subjectKeyStr,
            label = localDisplayName,
            requestedAtMs = now,
        ) ?: return
        val sig = GroupCanonical.encode(crypto.sign(bytes))
        val req = GroupWireFrame.GsJoinRequest(
            groupId = groupId,
            from = localDeviceId,
            epoch = epoch,
            subjectId = localDeviceId,
            subjectKey = subjectKeyStr,
            label = localDisplayName,
            requestedAtMs = now,
            signature = sig,
        )
        val sent = groupTransportSink?.send(peerId, req)
        FlashLog.i("GROUP", "Join request sent: group=$groupId to=$peerId delivered=$sent")
        groupInviteDao?.updateState(groupId, "PENDING_APPROVAL")
    }

    private suspend fun forwardJoinRequestToAdmins(req: GroupWireFrame.GsJoinRequest) {
        val members = groupMemberDao ?: return
        val active = members.activeMembers(req.groupId)
        val adminIds = active.filter { it.role == "owner" || it.role == "admin" }.map { it.deviceId }
        for (adminId in adminIds) {
            if (adminId != localDeviceId) {
                groupTransportSink?.send(adminId, req)
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

    /**
     * Stashes signature and timestamp when [beginGroupAttachment] is called before [sendGroupAttachment]
     * (the Android and Desktop UI caller sequence), so multiple recipient announcements share the
     * exact same timestamp and signature, and the subsequent [sendGroupAttachment] persists them identically.
     */
    private val pendingGroupAttachmentSignatures = SyncMap<String, Pair<Long, String?>>()

    /** messageId -> (root, pieceSize, rootSig) of a swarm file between its first announcement and its row (ERROR-108). */
    private val pendingSwarmOffers = SyncMap<String, Triple<String, Int, String>>()

    /** ERROR-117: keeps the origin's offer for [messageId] unless an announcement already stored one (that one is signed over the row's `sentAt`). */
    override fun recordSwarmOffer(messageId: String, root: String, pieceSize: Int, rootSig: String) {
        if (pendingSwarmOffers[messageId] == null) pendingSwarmOffers[messageId] = Triple(root, pieceSize, rootSig)
    }

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
    ): Boolean = beginGroupAttachment(
        groupId = groupId,
        recipientDeviceId = recipientDeviceId,
        messageId = messageId,
        transferId = transferId,
        wireFileId = wireFileId,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        root = null,
        pieceSize = null,
        swarm = null,
        rootSig = null,
    )

    override suspend fun beginGroupAttachment(
        groupId: String,
        recipientDeviceId: String,
        messageId: String,
        transferId: String,
        wireFileId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        root: String?,
        pieceSize: Int?,
        swarm: Int?,
        rootSig: String?,
    ): Boolean {
        if (messageId.isBlank() || transferId.isBlank() || wireFileId.isBlank()) return false
        val members = groupMemberDao ?: return false
        // ERROR-117: a swarm offer goes to any active member this device trusts in the group, paired or vouched (the
        // swarm gate admits both); a whole-file push stays paired-only (ADR-044 E3), because the transfer layer would
        // refuse an unpaired sender's bytes.
        val allowed = if (swarm == 1 && root != null && pieceSize != null) {
            isActiveGroupMember(members, groupId, recipientDeviceId)
        } else {
            isActiveTrustedMember(members, groupId, recipientDeviceId)
        }
        if (!allowed) return false
        groupMessageTransfers.getOrPut(messageId) { SyncSet() }.add(transferId)
        transferToGroupMessage[transferId] = messageId
        val existing = messageDao.getByLocalId(messageId)
        val (sentAt, signature) = if (existing != null) {
            existing.sentAt to existing.groupSig
        } else {
            val cached = pendingGroupAttachmentSignatures[messageId]
            if (cached != null) {
                cached
            } else {
                val now = timeSource.nowMs()
                val sig = if (isV2Group(groupId)) {
                    signedGroups?.signMessage(groupId, messageId, now, null, null, "")
                } else {
                    null
                }
                val pair = now to sig
                pendingGroupAttachmentSignatures[messageId] = pair
                pair
            }
        }
        // ERROR-108: the announcement signature must cover THIS message's sentAt, which only this layer knows. The one the
        // origin made when it registered the file used its own clock a moment earlier, so a receiver, which checks it
        // against the frame's sentAt, could not verify it and refused the offer. Sign here; fall back to the host's
        // signature only on a device that cannot sign (the receiver then has nothing to verify against either).
        val offerSig = if (swarm == 1 && root != null && pieceSize != null) {
            signedGroups?.signSwarmAnnouncement(groupId, messageId, root, sizeBytes, fileName, mimeType, sentAt) ?: rootSig
        } else {
            rootSig
        }
        if (swarm == 1 && root != null && pieceSize != null && offerSig != null) {
            pendingSwarmOffers[messageId] = Triple(root, pieceSize, offerSig)
        }
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
                sentAt = sentAt,
                signature = signature,
                root = root,
                pieceSize = pieceSize,
                swarm = swarm,
                rootSig = offerSig,
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
    internal fun requestGroupCatchUp(groupId: String, newlyJoined: Boolean = true) {
        scope.launch(ioDispatcher) {
            val members = groupMemberDao ?: return@launch
            // ADR-100: a member that has just joined and holds no message yet is asked first how much history it wants.
            if (!historyMayBeRequested(groupId, newlyJoined)) return@launch
            val others = members.activeMembers(groupId)
                .map { it.deviceId }
                .filter { it != localDeviceId }
            FlashProbe.emit("group.catchup.request", "group" to FlashProbe.short(groupId), "to" to others.size, "why" to "bootstrap")
            catchUpFrom(groupId, others)
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
                if (!peer.isActive) {
                    // A device the owner removed while it was offline hears of it here: a member never sends a roster to
                    // an inactive peer, and that peer (still thinking it belongs) is ignored when it sends its own, so
                    // without this it would wait for the group forever. Only the owner's tombstone travels this way.
                    if (isV2Group(groupId)) {
                        signedGroups?.removalNoticeFor(groupId, peerDeviceId)?.let { groupTransportSink?.send(peerDeviceId, it) }
                    }
                    continue
                }
                if (isV2Group(groupId)) {
                    // A v2 group reconciles with its signed roster (charter + every cert, tombstones included).
                    signedGroups?.bundleFor(groupId)?.let { groupTransportSink?.send(peerDeviceId, it) }
                    val latestRot = groupRotationDao?.getLatestForGroup(groupId)
                    val currentSec = groupSecretStore?.current(groupId)
                    if (latestRot != null && (currentSec?.epoch ?: 0L) < latestRot.newEpoch) {
                        if (groupGate.allows(groupId, peerDeviceId, GroupTraffic.CHAT)) {
                            groupTransportSink?.send(
                                peerDeviceId,
                                GroupWireFrame.GsSecretRequest(groupId, localDeviceId, latestRot.newEpoch),
                            )
                        }
                    }
                    continue
                }
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
        val rows = members.allMembers(groupId)
        val active = rows.filter { it.isActive }
        if (active.isEmpty()) return null
        // ADR-044 V1a (F-5): leave tombstones travel too, newest first, so a member that was
        // offline when someone left stops treating them as a member. The wire codec (and every
        // shipped client) rejects a roster above MAX_MEMBERS, so tombstones only fill what the
        // active members leave free; a full group carries none.
        val tombstones = rows.filter { !it.isActive }
            .sortedByDescending { it.membershipVersion }
            .take((GroupPolicy.MAX_MEMBERS - active.size).coerceAtLeast(0))
        val roster = active + tombstones
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

    /**
     * One F3 catch-up request for a single (group, peer) pair - the existing wire shape. True when a request was handed to the
     * transport (a live session took it); false when none must be sent (the join card is still up, a window of nothing) or the
     * peer has no session, so the caller can move on to the next holder.
     */
    private suspend fun sendSyncRequestFor(peerDeviceId: String, groupId: String): Boolean {
        val historyDao = groupHistoryDao
        val newest = messageDao.historyBefore(groupId, Long.MAX_VALUE, "\uFFFF", limit = 1).firstOrNull()
        if (historyDao != null) {
            // ADR-100: a windowed request that continues from the contiguous watermark of the asked holder.
            val now = timeSource.nowMs()
            val state = historyDao.state(groupId)
            val watermarkRow = historyDao.watermark(groupId, peerDeviceId)
            // G1: "returning" is a fact about the ASKED holder (when it last served this device), not about the group:
            // one holder finishing used to shrink every other holder's window to the 7-day floor.
            val choice = GroupHistoryPolicy.requestFor(
                historyCeilingOf(groupId), state?.toRequestState(watermarkRow?.updatedAtMs ?: 0L), now,
            ) ?: return false
            val watermark = watermarkRow?.let { GroupSyncCursor(it.sentAt, it.messageId) }
            // Without a watermark the request asks from the window floor, so a gap below the newest local row (defect S1)
            // is asked for. A holder that has sent a page marker is known to page, so it always is. One that has not
            // (an older build, or a first page that died) gets at most [SYNC_FLOOR_ATTEMPTS] such requests per process,
            // then the newest local row is the cursor again, which is exactly the pre-ADR-100 traffic.
            val holderKey = "$groupId|$peerDeviceId"
            val attempts = (syncFloorAttempts[holderKey] ?: 0)
            val firstFromFloor = watermark == null && (syncPagedHolders.contains(holderKey) || attempts < SYNC_FLOOR_ATTEMPTS)
            if (watermark == null && !syncPagedHolders.contains(holderKey)) syncFloorAttempts[holderKey] = attempts + 1
            val newestCursor = newest?.let { GroupSyncCursor(it.sentAt, it.localId) }
            val start = GroupHistoryPolicy.requestStart(watermark, if (firstFromFloor) null else newestCursor, now, choice.messageWindowMs)
            return sendHistoryRequest(peerDeviceId, groupId, start, choice, continuation = false, pageNo = 0)
        }
        val tier = syncTier()
        val (maxPerSecond, maxTotal) = GroupPolicy.syncLimits(tier)
        val syncId = UuidIdGenerator.newId()
        // Recorded BEFORE the send so a fast answer cannot beat the ledger entry (F-4).
        recordOutgoingSync(syncId, groupId, peerDeviceId)
        return groupTransportSink?.send(
            peerDeviceId,
            GroupWireFrame.SyncRequest(
                groupId = groupId,
                syncId = syncId,
                from = localDeviceId,
                sinceSentAt = newest?.sentAt ?: 0L,
                sinceMessageId = newest?.localId ?: "",
                tier = tier,
                maxPerSecond = maxPerSecond,
                maxTotal = maxTotal,
            ),
        ) ?: false
    }

    /** One windowed catch-up request (ADR-100); the legacy request above is the same frame without the window keys. */
    private suspend fun sendHistoryRequest(
        peerDeviceId: String,
        groupId: String,
        start: GroupSyncCursor,
        choice: GroupHistoryChoice,
        continuation: Boolean,
        pageNo: Int,
    ): Boolean {
        val tier = syncTier()
        val (maxPerSecond, maxTotal) = GroupPolicy.syncLimits(tier)
        val syncId = UuidIdGenerator.newId()
        recordOutgoingSync(syncId, groupId, peerDeviceId, choice.messageWindowMs, choice.includeFiles, pageNo)
        FlashProbe.emit(
            "group.catchup.request",
            "group" to FlashProbe.short(groupId),
            "to" to FlashProbe.short(peerDeviceId),
            "why" to if (continuation) "page" else "session",
            "windowMs" to choice.messageWindowMs,
            "files" to choice.includeFiles,
            "page" to pageNo,
        )
        return groupTransportSink?.send(
            peerDeviceId,
            GroupWireFrame.SyncRequest(
                groupId = groupId,
                syncId = syncId,
                from = localDeviceId,
                sinceSentAt = start.sentAt,
                sinceMessageId = start.messageId,
                tier = tier,
                maxPerSecond = maxPerSecond,
                maxTotal = maxTotal,
                windowMs = choice.messageWindowMs,
                includeFiles = choice.includeFiles,
                continuation = continuation,
            ),
        ) ?: false
    }

    /** Requests per (group|holder) that started at the window floor because no watermark existed (ADR-100); see [sendSyncRequestFor]. */
    private val syncFloorAttempts = SyncMap<String, Int>()

    /** (group|holder) pairs that have sent a page marker in this process, i.e. holders known to page. */
    private val syncPagedHolders = SyncSet<String>()

    /**
     * G1: [holderContactAtMs] is when the ASKED holder last served this device (its watermark's `updatedAtMs`, 0 when it never
     * has). The row's own group-wide `lastContactAtMs` is still written but no longer decides a window.
     */
    private fun GroupHistoryStateEntity.toRequestState(holderContactAtMs: Long) = GroupHistoryPolicy.RequestState(
        pending = cardState == HISTORY_PENDING,
        chosenWindowMs = windowMs,
        includeFiles = includeFiles,
        decidedAtMs = decidedAtMs,
        lastContactAtMs = holderContactAtMs,
    )

    /** ADR-106 (G3): who answers a group's catch-up; one lane per group, one holder in charge at a time. */
    private val catchUpLanes = SyncMap<String, CatchUpLane>()

    /** How long the holder in charge may stay silent before the next one is asked; a field so tests need not wait 45 s. */
    internal var catchUpStallMs: Long = GroupPolicy.CATCH_UP_STALL_MS

    /** G11: catch-up requests accepted per (group, requester) per window. */
    private val syncRequestBudget = VerifyBudget(GroupPolicy.SYNC_REQUESTS_PER_WINDOW, GroupPolicy.SYNC_REQUEST_RATE_WINDOW_MS)

    /**
     * Asks [holders] for the history this device lacks, ONE at a time (ADR-106, review G3). Holders that wait behind the one in
     * charge are asked only if it fails, goes quiet or delivers nothing; once one delivered a complete chain the others are
     * left alone for [GroupPolicy.CATCH_UP_EPISODE_MS]. Without the history store the old one-request-per-holder shape stays.
     */
    private suspend fun catchUpFrom(groupId: String, holders: List<String>) {
        val historyDao = groupHistoryDao
        if (historyDao == null) {
            holders.forEach { sendSyncRequestFor(it, groupId) }
            return
        }
        if (holders.isEmpty()) return
        val lane = catchUpLanes.getOrPut(groupId) { CatchUpLane(catchUpStallMs) }
        // The holder served longest ago leads (never-served first): the lead rotates from episode to episode, and a row one
        // holder missed can arrive from another later. Ties are broken per requester so a crowd of new members does not all
        // ask the same device.
        val served = historyDao.watermarks(groupId).associate { it.holderId to it.updatedAtMs }
        val ordered = holders.sortedWith(compareBy<String>({ served[it] ?: 0L }, { "$localDeviceId|$it".hashCode() }))
        runLane(groupId, lane, lane.offer(ordered, timeSource.nowMs()))
    }

    /** Starts [first]; while a holder cannot be asked at all the lane moves to the next one. */
    private suspend fun runLane(groupId: String, lane: CatchUpLane, first: String?) {
        var next = first
        while (next != null) {
            if (sendSyncRequestFor(next, groupId)) {
                watchLane(groupId, lane, next)
                return
            }
            next = lane.finish(next, complete = false, nowMs = timeSource.nowMs())
        }
    }

    /** The holder in charge stopped answering (no push, no marker) for [catchUpStallMs]: the next one is asked. */
    private fun watchLane(groupId: String, lane: CatchUpLane, holder: String) {
        scope.launch(ioDispatcher) {
            while (true) {
                delay((catchUpStallMs / 2).coerceAtLeast(20L))
                val step = lane.checkStall(holder, timeSource.nowMs())
                if (!step.stillLeads) {
                    if (step.next != null) runLane(groupId, lane, step.next)
                    return@launch
                }
            }
        }
    }

    private suspend fun finishLane(groupId: String, holder: String, complete: Boolean) {
        val lane = catchUpLanes[groupId] ?: return
        runLane(groupId, lane, lane.finish(holder, complete, timeSource.nowMs()))
    }

    /** Strictly increasing, so two refreshes in the same millisecond still reach a collector (G13). */
    internal fun touchConversationRefresh() {
        val now = timeSource.nowMs()
        conversationRefreshTrigger.update { maxOf(now, it + 1L) }
    }

    /** Test view of the refresh trigger (G13). */
    internal fun conversationRefreshValue(): Long = conversationRefreshTrigger.value

    /** The signed history ceiling of [groupId]; a legacy group, or one nobody changed, has the default. */
    private suspend fun historyCeilingOf(groupId: String): GroupHistoryCeiling =
        (signedGroups?.currentSettings(groupId) ?: groupSettingsDao?.getByGroupId(groupId)?.toSettings())
            ?.historyCeiling ?: GroupHistoryCeiling.DEFAULT

    /**
     * ADR-100: false while the member has to choose how much history to load. A device that just joined a group and holds
     * no message of it gets a `PENDING` row (the join card); one that already has rows, or only re-applied a roster, is
     * a returning member and is never asked. Always true without the history store.
     */
    private suspend fun historyMayBeRequested(groupId: String, newlyJoined: Boolean): Boolean {
        val dao = groupHistoryDao ?: return true
        val existing = dao.state(groupId)
        if (existing != null) return existing.cardState != HISTORY_PENDING
        if (!newlyJoined) return true
        val hasRows = messageDao.historyBefore(groupId, Long.MAX_VALUE, "\uFFFF", limit = 1).isNotEmpty()
        if (hasRows) return true
        val now = timeSource.nowMs()
        dao.upsertState(
            GroupHistoryStateEntity(
                groupId = groupId, cardState = HISTORY_PENDING, windowMs = 0L, includeFiles = false,
                decidedAtMs = 0L, lastContactAtMs = 0L, createdAtMs = now,
            ),
        )
        FlashProbe.emit("group.history.card", "group" to FlashProbe.short(groupId), "state" to HISTORY_PENDING)
        touchConversationRefresh()
        return false
    }

    override suspend fun chooseGroupHistory(groupId: String, windowMs: Long, includeFiles: Boolean): FlashResult<Unit> =
        withContext(ioDispatcher) { storeHistoryChoice(groupId, windowMs, includeFiles, resetWatermarks = false) }

    override suspend fun skipGroupHistory(groupId: String): FlashResult<Unit> =
        withContext(ioDispatcher) { storeHistoryChoice(groupId, 0L, false, resetWatermarks = false) }

    override suspend fun loadOlderGroupHistory(groupId: String, windowMs: Long): FlashResult<Unit> =
        // Older rows lie BELOW every watermark, so they are reset and the next request starts at the new window's floor.
        withContext(ioDispatcher) { storeHistoryChoice(groupId, windowMs, true, resetWatermarks = true) }

    private suspend fun storeHistoryChoice(
        groupId: String,
        windowMs: Long,
        includeFiles: Boolean,
        resetWatermarks: Boolean,
    ): FlashResult<Unit> {
        val dao = groupHistoryDao ?: return FlashResult.Failure(FlashError.Unknown("Group history is unavailable on this device"))
        val members = groupMemberDao ?: return FlashResult.Failure(FlashError.Unknown("Group storage unavailable"))
        if (members.member(groupId, localDeviceId)?.isActive != true) {
            return FlashResult.Failure(FlashError.Unknown("You are not a member of this group"))
        }
        val now = timeSource.nowMs()
        val ceiling = historyCeilingOf(groupId)
        val previous = dao.state(groupId)
        // G8: "Load older" only widens; asking for 7 days after choosing 30 must not lower the stored window.
        val wanted = if (resetWatermarks) maxOf(windowMs, previous?.windowMs ?: 0L) else windowMs
        val choice = GroupHistoryPolicy.clamp(GroupHistoryChoice(wanted.coerceAtLeast(0L), includeFiles), ceiling)
        dao.upsertState(
            GroupHistoryStateEntity(
                groupId = groupId,
                cardState = if (choice.messageWindowMs == 0L && !choice.includeFiles) HISTORY_SKIPPED else HISTORY_DECIDED,
                windowMs = choice.messageWindowMs,
                includeFiles = choice.includeFiles,
                decidedAtMs = now,
                lastContactAtMs = 0L,
                createdAtMs = previous?.createdAtMs ?: now,
            ),
        )
        if (resetWatermarks) dao.deleteWatermarks(groupId)
        FlashProbe.emit(
            "group.history.choice", "group" to FlashProbe.short(groupId),
            "windowMs" to choice.messageWindowMs, "files" to choice.includeFiles, "ceiling" to ceiling.name,
        )
        touchConversationRefresh()
        // A new choice is a new episode, whatever the lane was doing.
        catchUpLanes[groupId]?.reset()
        scope.launch(ioDispatcher) {
            catchUpFrom(groupId, members.activeMembers(groupId).map { it.deviceId }.filter { it != localDeviceId })
        }
        return FlashResult.Success(Unit)
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
    ) {
        /** ADR-100: set for a windowed request; the marker the holder sends after the pushes of this page. */
        @Volatile
        var page: SyncPageInfo? = null
    }

    /** What the holder tells the requester about one page: how many rows were pushed and how many remain behind them. */
    private class SyncPageInfo(val count: Int, val remaining: Int, val more: Boolean, val end: GroupSyncCursor?)

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
            for (groupId in groupIds) catchUpFrom(groupId, listOf(peerDeviceId))
        }
    }

    /**
     * ERROR-121: the rows a catch-up may carry. The query returns the OLDEST rows after the cursor, 100 at a time, and the
     * time window used to be applied afterwards, so a member with an empty cursor (a new member) of a group with more than
     * 100 older rows received nothing: the first page held only expired rows. The read now starts at the widest window
     * (a swarm offer's 7 days) and keeps paging until it has [wanted] rows inside their own window or the history ends.
     */
    private suspend fun catchUpCandidates(
        frame: GroupWireFrame.SyncRequest,
        v2Group: Boolean,
        nowMs: Long,
        wanted: Int,
        windows: GroupHistoryWindows,
    ): CatchUpScan {
        // ADR-100: the read starts at the widest window the request may use (for an older requester that is the
        // 7-day swarm window, exactly as before) and each row is kept when it is inside the window of its own kind.
        val widest = maxOf(windows.textMs, windows.fileMs)
        if (widest <= 0L) return CatchUpScan(emptyList(), null, exhausted = true)
        val floor = GroupHistoryPolicy.floorMs(nowMs, widest)
        var sinceAt = frame.sinceSentAt
        var sinceId = frame.sinceMessageId
        if (sinceAt < floor) {
            sinceAt = floor
            sinceId = ""
        }
        val page = GroupPolicy.MAX_PENDING_SYNC_MESSAGES
        val usable = ArrayList<MessageEntity>()
        var scanEnd: GroupSyncCursor? = null
        var exhausted = false
        var pages = 0
        scan@ while (pages < GroupPolicy.MAX_CATCH_UP_PAGES) {
            val rows = messageDao.historyAfter(frame.groupId, sinceAt, sinceId, page)
            for (row in rows) {
                scanEnd = GroupSyncCursor(row.sentAt, row.localId)
                if (row.deletedAt == null &&
                    GroupHistoryPolicy.inWindow(row.sentAt, row.swarmRoot != null, nowMs, windows) &&
                    (!v2Group || row.groupSig != null)
                ) {
                    usable.add(row)
                    // The page is full: stop exactly here, so the page end is the last row it carries.
                    if (usable.size >= wanted) break@scan
                }
            }
            if (rows.size < page) {
                exhausted = true
                break
            }
            val last = rows.last()
            sinceAt = last.sentAt
            sinceId = last.localId
            pages++
        }
        return CatchUpScan(usable, scanEnd, exhausted)
    }

    /** The rows a catch-up read found, where it stopped, and whether it reached the end of this holder's history. */
    private class CatchUpScan(val usable: List<MessageEntity>, val scanEnd: GroupSyncCursor?, val exhausted: Boolean)

    /**
     * How many more rows [after] this holder would serve under [windows], counted up to
     * [GroupHistoryPolicy.MAX_REMAINING_COUNT]; `reachedEnd` is false when the bounded read stopped before the end.
     */
    private suspend fun countRemaining(
        groupId: String,
        after: GroupSyncCursor,
        v2Group: Boolean,
        nowMs: Long,
        windows: GroupHistoryWindows,
    ): Pair<Int, Boolean> {
        var sinceAt = after.sentAt
        var sinceId = after.messageId
        var counted = 0
        val page = GroupPolicy.MAX_PENDING_SYNC_MESSAGES
        var pages = 0
        while (pages < GroupPolicy.MAX_CATCH_UP_PAGES) {
            val rows = messageDao.historyAfter(groupId, sinceAt, sinceId, page)
            for (row in rows) {
                if (row.deletedAt == null &&
                    GroupHistoryPolicy.inWindow(row.sentAt, row.swarmRoot != null, nowMs, windows) &&
                    (!v2Group || row.groupSig != null)
                ) {
                    counted++
                    if (counted >= GroupHistoryPolicy.MAX_REMAINING_COUNT) return counted to false
                }
            }
            if (rows.size < page) return counted to true
            sinceAt = rows.last().sentAt
            sinceId = rows.last().localId
            pages++
        }
        return counted to false
    }

    /**
     * Holder side of a SyncRequest: compute the messages this device owns that are newer than
     * the requester's cursor (capped, TTL-bounded), record the round, and broadcast a claim so
     * the co-holders can elect a single pusher deterministically. Rank 0 pushes after the
     * claim window; rank 1 arms the backup timer; the rest stand down.
     */
    private suspend fun handleSyncRequest(frame: GroupWireFrame.SyncRequest) {
        val (maxPerSecond, maxTotal) = GroupPolicy.syncLimits(frame.tier)
        // A v2 group only relays rows that carry their author's signature; an unsigned row (an
        // attachment) could not be verified by the receiver.
        val v2Group = isV2Group(frame.groupId)
        val nowMs = timeSource.nowMs()
        // G11: a member that sends requests back to back (continuations skip the pacing delay and cost a bounded read per
        // page) is answered up to a budget per window; an honest chain sends about one request per page.
        if (!syncRequestBudget.tryConsume("${frame.groupId}|${frame.from}", 1, nowMs)) {
            FlashProbe.emit("group.sync.drop", "group" to FlashProbe.short(frame.groupId), "from" to FlashProbe.short(frame.from), "reason" to "request_rate")
            return
        }
        // ADR-100: every holder enforces the signed ceiling, whatever the requester believes it may ask for. A request
        // without a window (an older build) is served today's 24 h of text and 7 days of file offers, inside the ceiling.
        val ceiling = historyCeilingOf(frame.groupId)
        val windows = GroupHistoryPolicy.holderWindows(ceiling, frame.windowMs, frame.includeFiles)
        val paged = frame.windowMs != null
        val pageSize = if (paged) GroupHistoryPolicy.PAGE_SIZE else maxTotal.coerceAtMost(GroupPolicy.MAX_PENDING_SYNC_MESSAGES)
        val scan = catchUpCandidates(frame, v2Group, nowMs, pageSize, windows)
        val owned = GroupSyncPolicy.ownedMessages(
            messages = scan.usable,
            cursor = GroupSyncCursor(frame.sinceSentAt, frame.sinceMessageId),
            maxTotal = if (paged) pageSize else maxTotal,
            nowMs = nowMs,
            sentAt = { it.sentAt },
            messageId = { it.localId },
            deletedAt = { it.deletedAt },
            ttlMs = { if (it.swarmRoot != null) windows.fileMs else windows.textMs },
        )
        // ADR-100: what follows this page, so the requester can ask for the next one and show "N of about M".
        var pageInfo: SyncPageInfo? = null
        if (paged) {
            val end = owned.lastOrNull()?.let { GroupSyncCursor(it.sentAt, it.localId) }
            val full = owned.size >= pageSize
            val (remaining, reachedEnd) = if (full && end != null) {
                countRemaining(frame.groupId, end, v2Group, nowMs, windows)
            } else {
                0 to scan.exhausted
            }
            val more = remaining > 0 || !reachedEnd
            // A page with nothing usable in a read that stopped early still moves the requester past what was scanned.
            val pageEnd = end ?: if (more) scan.scanEnd else null
            pageInfo = SyncPageInfo(owned.size, remaining, more, pageEnd)
        }
        if (owned.isEmpty()) {
            if (pageInfo != null) sendSyncPage(frame.from, frame.groupId, frame.syncId, pageInfo, frame.keyEpoch)
            return
        }
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
        round.page = pageInfo
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
        // A continuation page is the requester's next step of a request it already made, so it does not wait out the
        // backup delay again (the first request of a chain does, exactly as before).
        armBackupPush(frame.groupId, frame.syncId, if (frame.continuation) 0L else GroupPolicy.BACKUP_DELAY_MS)
    }

    private suspend fun sendSyncPage(to: String, groupId: String, syncId: String, info: SyncPageInfo, keyEpoch: Long) {
        groupTransportSink?.send(
            to,
            GroupWireFrame.SyncPage(
                groupId = groupId,
                syncId = syncId,
                from = localDeviceId,
                count = info.count,
                remaining = info.remaining,
                more = info.more,
                lastSentAt = info.end?.sentAt ?: 0L,
                lastMessageId = info.end?.messageId ?: "",
                keyEpoch = keyEpoch,
            ),
        )
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
    private fun armBackupPush(groupId: String, syncId: String, delayMs: Long = GroupPolicy.BACKUP_DELAY_MS) {
        scope.launch(ioDispatcher) {
            delay(delayMs)
            val round = syncRounds[syncId] ?: return@launch
            // Read before any push: the last ack can retire the round (and the requester entry) before the marker below.
            val requesterId = syncRequesters[syncId]
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
            // ADR-100: after the last push of a page, tell the requester how the page ended. Only when this device pushed
            // the whole page, so a marker never vouches for rows another holder was elected to send.
            val info = round.page
            if (info != null && requesterId != null && mine.size == ids.size) {
                sendSyncPage(requesterId, groupId, syncId, info, keyEpoch = 0L)
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

        // A signed (v2) attachment row was signed over "" (see sendGroupAttachment), but a voice note's row keeps its
        // waveform metadata in `text`; sending that would fail the author's signature on every receiver (ERROR-117).
        text = when {
            attachmentTransferId == null -> text
            groupSig == null -> attachmentLabel()
            else -> ""
        },

        replyToId = replyToId,
        replyToPreview = replyToPreview,
        signature = groupSig,
        swarmOffer = swarmOfferOrNull(),
    )

    /**
     * ERROR-108: the offer a relayed file row needs, only when the row is a signed (v2) swarm file; null for everything
     * else, so text, legacy rows and unsigned rows travel exactly as before.
     */
    private fun MessageEntity.swarmOfferOrNull(): GroupWireFrame.SwarmOffer? {
        val root = swarmRoot ?: return null
        val pieceSize = swarmPieceSize ?: return null
        val rootSig = swarmRootSig ?: return null
        val name = attachmentName ?: return null
        if (groupSig == null || attachmentSize <= 0L) return null
        return GroupWireFrame.SwarmOffer(name, attachmentMime ?: "application/octet-stream", attachmentSize, root, pieceSize, rootSig)
    }

    /**
     * The one-line stand-in a catch-up carries for an attachment row in legacy (v1) groups. A sync push
     * never re-sends file bytes, and the row's own `text` is empty (or a voice note's `vmsg:` waveform metadata),
     * so sending it as is would leave the newcomer with an empty bubble or raw metadata. Only legacy groups
     * use this: in v2 groups, `groupSig` covers the author's original `text`, so altering it would invalidate
     * the cryptographic signature.
     */
    private fun MessageEntity.attachmentLabel(): String {
        val mime = attachmentMime.orEmpty()
        val kind = when {
            mime.startsWith("audio/") -> return "[Voice message]"
            mime.startsWith("image/") -> "Photo"
            mime.startsWith("video/") -> "Video"
            else -> "File"
        }
        val name = attachmentName?.trim()?.ifEmpty { null }?.take(SYNC_LABEL_NAME_MAX)
        return if (name == null) "[$kind]" else "[$kind] $name"
    }

    /**
     * The `sentAt` a received message is stored with. A message's timestamp is never allowed far into this device's
     * future (it would pin the message to the end of the chat), so it is clamped to [now]. A **signed** (v2) message
     * is the exception for a small skew (ERROR-117): its signature covers the author's `sentAt`, so a clamped copy
     * can never be relayed in catch-up, every receiver would refuse it as "no valid signature". Phones' clocks differ
     * by seconds, so a receiver that is a little behind its author kept a copy that nobody else could verify.
     */
    private fun storedSentAt(sentAt: Long, now: Long, signed: Boolean): Long =
        if (signed && sentAt <= now + SIGNED_SENT_AT_SKEW_TOLERANCE_MS) sentAt else minOf(sentAt, now)

    /** A reason-coded evidence line for a group frame that was refused; `skewMs` is the author's clock minus this device's. */
    private fun probeGroupMessageDrop(
        name: String,
        groupId: String,
        from: String,
        reason: String,
        sentAt: Long,
        vararg extra: Pair<String, Any?>,
    ) {
        FlashProbe.emit(
            name,
            "group" to FlashProbe.short(groupId),
            "from" to FlashProbe.short(from),
            "reason" to reason,
            "skewMs" to (sentAt - timeSource.nowMs()),
            *extra,
        )
    }

    /** Requester side: an elected holder pushed a message — ingest idempotently by msgId. */
    private suspend fun handleSyncPush(frame: GroupWireFrame.SyncPush) {
        // ADR-044 V1a (F-4): only an answer to a request this device sent, from the peer it asked,
        // for that group. The nested message's `from` is not checked because the wire carries none
        // (see F-9): the codec sets it to the pusher.
        val request = outgoingSyncRequests[frame.syncId]
        if (request == null || !request.acceptsPush(frame.groupId, frame.from, timeSource.nowMs())) {
            probeGroupMessageDrop("group.sync.drop", frame.groupId, frame.from, "no_matching_request", frame.message.sentAt)
            FlashLog.w("CHAT", "Group SyncPush dropped: no matching request (syncId=${frame.syncId} from=${frame.from})")
            return
        }
        // ADR-100: a push of a windowed request is counted when it is SEEN, whatever becomes of it below (stored, a duplicate,
        // or refused as unsigned), because the page marker's count is the number of rows the holder sent.
        if (request.windowMs != null) {
            request.tally.record(GroupSyncCursor(frame.message.sentAt, frame.message.messageId))
            catchUpLanes[request.groupId]?.progress(frame.from, 1, timeSource.nowMs())
        }
        val message = frame.message
        if (message.text.length > GroupPolicy.MAX_MESSAGE_TEXT_LENGTH) {
            probeGroupMessageDrop("group.sync.drop", frame.groupId, frame.from, "too_long", message.sentAt)
            FlashLog.w("CHAT", "Group SyncPush text exceeds length cap (${message.text.length} > ${GroupPolicy.MAX_MESSAGE_TEXT_LENGTH}), dropping")
            return
        }
        // v2: the pusher is only a relay. The message counts only if its named author signed it and is
        // an active member, and the name stored is that member's signed label (fixes F-9 for v2).
        var senderName: String? = message.senderName
        var groupSig: String? = null
        if (isV2Group(frame.groupId)) {
            val label = signedGroups?.verifiedAuthorLabel(
                frame.groupId, message.from, message.messageId, message.sentAt,
                message.replyToId, message.replyToPreview, message.text, message.signature,
            )
            if (label == null) {
                probeGroupMessageDrop(
                    "group.sync.drop", frame.groupId, frame.from, "bad_signature", message.sentAt,
                    "author" to FlashProbe.short(message.from), "hasOffer" to (message.swarmOffer != null),
                )
                FlashLog.w("CHAT", "SECURITY: group SyncPush message dropped, no valid signature (group=${frame.groupId} msg=${message.messageId} author=${message.from} relay=${frame.from})")
                return
            }
            senderName = label
            groupSig = message.signature
        }
        // ERROR-108: a file the member missed. The author's announcement signature is checked against the roster key
        // before any field of the offer is believed, so a relaying member cannot swap the file. Without a swarm listener
        // (swarm switched off here) the row is taken as plain text, as before.
        val offer = message.swarmOffer
        val listener = swarmAnnouncementListener
        // ERROR-120: the offer is verified and kept even when swarm is off HERE. Dropping it left an empty message with
        // no file name, and this device could not relay the file's offer to a member that has swarm on.
        val swarmFile = if (offer != null && groupSig != null) {
            val verified = signedGroups?.verifySwarmAnnouncement(
                frame.groupId, message.from, message.messageId, offer.root,
                offer.sizeBytes, offer.fileName, offer.mimeType, message.sentAt, offer.rootSig,
            ) == true
            if (!verified) {
                probeGroupMessageDrop(
                    "group.sync.drop", frame.groupId, frame.from, "bad_offer_signature", message.sentAt,
                    "author" to FlashProbe.short(message.from),
                )
                FlashLog.w("CHAT", "SECURITY: group SyncPush file offer dropped, bad announcement signature (group=${frame.groupId} msg=${message.messageId} author=${message.from} relay=${frame.from})")
                return
            }
            offer
        } else {
            null
        }
        val now = timeSource.nowMs()
        val boundedSentAt = storedSentAt(message.sentAt, now, signed = groupSig != null)
        // PROBE: one line per pushed message; a file the swarm switch here could not use is told apart from a plain row.
        FlashProbe.emit(
            "group.sync.in",
            "group" to FlashProbe.short(frame.groupId),
            "relay" to FlashProbe.short(frame.from),
            "author" to FlashProbe.short(message.from),
            "kind" to when {
                swarmFile != null && listener != null -> "swarm_offer"
                swarmFile != null -> "offer_kept_swarm_off_here"
                message.swarmOffer != null -> "offer_unused_unsigned"
                else -> "text"
            },
            "signed" to (groupSig != null),
            "skewMs" to (message.sentAt - now),
        )
        val inserted = messageDao.insert(
            MessageEntity(
                localId = message.messageId,
                conversationId = frame.groupId,
                senderId = message.from,
                senderName = senderName,
                text = message.text,
                sentAt = boundedSentAt,
                status = "DELIVERED",
                replyToId = message.replyToId,
                replyToPreview = message.replyToPreview,
                groupSig = groupSig,
                attachmentTransferId = swarmFile?.let { message.messageId },
                attachmentName = swarmFile?.fileName,
                attachmentMime = swarmFile?.mimeType,
                attachmentSize = swarmFile?.sizeBytes ?: 0L,
                swarmRoot = swarmFile?.root,
                swarmPieceSize = swarmFile?.pieceSize,
                swarmRootSig = swarmFile?.rootSig,
            ),
        )
        if (inserted != -1L) {
            recordCatchUpArrival(frame.groupId)
            val groupTitle = conversationDao.get(frame.groupId)?.title?.ifBlank { null }
            if (swarmFile != null) {
                onInboundAttachmentWithGroupTitle(frame.groupId, senderName, swarmFile.fileName, swarmFile.mimeType, groupTitle)
            }
            if (swarmFile != null && listener != null) {
                listener.onSwarmAnnouncement(
                    groupId = frame.groupId,
                    messageId = message.messageId,
                    transferId = message.messageId,
                    from = message.from,
                    root = swarmFile.root,
                    pieceSize = swarmFile.pieceSize,
                    totalSize = swarmFile.sizeBytes,
                    fileName = swarmFile.fileName,
                    mimeType = swarmFile.mimeType,
                    sentAt = message.sentAt,
                    rootSig = swarmFile.rootSig,
                )
            } else if (swarmFile == null) {
                onInboundTextMessageWithGroupTitle(frame.groupId, senderName, message.text, groupTitle)
            }
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
        // Chat/group sync audit, step 2. The SyncAck goes to the relay, which may not be the author, and the author
        // is the only device that keeps a delivery row for this message. Tell it directly, as a live delivery
        // would have, or its tick stays at "1/2" until its own retry reaches us (and never moves once its outbox
        // gave up). Only a relayed message needs this: when the pusher is the author, the ack above is the news
        // (see [handleSyncAck]). Dropped without harm when the author has no session with this device.
        if (message.from != localDeviceId && message.from != frame.from) {
            groupTransportSink?.send(
                message.from,
                GroupWireFrame.Receipt(
                    groupId = frame.groupId,
                    messageId = message.messageId,
                    from = localDeviceId,
                    deliveredAt = timeSource.nowMs(),
                ),
            )
        }
    }

    /**
     * Holder side: retire only acknowledged ids and end the round after its full batch is acked.
     *
     * An acknowledged id is also news for the message's author: the requester now holds it. When this device
     * wrote the message, that is a delivery like any other (chat/group sync audit, step 2). Before, this handler
     * only updated the round's in-memory sets, so a member that caught up straight from its author never moved
     * the author's tick.
     */
    private suspend fun handleSyncAck(frame: GroupWireFrame.SyncAck) {
        val round = syncRounds[frame.syncId] ?: return
        val acknowledged = frame.messageIds.filter { it in round.messageIds }
        round.acknowledgedMessageIds.addAll(acknowledged)
        val now = timeSource.nowMs()
        acknowledged.forEach { recordGroupDelivery(it, frame.from, now) }
        val ackState = GroupSyncRoundState(round.messageIds.toSet(), round.acknowledgedMessageIds.toSet())
        if (ackState.isComplete) {
            syncRounds.remove(frame.syncId, round)
            syncRequesters.remove(frame.syncId)
        }
    }

    /**
     * [memberId] holds [messageId]: mark its delivery row done and, when that was the last one, retire the message.
     *
     * Only a message this device wrote has delivery rows, so a name this device merely holds (a sync ack lists
     * every message the round pushed, whoever wrote it) or no longer tracks changes nothing. The guard also stops
     * a receipt that names some other message id (a direct message, say) from marking it delivered and dropping
     * its outbox row. Idempotent: a repeated receipt re-evaluates the same rows.
     */
    private suspend fun recordGroupDelivery(messageId: String, memberId: String, deliveredAt: Long) {
        val deliveries = groupDeliveryDao ?: return
        if (deliveries.memberCount(messageId) == 0) return
        deliveries.markDelivered(messageId, memberId, deliveredAt)
        if (deliveries.pendingForMessage(messageId).isEmpty()) {
            messageDao.updateStatusIfUnacknowledged(messageId, "DELIVERED")
            outboxDao.delete(messageId)
        }
    }

    /** Catch-up requests this device sent, keyed by `syncId` (ADR-044 V1a, finding F-4). */
    private val outgoingSyncRequests = SyncMap<String, OutgoingSyncRequest>()

    /**
     * Requester side of a page marker (ADR-100): once every row the holder said it pushed has been seen, the watermark for
     * that holder moves to the page end (never earlier: a page that lost a frame leaves it, and the next session resumes
     * from it), the banner learns how many rows remain, and a page that is not the last asks for the next one.
     */
    private suspend fun handleSyncPage(frame: GroupWireFrame.SyncPage) {
        val request = outgoingSyncRequests[frame.syncId]
        if (request == null || !request.acceptsPush(frame.groupId, frame.from, timeSource.nowMs()) || request.windowMs == null) {
            FlashLog.w("CHAT", "Group SyncPage dropped: no matching request (syncId=${frame.syncId} from=${frame.from})")
            return
        }
        val dao = groupHistoryDao ?: return
        syncPagedHolders.add("${frame.groupId}|${frame.from}")
        val tally = request.tally
        // Frames of one session arrive in order, but handling is not serialised, so give the last push a moment to land.
        val complete = withTimeoutOrNull(SYNC_PAGE_WAIT_MS) {
            tally.seen.first { it >= frame.count }
        } != null
        val seen = tally.seen.value
        val newest = tally.newest
        val now = timeSource.nowMs()
        catchUpLanes[frame.groupId]?.progress(frame.from, 0, now)
        // G12: the marker is believed only as far as the pushes it announced. A claimed end past the newest row actually
        // received is cut back to it, and a scan end with no rows (a count-0 page) is refused when it lies in the future.
        val claimedEnd = if (frame.count > 0 || frame.more) GroupSyncCursor(frame.lastSentAt, frame.lastMessageId) else null
        val pageEnd = when {
            claimedEnd == null -> null
            frame.count > 0 -> if (newest != null && claimedEnd > newest) newest else claimedEnd
            claimedEnd.sentAt > now + MARKER_END_SKEW_MS -> null
            else -> claimedEnd
        }
        if (complete && pageEnd != null) {
            val current = dao.watermark(frame.groupId, frame.from)?.let { GroupSyncCursor(it.sentAt, it.messageId) }
            val next = GroupHistoryPolicy.advanceWatermark(current, pageEnd, frame.count, seen)
            if (next != null && next != current) {
                dao.upsertWatermark(GroupSyncWatermarkEntity(frame.groupId, frame.from, next.sentAt, next.messageId, now))
            }
        }
        FlashProbe.emit(
            "group.sync.page", "group" to FlashProbe.short(frame.groupId), "holder" to FlashProbe.short(frame.from),
            "count" to frame.count, "seen" to seen, "remaining" to frame.remaining, "more" to frame.more, "complete" to complete,
        )
        recordCatchUpPage(frame.groupId, if (frame.more) frame.remaining else null)
        if (complete && frame.more && pageEnd != null && request.pageNo < GroupHistoryPolicy.MAX_CONTINUATION_PAGES) {
            sendHistoryRequest(
                frame.from, frame.groupId, pageEnd,
                GroupHistoryChoice(request.windowMs, request.includeFiles ?: true),
                continuation = true, pageNo = request.pageNo + 1,
            )
            return
        }
        if (complete && !frame.more) {
            // G1: the contact is recorded PER HOLDER, in its watermark (an empty chain leaves a watermark at the start of time,
            // which asks from the window floor exactly as no watermark does), so one holder finishing no longer shortens the
            // window another holder is asked for.
            val mark = dao.watermark(frame.groupId, frame.from)
            dao.upsertWatermark(mark?.copy(updatedAtMs = now) ?: GroupSyncWatermarkEntity(frame.groupId, frame.from, 0L, "", now))
            val state = dao.state(frame.groupId)
            // A row made here (a member from before ADR-100) is a returning member that was served the 7-day floor.
            dao.upsertState(
                (state ?: GroupHistoryStateEntity(frame.groupId, HISTORY_DECIDED, GroupHistoryPolicy.RETURNING_FLOOR_MS, true, now, 0L, now))
                    .copy(lastContactAtMs = now),
            )
        }
        finishLane(frame.groupId, frame.from, complete)
    }

    private fun recordOutgoingSync(
        syncId: String,
        groupId: String,
        askedPeerId: String,
        windowMs: Long? = null,
        includeFiles: Boolean? = null,
        pageNo: Int = 0,
    ) {
        val now = timeSource.nowMs()
        outgoingSyncRequests[syncId] = OutgoingSyncRequest(groupId, askedPeerId, now, windowMs, includeFiles, pageNo)
        OutgoingSyncRequest.keysToDrop(outgoingSyncRequests.toMap(), now).forEach { outgoingSyncRequests.remove(it) }
    }

    /** True when this device already holds any record of [groupId], active or not. */
    private suspend fun isKnownGroup(members: GroupMemberDao, groupId: String): Boolean =
        conversationDao.get(groupId) != null || members.member(groupId, localDeviceId) != null

    /** Paired **and** an active member: legacy membership frames, group media and attachment sends (ADR-044 V2, E3). */
    private suspend fun isActiveTrustedMember(
        members: GroupMemberDao,
        groupId: String,
        deviceId: String,
    ): Boolean = isTrustedPeer(deviceId) && members.member(groupId, deviceId)?.isActive == true

    /**
     * ADR-044 V2 (E3): a peer this device accepts group traffic from. Paired, or an active member of a v2 group whose
     * verified certificate names the very key the peer's live TLS session presented. It never reads the pin store, so
     * a device that only knows a member's id, or connected as that id before the vouch replaced its pin, is refused.
     * Public so a host can hand the same predicate to the call layer (a vouched member may join a group call).
     */
    public suspend fun isGroupPeerTrusted(groupId: String, deviceId: String): Boolean =
        isTrustedPeer(deviceId) ||
            signedGroups?.isVouchedMember(groupId, deviceId, peerIdentityKey(deviceId)) == true

    /**
     * [isGroupPeerTrusted] and an active roster row: what group text, receipts, reads, deletes, typing and sync require.
     * A device that is itself no longer a member takes none of it (it left, or the owner removed it), so a member that
     * has not yet heard of the removal cannot keep feeding it messages it would otherwise store.
     */
    private suspend fun isActiveGroupMember(
        members: GroupMemberDao,
        groupId: String,
        deviceId: String,
    ): Boolean = !isRemovedHere(members, groupId) &&
        isGroupPeerTrusted(groupId, deviceId) && members.member(groupId, deviceId)?.isActive == true

    /** True when this device's own roster row says it is no longer a member: it left, or the owner removed it. */
    private suspend fun isRemovedHere(members: GroupMemberDao, groupId: String): Boolean =
        members.member(groupId, localDeviceId)?.isActive == false

    /**
     * A peer allowed into [groupId]'s calls: trusted in the group (paired, or vouched with a matching live key) **and**
     * an active roster member, while this device is itself still a member. [isGroupPeerTrusted] alone is not enough
     * for calls: a paired member the owner removed would otherwise keep ringing and joining the group's calls.
     * Public so a host can hand it to the call layer.
     */
    public suspend fun isGroupCallPeer(groupId: String, deviceId: String): Boolean {
        val members = groupMemberDao ?: return isGroupPeerTrusted(groupId, deviceId)
        return isActiveGroupMember(members, groupId, deviceId)
    }

    /**
     * Whether [deviceId] is somebody a call of [groupId] is offered to at all (ERROR-088): an active roster member this
     * device already trusts (paired) or has been introduced to by the group owner (a verified certificate names a key),
     * while this device is itself still a member. Unlike [isGroupCallPeer] it does not need a live session, so it
     * answers "who is in this group's call", not "may this frame go to, or come from, this connection". The call
     * layer builds a call's member list with it and applies [isGroupCallPeer] when it sends or receives an announcement:
     * a member the caller is not paired with and has no session to yet can still be dialed and invited.
     */
    public suspend fun isGroupCallMember(groupId: String, deviceId: String): Boolean {
        val members = groupMemberDao ?: return isGroupPeerTrusted(groupId, deviceId)
        if (isRemovedHere(members, groupId)) return false
        if (members.member(groupId, deviceId)?.isActive != true) return false
        return isTrustedPeer(deviceId) || signedGroups?.hasVouchedRosterKey(groupId, deviceId) == true
    }

    /**
     * The names of group [groupId]'s active members as the roster stores them (device id to display name), so a call
     * can label a member this device is not paired with, instead of "Member (a1b2)".
     */
    public suspend fun groupRosterNames(groupId: String): Map<String, String> {
        val members = groupMemberDao ?: return emptyMap()
        return members.activeMembers(groupId)
            .filter { it.displayName.isNotBlank() }
            .associate { it.deviceId to it.displayName }
    }

    private fun selfMembershipOf(row: GroupMemberEntity?): FlashSelfMembership = when {
        row == null || row.isActive -> FlashSelfMembership.Active
        // A leave is issued by the leaver (v2) or carries no issuer (legacy); only an owner tombstone is a removal.
        row.issuerId == null || row.issuerId == localDeviceId -> FlashSelfMembership.Left
        else -> FlashSelfMembership.Removed
    }

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
                if (frame.text.length > GroupPolicy.MAX_MESSAGE_TEXT_LENGTH) {
                    FlashLog.w("CHAT", "Inbound 1:1 message exceeds length cap (${frame.text.length} > ${GroupPolicy.MAX_MESSAGE_TEXT_LENGTH}), dropping")
                    return
                }
                // conversationId doubles as the transport routing key (a device id). The sender
                // addressed US by OUR id, so `frame.conversationId` is the receiver's own device id
                // — threading the message under it would key our reply's routing to ourselves (the
                // "one device can't send back" bug). The correct local thread id is the AUTHOR's
                // device id (`frame.senderId`), which is the remote peer from our side and the id our
                // outbound sends must target. Mirrors the delivery-receipt routing fix below.
                val threadId = frame.senderId
                val now = timeSource.nowMs()
                val boundedSentAt = minOf(frame.sentAt, now)
                val entity = MessageEntity(
                    localId = frame.localId,
                    conversationId = threadId,
                    senderId = frame.senderId,
                    senderName = frame.senderName,
                    text = frame.text,
                    sentAt = boundedSentAt,
                    status = "DELIVERED",
                    replyToId = frame.replyToId,
                    replyToPreview = frame.replyToPreview,
                )
                // -1 == IGNORE-conflict: this localId already exists (replayed frame after a
                // reconnect). Only a fresh row notifies (Bug 7 dedupe guarantee).
                // ERROR-087: the row is refreshed, not replaced (its read cursor, pin and mute are the device's own),
                // and in the SAME transaction as the insert so the open chat acknowledging this message cannot be
                // overwritten by a stale copy of the row.
                var insertedRowId = -1L
                runInTransaction {
                    insertedRowId = messageDao.insert(entity)
                    upsertDirectConversation(
                        conversationId = threadId,
                        title = peerNameResolver(threadId)?.ifBlank { null }
                            ?: frame.senderName?.ifBlank { null }
                            ?: frame.senderId,
                        sortOrder = boundedSentAt,
                    )
                }
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
                    if (!isActiveGroupMember(members, frame.conversationId, frame.memberId)) return
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
                val now = timeSource.nowMs()
                val message = messageDao.getByLocalId(frame.messageId)
                if (message != null) {
                    if (message.conversationId != peerId || message.senderId != frame.from) return
                    messageDao.markDeleted(frame.messageId, now)
                } else {
                    messageDao.insert(
                        MessageEntity(
                            localId = frame.messageId,
                            conversationId = peerId,
                            senderId = frame.from,
                            senderName = null,
                            text = "",
                            sentAt = now,
                            status = "DELIVERED",
                            deletedAt = now,
                        ),
                    )
                }
                messageDao.clearReplyPreviews(frame.messageId)
                outboxDao.delete(frame.messageId)
            }

            is MessageWireFrame.ReactionFrame -> {
                if (transportPeerId != null && frame.memberId != transportPeerId) return
                if (conversationDao.get(frame.conversationId)?.isGroup == true) {
                    val members = groupMemberDao ?: return
                    if (!isActiveGroupMember(members, frame.conversationId, frame.memberId)) return
                } else {
                    if (transportPeerId != null && !isTrustedPeer(transportPeerId)) return
                }
                val msg = messageDao.getByLocalId(frame.messageId)
                if (msg != null && msg.conversationId != frame.conversationId) return
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
        val scheduled = mutableListOf<Long>()
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
            val queuedForMs = maxOf(0L, now - item.createdAt)
            if (conversation?.isGroup == true) {
                // The group branch re-arms on the same ladder as the direct one and must tell the loop so,
                // or its retry waits for the idle net (chat/group sync audit, step 1).
                drainGroupMessage(item, message, now, queuedForMs)?.let { nextAttemptAt ->
                    scheduled += nextAttemptAt
                }
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
            scheduled += nextAttemptAt
        }
        // Wake for the earliest deadline still ahead of us, which is not necessarily one this pass set: a row
        // that was not yet due carries a deadline this pass never saw, and forgetting it would sleep straight
        // past that row. So every future deadline is kept, not only the earliest (see [outboxDeadlines]). One
        // already in the past belongs to a row that was acknowledged and deleted, or that this or a later pass
        // has rescheduled further out; it is dropped here rather than left to pin the loop at
        // [OutboxDrainSchedule.MIN_WAIT_MS] forever.
        outboxDeadlines.removeAll { it <= now }
        outboxDeadlines.addAll(scheduled)
        outboxNextDueAt = outboxDeadlines.minOrNull()
        items.size >= OUTBOX_BATCH_LIMIT
    }

    /** @return when this row is next due, or null when the pass ended it (given up, delivered, or unsendable). */
    private suspend fun drainGroupMessage(
        item: OutboxEntity,
        message: MessageEntity,
        now: Long,
        queuedForMs: Long,
    ): Long? {
        val deliveries = groupDeliveryDao ?: return null
        val sink = groupTransportSink ?: return null
        if (queuedForMs >= OUTBOX_GIVE_UP_AFTER_MS) {
            val delivered = deliveries.deliveredCount(item.localId)
            if (delivered == 0) {
                messageDao.updateStatusIfUnacknowledged(item.localId, "FAILED")
            }
            outboxDao.delete(item.localId)
            return null
        }
        val pending = deliveries.pendingForMessage(item.localId)
        if (pending.isEmpty()) {
            messageDao.updateStatusIfUnacknowledged(item.localId, "DELIVERED")
            outboxDao.delete(item.localId)
            return null
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
            signature = message.groupSig,
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
        val nextAttemptAt = now + backoffDelayMs(item.attempts + 1)
        outboxDao.rescheduleAttempt(item.localId, nextAttemptAt)
        return nextAttemptAt
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
            peerDeviceId?.let {
                currentConnectedPeers.add(it)
                groupDeliveryDao?.makePendingDueForMember(it, now)
                onPeerSessionUp(it)
            }
            notifyOutboxDrain()
            drainOutboxOnce()
        }
    }

    public fun notifyPeerSessionDown(peerDeviceId: String) {
        currentConnectedPeers.remove(peerDeviceId)
        groupProofSessions?.onSessionDown(peerDeviceId)
    }

    override fun openAttachmentPicker() {}

    private fun escapeSqlLike(query: String): String {
        return query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    override suspend fun searchMessageBodies(query: String): Set<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptySet()
        val escaped = escapeSqlLike(trimmed)
        // Case-insensitive substring match over ALL message bodies (LIKE is case-insensitive for
        // ASCII in SQLite), newest-first, capped. Collapse to the distinct set of conversations so
        // the chat list can surface a thread whose only match is deep in history (#12). Tombstoned
        // rows are already excluded by the query.
        return kotlinx.coroutines.withContext(ioDispatcher) {
            messageDao.searchMessages(escaped, limit = SEARCH_RESULT_LIMIT)
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
        val escaped = escapeSqlLike(trimmed)
        return kotlinx.coroutines.withContext(ioDispatcher) {
            messageDao.searchConversationMessages(conversationId, escaped, limit)
                .map { it.localId }
        }
    }

    override fun toggleReaction(messageId: String, emoji: String) {
        val activeConv = activeConversationId
        scope.launch(ioDispatcher) {
            val conversationId = messageDao.getByLocalId(messageId)?.conversationId
                ?: activeConv
                ?: return@launch
            val isGroup = conversationDao.get(conversationId)?.isGroup == true
            val members = groupMemberDao
            if (isGroup && members != null && isRemovedHere(members, conversationId)) {
                return@launch
            }
            // Toggle our own reaction: if we already reacted with this emoji, remove it; else add it.
            val existing = reactionDao.get(messageId, emoji)
            val currentlySelf = existing?.selfReacted == true ||
                (existing != null && localDeviceId in decodeReactorIds(existing.reactorIdsJson))
            val isAdded = !currentlySelf
            applyReactionDelta(messageId, emoji, localDeviceId, isAdded)
            val frame = MessageWireFrame.ReactionFrame(
                messageId = messageId,
                conversationId = conversationId,
                memberId = localDeviceId,
                emoji = emoji,
                isAdded = isAdded,
            )
            if (isGroup) {
                members?.activeMembers(conversationId)
                    ?.filter { it.deviceId != localDeviceId && isGroupPeerTrusted(conversationId, it.deviceId) }
                    ?.forEach { member -> transportSink?.send(member.deviceId, frame) }
            } else {
                // Broadcast the delta so the peer's aggregate matches (#7).
                transportSink?.send(conversationId, frame)
            }
        }
    }

    override fun setTyping(isTyping: Boolean) {
        val conversationId = activeConversationId ?: return
        scope.launch(ioDispatcher) {
            val isGroup = conversationDao.get(conversationId)?.isGroup == true
            val members = groupMemberDao
            if (isGroup && members != null && isRemovedHere(members, conversationId)) {
                return@launch
            }
            val frame = MessageWireFrame.TypingFrame(
                conversationId = conversationId,
                memberId = localDeviceId,
                memberName = localDisplayName,
                isTyping = isTyping,
                timestampMs = timeSource.nowMs(),
            )
            if (isGroup) {
                members?.activeMembers(conversationId)
                    ?.filter { it.deviceId != localDeviceId && isGroupPeerTrusted(conversationId, it.deviceId) }
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
        // ERROR-087: read-modify-write of the row; in its own write transaction so a read cursor written in between
        // (the open chat acknowledging the message just inserted) is not overwritten with the stale copy.
        runInTransaction { touchConversationInTransaction(conversationId, now, directFallbackTitle) }
    }

    /** [touchConversation] for a caller that is already inside a [runInTransaction] block (a transaction does not nest here). */
    private suspend fun touchConversationInTransaction(
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

    /**
     * ERROR-087: creates or refreshes a DIRECT chat's row WITHOUT replacing what only this device decides about it.
     * `@Upsert` is a full-row replace, so a freshly built [ConversationEntity] reset `lastReadCursor` on every inbound or
     * outbound text (a NULL cursor counts every message the peer ever sent as unread) and put `pinned` / `muted` back to
     * false. A new message brings an archived chat back, as [touchConversationInTransaction] does.
     *
     * Run it inside the same [runInTransaction] block as the message insert it accompanies: it reads the row and writes it
     * back, and a cursor written in between would otherwise be lost.
     */
    private suspend fun upsertDirectConversation(conversationId: String, title: String?, sortOrder: Long) {
        val existing = conversationDao.get(conversationId)
        conversationDao.upsert(
            existing?.copy(title = title ?: existing.title, sortOrder = sortOrder, archived = false)
                ?: ConversationEntity(id = conversationId, title = title ?: conversationId, isGroup = false, sortOrder = sortOrder),
        )
    }

    /**
     * ERROR-087: the legacy (v1) group `Create` / `State` write. An existing row keeps its position, provenance, archive,
     * pin, mute and read cursor and only takes the new [title]; a new row starts at [sortOrder]. Run inside a
     * [runInTransaction] block for the same reason as [upsertDirectConversation].
     */
    private suspend fun upsertLegacyGroupConversation(
        groupId: String,
        title: String,
        sortOrder: Long,
        createdBy: String?,
        createdAt: Long?,
    ) {
        val existing = conversationDao.get(groupId)
        conversationDao.upsert(
            existing?.copy(
                title = title,
                isGroup = true,
                groupCreatedBy = existing.groupCreatedBy ?: createdBy,
                groupCreatedAt = existing.groupCreatedAt ?: createdAt,
            ) ?: ConversationEntity(
                id = groupId,
                title = title,
                isGroup = true,
                sortOrder = sortOrder,
                groupCreatedBy = createdBy,
                groupCreatedAt = createdAt,
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
            messageDao.clearReplyPreviews(localId)
            outboxDao.delete(localId)

            if (conversation.isGroup) {
                onGroupMessageDeletedForEveryone?.invoke(conversation.id, localId)
                val members = groupMemberDao ?: return@launch
                val frame = GroupWireFrame.DeleteForEveryone(
                    groupId = conversation.id,
                    messageId = localId,
                    from = localDeviceId,
                )
                members.activeMembers(conversation.id)
                    .filter { it.deviceId != localDeviceId && isGroupPeerTrusted(conversation.id, it.deviceId) }
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
            list.forEach { messagePinDao?.clearConversation(it) }
            list.forEach {
                groupHistoryDao?.deleteState(it)
                groupHistoryDao?.deleteWatermarks(it)
            }
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
                val anyCanGoOffline = recipientTransfers.any { it.canGoOffline }
                val maxHolders = recipientTransfers.maxOfOrNull { it.holdersOnline } ?: 0
                val firstWaitReason = recipientTransfers.firstOrNull { it.waitReason != null }?.waitReason
                val firstError = recipientTransfers.firstOrNull { !it.errorMessage.isNullOrBlank() }?.errorMessage
                val sumDone = recipientTransfers.sumOf { it.bytesDone }
                val sumTotal = recipientTransfers.sumOf { it.bytesTotal }
                FlashAttachmentProgress(
                    progress = avgProgress,
                    status = status,
                    localPath = path,
                    speedMbps = totalSpeed,
                    etaSeconds = maxEta,
                    waitReason = firstWaitReason,
                    canGoOffline = anyCanGoOffline,
                    holdersOnline = maxHolders,
                    errorMessage = firstError,
                    bytesDone = sumDone,
                    bytesTotal = sumTotal,
                    pieceBlocks = recipientTransfers.firstOrNull { it.pieceBlocks.isNotEmpty() }?.pieceBlocks.orEmpty(),
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
            else -> {
                val recipientView = if (base.isMine && live != null && live.recipients.isNotEmpty() &&
                    status != FlashFileTransferStatus.Downloaded
                ) {
                    buildFileRecipientView(
                        recipients = live.recipients,
                        knownRecipientCount = base.deliveredTotal ?: 0,
                        nameOf = { id -> openGroupMemberNames[id] ?: peerNameResolver(id) },
                    )
                } else null
                val detailLine = if (recipientView != null) {
                    recipientView.summaryLine(live?.canGoOffline == true)
                } else if (base.isMine) {
                    if (live?.canGoOffline == true && status != FlashFileTransferStatus.Downloaded) {
                        if (base.deliveredTo != null && base.deliveredTotal != null && base.deliveredTotal > 0) {
                            "Delivered to ${base.deliveredTo} of ${base.deliveredTotal} · You can go offline now"
                        } else {
                            "You can go offline now"
                        }
                    } else if (base.deliveredTo != null && base.deliveredTotal != null && base.deliveredTotal > 0 && status != FlashFileTransferStatus.Downloaded) {
                        "Delivered to ${base.deliveredTo} of ${base.deliveredTotal}"
                    } else if (!live?.errorMessage.isNullOrBlank()) {
                        live?.errorMessage
                    } else null
                } else {
                    if (live?.waitReason != null) {
                        when (live.waitReason) {
                            "WaitingForSender" -> {
                                val done = live.bytesDone
                                val total = entity.attachmentSize
                                if (done > 0L && total > 0L) {
                                    TransferFailureText.waitingForSenderProgress(base.senderName, done, total)
                                } else {
                                    TransferFailureText.waitingForSender(base.senderName)
                                }
                            }
                            "WaitingForHolders" -> TransferFailureText.WAITING_FOR_MISSING_PARTS
                            "WaitingForNetwork" -> TransferFailureText.WAITING_FOR_WIFI
                            "WaitingForSpace" -> "Not enough free space"
                            "WaitingForStorage" -> TransferFailureText.STORAGE_UNAVAILABLE
                            "WaitingForSystem" -> TransferFailureText.SYSTEM_TIMEOUT
                            "WaitingForSession" -> TransferFailureText.CONNECTING_MEMBERS
                            else -> null
                        }
                    } else if (live == null && path == null && entity.swarmRoot != null && swarmAnnouncementListener == null) {
                        // ERROR-120: the offer is kept but nothing here can fetch it; say so instead of a dead "Tap to download".
                        SWARM_OFF_DETAIL
                    } else if ((live?.holdersOnline ?: 0) > 1 && status == FlashFileTransferStatus.Transferring) {
                        "Getting it from ${live?.holdersOnline} devices"
                    } else if (!live?.errorMessage.isNullOrBlank()) {
                        live?.errorMessage
                    } else null
                }
                base.copy(
                    fileAttachments = listOf(
                        FlashFileAttachmentUi(
                            id = transferId,
                            name = name,
                            sizeBytes = entity.attachmentSize,
                            mimeType = mime,
                            transferStatus = status,
                            // A sender's own bytes are always complete; what it is waiting on is the members' copies.
                            transferProgress = recipientView?.meanProgress ?: progress,
                            transferSpeedMbps = live?.speedMbps ?: 0f,
                            etaSeconds = live?.etaSeconds ?: 0,
                            localUri = path,
                            waitReason = live?.waitReason,
                            canGoOffline = live?.canGoOffline ?: false,
                            holdersOnline = live?.holdersOnline ?: 0,
                            detailLine = detailLine,
                            pieceBlocks = live?.pieceBlocks.orEmpty(),
                            recipients = recipientView?.rows.orEmpty(),
                            recipientsTotal = recipientView?.total ?: 0,
                        ),
                    ),
                )
            }
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

    /** Why a group was not created while an invitee is offline (ERROR-095): the names, so the owner knows whom to wait for. */
    private fun membersOfflineMessage(offline: List<String>): String {
        val names = offline.joinToString(", ") { peerNameResolver(it)?.takeIf { name -> name.isNotBlank() } ?: it }
        return "Wait until $names show as online, then try again. A group made while someone is offline " +
            "can't include them in calls."
    }

    private companion object {
        /** GM-8: Default timeout for address hint dialing before falling back to discovery (M-03). */
        const val HINT_DIAL_TIMEOUT_MS: Long = 30_000L
        const val INVITE_PROOF_MAX_RETRIES: Int = 3

        /** One "you were added but cannot join" notice per group per ten minutes, however often the bundle is resent. */
        const val OFFER_REFUSED_NOTICE_INTERVAL_MS: Long = 10 * 60_000L
        const val INVITE_PROOF_RETRY_DELAY_MS: Long = 3_000L

        /** Why a v2 group could not be created or extended: the invitee's key is not verifiably available. */
        const val V2_KEY_UNAVAILABLE: String =
            "Connect to every invited device and try again (its security key could not be verified)"

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
        /** Longest file name a catch-up label carries. */
        const val SYNC_LABEL_NAME_MAX = 80

        const val HISTORY_PENDING = GroupHistoryPolicy.STATE_PENDING
        const val HISTORY_DECIDED = GroupHistoryPolicy.STATE_DECIDED
        const val HISTORY_SKIPPED = GroupHistoryPolicy.STATE_SKIPPED

        /** How long a page marker waits for the pushes it counts (ADR-100); frames of one session normally arrive in order. */
        const val SYNC_PAGE_WAIT_MS = 5_000L

        /** G12: a page marker whose scan end lies further than this in the future is not believed. */
        const val MARKER_END_SKEW_MS = 5L * 60L * 1000L

        /** Floor-start requests a holder that never pages may cost per process (ADR-100). */
        const val SYNC_FLOOR_ATTEMPTS = 3

        /** Detail line of a group file whose offer is kept but cannot be fetched because swarm is off on this device. */
        const val SWARM_OFF_DETAIL = "Group file sharing is off on this device"

        /** How far into this device's future a signed message's own `sentAt` may be and still be stored as signed. */
        const val SIGNED_SENT_AT_SKEW_TOLERANCE_MS = 5 * 60_000L
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

    internal fun signedGroupsForTesting(): SignedGroups? = signedGroups
}
