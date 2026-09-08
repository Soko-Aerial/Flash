package com.transfer.flash.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Pure matching/navigation logic for in-chat search (UI-023).
 * Unit-testable without instrumentation (see [FlashChatSearchLogicTest]).
 */
object FlashChatSearchMath {
    /** Message ids containing [rawQuery] (case-insensitive), oldest-first. Blank query → empty. */
    fun findMatches(messages: List<FlashMessageUi>, rawQuery: String): List<String> {
        val query = normalizeQuery(rawQuery)
        if (query.isEmpty()) return emptyList()
        return messages.filter { it.text.contains(query, ignoreCase = true) }.map { it.id }
    }

    fun normalizeQuery(rawQuery: String): String = rawQuery.trim()

    /**
     * First case-insensitive occurrence of [query] inside [text], or null.
     * Non-overlapping scan from the start (standard contains semantics).
     */
    fun matchRange(text: String, query: String): IntRange? {
        if (query.isEmpty() || text.isEmpty()) return null
        val index = text.indexOf(query, ignoreCase = true)
        return if (index < 0) null else index..(index + query.length - 1)
    }

    /** All non-overlapping match ranges, left to right. */
    fun matchRanges(text: String, query: String): List<IntRange> {
        if (normalizeQuery(query).isEmpty() || text.isEmpty()) return emptyList()
        val q = normalizeQuery(query)
        val ranges = mutableListOf<IntRange>()
        var from = 0
        while (from <= text.length - q.length) {
            val index = text.indexOf(q, from, ignoreCase = true)
            if (index < 0) break
            ranges += index..(index + q.length - 1)
            from = index + q.length
        }
        return ranges
    }

    /** Active result starts at the newest match; stepping wraps around. */
    fun initialResultIndex(count: Int): Int =
        if (count <= 0) -1 else count - 1

    fun stepIndex(current: Int, count: Int, forward: Boolean): Int {
        if (count <= 0) return -1
        return when {
            current < 0 -> count - 1
            forward -> (current + 1) % count
            else -> (current - 1 + count) % count
        }
    }

    /** "3 / 7" tabular counter label. */
    fun counterLabel(activeIndex: Int, total: Int): String {
        if (total <= 0 || activeIndex < 0) return "0 / 0"
        return "${activeIndex + 1} / $total"
    }

    fun hasResults(activeIndex: Int, total: Int): Boolean = total > 0 && activeIndex in 0 until total
}

/**
 * UI-023 In-chat search bar — replaces the chat header while a search is active.
 * Custom close/stepper chrome over a foundation text field pill; counter with liveRegion.
 */
@Composable
fun FlashChatSearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    activeResultIndex: Int,
    resultCount: Int,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    // UI-041: press-scale micro-interaction on search chrome, token-driven springSnappy.
    val motion = FlashTheme.motion
    val closeInteraction = remember { MutableInteractionSource() }
    val isClosePressed by closeInteraction.collectIsPressedAsState()
    val closePressScale by animateFloatAsState(
        targetValue = if (isClosePressed && !motion.reduceMotion) 0.90f else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "searchClosePressScale",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .statusBarsPadding()
            .padding(horizontal = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Close search
        Box(
            modifier = Modifier
                .size(FlashDimensions.minTouchTarget)
                .graphicsLayer {
                    scaleX = closePressScale
                    scaleY = closePressScale
                }
                .clip(CircleShape)
                .clickable(
                    interactionSource = closeInteraction,
                    indication = null,
                    onClick = onClose,
                )
                .semantics { role = Role.Button; contentDescription = "Close search" },
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(icon = FlashIcons.Back)
        }

        // Query field pill
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(FlashShapes.composerInput)
                .background(colors.composerInputBackground)
                .border(
                    width = FlashDimensions.borderHairline,
                    color = colors.borderSubtle,
                    shape = FlashShapes.composerInput,
                )
                .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = typography.bodyDefault.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accentPrimary),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        FlashText(
                            text = "Search in conversation",
                            style = typography.bodyDefault,
                            color = colors.textTertiary,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                },
            )
        }

        Spacer(modifier = Modifier.size(FlashSpacing.space4))

        // Result counter (polite live region for TalkBack)
        if (resultCount > 0 && activeResultIndex >= 0) {
            FlashText(
                text = FlashChatSearchMath.counterLabel(activeResultIndex, resultCount),
                style = typography.numericEmphasis.copy(fontSize = typography.metadataDefault.fontSize),
                color = colors.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (FlashChatSearchMath.normalizeQuery(query).isNotEmpty()) {
            FlashText(
                text = "No matches",
                style = typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        // Previous / next steppers (Flash back glyph rotated into chevrons)
        SearchStepButton(
            rotationZ = 90f,
            description = "Previous match",
            enabled = resultCount > 0,
            onClick = onPrevious,
        )
        SearchStepButton(
            rotationZ = -90f,
            description = "Next match",
            enabled = resultCount > 0,
            onClick = onNext,
        )
    }
}

@Composable
private fun SearchStepButton(
    rotationZ: Float,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    // UI-041: press-scale micro-interaction on search chrome, token-driven springSnappy.
    val motion = FlashTheme.motion
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !motion.reduceMotion) 0.90f else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "searchStepPressScale",
    )
    Box(
        modifier = modifier
            .size(FlashDimensions.minTouchTarget)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = FlashIcons.Back,
            contentDescription = null,
            tint = if (enabled) colors.textSecondary else colors.textTertiary,
            size = FlashDimensions.iconMd,
            modifier = Modifier.graphicsLayer { this.rotationZ = rotationZ },
        )
    }
}

/**
 * Builds the highlighted [AnnotatedString] for a message body: every occurrence of the
 * query receives an accent background span (UI-023).
 */
fun buildHighlightedMessageText(
    text: String,
    query: String,
    highlightColor: Color,
): AnnotatedString {
    val ranges = FlashChatSearchMath.matchRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { range ->
            addStyle(
                SpanStyle(background = highlightColor, fontWeight = FontWeight.SemiBold),
                range.first,
                range.last + 1,
            )
        }
    }
}
