package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.adaptive.FlashAdaptiveTwoPane
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.delay

/**
 * Shared page furniture for the deck.
 *
 * The split follows the project's own adaptive rule (`FlashAdaptiveMath`: two panes only at ≥ 840dp), so the mobile and
 * desktop layouts are the same page rather than two pages. On a narrow screen a detail is a **pushed screen** — full
 * cover with a back affordance — which is what the shipped Hub tab does with its reader.
 */

/** The heading every page opens with: title, one-line orientation, the page's live verdict pill, and page actions. */
@Composable
internal fun HcPageHeader(
    title: String,
    subtitle: String,
    glyph: HcGlyph,
    tone: HcTone,
    modifier: Modifier = Modifier,
    statusLabel: String? = null,
    statusPulse: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = FlashSpacing.space12, bottom = FlashSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(FlashShapes.radius12)).background(t.soft)
                    .border(FlashDimensions.borderHairline, t.edge, RoundedCornerShape(FlashShapes.radius12)),
                contentAlignment = Alignment.Center,
            ) { HcGlyphIcon(glyph, tint = t.on, size = FlashDimensions.iconMd) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(
                    title,
                    modifier = Modifier.semantics { heading() },
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashText(
                    subtitle,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        if (statusLabel != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                HcPulseDot(tone, pulsing = statusPulse, diameter = 9.dp)
                FlashText(statusLabel, style = FlashTheme.typography.metadataEmphasis, color = t.on, maxLines = 1)
            }
        }
    }
}

/** True when the surface is wide enough for two panes (`FlashAdaptiveMath`: Expanded, ≥ 840dp). */
internal fun hcIsWide(widthDp: Float): Boolean =
    FlashAdaptiveMath.isTwoPaneAllowed(FlashAdaptiveMath.windowSizeForWidth(widthDp))
/** Tile columns for a width: the compact count on a phone, the wide count when two panes fit. */
internal fun hcColumns(widthDp: Float, compact: Int, wide: Int): Int = if (hcIsWide(widthDp)) wide else compact

/**
 * Mock progress for a tapped action: returns `busy` and a trigger. Tapping flips [busy] true for [ms] and back, so a
 * Refresh or Retry in the deck shows its own progress without a repository. Nothing here touches real state.
 */
@Composable
internal fun rememberHcBusy(ms: Long = 1500L): Pair<Boolean, () -> Unit> {
    var busy by remember { mutableStateOf(false) }
    var ticks by remember { mutableStateOf(0) }
    LaunchedEffect(ticks) {
        if (ticks == 0) return@LaunchedEffect
        busy = true
        delay(ms)
        busy = false
    }
    return busy to { ticks += 1 }
}

/**
 * The page body: two panes where [FlashAdaptiveMath] allows it, a listed page with a pushed detail where it does not.
 *
 * The detail is always drawn by this function — a back bar plus one capped, scrolling column — so a page writes its
 * detail once and gets the phone's pushed screen and the desktop's second pane from the same source.
 *
 * @param detailOpen whether a detail is chosen. On desktop the caller usually preselects the first row.
 * @param detailPlaceholder the desktop's right pane before anything is chosen. Never a blank pane.
 * @param contentMaxWidth caps a reading column so a card never stretches across a desktop window's full width.
 */
@Composable
internal fun HcSplitPage(
    widthDp: Float,
    detailOpen: Boolean,
    onCloseDetail: () -> Unit,
    detailTitle: String,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    detailPlaceholder: @Composable () -> Unit,
    contentMaxWidth: Dp = HcDims.contentMaxWidth,
) {
    val colors = FlashTheme.colors
    val twoPane = hcIsWide(widthDp)
    if (twoPane) {
        FlashAdaptiveTwoPane(
            listPane = { Box(Modifier.fillMaxSize().background(colors.backgroundApp)) { listPane() } },
            detailPane = {
                Box(Modifier.fillMaxSize().background(colors.backgroundApp)) {
                    if (detailOpen) {
                        HcDetailShell(detailTitle, onCloseDetail, "Close the detail pane", HcGlyph.CLOSE, contentMaxWidth, detailPane)
                    } else {
                        detailPlaceholder()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            windowWidthDp = widthDp,
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            listPane()
            if (detailOpen) {
                Box(Modifier.fillMaxSize().background(colors.backgroundApp)) {
                    HcDetailShell(detailTitle, onCloseDetail, "Back", HcGlyph.BACK, contentMaxWidth, detailPane)
                }
            }
        }
    }
}

/** The back bar plus the one capped, scrolling column every detail is drawn in. */
@Composable
private fun HcDetailShell(
    title: String,
    onBack: () -> Unit,
    backDescription: String,
    backGlyph: HcGlyph,
    contentMaxWidth: Dp,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        HcDetailBar(title = title, onBack = onBack, description = backDescription, glyph = backGlyph)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = contentMaxWidth).verticalScroll(rememberScrollState()).padding(horizontal = FlashSpacing.space16),
            ) {
                content()
                Spacer(Modifier.height(FlashSpacing.space32))
            }
        }
    }
}

