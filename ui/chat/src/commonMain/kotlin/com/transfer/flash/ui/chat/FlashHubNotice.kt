package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashHubNoticeKind
import com.transfer.flash.core.messaging.model.FlashHubNoticeUi
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * ADR-126: the words of the calm inline notice about how a chat travels through the organisation hub. Pure so the copy
 * (and its honesty: groups are not sealed yet, a changed key is never trusted silently) is unit-tested; the composable
 * only lays it out. No protocol words, no error codes (AGENTS.md section 22).
 */
public object FlashHubNoticeMath {

    public const val CONTINUE_ACTION: String = "Continue"
    public const val VERIFY_ACTION: String = "Verify"

    private fun nameOf(notice: FlashHubNoticeUi): String = notice.peerName.ifBlank { "your colleague" }

    public fun title(notice: FlashHubNoticeUi): String = when (notice.kind) {
        FlashHubNoticeKind.KEY_CHANGED -> "${nameOf(notice)}'s security key changed"
        FlashHubNoticeKind.PEER_NEEDS_UPDATE -> "Waiting for ${nameOf(notice)} to update the app"
        FlashHubNoticeKind.HUB_SWITCHED_OFF -> "Waiting for ${nameOf(notice)} to be reachable"
        FlashHubNoticeKind.GROUP_ENCRYPTED -> "Group messages are end-to-end encrypted"
        FlashHubNoticeKind.GROUP_E2EE_BLOCKED -> "Encrypted group relay paused"
        FlashHubNoticeKind.GROUP_READABLE -> "The hub can read this group's messages"
        FlashHubNoticeKind.GROUP_HUB_OFF -> "Group messages through the hub are off"
        FlashHubNoticeKind.IDENTITY_MISMATCH -> "${nameOf(notice)}'s key does not match"
    }

    public fun body(notice: FlashHubNoticeUi): String = when (notice.kind) {
        FlashHubNoticeKind.KEY_CHANGED ->
            "This usually means ${nameOf(notice)} reinstalled the app or changed phone. Messages to them through your " +
                "organisation wait until you continue. New code ${notice.newCode.orEmpty()}, before ${notice.oldCode.orEmpty()}. " +
                "To be sure, compare the new code with them."
        FlashHubNoticeKind.PEER_NEEDS_UPDATE ->
            "Their app cannot receive private messages through your organisation yet. The message is kept and goes out " +
                "when it can, or when you are in reach of each other."
        FlashHubNoticeKind.HUB_SWITCHED_OFF ->
            "Sending message text through your organisation's hub is switched off, so this message waits until you can " +
                "reach them directly. You can change this in Settings."
        FlashHubNoticeKind.GROUP_ENCRYPTED ->
            "Messages relayed through your organisation's hub are sealed with per-membership epoch keys so the hub " +
                "cannot read them. Keys rotate when members join or leave."
        FlashHubNoticeKind.GROUP_E2EE_BLOCKED ->
            "One or more active members do not have verified encryption keys yet (older app or pending key check). " +
                "Hub sending is paused instead of falling back to readable text."
        FlashHubNoticeKind.GROUP_READABLE ->
            "Group messages are not sealed yet, so the hub can read the ones it carries. Messages sent directly between " +
                "phones stay private."
        FlashHubNoticeKind.GROUP_HUB_OFF ->
            "Colleagues you cannot reach directly get this group's messages when you meet. Allowing the hub to carry them " +
                "would let it read them, because groups are not sealed yet."
        FlashHubNoticeKind.IDENTITY_MISMATCH ->
            "Your organisation's server shows a different key for ${nameOf(notice)} than the one your phone saved when you " +
                "paired. Nothing is sent to them through the server. Meet to pair again, or ask your administrator."
    }

    /** True when the notice asks the user to decide (Continue / Verify). */
    public fun needsDecision(notice: FlashHubNoticeUi): Boolean = notice.kind == FlashHubNoticeKind.KEY_CHANGED

    public fun contentDescription(notice: FlashHubNoticeUi): String = "${title(notice)}. ${body(notice)}"
}

internal fun hubKeyBannerMessage(messages: List<FlashMessageUi>): String =
    if (messages.any { it.isMine && it.deliveryStatus == FlashMessageStatus.Pending }) {
        "Messages are waiting to send through the Hub."
    } else {
        "Verify the new key to send through the Hub."
    }

