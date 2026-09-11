package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashQuotedReplyUi
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-010 In-bubble quoted reply card.
 *
 * Renders at the top of a message bubble surface when referencing an earlier message.
 * 1-tap jumps to the referenced message in the conversation list.
 */
@Composable
fun FlashQuotedReplyCard(
    quotedReply: FlashQuotedReplyUi,
    isParentOutgoing: Boolean,
    onJumpToMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    val cardBg = if (isParentOutgoing) {
        Color.White.copy(alpha = 0.14f)
    } else {
        colors.backgroundSurfaceSubtle
    }

    val barColor = if (isParentOutgoing) {
        Color.White.copy(alpha = 0.85f)
    } else {
        colors.accentPrimary
    }

    val senderColor = if (isParentOutgoing) {
        Color.White
    } else {
        colors.accentPrimary
    }

    val snippetColor = if (isParentOutgoing) {
        Color.White.copy(alpha = 0.85f)
    } else {
        colors.textSecondary
    }

    val shape = RoundedCornerShape(FlashShapes.radius8)
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .flashPressScale(interactionSource)
            .clip(shape)
            .background(cardBg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onJumpToMessage,
            )
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
            .semantics {
                role = Role.Button
                contentDescription = "Replying to ${quotedReply.senderName}: ${quotedReply.textSnippet}. Double tap to jump to message."
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left vertical accent indicator bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(barColor),
            )

            Spacer(modifier = Modifier.width(FlashSpacing.space8))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                FlashText(
                    text = quotedReply.senderName,
                    style = typography.captionEmphasis.copy(fontWeight = FontWeight.SemiBold),
                    color = senderColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashText(
                    text = quotedReply.textSnippet,
                    style = typography.captionDefault,
                    color = snippetColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(name = "Quoted Reply - Incoming Bubble", showBackground = true)
@Composable
private fun FlashQuotedReplyCardIncomingPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashQuotedReplyCard(
                quotedReply = FlashQuotedReplyUi(
                    messageId = "1",
                    senderName = "Alex Chen",
                    textSnippet = "Can you send the build over Wi‑Fi Direct?",
                ),
                isParentOutgoing = false,
                onJumpToMessage = {},
            )
        }
    }
}

@Preview(name = "Quoted Reply - Outgoing Bubble", showBackground = true)
@Composable
private fun FlashQuotedReplyCardOutgoingPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashQuotedReplyCard(
                quotedReply = FlashQuotedReplyUi(
                    messageId = "2",
                    senderName = "You",
                    textSnippet = "I'm pushing the latest build now.",
                    isMine = true,
                ),
                isParentOutgoing = true,
                onJumpToMessage = {},
            )
        }
    }
}
