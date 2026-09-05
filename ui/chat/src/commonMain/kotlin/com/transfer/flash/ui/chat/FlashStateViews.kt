package com.transfer.flash.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Pure decision + copy logic for system states (UI-025 / UI-026 / UI-027).
 * Unit-tested without instrumentation (see [FlashStatesLogicTest]).
 */
object FlashStateMath {
    /** Delay guard: loads resolving under [minimumDelayMs] show no skeleton at all. */
    const val MINIMUM_LOAD_DELAY_MS = 300L

    fun shouldShowLoadingIndicator(elapsedMs: Long, minimumDelayMs: Long = MINIMUM_LOAD_DELAY_MS): Boolean =
        elapsedMs >= minimumDelayMs

    /** Skeleton row budget: match expected content count, hard-capped for memory. */
    const val MAX_SKELETON_ROWS = 12

    fun skeletonRowCount(requested: Int): Int = requested.coerceIn(1, MAX_SKELETON_ROWS)
}

/** Screen-specific state copy (UI-025) — never generic "Nothing here yet". */
object FlashStateCopy {
    enum class EmptyKind { ChatListFirstRun, ConversationEmpty, TransfersFirstRun }

    data class Copy(val headline: String, val body: String, val actionLabel: String? = null)

    fun emptyStateCopy(kind: EmptyKind): Copy = when (kind) {
        EmptyKind.ChatListFirstRun -> Copy(
            headline = "No conversations yet",
            body = "Start a chat with a nearby device and it will show up here.",
            actionLabel = "Find devices",
        )
        EmptyKind.ConversationEmpty -> Copy(
            headline = "Say hello",
            body = "Messages you send appear right here — everything stays on your network.",
        )
        EmptyKind.TransfersFirstRun -> Copy(
            headline = "No transfers yet",
            body = "Send something from a chat or pick a device nearby and your files will appear here.",
            actionLabel = "Find devices",
        )
    }
}

/**
 * UI-025 Branded empty-state panel: icon medallion, screen-specific headline,
 * one-sentence explanation, optional single CTA.
 */
@Composable
fun FlashEmptyState(
    kind: FlashStateCopy.EmptyKind,
    modifier: Modifier = Modifier,
    icon: FlashIconSpec = when (kind) {
        FlashStateCopy.EmptyKind.ChatListFirstRun -> FlashIcons.Group
        FlashStateCopy.EmptyKind.ConversationEmpty -> FlashIcons.Send
        FlashStateCopy.EmptyKind.TransfersFirstRun -> FlashIcons.Transfer
    },
    onAction: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val copy = FlashStateCopy.emptyStateCopy(kind)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FlashSpacing.space32)
            .semantics(mergeDescendants = true) {
                contentDescription = "${copy.headline}. ${copy.body}"
            },
    ) {
        // Icon medallion
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.accentPrimary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = icon,
                contentDescription = null,
                tint = colors.accentPrimary,
                size = FlashDimensions.iconLg,
            )
        }

        FlashText(
            text = copy.headline,
            style = typography.headingSmall,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        FlashText(
            text = copy.body,
            style = typography.metadataDefault,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        if (copy.actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(FlashSpacing.space4))
            Box(
                modifier = Modifier
                    .clip(FlashShapes.avatar)
                    .background(colors.accentPrimary)
                    .clickable(onClick = onAction)
                    .semantics {
                        role = Role.Button
                        contentDescription = copy.actionLabel
                    }
                    .padding(horizontal = FlashSpacing.space20, vertical = FlashSpacing.space8),
            ) {
                FlashText(
                    text = copy.actionLabel,
                    style = typography.metadataEmphasis,
                    color = colors.textOnAccent,
                )
            }
        }
    }
}

/** Severity distinction from the design doc: faults are red; offline is a condition, not a fault. */
enum class FlashErrorSeverity { Failure, Environmental }

/**
 * UI-027 Container-level error panel with severity styling and a single Retry action.
 */
