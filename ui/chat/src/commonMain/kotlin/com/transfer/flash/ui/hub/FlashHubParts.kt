package com.transfer.flash.ui.hub

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Widest the content column grows (tablet portrait / large phones): design doc "Responsive behavior". */
internal val FlashHubContentMaxWidth = 640.dp

/** Top bar: the organisation name, a search toggle. Same height as the chat list bar. */
@Composable
internal fun FlashHubTopBar(
    title: String,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    searchFocus: FocusRequester = remember { FocusRequester() },
) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.headerHeight)
            .padding(horizontal = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchOpen) {
            FlashHubIconButton(FlashIcons.Back, FlashHubCopy.CLOSE_SEARCH, onSearchClose)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(FlashShapes.composerInput)
                    .background(colors.composerInputBackground)
                    .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.composerInput)
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocus)
                        .semantics { contentDescription = FlashHubCopy.SEARCH_HINT },
                    textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            FlashText(FlashHubCopy.SEARCH_HINT, style = FlashTheme.typography.bodyDefault, color = colors.textTertiary, maxLines = 1)
                        }
                        inner()
                    },
                )
            }
            if (query.isNotEmpty()) FlashHubIconButton(FlashIcons.Close, FlashHubCopy.CLOSE_SEARCH, { onQueryChange("") })
        } else {
            FlashText(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FlashSpacing.space12)
                    .semantics { heading() },
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlashHubIconButton(FlashIcons.Search, FlashHubCopy.SEARCH, onSearchOpen)
        }
    }
}

@Composable
internal fun FlashHubIconButton(icon: FlashIconSpec, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(FlashDimensions.minTouchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(icon = icon, contentDescription = null)
    }
}

/** The status strip (design doc "States"): icon + text + optional action, announced politely when it changes. */
@Composable
internal fun FlashHubStatusStripView(
    strip: FlashHubStrip,
    onWhy: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val (bg, fg, icon) = when (strip.tone) {
        FlashHubStripTone.CALM -> Triple(colors.backgroundSurfaceSubtle, colors.textSecondary, FlashIcons.Check)
        FlashHubStripTone.INFO -> Triple(colors.backgroundSurfaceSubtle, colors.textSecondary, FlashIcons.Wifi)
        FlashHubStripTone.WARNING -> Triple(colors.backgroundSurfaceStrong, colors.textPrimary, FlashIcons.Failed)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashIcon(icon = icon, contentDescription = null, tint = fg, size = FlashDimensions.iconSm)
        FlashText(strip.text, modifier = Modifier.weight(1f), style = FlashTheme.typography.metadataDefault, color = fg)
        when (strip.action) {
            FlashHubStripAction.NONE -> Unit
            FlashHubStripAction.WHY -> FlashHubTextAction(FlashHubCopy.STRIP_WHY, onWhy)
            FlashHubStripAction.RETRY -> FlashHubTextAction(FlashHubCopy.RETRY, onRetry)
        }
    }
}

@Composable
internal fun FlashHubTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.button)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FlashSpacing.space12),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(label, style = FlashTheme.typography.bodyEmphasis, color = if (enabled) colors.accentPrimary else colors.textTertiary)
    }
}