/** The pushed detail's leading bar: a back arrow on a phone, a close button on the desktop's second pane. */
@Composable
internal fun HcDetailBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = "Back",
    glyph: HcGlyph = HcGlyph.BACK,
) {
    val colors = FlashTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = FlashDimensions.headerHeight)
            .padding(horizontal = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        HcIconAction(glyph, description, onBack)
        FlashText(
            title,
            Modifier.weight(1f),
            style = FlashTheme.typography.headingSmall,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The one scrolling column a page uses when it is not split — centred and capped on a wide window. */
@Composable
internal fun HcScrollColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = HcDims.contentMaxWidth,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().background(FlashTheme.colors.backgroundApp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = maxWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FlashSpacing.space16),
        ) {
            content()
            Spacer(Modifier.height(FlashSpacing.space32))
        }
    }
}

/** A search field with a leading glyph and a trailing clear, used by Directory and Announcements. */
@Composable
internal fun HcSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(FlashShapes.composerInput)
            .background(colors.composerInputBackground)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.composerInput)
            .heightIn(min = FlashDimensions.minTouchTarget)
            .padding(horizontal = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        HcGlyphIcon(HcGlyph.SEARCH, tint = colors.textTertiary, size = FlashDimensions.iconSm)
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = placeholder },
                textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accentPrimary),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        FlashText(placeholder, style = FlashTheme.typography.bodyDefault, color = colors.textTertiary, maxLines = 1)
                    }
                    inner()
                },
            )
        }
        if (value.isNotEmpty()) {
            HcIconAction(HcGlyph.CLOSE, "Clear search", { onValueChange("") })
        }
    }
}

/** A chip that behaves as a radio: filled when selected, and always carrying a word. */
@Composable
internal fun HcChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: HcGlyph? = null,
) {
    val colors = FlashTheme.colors
    val t = hcTone(if (selected) HcTone.ACCENT else HcTone.NEUTRAL)
    Row(
        modifier
            .clip(FlashShapes.navBar)
            .background(if (selected) t.strong else colors.backgroundSurfaceSubtle)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        if (glyph != null) HcGlyphIcon(glyph, tint = if (selected) colors.textOnAccent else colors.textSecondary, size = 14.dp)
        FlashText(
            label,
            style = if (selected) FlashTheme.typography.metadataEmphasis else FlashTheme.typography.metadataDefault,
            color = if (selected) colors.textOnAccent else colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** A single-line or growing text input on the composer's own input surface. */
@Composable
internal fun HcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minHeight: Dp = FlashDimensions.minTouchTarget,
) {
    val colors = FlashTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(FlashShapes.composerInput)
            .background(colors.composerInputBackground)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.composerInput)
            .heightIn(min = minHeight)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
    ) {
        if (value.isEmpty()) {
            FlashText(placeholder, style = FlashTheme.typography.bodyDefault, color = colors.textTertiary, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
            textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accentPrimary),
        )
    }
}

/** A scrollable row of selectable filter pills with tab semantics. */
@Composable
internal fun HcFilterRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    countOf: ((Int) -> Int?)? = null,
) {
    val colors = FlashTheme.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = FlashSpacing.space8),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val count = countOf?.invoke(index)
            Row(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(FlashShapes.navBar)
                    .background(if (selected) colors.accentPrimary.copy(alpha = 0.16f) else colors.backgroundSurfaceSubtle)
                    .border(
                        FlashDimensions.borderHairline,
                        if (selected) colors.accentPrimary else colors.borderSubtle,
                        FlashShapes.navBar,
                    )
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(horizontal = FlashSpacing.space12)
                    .semantics {
                        this.selected = selected
                        contentDescription = if (count != null) "$label, $count" else label
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                FlashText(
                    label,
                    style = if (selected) FlashTheme.typography.metadataEmphasis else FlashTheme.typography.metadataDefault,
                    color = if (selected) colors.accentPrimary else colors.textSecondary,
                    maxLines = 1,
                )
                if (count != null && count > 0) {
                    FlashText("$count", style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1)
                }
            }
        }
    }
}

