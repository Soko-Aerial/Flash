package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.buildDirectMessageInfo
import com.transfer.flash.core.messaging.model.FlashMessageInfoUi
import com.transfer.flash.core.messaging.model.FlashMessageRecipientUi
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashRecipientState
import com.transfer.flash.core.messaging.model.FlashWaitingReason
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-051 Message Info copy and eligibility: pure so the words and the gate are unit-tested
 * ([FlashMessageInfoMathTest]); the sheet only lays them out. Plan: `docs/ui/message-info.md`.
 */
object FlashMessageInfoMath {
    const val TITLE = "Message info"
    const val EMPTY = "No recipient details yet"
    const val MENU_LABEL = "Message Info"

    /** Spoken hint on the delivery badge that opens the sheet. */
    const val BADGE_ACTION_LABEL = "Opens message info"

    /** "Read by · 2", "Delivered to · 3", "Not delivered yet · 1". */
    fun sectionTitle(state: FlashRecipientState, count: Int): String {
        val title = when (state) {
            FlashRecipientState.Read -> "Read by"
            FlashRecipientState.Delivered -> "Delivered to"
            FlashRecipientState.Waiting -> "Not delivered yet"
        }
        return "$title · $count"
    }

    /** The line under a recipient's name. Read rows have no time on purpose: Flash stores a read cursor, not when it moved. */
    fun subtitle(recipient: FlashMessageRecipientUi): String = when (recipient.state) {
        FlashRecipientState.Read -> "Read"
        FlashRecipientState.Delivered ->
            recipient.deliveredAtLabel?.let { "Delivered $it" } ?: "Delivered"
        FlashRecipientState.Waiting -> when (recipient.waitingReason) {
            FlashWaitingReason.LeftGroup -> "No longer in the group"
            else -> "Waiting for device to connect"
        }
    }

    /** The row as one spoken sentence: "Ada, delivered 14:02". */
    fun description(recipient: FlashMessageRecipientUi): String =
        "${recipient.name}, ${subtitle(recipient).replaceFirstChar { it.lowercase() }}"

    /** What to quote at the top of the sheet for a message that has no text of its own. */
    fun previewOf(message: FlashMessageUi): String {
        if (message.text.isNotBlank()) return message.text
        // A video rides the image tile (`isVideo`), so name what is actually in the message.
        val media = message.images
        return when {
            media.size == 1 -> if (media.single().isVideo) "Video" else "Photo"
            media.size > 1 -> when {
                media.all { it.isVideo } -> "${media.size} videos"
                media.none { it.isVideo } -> "${media.size} photos"
                else -> "${media.size} items"
            }
            message.voiceAttachments.isNotEmpty() -> "Voice message"
            message.fileAttachments.isNotEmpty() -> message.fileAttachments.first().name
            else -> ""
        }
    }

    /**
     * Whether a message offers Message Info: one the user sent that is not a call log row. Whether the host can supply
     * the recipients is decided where the callback is passed (a group needs `observeMessageInfo`), so a null callback
     * hides every entry point.
     */
    fun isAvailable(message: FlashMessageUi): Boolean = message.isMine && message.callEvent == null
}

/**
 * UI-051 Message Info sheet: who has read, received or not yet received a message the user sent. [info] is null until
 * the host has something to show (the first emission, or a message with no recipient rows), which reads as
 * [FlashMessageInfoMath.EMPTY].
 */
@Composable
fun FlashMessageInfoSheet(
    info: FlashMessageInfoUi?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    FlashSheetHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FlashSpacing.space12)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.borderSubtle),
            )
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = FlashSpacing.space20,
                    end = FlashSpacing.space20,
                    bottom = FlashSpacing.space32,
                ),
        ) {
            FlashText(
                text = FlashMessageInfoMath.TITLE,
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.padding(bottom = FlashSpacing.space8).semantics { heading() },
            )
            if (info != null) {
                if (info.preview.isNotBlank()) {
                    FlashText(
                        text = info.preview,
                        style = FlashTheme.typography.bodyDefault,
                        color = colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlashText(
                    text = "Sent ${info.sentLabel}",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = FlashSpacing.space4, bottom = FlashSpacing.space12),
                )
            }
            if (info == null || info.recipients.isEmpty()) {
                FlashText(
                    text = FlashMessageInfoMath.EMPTY,
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = FlashSpacing.space12),
                )
            } else {
                FlashInfoSection(FlashRecipientState.Read, info.readBy)
                FlashInfoSection(FlashRecipientState.Delivered, info.deliveredTo)
                FlashInfoSection(FlashRecipientState.Waiting, info.waiting)
            }
        }
    }
}