/** Filled primary button. */
@Composable
internal fun FlashHubButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: FlashIconSpec? = null,
    primary: Boolean = true,
) {
    val colors = FlashTheme.colors
    val bg = when {
        !enabled -> colors.backgroundSurfaceStrong
        primary -> colors.accentPrimary
        else -> colors.backgroundSurfaceSubtle
    }
    val fg = when {
        !enabled -> colors.textTertiary
        primary -> colors.textOnAccent
        else -> colors.textPrimary
    }
    Row(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.button)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FlashSpacing.space16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8, Alignment.CenterHorizontally),
    ) {
        if (icon != null) FlashIcon(icon = icon, contentDescription = null, tint = fg, size = FlashDimensions.iconSm)
        FlashText(label, style = FlashTheme.typography.bodyEmphasis, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The segmented control: a scrolling row of pills (icon, label, count) with tab semantics. Switching is by tap only, never by swipe. */
@Composable
internal fun FlashHubSegments(
    selected: FlashHubSegment,
    onSelect: (FlashHubSegment) -> Unit,
    unreadNews: Int,
    modifier: Modifier = Modifier,
    counts: Map<FlashHubSegment, Int> = emptyMap(),
) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashHubSegment.entries.forEach { segment ->
            val isSelected = segment == selected
            val label = FlashHubMath.segmentLabel(segment)
            val badge = if (segment == FlashHubSegment.NEWS && unreadNews > 0) unreadNews else 0
            val count = if (segment == FlashHubSegment.NEWS) 0 else counts[segment] ?: 0
            val icon = when (segment) {
                FlashHubSegment.NEWS -> FlashIcons.Announcement
                FlashHubSegment.PEOPLE -> FlashIcons.Group
                FlashHubSegment.DEPARTMENTS -> FlashIcons.Hub
                FlashHubSegment.GROUPS -> FlashIcons.Chat
            }
            val tint = if (isSelected) colors.accentPrimary else colors.textSecondary
            Row(
                modifier = Modifier
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(FlashShapes.navBar)
                    .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.16f) else colors.backgroundSurfaceSubtle)
                    .border(
                        BorderStroke(FlashDimensions.borderHairline, if (isSelected) colors.accentPrimary else colors.borderSubtle),
                        FlashShapes.navBar,
                    )
                    .clickable(role = Role.Tab) { onSelect(segment) }
                    .padding(horizontal = FlashSpacing.space12)
                    .semantics {
                        semanticsSelected = isSelected
                        contentDescription = when {
                            badge > 0 -> "$label, $badge unread"
                            count > 0 -> "$label, $count"
                            else -> label
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashIcon(icon = icon, contentDescription = null, tint = tint, size = FlashDimensions.iconSm)
                FlashText(
                    label,
                    style = if (isSelected) FlashTheme.typography.bodyEmphasis else FlashTheme.typography.bodyDefault,
                    color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                    maxLines = 1,
                )
                if (badge > 0) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = FlashDimensions.unreadBadgeMinSize)
                            .widthIn(min = FlashDimensions.unreadBadgeMinSize)
                            .clip(FlashShapes.navBar)
                            .background(colors.accentPrimary)
                            .padding(horizontal = FlashSpacing.space8),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashText(if (badge > 99) "99+" else badge.toString(), style = FlashTheme.typography.metadataEmphasis, color = colors.textOnAccent, maxLines = 1)
                    }
                } else if (count > 0) {
                    FlashText(
                        if (count > 999) "999+" else count.toString(),
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The card at the top of every segment: the organisation's monogram and name, what it holds, how this device stands
 * (role, whether it may post announcements) and whether the company server is reachable right now.
 */
@Composable
internal fun FlashHubOverview(
    orgName: String,
    summary: String,
    roleLine: String,
    canPost: Boolean,
    connected: Boolean?,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val name = orgName.ifBlank { FlashHubCopy.TAB_LABEL }
    val spoken = buildString {
        append(name)
        if (summary.isNotEmpty()) append(", ").append(summary)
        append(", ").append(roleLine)
        if (canPost) append(", ").append(FlashHubCopy.OVERVIEW_CAN_POST)
        if (connected != null) append(", ").append(if (connected) FlashHubCopy.OVERVIEW_CONNECTED else FlashHubCopy.OVERVIEW_OFFLINE)
    }
    Column(
        modifier = modifier
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accentPrimary.copy(alpha = 0.08f))
            .border(FlashDimensions.borderHairline, colors.accentPrimary.copy(alpha = 0.30f), shape)
            .padding(FlashSpacing.space16)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(colors.accentPrimary),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    FlashHubMath.initials(name).take(2).uppercase(),
                    style = FlashTheme.typography.headingSmall,
                    color = colors.textOnAccent,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(name, style = FlashTheme.typography.headingMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (summary.isNotEmpty()) {
                    FlashText(summary, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8), verticalAlignment = Alignment.CenterVertically) {
            FlashHubChip(roleLine, emphasised = true, modifier = Modifier.weight(1f, fill = false))
            if (canPost) FlashHubChip(FlashHubCopy.OVERVIEW_CAN_POST, modifier = Modifier.weight(1f, fill = false))
            if (connected != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) colors.statusOnline else colors.statusOffline))
                    FlashText(
                        if (connected) FlashHubCopy.OVERVIEW_CONNECTED else FlashHubCopy.OVERVIEW_OFFLINE,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A quiet information card: icon medallion and one calm sentence (e.g. why a member sees no composer). */
@Composable
internal fun FlashHubNoteCard(icon: FlashIconSpec, text: String, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val shape = FlashShapes.attachment
    Row(
        modifier = modifier
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.backgroundSurfaceSubtle)
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(colors.accentPrimary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            FlashIcon(icon = icon, contentDescription = null, tint = colors.accentPrimary, size = FlashDimensions.iconSm)
        }
        FlashText(text, modifier = Modifier.weight(1f), style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
    }
}

/** A real on / off switch (track + thumb) for the composer; the whole row is the touch target, this only draws. */
@Composable
internal fun FlashHubSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier.size(width = 46.dp, height = 28.dp).clip(CircleShape)
            .background(if (on) colors.accentPrimary else colors.backgroundSurfaceStrong)
            .border(FlashDimensions.borderHairline, if (on) colors.accentPrimary else colors.borderStrong, CircleShape)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) colors.textOnAccent else colors.textTertiary))
    }
}

