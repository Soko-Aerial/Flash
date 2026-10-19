package com.transfer.flash.ui.hub.competition.apex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashElevation
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashMotion

/**
 * APEX ZENITH — Interactive Competition Showcase Screen.
 *
 * Wraps all 5 pages with responsive mobile & desktop frame layouts,
 * seamless page switching, and the floating Judge's Control Bar.
 */

@Composable
public fun HubCompetitionShowcaseScreen(modifier: Modifier = Modifier) {
    ApexHubCompetitionShowcaseScreen(modifier)
}

@Composable
public fun ApexHubCompetitionShowcaseScreen(modifier: Modifier = Modifier) {
    var scenario by remember { mutableStateOf(ApexScenario.ACTIVE_SEALED) }
    var currentPage by remember { mutableStateOf(ApexPage.COMMAND) }
    var formFactor by remember { mutableStateOf(ApexFormFactor.MOBILE_COMPACT) }
    var themeMode by remember { mutableStateOf(ApexTheme.DARK) }
    var motionMode by remember { mutableStateOf(ApexMotionMode.HIGH_MOTION) }
    var isJudgeBarExpanded by remember { mutableStateOf(true) }

    val deck = remember(scenario) { ApexMockWorld.createDeck(scenario) }
    val motion = rememberFlashMotion(reduceMotion = motionMode == ApexMotionMode.REDUCED_MOTION)

    FlashTheme(
        darkTheme = themeMode == ApexTheme.DARK,
        motion = motion,
    ) {
        val colors = FlashTheme.colors

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.backgroundApp),
            contentAlignment = Alignment.Center,
        ) {
            // Main Stage Frame (Adaptive Mobile or Desktop)
            if (formFactor == ApexFormFactor.MOBILE_COMPACT) {
                ApexMobileFrame(
                    deck = deck,
                    currentPage = currentPage,
                    onSelectPage = { currentPage = it },
                )
            } else {
                ApexDesktopFrame(
                    deck = deck,
                    currentPage = currentPage,
                    onSelectPage = { currentPage = it },
                )
            }

            // Floating Judge's Control Bar Dock
            ApexJudgeControlBar(
                scenario = scenario,
                onScenarioChange = { scenario = it },
                formFactor = formFactor,
                onFormFactorChange = { formFactor = it },
                themeMode = themeMode,
                onThemeModeChange = { themeMode = it },
                motionMode = motionMode,
                onMotionModeChange = { motionMode = it },
                isExpanded = isJudgeBarExpanded,
                onToggleExpand = { isJudgeBarExpanded = !isJudgeBarExpanded },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = FlashSpacing.space16),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Mobile Frame Layout (412dp Compact)
// -------------------------------------------------------------------------------------------------

@Composable
private fun ApexMobileFrame(
    deck: ApexHubDeck,
    currentPage: ApexPage,
    onSelectPage: (ApexPage) -> Unit,
) {
    val colors = FlashTheme.colors

    Box(
        modifier = Modifier
            .width(ApexDims.compactFrameWidth)
            .fillMaxHeight()
            .padding(vertical = FlashSpacing.space8)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.backgroundSurface)
            .border(2.dp, colors.borderDefault, RoundedCornerShape(24.dp)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Mobile Top Bar
            ApexTopBar(
                title = currentPage.label,
                subtitle = deck.org.name,
                isSealed = deck.sealing.isSealed,
            )

            // Scrollable Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                ApexPageContent(
                    page = currentPage,
                    deck = deck,
                    onNavigatePage = onSelectPage,
                )
            }

            // Bottom Navigation Bar
            ApexBottomNav(
                currentPage = currentPage,
                onSelectPage = onSelectPage,
                unreadAnnouncements = deck.announcements.count { !it.isRead },
            )

            // Space reservation for Judge Bar
            Spacer(Modifier.height(72.dp))
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Desktop Multi-Pane Frame Layout (1180dp Expanded)
// -------------------------------------------------------------------------------------------------

@Composable
private fun ApexDesktopFrame(
    deck: ApexHubDeck,
    currentPage: ApexPage,
    onSelectPage: (ApexPage) -> Unit,
) {
    val colors = FlashTheme.colors

    Box(
        modifier = Modifier
            .width(ApexDims.desktopFrameWidth)
            .fillMaxHeight()
            .padding(vertical = FlashSpacing.space12)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.backgroundSurface)
            .border(2.dp, colors.borderDefault, RoundedCornerShape(20.dp)),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Navigation Rail
            Column(
                modifier = Modifier
                    .width(ApexDims.railWidth)
                    .fillMaxHeight()
                    .background(colors.backgroundSurfaceStrong.copy(alpha = 0.35f))
                    .border(FlashDimensions.borderHairline, colors.borderSubtle)
                    .padding(FlashSpacing.space16),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                // Brand Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.accentPrimary),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Hub,
                            tint = colors.textOnAccent,
                            size = FlashDimensions.iconSm,
                        )
                    }
                    Column {
                        FlashText(
                            text = "Flash Hub",
                            style = FlashTheme.typography.headingSmall,
                            color = colors.textPrimary,
                        )
                        FlashText(
                            text = "Apex Zenith Edition",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textTertiary,
                        )
                    }
                }

                Spacer(Modifier.height(FlashSpacing.space12))
                ApexDivider()
                Spacer(Modifier.height(FlashSpacing.space8))

                // Navigation Tabs
                ApexPage.values().forEach { page ->
                    val isSelected = page == currentPage
                    val interactionSource = remember { MutableInteractionSource() }

                    val bg = if (isSelected) colors.accentPrimary.copy(alpha = 0.15f) else colors.backgroundSurfaceStrong.copy(alpha = 0.3f)
                    val fg = if (isSelected) colors.accentPrimary else colors.textSecondary

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(ApexDims.tileRadius))
                            .background(bg)
                            .border(
                                FlashDimensions.borderHairline,
                                if (isSelected) colors.accentPrimary else colors.borderSubtle,
                                RoundedCornerShape(ApexDims.tileRadius),
                            )
                            .flashPressScale(interactionSource)
                            .clickable(interactionSource = interactionSource, indication = null) {
                                onSelectPage(page)
                            }
                            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                        ) {
                            FlashIcon(
                                icon = when (page) {
                                    ApexPage.COMMAND -> FlashIcons.Hub
                                    ApexPage.DIRECTORY -> FlashIcons.Device
                                    ApexPage.ANNOUNCEMENTS -> FlashIcons.Announcement
                                    ApexPage.VAULT -> FlashIcons.Encryption
                                    ApexPage.ROSTERS -> FlashIcons.Group
                                },
                                tint = fg,
                                size = FlashDimensions.iconSm,
                            )
                            FlashText(
                                text = page.label,
                                style = if (isSelected) FlashTheme.typography.bodyEmphasis else FlashTheme.typography.bodyDefault,
                                color = fg,
                            )
                        }

                        if (page == ApexPage.ANNOUNCEMENTS) {
                            val unread = deck.announcements.count { !it.isRead }
                            if (unread > 0) {
                                ApexBadge(text = "$unread", tone = ApexTone.ONLINE)
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Organisation & Sealing Status Footer
                ApexCard(
                    tone = if (deck.sealing.isSealed) ApexTone.SEALED else ApexTone.WARNING,
                    contentPadding = FlashSpacing.space12,
                ) {
                    FlashText(
                        text = deck.org.name,
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(FlashSpacing.space2))
                    FlashText(
                        text = if (deck.sealing.isSealed) "E2E Sealed (BLAKE3)" else "Unsealed",
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                    )
                }

                Spacer(Modifier.height(72.dp)) // Space for Judge Bar
            }

            // Right Main Content Pane
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                ApexTopBar(
                    title = currentPage.label,
                    subtitle = currentPage.subtitle,
                    isSealed = deck.sealing.isSealed,
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    ApexPageContent(
                        page = currentPage,
                        deck = deck,
                        onNavigatePage = onSelectPage,
                    )
                }

                Spacer(Modifier.height(72.dp)) // Space for Judge Bar
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Common Content & Navigation Components
// -------------------------------------------------------------------------------------------------

@Composable
private fun ApexPageContent(
    page: ApexPage,
    deck: ApexHubDeck,
    onNavigatePage: (ApexPage) -> Unit,
) {
    when (page) {
        ApexPage.COMMAND -> ApexPageCommandCenter(
            deck = deck,
            onNavigatePage = onNavigatePage,
        )
        ApexPage.DIRECTORY -> ApexPageDirectory(
            deck = deck,
        )
        ApexPage.ANNOUNCEMENTS -> ApexPageAnnouncements(
            deck = deck,
        )
        ApexPage.VAULT -> ApexPageTrustVault(
            deck = deck,
        )
        ApexPage.ROSTERS -> ApexPageRostersBridge(
            deck = deck,
        )
    }
}

@Composable
private fun ApexTopBar(
    title: String,
    subtitle: String,
    isSealed: Boolean,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle)
            .padding(horizontal = FlashSpacing.space16),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            FlashText(
                text = title,
                style = FlashTheme.typography.headingSmall,
                color = colors.textPrimary,
            )
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        ) {
            if (isSealed) {
                ApexBadge(
                    text = "SEALED",
                    tone = ApexTone.SEALED,
                    icon = FlashIcons.Encryption,
                )
            }
        }
    }
}

