package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.messaging.util.sampleDirectChatHeader
import com.transfer.flash.core.messaging.util.sampleFlashConversationState
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun FlashChatHeader(
    state: FlashChatHeaderUiState,
    onBack: () -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
    onCallClick: () -> Unit = {},
    onVideoCallClick: () -> Unit = {},
    /**
     * Whether the video button renders alongside the voice button (Phase 33a).
     *
     * Defaulted true so existing hosts are unchanged. The desktop passes false until 33c:
     * 33a is audio-only, and a visible video button with nowhere to go would be the dead-
     * control trap again.
     */
    showVideoCallAction: Boolean = true,
    onMenuClick: () -> Unit = {},
    /** UI-028: group-only search-in-conversation action. */
    onSearchClick: () -> Unit = {},
    /**
     * UI-031: verification-aware encryption trust state for the status-line badge. When not
     * [FlashEncryptionBadgeState.None], the tappable [FlashEncryptionBadge] replaces the static
     * lock icon and [onEncryptionClick] opens the trust sheet. Defaults to None so callers that
     * don't wire encryption (previews) keep the legacy static [state.isEncrypted] lock icon.
     */
    encryptionState: FlashEncryptionBadgeState = FlashEncryptionBadgeState.None,
    onEncryptionClick: () -> Unit = {},
    /** Anchored menu slot placed directly under the More button on the top right. */
    menuContent: @Composable () -> Unit = {},
) {
    val colors = FlashTheme.colors
    val haptics = rememberFlashHaptics()
    // UI-037 conversation-open handoff: the avatar springs up from 0.85 and the title slides in
    // from the leading edge while the message list runs its own messageEnter() stagger. Keyed on
    // the conversation identity so switching peers replays it; both reads are deferred into
    // graphicsLayer, so the header does not recompose per frame. Collapses under reduce-motion
    // (rememberStaggerProgress starts at 1f).
    val entryKey = state.avatarSeed
    val avatarEntry = FlashTheme.motion.rememberStaggerProgress(index = 0, key = entryKey)
    val titleEntry = FlashTheme.motion.rememberStaggerProgress(index = 1, key = entryKey)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FlashDimensions.headerHeight)
                .padding(horizontal = FlashSpacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashHeaderIconButton(
                onClick = {
                    haptics(FlashHaptic.Tick)
                    onBack()
                },
                description = "Back",
            ) {
                FlashIcon(icon = FlashIcons.Back)
            }

            val avatarEntryModifier = Modifier.graphicsLayer {
                val scale = AvatarEntryScale + (1f - AvatarEntryScale) * avatarEntry.value
                scaleX = scale
                scaleY = scale
                alpha = avatarEntry.value
            }

            if (state.isGroup && state.memberInitials.size >= 2) {
                // UI-028: collage identity built from member initials.
                FlashGroupAvatar(
                    initials = state.memberInitials,
                    seed = state.avatarSeed,
                    size = FlashDimensions.avatarMd,
                    modifier = avatarEntryModifier
                        .clip(CircleShape)
                        .clickable(onClick = onAvatarClick)
                        // UI-038: group collage opens group info — expose as a labeled button.
                        .semantics {
                            role = Role.Button
                            contentDescription = "View conversation info"
                        },
                )
            } else {
                FlashAvatar(
                    initials = state.avatarInitials,
                    seed = state.avatarSeed,
                    size = FlashDimensions.avatarMd,
                    modifier = avatarEntryModifier
                        .clip(CircleShape)
                        .clickable(onClick = onAvatarClick)
                        // UI-038: avatar opens the peer/group profile — expose as a labeled button.
                        .semantics {
                            role = Role.Button
                            contentDescription = "View conversation info"
                        },
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = FlashSpacing.space12)
                    .graphicsLayer {
                        val travel = (1f - titleEntry.value) * TitleEntryTravel.toPx()
                        translationX = if (isRtl) travel else -travel
                        alpha = titleEntry.value
                    },
            ) {
                FlashText(
                    text = state.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                FlashChatHeaderStatusLine(
                    state = state,
                    encryptionState = encryptionState,
                    onEncryptionClick = onEncryptionClick,
                )
            }

            FlashChatHeaderActions(
                state = state,
                onCallClick = onCallClick,
                onVideoCallClick = onVideoCallClick,
                showVideoCallAction = showVideoCallAction,
                onMenuClick = onMenuClick,
                onSearchClick = onSearchClick,
                menuContent = menuContent,
            )
        }
        // Hairline divider (design-system drawn; no Material divider component)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FlashDimensions.borderHairline)
                .background(colors.borderSubtle),
        )
    }
}

