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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale

/**
 * APEX ZENITH — Page 3: Hub Announcements & Broadcast Feed.
 *
 * Implements:
 * - High-craft announcements feed and detail view (HubAnnouncementModels, HubAnnouncementsApi)
 * - Pinned broadcast banners, scope indicators, read/unread state toggling
 * - Interactive Draft Broadcast Composer modal for authorized staff
 */
@Composable
internal fun ApexPageAnnouncements(
    deck: ApexHubDeck,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    var activeFilter by remember { mutableStateOf("ALL") } // "ALL", "UNREAD", "PINNED", "ORG"
    var expandedAnnouncementId by remember { mutableStateOf<String?>(null) }
    var readOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var showDraftDialog by remember { mutableStateOf(false) }

    val allItems = deck.announcements.map { ann ->
        val isRead = readOverrides[ann.id] ?: ann.isRead
        ann.copy(isRead = isRead)
    }

    val filteredItems = remember(allItems, activeFilter) {
        allItems.filter { item ->
            when (activeFilter) {
                "UNREAD" -> !item.isRead
                "PINNED" -> item.pinned
                "ORG" -> item.scope == "org"
                else -> true
            }
        }
    }

    val unreadCount = allItems.count { !it.isRead }
    val pinnedItems = allItems.filter { it.pinned }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FlashSpacing.space16),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
        ) {
            // --- 1. Top Header Bar & Compose Button ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    FlashText(
                        text = "Broadcast Feed",
                        style = FlashTheme.typography.headingLarge,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(FlashSpacing.space2))
                    FlashText(
                        text = if (unreadCount > 0) "$unreadCount unread announcements" else "All caught up",
                        style = FlashTheme.typography.captionDefault,
                        color = if (unreadCount > 0) colors.accentPrimary else colors.textTertiary,
                    )
                }

                if (deck.canPostAnnouncements) {
                    ApexButton(
                        text = "New Post",
                        icon = FlashIcons.Edit,
                        tone = ApexTone.BUSY,
                        onClick = { showDraftDialog = true },
                    )
                }
            }

            // --- 2. Filter Pills ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                listOf(
                    "ALL" to "All Announcements",
                    "UNREAD" to "Unread ($unreadCount)",
                    "PINNED" to "Pinned (${pinnedItems.size})",
                    "ORG" to "Company-Wide",
                ).forEach { (key, label) ->
                    ApexFilterChip(
                        label = label,
                        selected = activeFilter == key,
                        onClick = { activeFilter = key },
                    )
                }
            }

            // --- 3. Announcements List or Empty State ---
            if (filteredItems.isEmpty()) {
                ApexEmptyState(
                    title = if (deck.announcements.isEmpty()) "No Announcements" else "No Matching Broadcasts",
                    description = if (deck.announcements.isEmpty())
                        "No organization broadcasts have been published yet."
                    else
                        "No announcements matched the selected filter.",
                    icon = FlashIcons.Announcement,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                    filteredItems.forEach { announcement ->
                        val isExpanded = expandedAnnouncementId == announcement.id
                        ApexAnnouncementCard(
                            announcement = announcement,
                            isExpanded = isExpanded,
                            nowMs = deck.nowMs,
                            onToggleExpand = {
                                expandedAnnouncementId = if (isExpanded) null else announcement.id
                                // Auto mark as read on expansion
                                if (!announcement.isRead) {
                                    readOverrides = readOverrides + (announcement.id to true)
                                }
                            },
                            onToggleRead = {
                                readOverrides = readOverrides + (announcement.id to !announcement.isRead)
                            },
                        )
                    }
                }
            }
        }

        // --- 4. Interactive Draft Modal ---
        if (showDraftDialog) {
            ApexDraftAnnouncementDialog(
                onDismiss = { showDraftDialog = false },
                onPublish = { title, body, pinned ->
                    showDraftDialog = false
                },
            )
        }
    }
}

