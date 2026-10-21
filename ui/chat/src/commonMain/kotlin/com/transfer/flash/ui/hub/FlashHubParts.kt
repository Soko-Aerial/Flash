package com.transfer.flash.ui.hub

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlin.math.roundToInt

/** Widest the content column grows (tablet portrait / large phones): design doc "Responsive behavior". */
internal val FlashHubContentMaxWidth = 640.dp

/** Height of one segment pill: the touch target, so the pill and the hit area are the same thing. */
private val SegmentHeight = FlashDimensions.minTouchTarget

/** Top bar: the Hub mark and title, a search toggle. Same height as the chat list bar; title and search field crossfade. */
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
    val motion = FlashTheme.motion
    AnimatedContent(
        targetState = searchOpen,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = { motion.statusCrossfade() },
        label = "hubTopBar",
    ) { open ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FlashDimensions.headerHeight)
                .padding(horizontal = FlashSpacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (open) {
                FlashHubIconButton(FlashIcons.Back, FlashHubCopy.CLOSE_SEARCH, onSearchClose)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(HubShapes.control)
                        .background(colors.composerInputBackground)
                        .border(FlashDimensions.borderHairline, colors.borderSubtle, HubShapes.control)
                        .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    FlashIcon(icon = FlashIcons.Search, contentDescription = null, tint = colors.textTertiary, size = FlashDimensions.iconSm)
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        modifier = Modifier
                            .weight(1f)
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
                Box(
                    modifier = Modifier
                        .padding(start = FlashSpacing.space8)
                        .size(32.dp)
                        .clip(HubShapes.control)
                        .background(colors.accentPrimary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { FlashIcon(icon = FlashIcons.Hub, contentDescription = null, tint = colors.accentPrimary, size = FlashDimensions.iconSm) }
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
}

@Composable
internal fun FlashHubIconButton(icon: FlashIconSpec, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(FlashDimensions.minTouchTarget)
            .clip(HubShapes.control)
            .hubClickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(icon = icon, contentDescription = null)
    }
}

/**
 * The status strip (design doc "States"): a tone wash with an icon, the text and an optional action, announced politely when
 * it changes. The text crossfades when the state changes; "Updating…" shows a turning arrow.
 */
@Composable
internal fun FlashHubStatusStripView(
    strip: FlashHubStrip,
    onWhy: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = when (strip.tone) {
        FlashHubStripTone.CALM -> HubTone.SUCCESS
        FlashHubStripTone.INFO -> HubTone.ACCENT
        FlashHubStripTone.WARNING -> HubTone.WARNING
    }
    val tint = tone.color()
    val updating = strip.text == FlashHubCopy.STRIP_UPDATING
    val icon = when {
        updating -> FlashIcons.Retry
        strip.tone == FlashHubStripTone.CALM -> FlashIcons.Verified
        strip.tone == FlashHubStripTone.INFO -> FlashIcons.Wifi
        else -> FlashIcons.Failed
    }
    val turn = rememberHubPhase(periodMs = 1100)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f))
            .drawBehind { drawRect(tint.copy(alpha = 0.30f), topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f)) }
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Box(Modifier.graphicsLayer { rotationZ = if (updating) turn.value * 360f else 0f }) {
            FlashIcon(icon = icon, contentDescription = null, tint = tint, size = FlashDimensions.iconSm)
        }
        FlashText(strip.text, modifier = Modifier.weight(1f), style = FlashTheme.typography.metadataDefault, color = FlashTheme.colors.textPrimary)
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
            .clip(HubShapes.control)
            .hubClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = FlashSpacing.space12),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(label, style = FlashTheme.typography.bodyEmphasis, color = if (enabled) colors.accentPrimary else colors.textTertiary)
    }
}