@Composable
private fun FlashChatHeaderStatusLine(
    state: FlashChatHeaderUiState,
    encryptionState: FlashEncryptionBadgeState = FlashEncryptionBadgeState.None,
    onEncryptionClick: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val statusKey = remember(
        state.presence, state.memberSummary, state.transport, state.isEncrypted,
        state.memberCount, state.onlineCount, state.typingMemberNames, encryptionState,
    ) {
        "${state.presence}:${state.memberSummary}:${state.transport}:${state.isEncrypted}:" +
            "${state.memberCount}:${state.onlineCount}:${state.typingMemberNames}:$encryptionState"
    }

    val motion = FlashTheme.motion
    AnimatedContent(
        targetState = statusKey,
        transitionSpec = { motion.statusCrossfade() },
        label = "flashChatHeaderStatus",
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        ) {
            val showTypingDots = state.presence == FlashPeerPresence.Typing || state.typingMemberNames.isNotEmpty()
            val typingLabel = if (showTypingDots) {
                FlashGroupHeaderMath.typingStatusLabel(state.typingMemberNames)
                    ?: if (state.isGroup) "typing…" else null
            } else {
                null
            }

            when {
                // Named multi-person typing (groups): dots + who is typing.
                showTypingDots && state.isGroup -> {
                    FlashHeaderTypingStatus()
                    if (typingLabel != null) {
                        FlashText(
                            text = typingLabel,
                            style = typography.metadataEmphasis,
                            color = colors.accentPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                showTypingDots -> {
                    FlashHeaderTypingStatus()
                }

                else -> {
                    if (!state.isGroup && state.presence == FlashPeerPresence.Online) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.statusOnline)
                                .semantics { contentDescription = "Online" },
                        )
                    }
                    val label = headerStatusLabel(state)
                        ?: FlashGroupHeaderMath.groupSubtitle(state.memberSummary, state.memberCount, state.onlineCount)
                        ?: ""
                    FlashText(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = typography.metadataDefault,
                        color = colors.textSecondary,
                    )
                    FlashChatHeaderTransportIcon(state.transport)
                    // UI-031: verification-aware, tappable badge when the host wires encryption
                    // state; otherwise fall back to the legacy static lock icon.
                    if (encryptionState != FlashEncryptionBadgeState.None) {
                        FlashEncryptionBadge(
                            state = encryptionState,
                            onClick = onEncryptionClick,
                        )
                    } else if (state.isEncrypted) {
                        FlashIcon(
                            icon = FlashIcons.Encryption,
                            contentDescription = "Encrypted",
                            size = FlashDimensions.iconSm,
                            tint = colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

/** Non-null unless both group subtitle sources are unavailable and presence has no copy. */
private fun headerStatusLabel(state: FlashChatHeaderUiState): String? {
    if (state.isGroup) {
        return null // groups use the computed/explicit group subtitle path
    }
    return when (state.presence) {
        FlashPeerPresence.Online -> "Online"
        FlashPeerPresence.Offline -> "Offline"
        FlashPeerPresence.Connecting -> "Connecting…"
        FlashPeerPresence.Typing -> "typing…"
    }
}

@Composable
private fun FlashChatHeaderTransportIcon(transport: FlashNetworkTransport) {
    val spec = transport.iconSpec() ?: return
    FlashIcon(
        icon = spec,
        size = FlashDimensions.iconSm,
        tint = FlashTheme.colors.textTertiary,
    )
}

@Composable
private fun FlashChatHeaderActions(
    state: FlashChatHeaderUiState,
    onCallClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    showVideoCallAction: Boolean,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    menuContent: @Composable () -> Unit = {},
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.showCallActions) {
            FlashHeaderIconButton(onClick = onCallClick, description = "Voice call") {
                FlashIcon(icon = FlashIcons.Call)
            }
            if (showVideoCallAction) {
                FlashHeaderIconButton(onClick = onVideoCallClick, description = "Video call") {
                    FlashIcon(icon = FlashIcons.VideoCall)
                }
            }
        }
        Box {
            FlashHeaderIconButton(onClick = onMenuClick, description = "Conversation menu") {
                FlashIcon(icon = FlashIcons.More)
            }
            menuContent()
        }
    }
}

/** Custom 48dp touch-target header action — no Material IconButton. */
@Composable
private fun FlashHeaderIconButton(
    onClick: () -> Unit,
    description: String,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(FlashDimensions.minTouchTarget)
            // House press feel instead of a Material ripple, matching FlashChatListRow.
            .flashPressScale(interaction)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Scale the conversation avatar springs up from on open. */
private const val AvatarEntryScale = 0.85f

/** Distance the title/status block slides in from the leading edge on open. */
private val TitleEntryTravel: Dp = 12.dp

private fun FlashNetworkTransport.iconSpec(): FlashIconSpec? = when (this) {
    FlashNetworkTransport.Lan -> FlashIcons.Wifi
    FlashNetworkTransport.WifiDirect -> FlashIcons.WifiDirect
    FlashNetworkTransport.Relay -> FlashIcons.Relay
    FlashNetworkTransport.Unknown -> null
}

@Preview(name = "Header — group LAN", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatHeaderGroupPreview() {
    FlashTheme {
        FlashChatHeader(
            state = sampleFlashConversationState().header,
            onBack = {},
            onAvatarClick = {},
        )
    }
}

@Preview(name = "Header — direct Wi‑Fi Direct", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatHeaderDirectPreview() {
    FlashTheme {
        FlashChatHeader(
            state = sampleDirectChatHeader(),
            onBack = {},
            onAvatarClick = {},
        )
    }
}

@Preview(name = "Header — typing", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatHeaderTypingPreview() {
    FlashTheme {
        FlashChatHeader(
            state = sampleDirectChatHeader().copy(presence = FlashPeerPresence.Typing),
            onBack = {},
            onAvatarClick = {},
        )
    }
}

@Preview(name = "Header — dark", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatHeaderDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashChatHeader(
            state = sampleDirectChatHeader().copy(transport = FlashNetworkTransport.Relay),
            onBack = {},
            onAvatarClick = {},
        )
    }
}
