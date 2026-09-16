package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.theme.FlashText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashQuotedReplyUi
import com.transfer.flash.core.messaging.model.FlashReaction
import com.transfer.flash.core.messaging.util.sampleFlashConversationState
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.shims.FlashPermission
import com.transfer.flash.ui.shims.rememberFlashClipboard
import com.transfer.flash.ui.shims.rememberFlashFilePickerLauncher
import com.transfer.flash.ui.shims.rememberFlashPermissionRequester
import com.transfer.flash.ui.shims.rememberFlashVoiceRecorder
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun FlashConversationScreen(
    state: FlashConversationUiState,
    onBack: () -> Unit,
    /**
     * UI-032: legacy host hook for the peer-details affordance. The details are now shown by an
     * in-screen [FlashPeerDetailsSheet] driven by the header, so this defaults to a no-op; hosts
     * may still observe the tap. Trust status/revoke arrive via [isPeerTrusted]/[onRevokePeerTrust].
     */
    onOpenPeerDetails: () -> Unit = {},
    /** UI-032: whether the 1:1 peer is a paired/trusted device (drives the details sheet). */
    isPeerTrusted: Boolean = false,
    /** UI-032: drop the pairing for this peer, supplied by :app when [isPeerTrusted]. */
    onRevokePeerTrust: (() -> Unit)? = null,
    onSendText: (String) -> Unit,
    /**
     * Send a reply/quote (#8): body plus the quoted message's id and a short preview snapshot.
     * Defaults to routing through [onSendText] so lightweight callers/previews still send the body.
     */
    onSendReply: (text: String, replyToId: String, replyToPreview: String) -> Unit =
        { text, _, _ -> onSendText(text) },
    /**
     * Persist the current unsent composer text (#9) when leaving the screen, so it is restored via
     * [FlashConversationUiState.draftText] on the next open. Default no-op for previews.
     */
    onPersistDraft: (String) -> Unit = {},
    /**
     * Persist + broadcast a reaction toggle (#7). Defaults to no-op; the local optimistic update in
     * [toggleMessageReaction] still runs so the chip flips instantly regardless.
     */
    onToggleReaction: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    /** Broadcast the local user's typing state for the live typing indicator (#11). */
    onTypingChanged: (Boolean) -> Unit = {},
    onAttachmentClick: () -> Unit,
    onSendFile: (uri: String, displayName: String, size: Long) -> Unit = { _, _, _ -> },
    /** Local-only tombstone used by the existing Delete action and multi-select toolbar. */
    onDeleteMessage: (Set<String>) -> Unit = {},
    /** Shared tombstone request; shown only for a locally authored focused message. */
    onDeleteMessageForEveryone: (messageId: String) -> Unit = {},
    /**
     * Open an attachment outside the in-app image viewer: video playback and generic files hand off
     * to the system via an ACTION_VIEW intent (wired in :app). Images keep the in-app viewer (B4).
     */
    onOpenAttachment: (localPath: String?, mimeType: String, fileName: String) -> Unit = { _, _, _ -> },
    /**
     * UI-018: save a viewed image to the device gallery (MediaStore). Wired in :app; default no-op
     * keeps previews inert.
     */
    onSaveImage: (uri: String?, mimeType: String) -> Unit = { _, _ -> },
    /**
     * UI-018: share a viewed image out via the system chooser (ACTION_SEND). Wired in :app; default
     * no-op keeps previews inert.
     */
    onShareImage: (uri: String?, mimeType: String) -> Unit = { _, _ -> },
    /**
     * B9: send a captured voice note. [localPath] is a `file://` URI to the recorded audio; the
     * host (:app) routes it through the transfer pipeline + writes a chat row (wired in MainActivity).
     */
    onSendVoiceMessage: (localPath: String, durationMs: Long, amplitudes: List<Int>) -> Unit = { _, _, _ -> },
    /**
     * Host-owned mic arbitration. Return an opaque lease id when capture is acquired, or null when
     * PTT/calling owns it; [onVoiceRecordingStopped] releases only that matching acquisition.
     */
    onVoiceRecordingStarting: () -> String? = { "preview" },
    onVoiceRecordingStopped: (String) -> Unit = {},
    /**
     * Accept a pending inbound file/video offer from within the chat bubble (Bug 3). [file.id] is
     * the wire transferId; the host (:app) routes it to the transfer repository's acceptIncoming.
     * Default no-op keeps previews inert.
     */
    onAcceptOffer: (transferId: String) -> Unit = {},
    /**
     * Decline a pending inbound file/video offer from within the chat bubble (Bug 3). [transferId]
     * routes to the transfer repository's declineIncoming. Default no-op keeps previews inert.
     */
    onDeclineOffer: (transferId: String) -> Unit = {},
    /**
     * Retry a failed attachment transfer from its chat bubble. [transferId] is the attachment id;
     * the host (:app) routes it to the transfer repository's resumeTransfer, which restarts the
     * send from whatever the receiver already has. Default no-op keeps previews inert.
     */
    onRetryTransfer: (transferId: String) -> Unit = {},
    /**
     * C7: start a voice call with the conversation's peer (1:1 only). The host (:app) routes this
     * to the engine's CallCoordinator + starts the call foreground service. Default no-op keeps
     * previews inert.
     */
    onStartCall: () -> Unit = {},
    /**
     * C7: start a video call with the conversation's peer (1:1 only). Default no-op keeps
     * previews inert.
     */
    onStartVideoCall: () -> Unit = {},
    /**
     * Whether the header's video button renders (Phase 33a). Defaulted true so existing
     * hosts are unchanged; a host that only implements voice calls passes false rather
     * than showing a button with nowhere to go.
     */
    showVideoCallAction: Boolean = true,
    /**
     * Joins an ongoing group call advertised by peer presence.
     */
    onJoinGroupCall: (callId: String, video: Boolean) -> Unit = { _, _ -> },
    /**
     * Re-arm the P2P link behind the connection banner's Retry button: the host (:app) restarts
     * discovery browsing and re-dials known peers. Returns false when the engine has not booted, so
     * the banner can say so instead of pretending. Default reports "nothing to retry".
     */
    onRetryConnection: () -> Boolean = { false },
    /**
     * Forward message text out of the app via the system chooser (ACTION_SEND). In-app forwarding
     * needs a conversation picker that does not exist yet; sharing is the honest action behind a
     * "Forward" button until it does. Default no-op keeps previews inert.
     */
    onShareText: (String) -> Unit = {},
    /**
     * Group Phase D: add trusted peers to this group (empty members is a no-op). Default no-op
     * keeps previews inert; the host routes to the repository's addGroupMembers.
     */
    onAddGroupMembers: (groupId: String, memberIds: Set<String>) -> Unit = { _, _ -> },
    /**
     * Group Phase D: leave this group. Default no-op keeps previews inert; the host routes to the
     * repository's leaveGroup and navigates back on success.
     */
    onLeaveGroup: (groupId: String) -> Unit = {},
    /**
     * Group Phase D: trusted peers that could be added to this group (host filters out current
     * members); drives the Add-members sheet's roster. Default empty keeps previews inert.
     */
    addablePeers: List<FlashCreateGroupPeerUi> = emptyList(),
    /**
     * Group Phase D: clear (hard-delete) this conversation, behind the direct-chat menu.
     * Default no-op keeps previews inert; the host routes to deleteConversations + nav back.
     */
    onClearConversation: (conversationId: String) -> Unit = {},
    /** Mark this conversation unread while keeping the thread open. */
    onMarkUnread: (conversationId: String) -> Unit = {},
    /**
     * Group Phase D: the id of the conversation this screen renders. Required for the group menu
     * actions (add members / leave) to address the right group; null keeps previews inert.
     */
    conversationId: String? = null,
) {
    val motion = FlashTheme.motion
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // D7a: every transient message on this screen goes through one SnackbarHostState instead of the
    // 13 Toast.makeText calls it used to make. Dismissing the current message before showing the next
    // keeps Toast's newest-wins feel: SnackbarHostState otherwise queues, so a message raised while
    // another was showing would surface seconds after the action that caused it.
    val snackbarHostState = remember { SnackbarHostState() }
    fun showMessage(text: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message = text, duration = SnackbarDuration.Short)
        }
    }

    val clipboard = rememberFlashClipboard()
    fun copyText(text: String) {
        clipboard.copy(text)
        showMessage("Copied to clipboard")
    }

    // UI-012: system document picker for attachments. The shim resolves the display name + size from
    // the platform (content resolver on Android) and takes the persistable read grant, so the transfer
    // can still stream the file after this screen dies; the callback fires only when a file was picked.
    val filePicker = rememberFlashFilePickerLauncher { picked ->
        onSendFile(picked.uri, picked.name, picked.size)
        showMessage("Sending ${picked.name}")
    }

    // B9: real microphone capture for voice messages. The recorder lives at screen scope so its
    // encoder survives the composer's gesture recompositions; released when the screen leaves.
    val voiceRecorder = rememberFlashVoiceRecorder()
    var voiceRecordingLeaseId by remember { mutableStateOf<String?>(null) }
    fun releaseVoiceRecordingLease() {
        val leaseId = voiceRecordingLeaseId ?: return
        voiceRecordingLeaseId = null
        onVoiceRecordingStopped(leaseId)
    }
    DisposableEffect(voiceRecorder) {
        onDispose {
            voiceRecorder.cancel()
            releaseVoiceRecordingLease()
        }
    }
    // Microphone access is requested lazily on the first hold. We can't retroactively start the
    // capture the user just attempted, so a granted result simply enables the next hold to record.
    val permissions = rememberFlashPermissionRequester()

    var localMessages by remember(state.messages) { mutableStateOf(state.messages) }
    var draft by remember { mutableStateOf("") }
    // #11: whether we've told the peer we're currently typing, so we only send on transitions.
    var isTypingSignalled by remember { mutableStateOf(false) }

    // #9: seed the composer from the persisted draft the first time it arrives (and the user has
    // not started typing). Keyed on the restored text so a late-arriving draft flow still lands.
    LaunchedEffect(state.draftText) {
        if (draft.isEmpty() && state.draftText.isNotEmpty()) {
            draft = state.draftText
        }
    }
    // Persist the in-progress composer text when the screen leaves (navigation / process death).
    // rememberUpdatedState keeps the dispose closure reading the latest draft, not the initial "".
    val latestDraft by rememberUpdatedState(draft)
    DisposableEffect(Unit) {
        onDispose { onPersistDraft(latestDraft) }
    }
    var focusedMessage by remember { mutableStateOf<FlashMessageUi?>(null) }
    var replyingToMessage by remember { mutableStateOf<FlashMessageUi?>(null) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessageIds by remember { mutableStateOf(emptySet<String>()) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var mediaViewerVisible by remember { mutableStateOf(false) }
    var mediaViewerItems by remember { mutableStateOf(emptyList<FlashMediaViewerItem>()) }
    var mediaViewerStartIndex by remember { mutableStateOf(0) }

    // UI-029: group member sheet. The roster is derived from the live header (member initials +
    // online count), not from sample data — the older "demo roster" comment here was stale.
    // Known gap: the header carries initials only, so a member's row shows "AC" rather than
    // "Alex Chen" until the repository supplies full member names.
    var showGroupMembers by remember { mutableStateOf(false) }
    // Group Phase B: prefer the repository's real roster (names, online flags, roles). The
    // header-derived fallback keeps older states rendering instead of an empty sheet.
    val groupMembers = remember(state.members, state.header) {
        state.members.ifEmpty { groupMembersFromHeader(state.header) }
    }

    // UI-032: 1:1 peer details sheet (opened from the header avatar for non-group chats).
    var showPeerDetails by remember { mutableStateOf(false) }

    // UI-031: encryption trust sheet, opened from the header badge. Trust state comes from the
    // engine via isPeerTrusted (verified) + header.isEncrypted (channel encrypted). Groups keep
    // the legacy static lock (per-member verification isn't modeled yet).
    var showEncryptionSheet by remember { mutableStateOf(false) }

    // Group Phase D: three-dot menu state. Items are derived per conversation type; a group with
    // only this device left (memberCount <= 1) cannot offer Leave.
    var menuExpanded by remember { mutableStateOf(false) }
    var showAddMembers by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    val menuItems = if (state.header.isGroup) {
        FlashConversationMenuMath.groupItems(canLeave = state.header.memberCount > 1)
    } else {
        FlashConversationMenuMath.directItems(canRevokeTrust = isPeerTrusted && onRevokePeerTrust != null)
    }
    val encryptionState = if (state.header.isGroup) {
        FlashEncryptionBadgeState.None
    } else {
        FlashEncryptionMath.badgeState(
            isEncrypted = state.header.isEncrypted,
            isVerified = isPeerTrusted,
        )
    }

    // UI-023: in-chat search state.
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var activeResultIndex by remember { mutableIntStateOf(-1) }
    val searchMatches = remember(searchQuery, localMessages) {
        FlashChatSearchMath.findMatches(localMessages, searchQuery)
    }

    fun closeSearch() {
        isSearchActive = false
        searchQuery = ""
        activeResultIndex = -1
    }

    val inSelectionMode = selectedMessageIds.isNotEmpty()

    // UI-030: connection health derived from header state. A Connecting peer still counts as one
    // known peer (ERROR-031): the repository now emits Connecting while a dropped session is being
    // re-established, and counting only Online/Typing collapsed that window straight to the
    // "Offline — no peers" banner, contradicting the "Connecting…" the header was showing.
    val connectionHealth = FlashNetworkStatusMath.resolveHealth(
        transport = state.header.transport,
        peerPresence = state.header.presence,
        peerCount = when (state.header.presence) {
            FlashPeerPresence.Online, FlashPeerPresence.Typing, FlashPeerPresence.Connecting -> 1
            else -> 0
        },
    )

    // Hardware back press exits media viewer → search → selection mode
    FlashBackHandler(enabled = mediaViewerVisible) {
        mediaViewerVisible = false
    }
    FlashBackHandler(enabled = isSearchActive && !mediaViewerVisible) {
        closeSearch()
    }
    FlashBackHandler(enabled = inSelectionMode && !isSearchActive && !mediaViewerVisible) {
        selectedMessageIds = emptySet()
    }

    // Auto-clear message highlight after 700ms pulse glow
    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(700L)
            highlightedMessageId = null
        }
    }

    fun jumpToMessage(targetId: String) {
        val targetIndex = localMessages.asReversed().indexOfFirst { it.id == targetId }
        if (targetIndex >= 0) {
            coroutineScope.launch {
                if (motion.reduceMotion) {
                    listState.scrollToItem(targetIndex)
                } else {
                    listState.animateScrollToItem(targetIndex)
                }
                highlightedMessageId = targetId
            }
        }
    }

    fun stepSearchResult(forward: Boolean) {
        if (searchMatches.isEmpty()) return
        activeResultIndex = FlashChatSearchMath.stepIndex(activeResultIndex, searchMatches.size, forward)
        searchMatches.getOrNull(activeResultIndex)?.let { jumpToMessage(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AnimatedContent(
                targetState = inSelectionMode to isSearchActive,
                transitionSpec = { motion.statusCrossfade() },
                label = "conversation_header_swap",
            ) { headerMode ->
                val (isSelecting, isSearching) = headerMode
                when {
                    isSelecting -> {
                        FlashSelectionToolbar(
                            selectedCount = selectedMessageIds.size,
                            onClose = { selectedMessageIds = emptySet() },
                            onCopy = {
                                val selectedTexts = localMessages
                                    .filter { it.id in selectedMessageIds }
                                    .joinToString("\n") { it.text }
                                copyText(selectedTexts)
                                selectedMessageIds = emptySet()
                            },
                            onReply = {
                                val singleId = selectedMessageIds.firstOrNull()
                                val msg = localMessages.firstOrNull { it.id == singleId }
                                if (msg != null) {
                                    replyingToMessage = msg
                                }
                                selectedMessageIds = emptySet()
                            },
                            onForward = {
                                // Was a "Forwarding N messages" toast that forwarded nothing. There
                                // is no in-app conversation picker yet, so hand the selection to the
                                // system chooser — the same fallback the media viewer's Forward uses.
                                val selectedTexts = localMessages
                                    .filter { it.id in selectedMessageIds }
                                    .map { it.text }
                                    .filter { it.isNotBlank() }
                                if (selectedTexts.isEmpty()) {
                                    showMessage("Nothing to forward")
                                } else {
                                    onShareText(selectedTexts.joinToString("\n"))
                                }
                                selectedMessageIds = emptySet()
                            },
                            onDelete = {
                                onDeleteMessage(selectedMessageIds)
                                selectedMessageIds = emptySet()
                            },
                        )
                    }

                    isSearching -> {
                        // UI-023: in-chat search replaces the header while active.
                        FlashChatSearchBar(
                            query = searchQuery,
                            onQueryChanged = { newQuery ->
                                searchQuery = newQuery
                                activeResultIndex = FlashChatSearchMath.initialResultIndex(
                                    FlashChatSearchMath.findMatches(localMessages, newQuery).size,
                                )
                            },
                            activeResultIndex = activeResultIndex,
                            resultCount = searchMatches.size,
                            onClose = { closeSearch() },
                            onPrevious = { stepSearchResult(forward = false) },
                            onNext = { stepSearchResult(forward = true) },
                        )
                    }

                    else -> {
                        Column {
                            FlashChatHeader(
                                state = state.header,
                                onBack = onBack,
                                showVideoCallAction = showVideoCallAction,
                                onAvatarClick = {
                                    if (state.header.isGroup) {
                                        showGroupMembers = true
                                    } else {
                                        showPeerDetails = true
                                    }
                                },
                                onSearchClick = { isSearchActive = true },
                                encryptionState = encryptionState,
                                onEncryptionClick = { showEncryptionSheet = true },
                                onCallClick = {
                                    val ongoing = state.ongoingCall
                                    if (ongoing != null) {
                                        onJoinGroupCall(ongoing.callId, false)
                                    } else {
                                        onStartCall()
                                    }
                                },
                                onVideoCallClick = {
                                    val ongoing = state.ongoingCall
                                    if (ongoing != null) {
                                        onJoinGroupCall(ongoing.callId, ongoing.video)
                                    } else {
                                        onStartVideoCall()
                                    }
                                },
                                // Group Phase D: the header's More button finally does something —
                                // it opens the conversation menu anchored to it.
                                onMenuClick = { menuExpanded = true },
                                menuContent = {
                                    FlashConversationMenu(
                                        expanded = menuExpanded,
                                        items = menuItems,
                                        onDismiss = { menuExpanded = false },
                                        onItemSelected = { item ->
                                            when (item) {
                                                FlashConversationMenuItem.VIEW_PROFILE -> showPeerDetails = true
                                                FlashConversationMenuItem.SEARCH -> isSearchActive = true
                                                FlashConversationMenuItem.REVOKE_TRUST -> onRevokePeerTrust?.invoke()
                                                FlashConversationMenuItem.MARK_UNREAD ->
                                                    conversationId?.let { onMarkUnread(it) }
                                                FlashConversationMenuItem.CLEAR_CONVERSATION ->
                                                    conversationId?.let { onClearConversation(it) }
                                                FlashConversationMenuItem.GROUP_INFO -> showGroupMembers = true
                                                FlashConversationMenuItem.ADD_MEMBERS -> showAddMembers = true
                                                FlashConversationMenuItem.LEAVE_GROUP ->
                                                    conversationId?.let { showLeaveConfirm = true }
                                            }
                                        },
                                    )
                                },
                            )
                            // UI-030 connection banner — hidden while fully connected.
                            AnimatedVisibility(
                                visible = connectionHealth != FlashConnectionHealth.Connected,
                                enter = fadeIn(motion.tweenNormalSpec()),
                                exit = fadeOut(motion.tweenFastSpec()),
                            ) {
                                FlashConnectionBanner(
                                    health = connectionHealth,
                                    onRetry = {
                                        // Used to be a bare "Reconnecting…" toast with no work behind
                                        // it. Now it re-arms discovery + re-dials the peer, and says
                                        // so only when there is an engine to do it.
                                        val armed = onRetryConnection()
                                        showMessage(
                                            if (armed) {
                                                "Reconnecting…"
                                            } else {
                                                "Still starting up — try again in a moment"
                                            },
                                        )
                                    },
                                )
                            }
                            // Ongoing group call banner — displayed when active call presence is detected.
                            AnimatedVisibility(
                                visible = state.ongoingCall != null,
                                enter = fadeIn(motion.tweenNormalSpec()),
                                exit = fadeOut(motion.tweenFastSpec()),
                            ) {
                                state.ongoingCall?.let { ongoing ->
                                    FlashOngoingCallBanner(
                                        ongoingCall = ongoing,
                                        onJoin = { onJoinGroupCall(ongoing.callId, ongoing.video) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            FlashComposer(
                draft = draft,
                onDraftChanged = {
                    draft = it
                    // #11: fire only on transitions (not every keystroke) to keep the wire quiet.
                    val typingNow = it.isNotBlank()
                    if (typingNow != isTypingSignalled) {
                        isTypingSignalled = typingNow
                        onTypingChanged(typingNow)
                    }
                },
                onAttachmentClick = {
                    showAttachmentSheet = true
                },
                isAttachmentExpanded = showAttachmentSheet,
                replyingTo = replyingToMessage,
                onDismissReply = { replyingToMessage = null },
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        val quoted = replyingToMessage
                        if (quoted != null) {
                            // #8: carry the quoted id + a trimmed preview so the peer renders the quote.
                            onSendReply(text, quoted.id, quoted.text.take(120))
                        } else {
                            onSendText(text)
                        }
                        draft = ""
                        replyingToMessage = null
                        // Sending clears the composer → stop the typing indicator (#11).
                        if (isTypingSignalled) {
                            isTypingSignalled = false
                            onTypingChanged(false)
                        }
                    }
                },
                onSendVoice = { voice ->
                    // Stop capture → file:// URI; route the recording out to :app for real sending.
                    val path = voiceRecorder.stop()
                    releaseVoiceRecordingLease()
                    if (path != null) {
                        onSendVoiceMessage(path, voice.durationMs, voice.amplitudes)
                    } else {
                        showMessage("Recording too short")
                    }
                },
                onVoiceRecordStart = {
                    if (!permissions.isGranted(FlashPermission.Microphone)) {
                        // The request is asynchronous and this callback has to answer the composer
                        // now, so the hold that triggered it cannot record; the result only decides
                        // which message the user reads before their next attempt.
                        coroutineScope.launch {
                            val granted = permissions.ensureGranted(FlashPermission.Microphone)
                            showMessage(
                                if (granted) {
                                    "Microphone ready — hold to record"
                                } else {
                                    "Microphone permission is required for voice messages"
                                },
                            )
                        }
                        false
                    } else {
                        val leaseId = onVoiceRecordingStarting()
                        if (leaseId == null) {
                            showMessage("PTT session active — voice recording unavailable")
                            false
                        } else {
                            voiceRecordingLeaseId = leaseId
                            val started = voiceRecorder.start()
                            if (!started) releaseVoiceRecordingLease()
                            started
                        }
                    }
                },
                onVoiceRecordCancel = {
                    voiceRecorder.cancel()
                    releaseVoiceRecordingLease()
                },
                voiceAmplitudeProvider = { voiceRecorder.maxAmplitude() },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            FlashMessageList(
                messages = localMessages,
                onOpenMessageActions = { msg -> focusedMessage = msg },
                selectedMessageIds = selectedMessageIds,
                inSelectionMode = inSelectionMode,
                onSelectToggle = { id ->
                    selectedMessageIds = if (id in selectedMessageIds) {
                        selectedMessageIds - id
                    } else {
                        selectedMessageIds + id
                    }
                },
                onToggleReaction = { messageId, emoji ->
                    localMessages = toggleMessageReaction(localMessages, messageId, emoji)
                    onToggleReaction(messageId, emoji)
                },
                onReplySwipe = { msg ->
                    replyingToMessage = msg
                },
                onJumpToMessage = { targetId ->
                    jumpToMessage(targetId)
                },
                onImageClick = { msg, index ->
                    if (msg.images.isNotEmpty()) {
                        mediaViewerItems = msg.images.map { img ->
                            FlashMediaViewerItem(
                                image = img,
                                senderName = msg.senderName,
                                timeLabel = msg.timeLabel,
                            )
                        }
                        mediaViewerStartIndex = index
                        mediaViewerVisible = true
                    }
                },
                onFileClick = { _, file ->
                    // A failed card advertises "Failed (Tap to retry)" and a Retry badge, so the
                    // tap has to retry. Downloaded video files play in-app via FlashMediaViewer.
                    if (file.transferStatus == FlashFileTransferStatus.Failed) {
                        onRetryTransfer(file.id)
                    } else if (file.mimeType.startsWith("video/") && file.localUri != null) {
                        mediaViewerItems = listOf(
                            FlashMediaViewerItem(
                                image = FlashImageAttachmentUi(
                                    id = file.id,
                                    uri = file.localUri,
                                    thumbUri = file.localUri,
                                    mimeType = file.mimeType,
                                    isVideo = true,
                                ),
                                senderName = file.name,
                                timeLabel = "",
                            ),
                        )
                        mediaViewerStartIndex = 0
                        mediaViewerVisible = true
                    } else {
                        onOpenAttachment(file.localUri, file.mimeType, file.name)
                    }
                },
                onAcceptOffer = { _, file ->
                    onAcceptOffer(file.id)
                },
                onDeclineOffer = { _, file ->
                    onDeclineOffer(file.id)
                },
                highlightedMessageId = highlightedMessageId,
                peerTypingName = state.header.typingMemberNames.firstOrNull()
                    ?: if (state.header.presence == FlashPeerPresence.Typing) state.header.title else null,
                listState = listState,
                showSenderHeaders = state.header.isGroup,
                searchQuery = if (isSearchActive) searchQuery else null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                // NOTE: no .imePadding() here — the composer bottomBar already grows with
                // the IME via innerPadding; adding it again doubles the inset (ERROR-009).
            )

            // UI-025 empty conversation state
            if (localMessages.isEmpty()) {
                FlashEmptyState(
                    kind = FlashStateCopy.EmptyKind.ConversationEmpty,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = FlashSpacing.space40),
                )
            }
        }
    }

    // UI-007 / UI-008 Immersive Message Focus Overlay & Context Menu (in-screen, not a Dialog)
    focusedMessage?.let { msg ->
        FlashMessageFocusOverlay(
            message = localMessages.firstOrNull { it.id == msg.id } ?: msg,
            onDismiss = { focusedMessage = null },
            onReactionSelect = { reaction ->
                localMessages = toggleMessageReaction(localMessages, msg.id, reaction)
                onToggleReaction(msg.id, reaction)
                showMessage("Reacted $reaction")
            },
            onReply = {
                replyingToMessage = msg
            },
            onCopy = {
                copyText(msg.text)
            },
            onForward = {
                // Was a "Forwarding message" toast that forwarded nothing. No in-app conversation
                // picker exists yet, so route to the system chooser: text as text, a media/file
                // message as its local stream (both fall back to the file card's localUri).
                val image = msg.images.firstOrNull()
                val file = msg.fileAttachments.firstOrNull()
                val voice = msg.voiceAttachments.firstOrNull()
                when {
                    msg.text.isNotBlank() -> onShareText(msg.text)
                    image?.uri != null -> onShareImage(image.uri, image.mimeType)
                    voice?.uri != null -> onShareImage(voice.uri, voice.mimeType)
                    file?.localUri != null -> onShareImage(file.localUri, file.mimeType)
                    else -> showMessage("Nothing to forward yet")
                }
                focusedMessage = null
            },
            onSelectMultiple = {
                selectedMessageIds = setOf(msg.id)
            },
            onDelete = {
                onDeleteMessage(setOf(msg.id))
                focusedMessage = null
            },
            onDeleteForEveryone = if (msg.isMine) {
                {
                    onDeleteMessageForEveryone(msg.id)
                    focusedMessage = null
                }
            } else {
                null
            },
        )
    }

    // UI-012 Modal Attachment Sheet & Palette
    if (showAttachmentSheet) {
        FlashAttachmentSheet(
            onDismiss = { showAttachmentSheet = false },
            onSelectAction = { action ->
                showAttachmentSheet = false
                // Map the tapped palette action to a document-picker MIME filter and launch SAF.
                // Camera has no picker (would need a capture intent); keep the existing hook for it.
                val mimeTypes = when (action) {
                    // Videos belong in the gallery picker: chat bubbles render video thumbnails and
                    // the viewer plays them, but "Gallery" filtered to image/* meant a clip could
                    // only be sent through the generic Files action.
                    FlashAttachmentType.Gallery -> listOf("image/*", "video/*")
                    FlashAttachmentType.Audio -> listOf("audio/*")
                    FlashAttachmentType.Files, FlashAttachmentType.FlashTransfer -> listOf("*/*")
                    FlashAttachmentType.Camera -> null
                }
                if (mimeTypes != null) {
                    filePicker.launch(mimeTypes)
                } else {
                    onAttachmentClick()
                }
            },
        )
    }


    // UI-029 Group members sheet
    if (showGroupMembers) {
        FlashGroupMembersSheet(
            members = groupMembers,
            onDismiss = { showGroupMembers = false },
        )
    }

    // Group Phase D: add members (trusted peers supplied by the host) and the leave confirmation.
    if (showAddMembers && conversationId != null) {
        FlashAddMembersSheet(
            availablePeers = addablePeers,
            onDismiss = { showAddMembers = false },
            onAdd = { memberIds -> onAddGroupMembers(conversationId, memberIds) },
        )
    }
    if (showLeaveConfirm && conversationId != null) {
        FlashLeaveGroupDialog(
            onConfirm = {
                showLeaveConfirm = false
                onLeaveGroup(conversationId)
            },
            onDismiss = { showLeaveConfirm = false },
        )
    }

    // UI-032 1:1 peer details sheet
    if (showPeerDetails) {
        FlashPeerDetailsSheet(
            header = state.header,
            onDismiss = { showPeerDetails = false },
            isTrusted = isPeerTrusted,
            onRevokeTrust = onRevokePeerTrust,
        )
    }

    // UI-031 encryption trust sheet
    if (showEncryptionSheet) {
        FlashEncryptionSheet(
            state = encryptionState,
            onDismiss = { showEncryptionSheet = false },
        )
    }

    // UI-018 Full-screen media viewer overlay.
    // Items intentionally persist after dismissal so the exit animation fades out real content;
    // they are replaced on next open and only composed while the overlay is visible/leaving.
    AnimatedVisibility(
        visible = mediaViewerVisible,
        enter = motion.mediaOpenEnter(),
        exit = motion.mediaOpenExit(),
    ) {
        if (mediaViewerItems.isNotEmpty()) {
            FlashMediaViewer(
                items = mediaViewerItems,
                initialIndex = mediaViewerStartIndex,
                initialPlayVideo = mediaViewerItems.getOrNull(mediaViewerStartIndex)?.image?.isVideo == true,
                onDismiss = {
                    mediaViewerVisible = false
                },
                onSave = { index ->
                    val image = mediaViewerItems.getOrNull(index)?.image
                    if (image?.uri != null) {
                        onSaveImage(image.uri, image.mimeType)
                    } else {
                        showMessage(notReadyLabel(image))
                    }
                },
                onShare = { index ->
                    val image = mediaViewerItems.getOrNull(index)?.image
                    if (image?.uri != null) {
                        onShareImage(image.uri, image.mimeType)
                    } else {
                        showMessage(notReadyLabel(image))
                    }
                },
                onForward = { index ->
                    // Forwarding into another in-app conversation needs a chat picker that does not
                    // exist yet; offer the system share sheet instead of silently doing nothing.
                    val image = mediaViewerItems.getOrNull(index)?.image
                    if (image?.uri != null) {
                        onShareImage(image.uri, image.mimeType)
                    } else {
                        showMessage(notReadyLabel(image))
                    }
                },
                onPlayVideo = { index ->
                    // Reachable by swiping: a message's images and videos share one album, and a
                    // video page renders a still frame, so the badge hands playback to the system
                    // player exactly as tapping the tile does.
                    val item = mediaViewerItems.getOrNull(index)
                    val uri = item?.image?.uri
                    if (item != null && uri != null) {
                        onOpenAttachment(uri, item.image.mimeType, item.senderName)
                    } else {
                        showMessage("Video not available yet")
                    }
                },
            )
        }
    }

    // D7a: the snackbar host is the LAST sibling of this screen, not the Scaffold's `snackbarHost`
    // slot. Six of the messages raised here come from inside the focus overlay and the media viewer,
    // both emitted after the Scaffold and therefore painted over anything the Scaffold owns — a host
    // in that slot would have shown them underneath a full-screen overlay. An idle SnackbarHost
    // composes nothing and this Box takes no pointer input, so it costs nothing when nothing is shown.
    // The insets keep the message off the navigation bar and above the keyboard, where a Toast sat.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SnackbarHost(hostState = snackbarHostState)
    }
}

/**
 * UI-009 Pure reaction toggling logic for message list.
 */
fun toggleMessageReaction(
    messages: List<FlashMessageUi>,
    messageId: String,
    emoji: String,
): List<FlashMessageUi> {
    return messages.map { msg ->
        if (msg.id != messageId) return@map msg

        val existingIndex = msg.reactions.indexOfFirst { it.emoji == emoji }
        val updatedReactions = if (existingIndex >= 0) {
            val existing = msg.reactions[existingIndex]
            if (existing.isSelfReacted) {
                if (existing.count <= 1) {
                    msg.reactions.filterIndexed { i, _ -> i != existingIndex }
                } else {
                    msg.reactions.mapIndexed { i, r ->
                        if (i == existingIndex) r.copy(count = r.count - 1, isSelfReacted = false) else r
                    }
                }
            } else {
                msg.reactions.mapIndexed { i, r ->
                    if (i == existingIndex) r.copy(count = r.count + 1, isSelfReacted = true) else r
                }
            }
        } else {
            msg.reactions + FlashReaction(emoji = emoji, count = 1, isSelfReacted = true)
        }
        msg.copy(reactions = updatedReactions)
    }
}

/**
 * Copy for a viewer action on a page whose bytes have not landed yet. The album mixes photos and
 * clips, so a hardcoded "Image not available yet" was wrong on half its pages.
 */
private fun notReadyLabel(image: FlashImageAttachmentUi?): String =
    if (image?.isVideo == true) "Video not available yet" else "Image not available yet"

/**
 * Ongoing call banner displayed at top of group conversation when an active call is ongoing.
 */
@Composable
fun FlashOngoingCallBanner(
    ongoingCall: com.transfer.flash.core.messaging.model.FlashActiveGroupCallBarUi,
    modifier: Modifier = Modifier,
    onJoin: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val callGreen = Color(0xFF22C55E)
    val background = callGreen.copy(alpha = 0.12f)
    val contentColor = colors.textPrimary

    val typeLabel = if (ongoingCall.video) "Ongoing video call" else "Ongoing call"
    val countLabel = if (ongoingCall.participantCount > 0) {
        "$typeLabel • ${ongoingCall.participantCount} in call"
    } else {
        typeLabel
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(background)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                contentDescription = countLabel
            },
    ) {
        FlashIcon(
            icon = if (ongoingCall.video) FlashIcons.VideoCall else FlashIcons.Call,
            contentDescription = null,
            tint = callGreen,
            size = FlashDimensions.iconSm,
        )
        FlashText(
            text = countLabel,
            style = typography.metadataEmphasis,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(FlashShapes.chip)
                .background(callGreen)
                .defaultMinSize(minHeight = 32.dp)
                .clickable(onClick = onJoin)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "Join call"
                }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = "Join",
                style = typography.metadataEmphasis,
                color = Color.White,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashConversationScreenPreview() {
    FlashTheme {
        FlashConversationScreen(
            state = sampleFlashConversationState(),
            onBack = {},
            onOpenPeerDetails = {},
            onSendText = {},
            onAttachmentClick = {},
        )
    }
}
