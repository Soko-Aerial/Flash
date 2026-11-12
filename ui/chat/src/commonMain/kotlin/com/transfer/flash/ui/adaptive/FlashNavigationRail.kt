package com.transfer.flash.ui.adaptive

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BasicTooltipBox
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberBasicTooltipState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Adaptive navigation rail model for large displays (Desktop, tablets, foldables).
 */
@Immutable
data class FlashNavigationRailTab(
    val destination: FlashDestination,
    val icon: FlashIconSpec,
    val label: String,
    val badgeCount: Int = 0,
)

/** Pure helpers for [FlashNavigationRail] semantics, sizing, and labels. */
internal object FlashNavigationRailMath {
    const val RAIL_WIDTH_DP = FlashAdaptiveMath.NavigationRailWidthDp
    const val PROFILE_HIT_TARGET_DP = 48f
    const val PROFILE_CONTENT_DESCRIPTION = "Settings / Device profile"
    const val HOME_CONTENT_DESCRIPTION = "Flash Home"

    fun badgeText(badgeCount: Int): String = if (badgeCount > 99) "99+" else badgeCount.toString()

    fun tabContentDescription(label: String, badgeCount: Int): String =
        if (badgeCount > 0) "$label tab, $badgeCount unread" else "$label tab"

    fun profileTooltipText(displayName: String?): String =
        displayName?.trim()?.takeIf { it.isNotBlank() }
            ?.let { "$it · $PROFILE_CONTENT_DESCRIPTION" }
            ?: PROFILE_CONTENT_DESCRIPTION
}

/**
 * Sleek, icon-first vertical navigation rail for large screens (WhatsApp & Telegram Desktop style).
 *
 * Placed on the far left edge of the application when screen width >= 600dp (or in two-pane mode >= 840dp).
 * Features:
 * - 68dp compact width ensuring maximum space for chat list and message contents.
 * - Flash lightning bolt app medallion at top.
 * - Concise single-line tab labels and hover/long-press tooltips.
 * - Sleek active pill indicator and soft highlight container with tab-group semantics.
 * - Dynamic unread badges on the Chats tab and Hub tab.
 * - Accessible >= 48dp user/device avatar shortcut at bottom.
 */
