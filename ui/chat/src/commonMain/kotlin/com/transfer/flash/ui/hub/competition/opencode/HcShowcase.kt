package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashElevation
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashMotion

/**
 * **Flash Hub — Command Deck: the competition showcase harness.**
 *
 * Five pages, one deck, and a Judge's control bar that can flip every variable the brief names without a rebuild:
 * the hub state, the form factor, the theme and the motion budget. Nothing here is on the production path — the whole
 * package reads the shipped design system and the public `:core:messaging` read models, and paints its own surfaces.
 *
 * The stage is deliberately a *real* frame width rather than a scaled-down mock: a 412dp frame genuinely renders the
 * phone layout and an 1180dp frame genuinely crosses the project's own `FlashAdaptiveMath` breakpoint at 840dp, so the
 * two-pane desktop layout is the same page, not a second design.
 *
 * ```
 * HubCompetitionShowcaseScreen()   // the harness with the Judge's control bar
 * ```
 */
internal enum class HcPage(val label: String, val short: String, val glyph: HcGlyph) {
    COMMAND("Command Center", "Command", HcGlyph.HUB),
    DIRECTORY("Directory & Peers", "People", HcGlyph.PERSON),
    ANNOUNCEMENTS("Announcements", "News", HcGlyph.ANNOUNCEMENT),
    TRUST("Trust Vault", "Trust", HcGlyph.SHIELD),
    ROSTERS("Rosters & Bridge", "Rosters", HcGlyph.ROSTER),
}

/** The frame width a form factor gets. Passed to the pages so their own breakpoint math is the real one. */
internal fun hcFrameWidth(form: HcFormFactor): Dp =
    if (form == HcFormFactor.DESKTOP) HcDims.desktopFrameWidth else HcDims.compactFrameWidth

/**
 * The showcase. One entry point, five pages, an always-visible control bar for the judge.
 */
@Composable
fun HubCompetitionShowcaseScreen(modifier: Modifier = Modifier) {
    var scenario by remember { mutableStateOf(HcScenario.ACTIVE_SEALED) }
    var page by remember { mutableStateOf(HcPage.COMMAND) }
    var form by remember { mutableStateOf(HcFormFactor.COMPACT) }
    var theme by remember { mutableStateOf(HcTheme.DARK) }
    var motion by remember { mutableStateOf(HcMotionMode.HIGH) }
    var barVisible by remember { mutableStateOf(true) }

    val deck = remember(scenario) { hcDeckFor(scenario) }
    val flashMotion = rememberFlashMotion(reduceMotion = motion == HcMotionMode.REDUCED)

    FlashTheme(darkTheme = theme == HcTheme.DARK, motion = flashMotion) {
        val colors = FlashTheme.colors
        Box(modifier.fillMaxSize().background(colors.backgroundApp)) {
            Column(Modifier.fillMaxSize()) {
                HcShowcaseTopBar(page = page, deck = deck, onPage = { page = it })
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)) {
                    // The desktop frame is 1180dp wide. In a narrower window it would simply be clipped, so the stage
                    // pans: a judge can always reach the whole frame, whichever form factor they pick.
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        HcShowcaseFrame(form = form) {
                            HcShowcasePage(page, deck, hcFrameWidth(form).value, onOpenPage = { page = it })
                        }
                    }
                }
            }
            if (barVisible) {
                HcJudgeBar(
                    scenario = scenario,
                    onScenario = { scenario = it },
                    form = form,
                    onForm = { form = it },
                    theme = theme,
                    onTheme = { theme = it },
                    motion = motion,
                    onMotion = { motion = it },
                    page = page,
                    onPage = { page = it },
                    onHide = { barVisible = false },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(FlashSpacing.space12),
                )
            } else {
                HcJudgeToggle(
                    onShow = { barVisible = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(FlashSpacing.space16),
                )
            }
        }
    }
}

/** The frame: a real width, a real breakpoint, a device-shaped outline. */
@Composable
internal fun HcShowcaseFrame(
    form: HcFormFactor,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = FlashTheme.colors
    val shape = RoundedCornerShape(if (form == HcFormFactor.COMPACT) 28.dp else 18.dp)
    val lined = if (FlashTheme.minimalChrome) modifier else modifier.shadow(FlashElevation.sheet, shape)
    Box(
        lined
            .width(hcFrameWidth(form))
            .fillMaxHeight()
            .clip(shape)
            .border(BorderStroke(1.dp, colors.borderDefault), shape)
            .background(colors.backgroundApp),
    ) { content() }
}

