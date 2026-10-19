package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). The judge showcase.
 *
 * [HubCompetitionShowcaseScreen] is the whole deliverable in one screen: the five designed pages,
 * the mock world behind them, and the floating Judge's Control Bar that switches
 * - the six hub states (unenrolled, enrolling, active & sealed, relay degraded, cert revoked, empty/offline),
 * - the form factor (phone column vs desktop split-pane at expanded width),
 * - dark / light, and full / reduced motion — applied through a nested [FlashTheme] override,
 *   exactly the way a host would wrap the tab.
 *
 * Design-only: nothing here touches a repository, an engine or a platform service. Demo actions
 * surface an honest "demo" notice instead of pretending work happened.
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.adaptive.FlashWindowSizeClass
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashElevation
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashMotion
import kotlinx.coroutines.delay

/** Which Flash icon stands for a page in navigation. All verified members of FlashIcons. */
private fun novaPageIcon(page: NovaPage): FlashIconSpec = when (page) {
    NovaPage.Command -> FlashIcons.Bolt
    NovaPage.Directory -> FlashIcons.Group
    NovaPage.Broadcast -> FlashIcons.Announcement
    NovaPage.Vault -> FlashIcons.Encryption
    NovaPage.Bridge -> FlashIcons.Relay
}

/**
 * The NOVA competition showcase: five Hub pages over a six-state mock world with the judge's
 * control bar floating at the bottom. All state lives here so every page stays stateless.
 */
@Composable
public fun HubCompetitionShowcaseScreen(
    modifier: Modifier = Modifier,
    initialScenario: NovaScenario = NovaScenario.ActiveSealed,
    initialForm: NovaForm = NovaForm.MobileCompact,
    initialDark: Boolean = false,
    initialReduceMotion: Boolean = false,
) {
    var scenario by rememberSaveable { mutableStateOf(initialScenario) }
    var form by rememberSaveable { mutableStateOf(initialForm) }
    var dark by rememberSaveable { mutableStateOf(initialDark) }
    var reduceMotion by rememberSaveable { mutableStateOf(initialReduceMotion) }
    var page by rememberSaveable { mutableStateOf(NovaPage.Command) }
    var demoNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(demoNotice) {
        if (demoNotice != null) {
            delay(3200)
            demoNotice = null
        }
    }

    // The judge bar owns the presentation theme; a nested FlashTheme override is exactly how a
    // host wraps the tab, so dark/light and reduced-motion behave identically here and in prod.
    FlashTheme(
        darkTheme = dark,
        motion = rememberFlashMotion(reduceMotion = reduceMotion),
    ) {
        val colors = FlashTheme.colors
        BoxWithConstraints(modifier.fillMaxSize().background(colors.backgroundApp)) {
            val world = NovaMockWorld.worldFor(scenario)
            val widthClass = FlashAdaptiveMath.windowSizeForWidth(maxWidth.value)
            val effectiveForm =
                if (form == NovaForm.DesktopSplit && widthClass != FlashWindowSizeClass.Expanded) {
                    NovaForm.MobileCompact // a split pane needs the expanded width; degrade honestly
                } else {
                    form
                }

            when (effectiveForm) {
                NovaForm.DesktopSplit -> {
                    Row(Modifier.fillMaxSize()) {
                        NovaRail(
                            world = world,
                            selected = page,
                            onSelect = { page = it },
                            modifier = Modifier.width(320.dp).fillMaxHeight(),
                        )
                        Box(
                            Modifier
                                .width(FlashDimensions.borderHairline)
                                .fillMaxHeight()
                                .background(colors.borderSubtle),
                        )
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = NovaContentMaxWidth).fillMaxSize()) {
                                    NovaPageContent(world, page, onDemo = { demoNotice = it })
                                }
                            }
                            NovaDemoNotice(demoNotice, Modifier.padding(horizontal = FlashSpacing.space16))
                            NovaJudgeBar(
                                scenario = scenario, onScenario = { scenario = it },
                                form = form, onForm = { form = it },
                                dark = dark, onDark = { dark = it },
                                reduceMotion = reduceMotion, onReduce = { reduceMotion = it },
                                modifier = Modifier.padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                            )
                        }
                    }
                }

                NovaForm.MobileCompact -> {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
                                NovaPageContent(world, page, onDemo = { demoNotice = it })
                            }
                        }
                        NovaDemoNotice(demoNotice, Modifier.padding(horizontal = FlashSpacing.space16))
                        NovaJudgeBar(
                            scenario = scenario, onScenario = { scenario = it },
                            form = form, onForm = { form = it },
                            dark = dark, onDark = { dark = it },
                            reduceMotion = reduceMotion, onReduce = { reduceMotion = it },
                            modifier = Modifier.padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
                        )
                        NovaTabBar(selected = page, onSelect = { page = it })
                    }
                }
            }
        }
    }
}

/** Page dispatch. The showcase is the only caller; each page is otherwise independent. */
@Composable
private fun NovaPageContent(
    world: NovaWorld,
    page: NovaPage,
    onDemo: (String) -> Unit,
) {
    when (page) {
        NovaPage.Command -> NovaCommandPage(world, onDemo)
        NovaPage.Directory -> NovaDirectoryPage(world, onDemo)
        NovaPage.Broadcast -> NovaNewsPage(world, onDemo)
        NovaPage.Vault -> NovaVaultPage(world, onDemo)
        NovaPage.Bridge -> NovaBridgePage(world, onDemo)
    }
}

// --- Phone navigation ----------------------------------------------------------------------------------