/** Kept next to Send so the key decision is visible at any scroll position. */
@Composable
internal fun FlashHubKeyChangeBanner(
    notice: FlashHubNoticeUi,
    message: String,
    onVerify: (() -> Unit)?,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .padding(start = FlashSpacing.space16, end = FlashSpacing.space8, top = FlashSpacing.space4, bottom = FlashSpacing.space4),
    ) {
        val stackAction = maxWidth / LocalDensity.current.fontScale < 264.dp
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashIcon(FlashIcons.Encryption, contentDescription = null, tint = colors.accentPrimary, size = 20.dp)
                Column(
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                ) {
                    FlashText(
                        text = FlashHubNoticeMath.title(notice),
                        style = typography.metadataEmphasis,
                        color = colors.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    FlashText(text = message, style = typography.metadataDefault, color = colors.textSecondary)
                }
                if (!stackAction && onVerify != null) {
                    FlashHubVerifyButton(onVerify)
                }
            }
            if (stackAction && onVerify != null) {
                FlashHubVerifyButton(onVerify, Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun FlashHubVerifyButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text("Verify key", color = FlashTheme.colors.accentFill)
    }
}

@Composable
internal fun FlashHubKeyVerification(
    notice: FlashHubNoticeUi,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        title = {
            FlashText("Verify Hub security key", style = typography.headingMedium, color = colors.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                FlashText(
                    "Compare the new code with the Hub security code in your contact’s connection details.",
                    style = typography.bodyDefault,
                    color = colors.textSecondary,
                )
                notice.newCode?.let {
                    FlashText("New code\n$it", style = typography.bodyEmphasis, color = colors.textPrimary)
                }
                notice.oldCode?.let {
                    FlashText("Previous code\n$it", style = typography.metadataDefault, color = colors.textSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onTrust,
                enabled = !notice.newCode.isNullOrBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accentFill),
            ) {
                Text("Trust new key")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}

/**
 * The inline hub notice of a conversation (ADR-126). [onContinue] confirms a changed key; [onVerify] opens the peer
 * details where the security codes can be compared. Both are only shown for [FlashHubNoticeKind.KEY_CHANGED].
 */
@Composable
public fun FlashHubNotice(
    notice: FlashHubNoticeUi,
    onContinue: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .clip(FlashShapes.bubbleGrouped)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.bubbleGrouped)
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) { contentDescription = FlashHubNoticeMath.contentDescription(notice) },
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            FlashIcon(
                icon = if (FlashHubNoticeMath.needsDecision(notice)) FlashIcons.Encryption else FlashIcons.Relay,
                contentDescription = null,
                tint = colors.accentPrimary,
                size = 20.dp,
            )
            FlashText(
                text = FlashHubNoticeMath.title(notice),
                style = typography.bodyEmphasis,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
        }
        FlashText(text = FlashHubNoticeMath.body(notice), style = typography.metadataDefault, color = colors.textSecondary)
        if (FlashHubNoticeMath.needsDecision(notice)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistoryButton(text = FlashHubNoticeMath.VERIFY_ACTION, filled = false, onClick = onVerify)
                HistoryButton(text = FlashHubNoticeMath.CONTINUE_ACTION, filled = true, onClick = onContinue)
            }
        }
    }
}

@Preview(name = "Hub notice - key changed", showBackground = true, widthDp = 390)
@Composable
private fun FlashHubNoticeKeyChangedPreview() {
    FlashTheme {
        FlashHubNotice(
            notice = FlashHubNoticeUi(FlashHubNoticeKind.KEY_CHANGED, "bob", "Bob", "1111 2222 3333 4444", "aaaa bbbb cccc dddd"),
            onContinue = {}, onVerify = {},
        )
    }
}

@Preview(name = "Hub notice - waiting", showBackground = true, widthDp = 390)
@Composable
private fun FlashHubNoticeWaitingPreview() {
    FlashTheme {
        FlashHubNotice(
            notice = FlashHubNoticeUi(FlashHubNoticeKind.PEER_NEEDS_UPDATE, "bob", "Bob"),
            onContinue = {}, onVerify = {},
        )
    }
}