@Composable
fun FlashNavigationRail(
    tabs: List<FlashNavigationRailTab>,
    selectedTab: FlashDestination,
    onTabSelected: (FlashDestination) -> Unit,
    modifier: Modifier = Modifier,
    localDisplayName: String? = null,
    onProfileClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = FlashTheme.colors

    Row(
        modifier = modifier
            .width(FlashNavigationRailMath.RAIL_WIDTH_DP.dp)
            .fillMaxHeight(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(colors.backgroundSurface)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(vertical = FlashSpacing.space12),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // App Branding Medallion (Option B: Upgraded prominent Flash Pulse medallion)
            FlashRailTooltip(label = FlashNavigationRailMath.HOME_CONTENT_DESCRIPTION) {
                Box(
                    modifier = Modifier
                        .size(FlashDimensions.minTouchTarget)
                        .clip(RoundedCornerShape(FlashShapes.radius12))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            role = Role.Button,
                            onClick = { onTabSelected(FlashDestination.ChatList) },
                        )
                        .semantics {
                            role = Role.Button
                            contentDescription = FlashNavigationRailMath.HOME_CONTENT_DESCRIPTION
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(FlashShapes.radius12))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        colors.accentPrimary.copy(alpha = 0.22f),
                                        colors.accentPrimary.copy(alpha = 0.08f),
                                    ),
                                ),
                            )
                            .border(
                                width = 1.dp,
                                color = colors.accentPrimary.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(FlashShapes.radius12),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Layered soft inner glow / backdrop
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colors.accentPrimary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            FlashIcon(
                                icon = FlashIcons.Bolt,
                                contentDescription = null,
                                tint = colors.accentPrimary,
                                size = 22.dp,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(FlashSpacing.space12))

            // Navigation Tab Items
            Column(
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            ) {
                tabs.forEach { tab ->
                    key(tab.destination) {
                        val isSelected = tab.destination == selectedTab
                        val iconTint by animateColorAsState(
                            targetValue = if (isSelected) colors.textLink else colors.textSecondary,
                            label = "railIconTint",
                        )
                        val containerBg by animateColorAsState(
                            targetValue = if (isSelected) colors.accentPrimary.copy(alpha = 0.14f) else Color.Transparent,
                            label = "railContainerBg",
                        )
                        val labelColorState = animateColorAsState(
                            targetValue = if (isSelected) colors.textLink else colors.textSecondary,
                            label = "railLabelColor",
                        )
                        val labelColorProducer = remember(labelColorState) {
                            ColorProducer { labelColorState.value }
                        }

                        FlashRailTooltip(label = tab.label) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.Tab,
                                        onClick = { onTabSelected(tab.destination) },
                                    )
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = FlashNavigationRailMath.tabContentDescription(
                                            label = tab.label,
                                            badgeCount = tab.badgeCount,
                                        )
                                    }
                                    .padding(vertical = FlashSpacing.space2),
                                contentAlignment = Alignment.Center,
                            ) {
                                // Left-edge active indicator bar
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .width(3.dp)
                                            .height(24.dp)
                                            .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                                            .background(colors.accentFill),
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
                                ) {
                                    // Icon button container
                                    Box(
                                        modifier = Modifier
                                            .width(44.dp)
                                            .height(32.dp)
                                            .clip(RoundedCornerShape(FlashShapes.radius12))
                                            .background(containerBg),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        FlashIcon(
                                            icon = tab.icon,
                                            contentDescription = null,
                                            tint = iconTint,
                                            size = 20.dp,
                                            modifier = Modifier.clearAndSetSemantics {},
                                        )

                                        // Unread Badge Pill
                                        if (tab.badgeCount > 0) {
                                            val badgeText = FlashNavigationRailMath.badgeText(tab.badgeCount)
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 4.dp, y = (-3).dp)
                                                    .clip(CircleShape)
                                                    .background(colors.accentFill)
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                                    .clearAndSetSemantics {},
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                FlashText(
                                                    text = badgeText,
                                                    color = colors.onAccentFill,
                                                    style = FlashTheme.typography.captionEmphasis.copy(
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                    ),
                                                )
                                            }
                                        }
                                    }

                                    BasicText(
                                        text = tab.label,
                                        modifier = Modifier
                                            .padding(horizontal = FlashSpacing.space4)
                                            .clearAndSetSemantics {},
                                        style = FlashTheme.typography.metadataDefault.copy(
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                            textAlign = TextAlign.Center,
                                        ),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = labelColorProducer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FlashSpacing.space12),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Optional host control above the profile avatar (desktop: the push-to-talk button). Null on
                    // Android, so its rail is unchanged.
                    if (footer != null) {
                        footer()
                        Spacer(Modifier.height(FlashSpacing.space12))
                    }

                    // Bottom Profile / Device Avatar (>= 48dp accessible hit target)
                    val initials = localDisplayName?.trim()?.take(1)?.uppercase() ?: ""
                    FlashRailTooltip(label = FlashNavigationRailMath.profileTooltipText(localDisplayName)) {
                        Box(
                            modifier = Modifier
                                .size(FlashDimensions.minTouchTarget)
                                .clip(CircleShape)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    role = Role.Button,
                                    onClick = { onProfileClick?.invoke() ?: onTabSelected(FlashDestination.Settings) },
                                )
                                .semantics(mergeDescendants = true) {
                                    role = Role.Button
                                    contentDescription = FlashNavigationRailMath.PROFILE_CONTENT_DESCRIPTION
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(colors.backgroundSurfaceStrong),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (initials.isNotBlank()) {
                                    FlashText(
                                        text = initials,
                                        style = FlashTheme.typography.captionEmphasis.copy(fontWeight = FontWeight.Bold),
                                        color = colors.textPrimary,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                } else {
                                    FlashIcon(
                                        icon = FlashIcons.Device,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        size = 18.dp,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Right hairline divider separating rail from content
        Box(
            modifier = Modifier
                .width(FlashDimensions.borderHairline)
                .fillMaxHeight()
                .background(colors.borderSubtle),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlashRailTooltip(
    label: String,
    content: @Composable () -> Unit,
) {
    val colors = FlashTheme.colors
    val density = LocalDensity.current
    val gapPx = with(density) { FlashSpacing.space8.roundToPx() }
    val positionProvider = remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val rawX = if (layoutDirection == LayoutDirection.Ltr) {
                    anchorBounds.right + gapPx
                } else {
                    anchorBounds.left - popupContentSize.width - gapPx
                }
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                val x = rawX.coerceIn(0, maxX)
                val y = (anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2)
                    .coerceIn(0, maxY)
                return IntOffset(x, y)
            }
        }
    }
    val tooltipState = rememberBasicTooltipState()
    BasicTooltipBox(
        positionProvider = positionProvider,
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(FlashShapes.chip)
                    .background(colors.backgroundSurfaceStrong)
                    .border(FlashDimensions.borderHairline, colors.borderDefault, FlashShapes.chip)
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
            ) {
                FlashText(
                    text = label,
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
        },
        state = tooltipState,
        focusable = false,
        content = content,
    )
}