@Composable
internal fun HcShowcasePage(page: HcPage, deck: HcDeck, widthDp: Float, onOpenPage: (HcPage) -> Unit = {}) {
    when (page) {
        HcPage.COMMAND -> HcPageCommandCenter(deck, widthDp, onOpenPage)
        HcPage.DIRECTORY -> HcPageDirectory(deck, widthDp)
        HcPage.ANNOUNCEMENTS -> HcPageAnnouncements(deck, widthDp)
        HcPage.TRUST -> HcPageTrust(deck, widthDp)
        HcPage.ROSTERS -> HcPageRosters(deck, widthDp)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HcShowcaseTopBar(page: HcPage, deck: HcDeck, onPage: (HcPage) -> Unit) {
    val colors = FlashTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(FlashShapes.radius8)).background(hcTone(HcTone.ACCENT).soft),
                contentAlignment = Alignment.Center,
            ) { HcGlyphIcon(HcGlyph.BOLT, tint = hcTone(HcTone.ACCENT).on) }
            FlashText(
                "Flash Hub — Command Deck",
                Modifier.weight(1f),
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HcChip("Design entry · opencode", tone = HcTone.NEUTRAL, compact = true)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HcPage.entries.forEachIndexed { index, entry ->
                HcChoiceChip(
                    label = "${entry.short}${hcTabBadge(deck, entry)}",
                    selected = entry == page,
                    glyph = entry.glyph,
                    onClick = { onPage(HcPage.entries[index]) },
                )
            }
        }
    }
}

private fun hcTabBadge(deck: HcDeck, page: HcPage): String = when (page) {
    HcPage.ANNOUNCEMENTS -> if (deck.unreadAnnouncements > 0) " ${deck.unreadAnnouncements}" else ""
    HcPage.ROSTERS -> if (deck.rosters.any { it.pendingRequests > 0 }) " !" else ""
    HcPage.TRUST -> if (hcCertificateTone(deck) == HcTone.DANGER) " !" else ""
    else -> ""
}

// ---------------------------------------------------------------------------------------------------------------
// Judge's control bar
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HcJudgeBar(
    scenario: HcScenario,
    onScenario: (HcScenario) -> Unit,
    form: HcFormFactor,
    onForm: (HcFormFactor) -> Unit,
    theme: HcTheme,
    onTheme: (HcTheme) -> Unit,
    motion: HcMotionMode,
    onMotion: (HcMotionMode) -> Unit,
    page: HcPage,
    onPage: (HcPage) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .widthIn(max = 940.dp)
            .then(if (FlashTheme.minimalChrome) Modifier else Modifier.shadow(FlashElevation.sheet, RoundedCornerShape(HcDims.cardRadius)))
            .hcCardSurface(HcTone.NEUTRAL, RoundedCornerShape(HcDims.cardRadius))
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            HcPulseDot(scenario.tone, pulsing = true, diameter = 10.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText("Judge's control bar", style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1)
                FlashText(
                    "${scenario.label} — ${scenario.blurb}",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HcIconAction(HcGlyph.CLOSE, "Hide the control bar", onHide)
        }
        HcJudgeRow("Hub state") {
            HcScenario.entries.forEach { entry ->
                HcChoiceChip(
                    label = entry.label,
                    selected = entry == scenario,
                    glyph = entry.icon,
                    onClick = { onScenario(entry) },
                )
            }
        }
        HcJudgeSegmented("Form factor", listOf("Mobile (412dp)", "Desktop split-pane (1180dp)"), form.ordinal) { onForm(HcFormFactor.entries[it]) }
        HcJudgeRow("Theme & motion") {
            HcChoiceChip("Dark", theme == HcTheme.DARK, { onTheme(HcTheme.DARK) })
            HcChoiceChip("Light", theme == HcTheme.LIGHT, { onTheme(HcTheme.LIGHT) })
            Box(Modifier.width(FlashSpacing.space8))
            HcChoiceChip("High motion", motion == HcMotionMode.HIGH, { onMotion(HcMotionMode.HIGH) })
            HcChoiceChip("Reduced motion", motion == HcMotionMode.REDUCED, { onMotion(HcMotionMode.REDUCED) })
        }
        HcJudgeSegmented("Page", HcPage.entries.map { it.short }, page.ordinal) { onPage(HcPage.entries[it]) }
    }
}

/** Label + a horizontally scrolling row of choices, so six hub states never squash on a phone. */
@Composable
private fun HcJudgeRow(label: String, content: @Composable () -> Unit) {
    val colors = FlashTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
        FlashText(label.uppercase(), style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun HcJudgeSegmented(label: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val colors = FlashTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
        FlashText(label.uppercase(), Modifier.width(84.dp), style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1)
        HcSegmented(options, selectedIndex, onSelect, Modifier.weight(1f))
    }
}

/** The way back when the judge hides the bar. */
@Composable
private fun HcJudgeToggle(onShow: () -> Unit, modifier: Modifier = Modifier) {
    HcButton("Judge", onShow, modifier = modifier, glyph = HcGlyph.SLIDERS)
}