/**
 * Resolves what Message Info shows for [message] and hosts the sheet. A group message's recipients come live from
 * [observeMessageInfo]; nothing is drawn until its first emission so the sheet never flashes "no details" while the
 * database answers. A one-to-one message has a single recipient, the peer, whose state follows the bubble's own status
 * (a missing status reads as read, exactly as the bubble draws it) so the sheet never contradicts the tick.
 */
@Composable
fun FlashMessageInfoHost(
    message: FlashMessageUi,
    isGroup: Boolean,
    peerId: String,
    peerName: String,
    peerInitials: String,
    observeMessageInfo: ((messageId: String) -> Flow<FlashMessageInfoUi?>)?,
    onDismiss: () -> Unit,
) {
    if (isGroup) {
        val source = remember(message.id, observeMessageInfo) {
            observeMessageInfo?.invoke(message.id) ?: flowOf(null)
        }
        var loaded by remember(source) { mutableStateOf(false) }
        val info by produceState<FlashMessageInfoUi?>(initialValue = null, source) {
            source.collect {
                value = it
                loaded = true
            }
        }
        if (loaded) FlashMessageInfoSheet(info = info, onDismiss = onDismiss)
    } else {
        val status = message.deliveryStatus ?: FlashMessageStatus.Read
        val info = remember(message.id, status, message.timeLabel, message.text, peerId, peerName, peerInitials) {
            buildDirectMessageInfo(
                messageId = message.id,
                preview = FlashMessageInfoMath.previewOf(message),
                sentLabel = message.timeLabel,
                recipientId = peerId,
                recipientName = peerName,
                recipientInitials = peerInitials,
                status = status,
            )
        }
        FlashMessageInfoSheet(info = info, onDismiss = onDismiss)
    }
}

/** One titled group of recipients; draws nothing when [recipients] is empty so there are no empty headers. */
@Composable
private fun FlashInfoSection(state: FlashRecipientState, recipients: List<FlashMessageRecipientUi>) {
    if (recipients.isEmpty()) return
    val colors = FlashTheme.colors
    val title = FlashMessageInfoMath.sectionTitle(state, recipients.size)
    val (icon, tint) = when (state) {
        FlashRecipientState.Read -> FlashIcons.Read to colors.accentPrimary
        FlashRecipientState.Delivered -> FlashIcons.Delivered to colors.textSecondary
        FlashRecipientState.Waiting -> FlashIcons.Clock to colors.textTertiary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FlashSpacing.space12, bottom = FlashSpacing.space4)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = title
            },
    ) {
        FlashIcon(icon = icon, contentDescription = null, size = FlashDimensions.iconSm, tint = tint)
        FlashText(
            text = title,
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
    recipients.forEachIndexed { index, recipient ->
        if (index > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FlashDimensions.borderHairline)
                    .background(colors.borderSubtle),
            )
        }
        FlashRecipientRow(recipient)
    }
}

@Composable
private fun FlashRecipientRow(recipient: FlashMessageRecipientUi) {
    val colors = FlashTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = FlashMessageInfoMath.description(recipient)
            },
    ) {
        FlashAvatar(initials = recipient.initials, seed = recipient.id, size = 32.dp)
        Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(
                text = recipient.name,
                style = FlashTheme.typography.bodyDefault.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(
                text = FlashMessageInfoMath.subtitle(recipient),
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val previewInfo = FlashMessageInfoUi(
    messageId = "m1",
    preview = "Meet at the north gate at 9",
    sentLabel = "9:41 AM",
    recipients = listOf(
        FlashMessageRecipientUi("a", "Ada", "AD", FlashRecipientState.Read),
        FlashMessageRecipientUi("b", "Bo", "BO", FlashRecipientState.Delivered, deliveredAtLabel = "9:42 AM"),
        FlashMessageRecipientUi("c", "Cy", "CY", FlashRecipientState.Waiting, waitingReason = FlashWaitingReason.DeviceNotReached),
        FlashMessageRecipientUi("d", "Di", "DI", FlashRecipientState.Waiting, waitingReason = FlashWaitingReason.LeftGroup),
    ),
)

@Preview(name = "Message info - mixed", showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun FlashMessageInfoSheetMixedPreview() {
    FlashTheme {
        Box(Modifier.background(Color(0xFF101418))) {
            FlashMessageInfoSheet(info = previewInfo, onDismiss = {})
        }
    }
}

@Preview(name = "Message info - empty", showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun FlashMessageInfoSheetEmptyPreview() {
    FlashTheme {
        Box(Modifier.background(Color(0xFF101418))) {
            FlashMessageInfoSheet(info = null, onDismiss = {})
        }
    }
}