/** A compact refresh affordance that shows its own progress: idle → spinning → done, plus a failure state. */
@Composable
internal fun HcRefreshRow(
    sentence: String,
    versionLine: String,
    refreshing: Boolean,
    failed: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val tone = when {
        failed -> HcTone.WARNING
        refreshing -> HcTone.ACCENT
        else -> HcTone.NEUTRAL
    }
    val t = hcTone(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HcDims.tileRadius))
            .background(if (tone == HcTone.NEUTRAL) colors.backgroundSurfaceSubtle else t.soft)
            .padding(start = FlashSpacing.space12, end = FlashSpacing.space4, top = FlashSpacing.space4, bottom = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        HcPulseDot(tone, pulsing = refreshing, diameter = 8.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(
                if (refreshing) "Syncing the directory…" else sentence,
                style = FlashTheme.typography.metadataEmphasis,
                color = if (tone == HcTone.NEUTRAL) colors.textPrimary else t.on,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(versionLine, style = hcMono(FlashTheme.typography.numericDefault), color = colors.textTertiary, maxLines = 1)
        }
        HcIconAction(HcGlyph.RETRY, "Refresh directory", onRefresh, enabled = !refreshing, tone = tone)
    }
}

/** A signal-strength wedge used by the directory's reachability readout. Decorative; the label carries the meaning. */
@Composable
internal fun HcSignalBars(strength: Int, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.height(16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(4) { index ->
            val on = index < strength
            Box(
                Modifier
                    .width(3.dp)
                    .height((5 + index * 3).dp)
                    .clip(RoundedCornerShape(FlashShapes.radius2))
                    .background(if (on) tint else tint.copy(alpha = 0.22f)),
            )
        }
    }
}

/** Section band with a label and an optional trailing count, used to group a page into readable chunks. */
@Composable
internal fun HcBand(title: String, trailing: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
        HcSectionLabel(title, trailing = trailing)
        content()
    }
}

/** Small "verified / failed" tick row used across the trust screens. */
@Composable
internal fun HcVerdictRow(label: String, ok: Boolean, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = FlashTheme.colors
    val tone = if (ok) HcTone.SUCCESS else HcTone.DANGER
    val t = hcTone(tone)
    Row(modifier.fillMaxWidth().padding(vertical = FlashSpacing.space4), verticalAlignment = Alignment.CenterVertically) {
        HcGlyphIcon(if (ok) HcGlyph.CHECK else HcGlyph.CLOSE, tint = t.on, size = 14.dp)
        Spacer(Modifier.width(FlashSpacing.space8))
        Column(Modifier.weight(1f)) {
            FlashText(label, style = FlashTheme.typography.metadataDefault, color = colors.textPrimary, maxLines = 2)
            if (detail != null) {
                FlashText(detail, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FlashText(
            if (ok) "Verified" else "Failed",
            style = FlashTheme.typography.metadataEmphasis,
            color = t.on,
            maxLines = 1,
        )
    }
}

/** A horizontal trust-chain node: credential → issuer → root, with a connector between. */
@Composable
internal fun HcChainNode(
    eyebrow: String,
    title: String,
    fingerprint: String,
    glyph: HcGlyph,
    tone: HcTone,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val t = hcTone(tone)
    Column(
        modifier
            .width(172.dp)
            .hcCardSurface(tone, RoundedCornerShape(HcDims.tileRadius))
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            HcGlyphIcon(glyph, tint = t.on, size = 14.dp)
            FlashText(eyebrow.uppercase(), style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1)
        }
        FlashText(title, style = FlashTheme.typography.captionEmphasis, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlashText(hcFingerprint(fingerprint, 2), style = hcMono(FlashTheme.typography.numericDefault), color = colors.textSecondary, maxLines = 1)
    }
}

/** The connector drawn between two [HcChainNode]s. */
@Composable
internal fun HcChainLink(tone: HcTone, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Box(modifier.width(28.dp).height(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(FlashDimensions.borderHairline * 2).background(hcTone(tone).edge))
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(colors.backgroundSurface)
                .border(FlashDimensions.borderHairline, hcTone(tone).edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) { HcGlyphIcon(HcGlyph.CHECK, tint = hcTone(tone).on, size = 10.dp) }
    }
}