/** Filled primary button (or a quiet secondary). The label crossfades when it changes (Join -> Waiting -> Open). */
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
    val motion = FlashTheme.motion
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> colors.backgroundSurfaceStrong
            primary -> colors.accentPrimary
            else -> colors.backgroundSurfaceSubtle
        },
        animationSpec = motion.tweenFastSpec(),
        label = "hubButtonBg",
    )
    val fg = when {
        !enabled -> colors.textTertiary
        primary -> colors.textOnAccent
        else -> colors.textPrimary
    }
    Row(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(HubShapes.control)
            .background(bg)
            .then(if (!primary && enabled) Modifier.border(FlashDimensions.borderHairline, colors.borderSubtle, HubShapes.control) else Modifier)
            .hubClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = FlashSpacing.space16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8, Alignment.CenterHorizontally),
    ) {
        if (icon != null) FlashIcon(icon = icon, contentDescription = null, tint = fg, size = FlashDimensions.iconSm)
        AnimatedContent(targetState = label, transitionSpec = { motion.statusCrossfade() }, label = "hubButtonLabel") { text ->
            FlashText(text, style = FlashTheme.typography.bodyEmphasis, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The section tabs: plain text on a hairline rule with a 2 dp accent underline that glides (spring) to the selected tab; the
 * unread count on News is a small square. No pill, no track. Switching is by tap only; the row scrolls when the window is
 * narrow and keeps the selected tab in view.
 */
@Composable
internal fun FlashHubSegments(
    selected: FlashHubSegment,
    onSelect: (FlashHubSegment) -> Unit,
    unreadNews: Int,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<FlashHubSegment, IntRange>() }
    var viewport by remember { mutableStateOf(0) }
    val target = bounds[selected]
    val indicatorX by animateDpAsState(
        targetValue = with(density) { (target?.first ?: 0).toDp() },
        animationSpec = motion.springDefaultSpec(),
        label = "hubSegX",
    )
    val indicatorW by animateDpAsState(
        targetValue = with(density) { (target?.let { it.last - it.first } ?: 0).toDp() },
        animationSpec = motion.springDefaultSpec(),
        label = "hubSegW",
    )
    androidx.compose.runtime.LaunchedEffect(selected, target, viewport) {
        val t = target ?: return@LaunchedEffect
        val centre = (t.first + t.last) / 2 - viewport / 2
        val to = centre.coerceIn(0, scroll.maxValue)
        if (motion.reduceMotion) scroll.scrollTo(to) else scroll.animateScrollTo(to)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .hubRuleBelow()
            .onGloballyPositioned { viewport = it.size.width },
    ) {
        Box(Modifier.horizontalScroll(scroll)) {
            Row(Modifier.padding(horizontal = FlashSpacing.space8)) {
                FlashHubSegment.entries.forEach { segment ->
                    val isSelected = segment == selected
                    val label = FlashHubMath.segmentLabel(segment)
                    val badge = if (segment == FlashHubSegment.NEWS && unreadNews > 0) unreadNews else 0
                    val tint by animateColorAsState(
                        if (isSelected) colors.textPrimary else colors.textSecondary,
                        animationSpec = motion.tweenFastSpec(),
                        label = "hubSegTint",
                    )
                    Row(
                        modifier = Modifier
                            .height(SegmentHeight)
                            .onGloballyPositioned {
                                val x = it.positionInParent().x.roundToInt()
                                bounds[segment] = x..(x + it.size.width)
                            }
                            .hubClickable(role = Role.Tab) { onSelect(segment) }
                            .padding(horizontal = FlashSpacing.space12)
                            .semantics {
                                semanticsSelected = isSelected
                                contentDescription = if (badge > 0) "$label, $badge unread" else label
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    ) {
                        FlashText(
                            label,
                            style = if (isSelected) FlashTheme.typography.bodyEmphasis else FlashTheme.typography.bodyDefault,
                            color = tint,
                            maxLines = 1,
                        )
                        AnimatedVisibility(visible = badge > 0, enter = motion.badgePopEnter(), exit = motion.badgePopExit()) {
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 18.dp)
                                    .widthIn(min = 18.dp)
                                    .clip(HubShapes.capsule)
                                    .background(colors.accentPrimary)
                                    .padding(horizontal = FlashSpacing.space4),
                                contentAlignment = Alignment.Center,
                            ) {
                                FlashText(if (badge > 99) "99+" else badge.toString(), style = FlashTheme.typography.numericEmphasis, color = colors.textOnAccent, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (target != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = indicatorX + FlashSpacing.space8)
                        .width(indicatorW)
                        .height(3.dp)
                        .clip(HubShapes.capsule)
                        .background(colors.accentPrimary),
                )
            }
        }
    }
}

/** One glance number on the header: a count that rolls up on first show, tappable (it opens that segment). */
internal data class FlashHubStat(
    val segment: FlashHubSegment,
    val label: String,
    val count: Int,
    val icon: FlashIconSpec,
)

/**
 * The masthead at the top of every section: the organisation's square mark and name, whether the company server is reachable
 * (a square that breathes while it is), the offline-trust meter (one tick per day), how this device stands (role, whether it
 * may post) and a ledger row of three counts separated by hairlines. No card, no gradient: it sits on the page between two
 * rules. The title row opens the status page when [onOpenStatus] is given.
 */
@Composable
internal fun FlashHubOverview(
    orgName: String,
    summary: String,
    roleLine: String,
    canPost: Boolean,
    connected: Boolean?,
    modifier: Modifier = Modifier,
    stats: List<FlashHubStat> = emptyList(),
    lease: OrgLease = OrgLease.None,
    nowMs: Long = 0L,
    onStat: (FlashHubSegment) -> Unit = {},
    onOpenStatus: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val name = orgName.ifBlank { FlashHubCopy.TAB_LABEL }
    val days = FlashHubVisualMath.leaseDaysLeft(lease, nowMs)
    val fraction = FlashHubVisualMath.leaseFraction(lease, nowMs)
    val leaseTone = FlashHubVisualMath.leaseTone(lease, nowMs).hubTone()
    val spoken = buildString {
        append(name)
        if (summary.isNotEmpty()) append(", ").append(summary)
        append(", ").append(roleLine)
        if (canPost) append(", ").append(FlashHubCopy.OVERVIEW_CAN_POST)
        if (connected != null) append(", ").append(if (connected) FlashHubCopy.OVERVIEW_CONNECTED else FlashHubCopy.OVERVIEW_OFFLINE)
        if (fraction != null) append(", ").append(FlashHubCopy.statusLeaseSpoken(days))
    }
    Column(
        modifier = modifier
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12)
            .fillMaxWidth()
            .hubStagger(0, "hub-hero")
            .clip(HubShapes.card)
            .background(hubHeroBrush())
            .border(FlashDimensions.borderHairline, colors.accentPrimary.copy(alpha = 0.28f), HubShapes.card)
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onOpenStatus != null) Modifier.clip(HubShapes.control).hubClickable(onClick = onOpenStatus) else Modifier)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (onOpenStatus != null) "${FlashHubCopy.STATUS_OPEN}. $spoken" else spoken
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            HubMonogram(FlashHubMath.initials(name).take(2).uppercase(), 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(name, style = FlashTheme.typography.headingMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (connected != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                        HubLiveDot(color = if (connected) colors.statusOnline else colors.statusOffline, live = connected)
                        FlashText(
                            if (connected) FlashHubCopy.OVERVIEW_CONNECTED else FlashHubCopy.OVERVIEW_OFFLINE,
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                } else if (summary.isNotEmpty() && stats.isEmpty()) {
                    FlashText(summary, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (fraction != null) {
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FlashText(FlashHubCopy.STATUS_TRUST.uppercase(), modifier = Modifier.weight(1f), style = hubCapsStyle(), color = colors.textSecondary, maxLines = 1)
                    FlashText(
                        if (days == null || days == 0) FlashHubCopy.STATUS_LEASE_ENDED else FlashHubCopy.statusLeaseDays(days),
                        style = FlashTheme.typography.numericEmphasis,
                        color = leaseTone.color(),
                        maxLines = 1,
                    )
                }
                HubTickMeter(fraction = fraction, color = leaseTone.color())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8), verticalAlignment = Alignment.CenterVertically) {
            FlashHubChip(roleLine, emphasised = true, modifier = Modifier.weight(1f, fill = false))
            if (canPost) FlashHubChip(FlashHubCopy.OVERVIEW_CAN_POST, icon = FlashIcons.Announcement, modifier = Modifier.weight(1f, fill = false))
        }
        if (stats.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8), modifier = Modifier.fillMaxWidth()) {
                stats.forEach { stat -> HubStatCell(stat, onClick = { onStat(stat.segment) }, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** One count tile of the masthead: icon, the number in tabular figures that counts up, its label. Opens that section. */
@Composable
private fun HubStatCell(stat: FlashHubStat, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val shown = rememberHubCount(stat.count)
    Column(
        modifier = modifier
            .clip(HubShapes.control)
            .background(colors.backgroundSurface.copy(alpha = 0.78f))
            .border(FlashDimensions.borderHairline, colors.borderSubtle, HubShapes.control)
            .hubClickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space12)
            .semantics(mergeDescendants = true) { contentDescription = FlashHubCopy.statNumberSpoken(stat.count, stat.label) },
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        FlashIcon(icon = stat.icon, contentDescription = null, tint = colors.accentPrimary, size = FlashDimensions.iconSm)
        FlashText(shown.toString(), style = FlashTheme.typography.headingLarge.copy(fontFeatureSettings = "tnum"), color = colors.textPrimary, maxLines = 1)
        FlashText(stat.label, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The organisation / department mark: a square tile with initials (flat accent; a faint gradient only in the full mode). */
@Composable
internal fun HubMonogram(text: String, size: Dp, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val decor = hubDecorAllowed()
    Box(
        modifier = modifier
            .size(size)
            .clip(HubShapes.mark(size))
            .background(
                if (decor) Brush.linearGradient(listOf(colors.accentPrimary, colors.accentPrimary.copy(alpha = 0.78f)))
                else SolidColor(colors.accentPrimary),
            ),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(text, style = if (size >= 48.dp) FlashTheme.typography.headingSmall else FlashTheme.typography.bodyEmphasis, color = colors.textOnAccent, maxLines = 1)
    }
}

/** A quiet information strip: an accent edge, an icon and one calm sentence (e.g. why a member sees no composer). */
@Composable
internal fun FlashHubNoteCard(
    icon: FlashIconSpec,
    text: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = FlashSpacing.space16,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .padding(horizontal = horizontalPadding, vertical = FlashSpacing.space4)
            .fillMaxWidth()
            .clip(HubShapes.control)
            .background(colors.backgroundSurfaceSubtle)
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashIcon(icon = icon, contentDescription = null, tint = colors.accentPrimary, size = FlashDimensions.iconSm)
        FlashText(text, modifier = Modifier.weight(1f), style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
    }
}

/** A real on / off switch for the composer, squared: a 2 dp track and a square thumb that glides. The whole row is the touch target. */
@Composable
internal fun FlashHubSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val thumbX by animateDpAsState(if (on) 18.dp else 0.dp, animationSpec = motion.springSnappySpec(), label = "hubSwitchThumb")
    val track by animateColorAsState(if (on) colors.accentPrimary else colors.backgroundSurfaceStrong, animationSpec = motion.tweenFastSpec(), label = "hubSwitchTrack")
    val thumb by animateColorAsState(if (on) colors.textOnAccent else colors.textTertiary, animationSpec = motion.tweenFastSpec(), label = "hubSwitchThumbColor")
    Box(
        modifier = modifier.size(width = 46.dp, height = 28.dp).clip(HubShapes.capsule)
            .background(track)
            .border(FlashDimensions.borderHairline, if (on) colors.accentPrimary else colors.borderStrong, HubShapes.capsule)
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = thumbX).size(22.dp).clip(HubShapes.capsule).background(thumb))
    }
}

/** A section label inside a list ("Pinned", "Latest"): small capitals followed by a hairline that runs to the edge. */
@Composable
internal fun FlashHubSectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashText(
            text.uppercase(),
            style = hubCapsStyle(),
            color = FlashTheme.colors.textSecondary,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(1.dp).background(FlashTheme.colors.borderSubtle))
    }
}

/** A designed empty / error state: a square icon medallion, headline, optional body, optional single action. Never blank. */
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
    val phase = rememberHubPhase(periodMs = 3200)
    val decor = hubDecorAllowed()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FlashSpacing.space32, vertical = FlashSpacing.space32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .then(
                    if (decor) {
                        Modifier.drawBehind {
                            val p = phase.value
                            val grow = this.size.minDimension * 0.18f * p
                            drawRect(
                                colors.accentPrimary.copy(alpha = 0.16f * (1f - p)),
                                topLeft = Offset(-grow / 2f, -grow / 2f),
                                size = Size(this.size.width + grow, this.size.height + grow),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .clip(HubShapes.mark(88.dp))
                .background(colors.accentPrimary.copy(alpha = 0.10f))
                .border(FlashDimensions.borderHairline, colors.accentPrimary.copy(alpha = 0.40f), HubShapes.mark(88.dp)),
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

/**
 * Placeholder blocks for the first load: a masthead block and ruled rows, with a soft highlight sweeping across in the full
 * mode and flat blocks otherwise. Decorative: hidden from TalkBack except one "Loading" label.
 */
@Composable
internal fun FlashHubSkeleton(modifier: Modifier = Modifier, rows: Int = 8) {
    val colors = FlashTheme.colors
    val sweep = rememberHubPhase(periodMs = 1500, still = 0.5f)
    val shimmer = hubLoopAllowed()
    val base: Color = colors.backgroundSurfaceStrong.copy(alpha = 0.55f)
    val highlight = colors.textPrimary.copy(alpha = 0.07f)
    @Composable
    fun block(width: Dp?, height: Dp, mod: Modifier = Modifier) {
        Box(
            mod.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).height(height)
                .clip(HubShapes.control).background(base).hubShimmer(highlight, sweep, shimmer),
        )
    }
    Column(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = FlashHubCopy.LOADING; liveRegion = LiveRegionMode.Polite },
    ) {
        block(null, 196.dp, Modifier.padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12).clearAndSetSemantics { })
        repeat(rows.coerceIn(1, 12)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = FlashSpacing.space16).clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                block(40.dp, 40.dp)
                Spacer(Modifier.width(FlashSpacing.space12))
                Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    block(140.dp, 14.dp)
                    block(96.dp, 12.dp)
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

/** Small chip (role, scope, "Paired"): text always present, never colour alone. An optional icon leads. */
@Composable
internal fun FlashHubChip(text: String, modifier: Modifier = Modifier, emphasised: Boolean = false, icon: FlashIconSpec? = null) {
    val colors = FlashTheme.colors
    val fg = if (emphasised) colors.accentPrimary else colors.textSecondary
    Row(
        modifier = modifier
            .clip(HubShapes.chip)
            .background(if (emphasised) colors.accentPrimary.copy(alpha = 0.16f) else colors.backgroundSurfaceStrong)
            .then(if (emphasised) Modifier.border(FlashDimensions.borderHairline, colors.accentPrimary.copy(alpha = 0.5f), HubShapes.chip) else Modifier)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        if (icon != null) FlashIcon(icon = icon, contentDescription = null, tint = fg, size = 14.dp)
        FlashText(
            text,
            style = FlashTheme.typography.metadataEmphasis,
            color = fg,
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
        modifier = modifier.size(14.dp).clip(HubShapes.capsule).background(colors.backgroundApp),
        contentAlignment = Alignment.Center,
    ) {
        HubLiveDot(color = if (online) colors.statusOnline else colors.statusOffline, live = false, size = 10.dp)
    }
}
