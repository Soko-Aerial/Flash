package com.transfer.flash.ui.adaptive

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Sleek, icon-first vertical navigation rail for large screens (WhatsApp & Telegram Desktop style).
 *
 * Placed on the far left edge of the application when screen width >= 600dp (or in two-pane mode >= 840dp).
 * Features:
 * - 68dp compact width ensuring maximum space for chat list and message contents.
 * - Flash lightning bolt app medallion at top.
 * - Sleek active pill indicator and soft highlight container.
 * - Dynamic unread badges on the Chats tab and transfer activity badges.
 * - User/device avatar shortcut at bottom.
 */
@Composable
fun FlashNavigationRail(
    tabs: List<FlashNavigationRailTab>,
    selectedTab: FlashDestination,
    onTabSelected: (FlashDestination) -> Unit,
    modifier: Modifier = Modifier,
    localDisplayName: String? = null,
    onProfileClick: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors

    Row(
        modifier = modifier
            .width(68.dp)
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
            // App Branding Medallion (Option B: Upgraded prominent Flash Pulse medallion)
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
                    )
                    .clickable { onTabSelected(FlashDestination.ChatList) }
                    .semantics { contentDescription = "Flash Home" },
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
                        contentDescription = "Flash",
                        tint = colors.accentPrimary,
                        size = 22.dp,
                    )
                }
            }

            Spacer(Modifier.height(FlashSpacing.space16))

            // Navigation Tab Items
            Column(
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                tabs.forEach { tab ->
                    val isSelected = tab.destination == selectedTab
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) colors.accentPrimary else colors.textSecondary,
                        label = "railIconTint",
                    )
                    val containerBg by animateColorAsState(
                        targetValue = if (isSelected) colors.accentPrimary.copy(alpha = 0.14f) else Color.Transparent,
                        label = "railContainerBg",
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { onTabSelected(tab.destination) }
                            .semantics { contentDescription = "${tab.label} tab" },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Left-edge active indicator bar
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width(3.dp)
                                    .height(22.dp)
                                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                                    .background(colors.accentPrimary),
                            )
                        }

                        // Icon button container
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(FlashShapes.radius12))
                                .background(containerBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            FlashIcon(
                                icon = tab.icon,
                                contentDescription = tab.label,
                                tint = iconTint,
                                size = FlashDimensions.iconMd,
                            )

                            // Unread Badge Pill
                            if (tab.badgeCount > 0) {
                                val badgeText = if (tab.badgeCount > 99) "99+" else tab.badgeCount.toString()
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-4).dp)
                                        .clip(CircleShape)
                                        .background(colors.accentPrimary)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    FlashText(
                                        text = badgeText,
                                        color = colors.textOnAccent,
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

            Spacer(Modifier.weight(1f))

            // Bottom Profile / Device Avatar
            val initials = localDisplayName?.take(1)?.uppercase() ?: ""
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.backgroundSurfaceStrong)
                    .clickable { onProfileClick?.invoke() ?: onTabSelected(FlashDestination.Settings) },
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
                        contentDescription = "Profile",
                        tint = colors.textSecondary,
                        size = 18.dp,
                    )
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
