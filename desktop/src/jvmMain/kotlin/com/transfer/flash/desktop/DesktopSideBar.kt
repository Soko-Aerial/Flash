@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.transfer.flash.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.shell.FlashShellTabs
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashElevation
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * The vertical navigation rail shown at Medium and Expanded desktop widths (>= 600dp).
 * Compact 68dp icon rail with hover tooltips, unread badges, and accessible tab/profile targets.
 */
@Composable
public fun DesktopSideBar(
    tabs: List<DesktopSideTab>,
    selectedTab: FlashDestination,
    onTabSelected: (FlashDestination) -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    /** Unread announcements, shown on the Hub tab (UI-058). */
    hubBadge: Int = 0,
    localDisplayName: String? = null,
    onProfileClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val tooltipPlacement = TooltipPlacement.ComponentRect(
        anchor = Alignment.CenterEnd,
        alignment = Alignment.CenterEnd,
        offset = DpOffset(x = FlashSpacing.space8, y = 0.dp),
    )

    Row(
        modifier = modifier
            .width(DESKTOP_SIDE_RAIL_DP.dp)
            .fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(colors.backgroundSurface)
                .padding(vertical = FlashSpacing.space12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TooltipArea(
                tooltip = { SidebarTooltip("Flash Home") },
                tooltipPlacement = tooltipPlacement,
            ) {
                Box(
                    modifier = Modifier
                        .size(FlashDimensions.minTouchTarget)
                        .clip(RoundedCornerShape(FlashShapes.radius12))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Flash Home",
                        ) { onTabSelected(FlashDestination.ChatList) }
                        .semantics { contentDescription = "Flash Home" },
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
                    val isSelected = tab.destination == selectedTab
                    val badgeCount = when (tab.destination) {
                        FlashDestination.ChatList -> unreadCount
                        FlashDestination.Hub -> hubBadge
                        else -> 0
                    }
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) colors.accentFill else colors.textSecondary,
                        label = "sidebarIconTint",
                    )
                    val containerBg by animateColorAsState(
                        targetValue = if (isSelected) colors.accentPrimary.copy(alpha = 0.14f) else Color.Transparent,
                        label = "sidebarContainerBg",
                    )

                    TooltipArea(
                        tooltip = { SidebarTooltip(tab.label) },
                        tooltipPlacement = tooltipPlacement,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FlashDimensions.minTouchTarget)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .selectable(
                                    selected = isSelected,
                                    role = Role.Tab,
                                    onClick = { onTabSelected(tab.destination) },
                                )
                                .semantics {
                                    contentDescription = if (badgeCount > 0) {
                                        "${tab.label} tab, $badgeCount unread"
                                    } else {
                                        "${tab.label} tab"
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .width(3.dp)
                                        .height(22.dp)
                                        .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                                        .background(colors.accentFill),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(FlashShapes.radius12))
                                    .background(containerBg),
                                contentAlignment = Alignment.Center,
                            ) {
                                FlashIcon(
                                    icon = tab.icon,
                                    contentDescription = null,
                                    tint = iconTint,
                                    size = FlashDimensions.iconMd,
                                )

                                if (badgeCount > 0) {
                                    val badgeText = if (badgeCount > 99) "99+" else badgeCount.toString()
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 4.dp, y = (-4).dp)
                                            .clip(CircleShape)
                                            .background(colors.accentFill)
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
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
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (footer != null) {
                footer()
                Spacer(Modifier.height(FlashSpacing.space12))
            }

            // Bottom Profile / Device Avatar (>= 48.dp touch target)
            val initials = localDisplayName?.trim()?.take(1)?.uppercase().orEmpty()
            val profileLabel = desktopSidebarProfileDescription(localDisplayName)
            TooltipArea(
                tooltip = { SidebarTooltip(profileLabel) },
                tooltipPlacement = tooltipPlacement,
            ) {
                Box(
                    modifier = Modifier
                        .size(FlashDimensions.minTouchTarget)
                        .clip(CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = profileLabel,
                        ) {
                            onProfileClick?.invoke() ?: onTabSelected(FlashDestination.Settings)
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = profileLabel
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
                            )
                        } else {
                            FlashIcon(
                                icon = FlashIcons.Device,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                size = FlashDimensions.iconSm,
                            )
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

@Composable
private fun SidebarTooltip(text: String) {
    val colors = FlashTheme.colors
    Box(
        modifier = Modifier
            .shadow(FlashElevation.overlay, RoundedCornerShape(FlashShapes.radius8))
            .clip(RoundedCornerShape(FlashShapes.radius8))
            .background(colors.backgroundSurfaceStrong)
            .border(FlashDimensions.borderHairline, colors.borderDefault, RoundedCornerShape(FlashShapes.radius8))
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
    ) {
        FlashText(
            text = text,
            style = FlashTheme.typography.captionEmphasis,
            color = colors.textPrimary,
            maxLines = 1,
        )
    }
}

internal fun desktopSidebarProfileDescription(localDisplayName: String?): String {
    val name = localDisplayName?.trim()?.takeIf { it.isNotEmpty() }
    return if (name != null) "$name — Settings / Device profile" else "Settings / Device profile"
}

/** One sidebar row: the same tuple the bottom nav's `FlashBottomNavItem` carries. */
public data class DesktopSideTab(
    val destination: FlashDestination,
    val icon: FlashIconSpec,
    val label: String,
)

/**
 * The desktop tab set for the current enrolment (UI-058): built from [FlashShellTabs], the one source
 * shared with the bottom bar and the Android host, so it cannot drift from `FlashNavigationMath.tabOrder`.
 */
public fun desktopSideTabs(hubEnrolled: Boolean): List<DesktopSideTab> =
    FlashShellTabs.of(hubEnrolled).map { DesktopSideTab(it.destination, it.icon, it.label) }

/** The tab set of a device that is not enrolled (what every device had before the Hub tab). */
public val DESKTOP_SIDE_TABS: List<DesktopSideTab> = desktopSideTabs(hubEnrolled = false)
