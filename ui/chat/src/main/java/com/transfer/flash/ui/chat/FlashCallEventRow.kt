package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.transfer.flash.core.messaging.model.FlashCallEventKind
import com.transfer.flash.core.messaging.model.FlashCallEventUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Call log row inside a message bubble (UI-050) — the chat-side record of a voice/video call.
 *
 * Rendered as bubble content rather than as its own list item so it inherits selection,
 * long-press actions, reactions and the timestamp row from [FlashMessageBubble]; the bubble's
 * side already encodes direction, so the label only has to say what kind of call it was.
 *
 * A missed call is the one case tinted differently — everything else reads as history.
 */
@Composable
internal fun FlashCallEventRow(
    event: FlashCallEventUi,
    isParentOutgoing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val bodyColor = if (isParentOutgoing) colors.chatTextOutgoing else colors.chatTextIncoming
    val title = when {
        event.missed && event.video -> "Missed video call"
        event.missed -> "Missed voice call"
        event.video -> "Video call"
        else -> "Voice call"
    }
    // Duration is only present when media actually flowed; "No answer" covers the outgoing
    // ring that nobody picked up.
    val detail = event.durationLabel
        ?: "No answer".takeIf { event.kind == FlashCallEventKind.Unanswered }
    val accent = if (event.missed) colors.textError else bodyColor

    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (detail != null) "$title, $detail" else title
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Box(
            modifier = Modifier
                .size(FlashDimensions.iconLg)
                .background(color = accent.copy(alpha = 0.16f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = if (event.video) FlashIcons.VideoCall else FlashIcons.Call,
                contentDescription = null,
                size = FlashDimensions.iconSm,
                tint = accent,
            )
        }
        Column(modifier = Modifier.padding(end = FlashSpacing.space4)) {
            FlashText(
                text = title,
                style = typography.bodyDefault,
                color = if (event.missed) colors.textError else bodyColor,
            )
            if (detail != null) {
                FlashText(
                    text = detail,
                    style = typography.metadataDefault,
                    color = bodyColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}
