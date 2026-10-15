package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashHubNoticeKind
import com.transfer.flash.core.messaging.model.FlashHubNoticeUi
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