@Composable
fun FlashErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    severity: FlashErrorSeverity = FlashErrorSeverity.Failure,
    retryLabel: String = "Retry",
    icon: FlashIconSpec = if (severity == FlashErrorSeverity.Failure) FlashIcons.Failed else FlashIcons.Connection,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    val medallionFill = when (severity) {
        FlashErrorSeverity.Failure -> colors.textError.copy(alpha = 0.10f)
        FlashErrorSeverity.Environmental -> colors.accentPrimary.copy(alpha = 0.10f)
    }
    val medallionTint = when (severity) {
        FlashErrorSeverity.Failure -> colors.textError
        FlashErrorSeverity.Environmental -> colors.textSecondary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FlashSpacing.space32)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $message"
            },
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(medallionFill),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = icon,
                contentDescription = null,
                tint = medallionTint,
                size = FlashDimensions.iconLg,
            )
        }

        FlashText(
            text = title,
            style = typography.headingSmall,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        FlashText(
            text = message,
            style = typography.metadataDefault,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(FlashSpacing.space4))
        Box(
            modifier = Modifier
                .clip(FlashShapes.avatar)
                .background(colors.accentPrimary)
                .clickable(onClick = onRetry)
                .semantics {
                    role = Role.Button
                    contentDescription = retryLabel
                }
                .padding(horizontal = FlashSpacing.space20, vertical = FlashSpacing.space8),
        ) {
            FlashText(
                text = retryLabel,
                style = typography.metadataEmphasis,
                color = colors.textOnAccent,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// UI-026 Skeletons — layout-matched placeholders, decorative semantics only.
// ---------------------------------------------------------------------------

/** Skeleton placeholder for the chat list: N rows matching real 72dp row geometry. */
@Composable
fun FlashSkeletonChatList(
    modifier: Modifier = Modifier,
    rowCount: Int = 8,
) {
    Column(modifier = modifier.fillMaxSize()) {
        repeat(FlashStateMath.skeletonRowCount(rowCount)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FlashDimensions.chatListRowHeight)
                    .padding(horizontal = FlashSpacing.space16),
            ) {
                FlashSkeletonCircle(size = 48.dp)
                Spacer(modifier = Modifier.width(FlashSpacing.space12))
                Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    FlashSkeletonBar(width = 140.dp, height = 14.dp)
                    FlashSkeletonBar(width = 220.dp, height = 12.dp)
                }
            }
        }
    }
}

/** Skeleton placeholder for an open conversation: alternating bubble shapes. */
@Composable
fun FlashSkeletonConversation(
    modifier: Modifier = Modifier,
    bubbleCount: Int = 6,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space16),
    ) {
        repeat(bubbleCount) { index ->
            val isMine = index % 2 == 1
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                FlashSkeletonRoundedRect(
                    width = if (isMine) 210.dp else 250.dp,
                    height = 44.dp,
                    cornerRadius = 18.dp,
                )
            }
        }
    }
}

/** Soft opacity pulse (0.5↔1); fully static under reduce-motion (accessible default). */
@Composable
private fun rememberSkeletonAlpha(): Float {
    val motion = FlashTheme.motion
    return if (motion.reduceMotion) {
        0.6f
    } else {
        val transition = rememberInfiniteTransition(label = "skeletonPulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(motion.slowMillis),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeletonPulseAlpha",
        )
        alpha
    }
}

@Composable
private fun FlashSkeletonCircle(size: androidx.compose.ui.unit.Dp) {
    val alpha = rememberSkeletonAlpha()
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayerAlpha(alpha)
            .clip(CircleShape)
            .background(FlashTheme.colors.backgroundSurfaceSubtle)
            .clearAndSetSemantics {},
    )
}

@Composable
private fun FlashSkeletonBar(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    val alpha = rememberSkeletonAlpha()
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .graphicsLayerAlpha(alpha)
            .clip(FlashShapes.chip)
            .background(FlashTheme.colors.backgroundSurfaceSubtle)
            .clearAndSetSemantics {},
    )
}

@Composable
private fun FlashSkeletonRoundedRect(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
) {
    val alpha = rememberSkeletonAlpha()
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .graphicsLayerAlpha(alpha)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius))
            .background(FlashTheme.colors.chatBgIncoming.copy(alpha = 0.7f))
            .clearAndSetSemantics {},
    )
}

private fun Modifier.graphicsLayerAlpha(alpha: Float): Modifier =
    this.then(Modifier.graphicsLayer { this.alpha = alpha })