/** The compact-width tab bar: five pages, Flash icons, selected wash — no Material chrome. */
@Composable
private fun NovaTabBar(
    selected: NovaPage,
    onSelect: (NovaPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val wash = NovaTone.INFO.wash()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .clip(NovaPillShape)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, NovaPillShape)
            .padding(horizontal = FlashSpacing.space4, vertical = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovaPage.entries.forEach { page ->
            val isSelected = page == selected
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(NovaPillShape)
                    .background(if (isSelected) wash.fill else Color.Transparent)
                    .novaClick(onClick = { onSelect(page) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
            ) {
                FlashIcon(
                    novaPageIcon(page),
                    size = 20.dp,
                    tint = if (isSelected) wash.strong else colors.textSecondary,
                )
                FlashText(
                    text = page.label,
                    style = FlashTheme.typography.metadataEmphasis,
                    color = if (isSelected) wash.strong else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

// --- Desktop rail --------------------------------------------------------------------------------------

/** The expanded-width rail: org header, live strip, the five pages, and the honest entry note. */
@Composable
private fun NovaRail(
    world: NovaWorld,
    selected: NovaPage,
    onSelect: (NovaPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val wash = NovaTone.INFO.wash()
    Column(
        modifier = modifier
            .background(colors.backgroundSurface)
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            NovaMonogramTile(
                initials = world.org.name.split(' ').take(2).mapNotNull { it.firstOrNull() }.joinToString(""),
                seed = world.org.name,
                size = 40.dp,
                shape = NovaTileShape,
            )
            Column(Modifier.weight(1f)) {
                FlashText(
                    text = world.org.name,
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                FlashText(
                    text = world.org.roleLine,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        NovaStatusStrip(strip = world.strip)
        NovaPage.entries.forEach { page ->
            val isSelected = page == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(NovaTileShape)
                    .background(if (isSelected) wash.fill else Color.Transparent)
                    .novaClick(onClick = { onSelect(page) })
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashIcon(
                    novaPageIcon(page),
                    size = 18.dp,
                    tint = if (isSelected) wash.strong else colors.textSecondary,
                )
                Column(Modifier.weight(1f)) {
                    FlashText(
                        text = page.label,
                        style = FlashTheme.typography.captionEmphasis,
                        color = if (isSelected) wash.strong else colors.textPrimary,
                    )
                    FlashText(
                        text = page.caption,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textTertiary,
                        maxLines = 1,
                    )
                }
                if (isSelected) NovaDot(tone = NovaTone.INFO)
            }
        }
        Spacer(Modifier.weight(1f))
        FlashText(
            text = "NOVA · design-only competition entry — nothing here touches production data",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

// --- Judge's Control Bar -------------------------------------------------------------------------------

/**
 * The floating judge bar: the six hub states as chips, then form factor, theme and motion toggles.
 * It floats on the sheet surface with Flash's floating elevation where the budget allows a shadow.
 */
@Composable
private fun NovaJudgeBar(
    scenario: NovaScenario,
    onScenario: (NovaScenario) -> Unit,
    form: NovaForm,
    onForm: (NovaForm) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    reduceMotion: Boolean,
    onReduce: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val decor = novaDecorAllowed()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (decor) Modifier.shadow(elevation = FlashElevation.floating, shape = NovaSheetShape) else Modifier)
            .clip(NovaSheetShape)
            .background(colors.sheetSurface)
            .border(FlashDimensions.borderHairline, colors.borderStrong, NovaSheetShape)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            FlashIcon(FlashIcons.Hub, size = 16.dp, tint = colors.accentPrimary)
            FlashText(
                text = "Judge's control bar",
                style = FlashTheme.typography.metadataEmphasis,
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            FlashText(
                text = "Hub state:",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovaScenario.entries.forEach { candidate ->
                NovaFilterChip(
                    label = candidate.label,
                    selected = candidate == scenario,
                    onClick = { onScenario(candidate) },
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovaFilterChip(label = "Phone", selected = form == NovaForm.MobileCompact, onClick = { onForm(NovaForm.MobileCompact) })
            NovaFilterChip(label = "Desktop split", selected = form == NovaForm.DesktopSplit, onClick = { onForm(NovaForm.DesktopSplit) })
            Box(
                Modifier
                    .width(FlashDimensions.borderHairline)
                    .heightIn(min = 24.dp)
                    .background(colors.borderSubtle),
            )
            NovaFilterChip(label = "Dark", selected = dark, onClick = { onDark(true) })
            NovaFilterChip(label = "Light", selected = !dark, onClick = { onDark(false) })
            Box(
                Modifier
                    .width(FlashDimensions.borderHairline)
                    .heightIn(min = 24.dp)
                    .background(colors.borderSubtle),
            )
            NovaFilterChip(label = "Full motion", selected = !reduceMotion, onClick = { onReduce(false) })
            NovaFilterChip(label = "Reduced", selected = reduceMotion, onClick = { onReduce(true) })
        }
    }
}

/** The honest demo action notice: says what a press *would* do, changes nothing in the world. */
@Composable
private fun NovaDemoNotice(text: String?, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(FlashTheme.motion.tweenFastSpec()),
        exit = fadeOut(FlashTheme.motion.tweenFastSpec()),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(NovaPillShape)
                .background(colors.sheetSurface)
                .border(FlashDimensions.borderHairline, colors.borderStrong, NovaPillShape)
                .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            FlashIcon(FlashIcons.Check, size = 16.dp, tint = colors.textSecondary)
            FlashText(
                text = text ?: "",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 2,
            )
        }
    }
}