/** A section title inside a list ("Pinned", "Latest"). */
@Composable
internal fun FlashHubSectionTitle(text: String, modifier: Modifier = Modifier) {
    FlashText(
        text.uppercase(),
        modifier = modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8).semantics { heading() },
        style = FlashTheme.typography.metadataEmphasis,
        color = FlashTheme.colors.textSecondary,
    )
}


/** A designed empty / error state: icon medallion, headline, optional body, optional single action. Never blank. */
@Composable
internal fun FlashHubEmptyState(
    icon: FlashIconSpec,
    headline: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionBusy: Boolean = false,
) {
    val colors = FlashTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FlashSpacing.space32, vertical = FlashSpacing.space32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(colors.accentPrimary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { FlashIcon(icon = icon, contentDescription = null, tint = colors.accentPrimary, size = FlashDimensions.iconLg) }
        FlashText(
            headline,
            modifier = Modifier.semantics { heading() },
            style = FlashTheme.typography.headingSmall,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (body != null) FlashText(body, style = FlashTheme.typography.bodyDefault, color = colors.textSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            FlashHubButton(
                label = if (actionBusy) FlashHubCopy.REFRESHING else actionLabel,
                onClick = onAction,
                enabled = !actionBusy,
            )
        }
    }
}

/** Pulsing placeholder rows for the first load. Decorative: hidden from TalkBack except one "Loading" label. */
@Composable
internal fun FlashHubSkeleton(modifier: Modifier = Modifier, rows: Int = 8) {
    val colors = FlashTheme.colors
    val reduce = FlashTheme.motion.reduceMotion
    val alpha by if (reduce) {
        remember { androidx.compose.runtime.mutableStateOf(0.5f) }
    } else {
        rememberInfiniteTransition(label = "hubSkeleton").animateFloat(
            initialValue = 0.35f, targetValue = 0.75f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "hubSkeletonAlpha",
        )
    }
    val tone: Color = colors.backgroundSurfaceStrong.copy(alpha = alpha)
    Column(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = FlashHubCopy.LOADING; liveRegion = LiveRegionMode.Polite },
    ) {
        repeat(rows.coerceIn(1, 12)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = FlashSpacing.space16).clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(FlashSpacing.space12))
                Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    Box(Modifier.width(140.dp).height(14.dp).clip(FlashShapes.chip).background(tone))
                    Box(Modifier.width(96.dp).height(12.dp).clip(FlashShapes.chip).background(tone))
                }
            }
        }
    }
}

/** Revoked: the whole tab is replaced by the notice and the one action (design doc "States"). */
@Composable
internal fun FlashHubRevokedView(onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    FlashHubEmptyState(
        icon = FlashIcons.Hub,
        headline = FlashHubCopy.REVOKED_TITLE,
        body = FlashHubCopy.REVOKED_BODY,
        actionLabel = FlashHubCopy.SIGN_OUT_ORG,
        onAction = onSignOut,
        modifier = modifier.fillMaxSize(),
    )
}

/** Small chip (role, scope, "Paired"): text always present, never colour alone. */
@Composable
internal fun FlashHubChip(text: String, modifier: Modifier = Modifier, emphasised: Boolean = false) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .clip(FlashShapes.chip)
            .background(if (emphasised) colors.accentPrimary.copy(alpha = 0.16f) else colors.backgroundSurfaceStrong)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space2),
    ) {
        FlashText(
            text,
            style = FlashTheme.typography.metadataEmphasis,
            color = if (emphasised) colors.accentPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The online dot: drawn only when presence is known (design doc: withheld presence shows no dot, not a grey one). */
@Composable
internal fun FlashHubPresenceDot(online: Boolean?, modifier: Modifier = Modifier) {
    if (online == null) return
    val colors = FlashTheme.colors
    Box(
        modifier = modifier.size(10.dp).clip(CircleShape).background(if (online) colors.statusOnline else colors.statusOffline)
            .border(FlashDimensions.borderHairline, colors.backgroundApp, CircleShape),
    )
}
