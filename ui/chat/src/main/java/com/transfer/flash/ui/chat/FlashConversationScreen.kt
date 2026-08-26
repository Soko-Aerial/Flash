package com.transfer.flash.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashQuotedReplyUi
import com.transfer.flash.core.messaging.model.FlashReaction
import com.transfer.flash.core.messaging.util.sampleFlashConversationState
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onDeleteMessage: (Set<String>) -> Unit = {},
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
) {
    val context = LocalContext.current
    val motion = FlashTheme.motion
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // UI-012: system document picker for attachments. On result we resolve the display name + size
    // from the content resolver (same as the Dev Console) and hand the URI to onSendFile, which
    // kicks off a real P2P transfer to the conversation's peer over the existing pipeline.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read access so the transfer can stream the file even after this screen dies.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val (name, size) = resolveFileMetadata(context, uri)
            onSendFile(uri.toString(), name, size)
            Toast.makeText(context, "Sending $name", Toast.LENGTH_SHORT).show()
        }
    }

    // B9: real microphone capture for voice messages. The recorder lives at screen scope so its
    // encoder survives the composer's gesture recompositions; released when the screen leaves.
    val voiceRecorder = remember { FlashVoiceRecorder(context) }
    DisposableEffect(voiceRecorder) {
        onDispose { voiceRecorder.cancel() }
    }
    // RECORD_AUDIO is requested lazily on the first hold. We can't retroactively start the capture
    // the user just attempted, so a granted result simply enables the next hold to record.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            Toast.makeText(context, "Microphone ready — hold to record", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice messages", Toast.LENGTH_SHORT).show()
        }
    }

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

    // UI-029: group member sheet (demo roster until the repository feeds live members).
    var showGroupMembers by remember { mutableStateOf(false) }
    val groupMembers = remember(state.header) { groupMembersFromHeader(state.header) }

    // UI-032: 1:1 peer details sheet (opened from the header avatar for non-group chats).
    var showPeerDetails by remember { mutableStateOf(false) }

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

    // UI-030: connection health derived from header state.
    val connectionHealth = FlashNetworkStatusMath.resolveHealth(
        transport = state.header.transport,
        peerPresence = state.header.presence,
        peerCount = if (state.header.presence == FlashPeerPresence.Online || state.header.presence == FlashPeerPresence.Typing) 1 else 0,
    )

    // Hardware back press exits media viewer → search → selection mode
    BackHandler(enabled = mediaViewerVisible) {
        mediaViewerVisible = false
    }
    BackHandler(enabled = isSearchActive && !mediaViewerVisible) {
        closeSearch()
    }
    BackHandler(enabled = inSelectionMode && !isSearchActive && !mediaViewerVisible) {
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
                                copyToClipboard(context, selectedTexts)
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
                                Toast.makeText(context, "Forwarding ${selectedMessageIds.size} messages", Toast.LENGTH_SHORT).show()
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
                                onAvatarClick = {
                                    if (state.header.isGroup) {
                                        showGroupMembers = true
                                    } else {
                                        showPeerDetails = true
                                    }
                                },
                                onSearchClick = { isSearchActive = true },
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
                                        Toast.makeText(context, "Reconnecting…", Toast.LENGTH_SHORT).show()
                                    },
                                )
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
                    if (path != null) {
                        onSendVoiceMessage(path, voice.durationMs, voice.amplitudes)
                    } else {
                        Toast.makeText(context, "Recording too short", Toast.LENGTH_SHORT).show()
                    }
                },
                onVoiceRecordStart = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        false
                    } else {
                        voiceRecorder.start()
                    }
                },
                onVoiceRecordCancel = { voiceRecorder.cancel() },
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
                    val image = msg.images.getOrNull(index)
                    if (image != null && image.isVideo) {
                        // Video plays in the system player, not the in-app photo viewer.
                        onOpenAttachment(image.uri, image.mimeType, msg.senderName)
                    } else if (msg.images.isNotEmpty()) {
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
                    onOpenAttachment(file.localUri, file.mimeType, file.name)
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
                Toast.makeText(context, "Reacted $reaction", Toast.LENGTH_SHORT).show()
            },
            onReply = {
                replyingToMessage = msg
            },
            onCopy = {
                copyToClipboard(context, msg.text)
            },
            onForward = {
                Toast.makeText(context, "Forwarding message", Toast.LENGTH_SHORT).show()
            },
            onSelectMultiple = {
                selectedMessageIds = setOf(msg.id)
            },
            onDelete = {
                onDeleteMessage(setOf(msg.id))
                focusedMessage = null
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
                    FlashAttachmentType.Gallery -> arrayOf("image/*")
                    FlashAttachmentType.Audio -> arrayOf("audio/*")
                    FlashAttachmentType.Files, FlashAttachmentType.FlashTransfer -> arrayOf("*/*")
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

    // UI-032 1:1 peer details sheet
    if (showPeerDetails) {
        FlashPeerDetailsSheet(
            header = state.header,
            onDismiss = { showPeerDetails = false },
            isTrusted = isPeerTrusted,
            onRevokeTrust = onRevokePeerTrust,
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
                onDismiss = {
                    mediaViewerVisible = false
                },
                onSave = { index ->
                    val image = mediaViewerItems.getOrNull(index)?.image
                    if (image?.uri != null) {
                        onSaveImage(image.uri, image.mimeType)
                    } else {
                        Toast.makeText(context, "Image not available yet", Toast.LENGTH_SHORT).show()
                    }
                },
                onShare = { index ->
                    val image = mediaViewerItems.getOrNull(index)?.image
                    if (image?.uri != null) {
                        onShareImage(image.uri, image.mimeType)
                    } else {
                        Toast.makeText(context, "Image not available yet", Toast.LENGTH_SHORT).show()
                    }
                },
                onForward = { index ->
                    // Forwarding into another in-app conversation needs a chat picker that does not
                    // exist yet; offer the system share sheet instead of silently doing nothing.
                    val image = mediaViewerItems.getOrNull(index)?.image
                    if (image?.uri != null) {
                        onShareImage(image.uri, image.mimeType)
                    } else {
                        Toast.makeText(context, "Image not available yet", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
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

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Flash Message", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

/**
 * UI-012: resolve a SAF content URI to its display name + byte size via [OpenableColumns].
 * Falls back to the URI's last path segment / 0 bytes when the provider omits the columns.
 */
private fun resolveFileMetadata(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "file"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    return name to size
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