@Composable
private fun ApexBottomNav(
    currentPage: ApexPage,
    onSelectPage: (ApexPage) -> Unit,
    unreadAnnouncements: Int,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle)
            .padding(horizontal = FlashSpacing.space8),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ApexPage.values().forEach { page ->
            val isSelected = page == currentPage
            val interactionSource = remember { MutableInteractionSource() }

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(ApexDims.tileRadius))
                    .flashPressScale(interactionSource)
                    .clickable(interactionSource = interactionSource, indication = null) {
                        onSelectPage(page)
                    }
                    .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
            ) {
                Box {
                    FlashIcon(
                        icon = when (page) {
                            ApexPage.COMMAND -> FlashIcons.Hub
                            ApexPage.DIRECTORY -> FlashIcons.Device
                            ApexPage.ANNOUNCEMENTS -> FlashIcons.Announcement
                            ApexPage.VAULT -> FlashIcons.Encryption
                            ApexPage.ROSTERS -> FlashIcons.Group
                        },
                        tint = if (isSelected) colors.accentPrimary else colors.textTertiary,
                        size = FlashDimensions.iconSm,
                    )
                    if (page == ApexPage.ANNOUNCEMENTS && unreadAnnouncements > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.accentPrimary),
                        )
                    }
                }
                FlashText(
                    text = page.shortName,
                    style = if (isSelected) FlashTheme.typography.metadataEmphasis else FlashTheme.typography.metadataDefault,
                    color = if (isSelected) colors.accentPrimary else colors.textTertiary,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Floating Judge's Control Bar
// -------------------------------------------------------------------------------------------------

@Composable
private fun ApexJudgeControlBar(
    scenario: ApexScenario,
    onScenarioChange: (ApexScenario) -> Unit,
    formFactor: ApexFormFactor,
    onFormFactorChange: (ApexFormFactor) -> Unit,
    themeMode: ApexTheme,
    onThemeModeChange: (ApexTheme) -> Unit,
    motionMode: ApexMotionMode,
    onMotionModeChange: (ApexMotionMode) -> Unit,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ApexDims.pillRadius))
            .background(colors.sheetSurface.copy(alpha = 0.95f))
            .border(FlashDimensions.borderHairline, colors.accentPrimary.copy(alpha = 0.5f), RoundedCornerShape(ApexDims.pillRadius))
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
    ) {
        if (!isExpanded) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                ApexBadge(
                    text = "JUDGE'S BAR: ${scenario.label}",
                    tone = ApexTone.SPECIAL,
                    icon = FlashIcons.Sliders,
                )
                ApexSecondaryButton(
                    text = "Expand Controls",
                    onClick = onToggleExpand,
                )
            }
        } else {
            Column(
                modifier = Modifier.widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                    ) {
                        FlashIcon(icon = FlashIcons.Sliders, tint = colors.accentPrimary, size = FlashDimensions.iconSm)
                        FlashText(
                            text = "JUDGE'S CONTROL BAR",
                            style = FlashTheme.typography.metadataEmphasis,
                            color = colors.accentPrimary,
                        )
                    }

                    ApexSecondaryButton(
                        text = "Collapse",
                        onClick = onToggleExpand,
                    )
                }

                // 1. Hub Scenario Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                ) {
                    ApexScenario.values().forEach { scn ->
                        ApexFilterChip(
                            label = scn.label,
                            selected = scn == scenario,
                            onClick = { onScenarioChange(scn) },
                        )
                    }
                }

                // 2. Form Factor, Theme & Motion Toggles
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Form Factor
                    ApexFilterChip(
                        label = if (formFactor == ApexFormFactor.MOBILE_COMPACT) "📱 Phone" else "💻 Desktop Split",
                        selected = true,
                        onClick = {
                            onFormFactorChange(
                                if (formFactor == ApexFormFactor.MOBILE_COMPACT) ApexFormFactor.DESKTOP_SPLIT
                                else ApexFormFactor.MOBILE_COMPACT,
                            )
                        },
                    )

                    // Theme
                    ApexFilterChip(
                        label = if (themeMode == ApexTheme.DARK) "🌙 Dark" else "☀️ Light",
                        selected = true,
                        onClick = {
                            onThemeModeChange(
                                if (themeMode == ApexTheme.DARK) ApexTheme.LIGHT else ApexTheme.DARK,
                            )
                        },
                    )

                    // Motion
                    ApexFilterChip(
                        label = if (motionMode == ApexMotionMode.HIGH_MOTION) "⚡ 60fps Spring" else "🐢 Reduced",
                        selected = true,
                        onClick = {
                            onMotionModeChange(
                                if (motionMode == ApexMotionMode.HIGH_MOTION) ApexMotionMode.REDUCED_MOTION
                                else ApexMotionMode.HIGH_MOTION,
                            )
                        },
                    )
                }
            }
        }
    }
}