@Composable
private fun ApexAnnouncementCard(
    announcement: HubAnnouncementUi,
    isExpanded: Boolean,
    nowMs: Long,
    onToggleExpand: () -> Unit,
    onToggleRead: () -> Unit,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    val tone = if (announcement.pinned) ApexTone.SPECIAL else if (!announcement.isRead) ApexTone.BUSY else null

    ApexCard(
        tone = tone,
        shape = RoundedCornerShape(ApexDims.cardRadius),
        contentPadding = FlashSpacing.space16,
        onClick = onToggleExpand,
    ) {
        // Scope & Pin Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                if (announcement.pinned) {
                    ApexBadge(
                        text = "PINNED",
                        tone = ApexTone.SPECIAL,
                        icon = FlashIcons.Pin,
                    )
                }
                ApexBadge(
                    text = if (announcement.scope == "org") "Company-Wide" else "Department",
                    tone = if (announcement.scope == "org") ApexTone.STANDBY else ApexTone.MUTED,
                )
                if (!announcement.isRead) {
                    ApexBadge(text = "NEW", tone = ApexTone.ONLINE)
                }
            }

            // Mark Read/Unread Icon Button
            ApexIconButton(
                icon = if (announcement.isRead) FlashIcons.Check else FlashIcons.Clock,
                tint = if (announcement.isRead) colors.textTertiary else colors.accentPrimary,
                onClick = onToggleRead,
                size = 32.dp,
                contentDescription = if (announcement.isRead) "Mark unread" else "Mark read",
            )
        }

        Spacer(Modifier.height(FlashSpacing.space8))

        // Title
        FlashText(
            text = announcement.title,
            style = FlashTheme.typography.headingMedium,
            color = if (!announcement.isRead) colors.accentPrimary else colors.textPrimary,
        )

        Spacer(Modifier.height(FlashSpacing.space4))

        // Body Preview or Full Expanded Text
        FlashText(
            text = announcement.body,
            style = FlashTheme.typography.bodyDefault,
            color = colors.textSecondary,
            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(FlashSpacing.space12))
        ApexDivider()
        Spacer(Modifier.height(FlashSpacing.space8))

        // Author attribution and sequence footer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                FlashText(
                    text = "${announcement.authorName} • ${announcement.authorRole}",
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.textPrimary,
                )
                FlashText(
                    text = "${apexFormatAgo(nowMs, announcement.createdAtMs)} • Seq #${announcement.seq}",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textTertiary,
                )
            }

            FlashText(
                text = if (isExpanded) "Show Less" else "Read More",
                style = FlashTheme.typography.captionEmphasis,
                color = colors.accentPrimary,
            )
        }
    }
}

@Composable
private fun ApexDraftAnnouncementDialog(
    onDismiss: () -> Unit,
    onPublish: (title: String, body: String, pinned: Boolean) -> Unit,
) {
    val colors = FlashTheme.colors
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(ApexDims.sheetRadius))
                .background(colors.sheetSurface)
                .border(FlashDimensions.borderHairline, colors.borderDefault, RoundedCornerShape(ApexDims.sheetRadius))
                .clickable(enabled = false) {}
                .padding(FlashSpacing.space24),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText(
                    text = "New Announcement",
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                ApexIconButton(
                    icon = FlashIcons.Close,
                    onClick = onDismiss,
                    contentDescription = "Close",
                )
            }

            ApexDivider()

            // Title input
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = "HEADLINE TITLE",
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.textTertiary,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ApexDims.tileRadius))
                        .background(colors.composerInputBackground)
                        .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(ApexDims.tileRadius))
                        .padding(FlashSpacing.space12),
                ) {
                    if (title.isEmpty()) {
                        FlashText(
                            text = "e.g., Critical Roster Key Ceremony Schedule",
                            style = FlashTheme.typography.bodyDefault,
                            color = colors.textTertiary,
                        )
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it },
                        textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accentPrimary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Body input
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = "MESSAGE CONTENT",
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.textTertiary,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(ApexDims.tileRadius))
                        .background(colors.composerInputBackground)
                        .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(ApexDims.tileRadius))
                        .padding(FlashSpacing.space12),
                ) {
                    if (body.isEmpty()) {
                        FlashText(
                            text = "Write the announcement message details for your team...",
                            style = FlashTheme.typography.bodyDefault,
                            color = colors.textTertiary,
                        )
                    }
                    BasicTextField(
                        value = body,
                        onValueChange = { body = it },
                        textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accentPrimary),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Pinned Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    FlashIcon(icon = FlashIcons.Pin, tint = colors.accentPrimary, size = FlashDimensions.iconSm)
                    FlashText(
                        text = "Pin this broadcast to top of feed",
                        style = FlashTheme.typography.bodyDefault,
                        color = colors.textPrimary,
                    )
                }
                ApexFilterChip(
                    label = if (pinned) "Pinned" else "Normal",
                    selected = pinned,
                    onClick = { pinned = !pinned },
                )
            }

            Spacer(Modifier.height(FlashSpacing.space8))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ApexSecondaryButton(
                    text = "Cancel",
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(FlashSpacing.space12))
                ApexButton(
                    text = "Publish",
                    icon = FlashIcons.Send,
                    enabled = title.isNotBlank() && body.isNotBlank(),
                    onClick = { onPublish(title, body, pinned) },
                )
            }
        }
    }
}
