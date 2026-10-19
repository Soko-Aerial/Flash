package com.transfer.flash.ui.hub.competition.apex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale

/**
 * APEX ZENITH — Page 2: Organization Directory & Peer Explorer.
 *
 * Implements:
 * - Searchable colleague and department directory (OrgDepartmentEntity, OrgDirectoryMemberEntity, HubPeerDirectory)
 * - Visual sync watermarks and refresh states (OrgDirectorySync, OrgSnapshotCoordinator, OrgSyncStateEntity)
 * - Interactive Peer Profile / Resolution drawer (OrgPeerResolver, DirectoryPrivacyClient)
 */
@Composable
internal fun ApexPageDirectory(
    deck: ApexHubDeck,
    modifier: Modifier = Modifier,
    onTriggerRefresh: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    var searchQuery by remember { mutableStateOf("") }
    var selectedDepartmentId by remember { mutableStateOf<String?>(null) }
    var activeFilter by remember { mutableStateOf("ALL") } // "ALL", "LAN", "ONLINE", "VERIFIED"
    var selectedPeer by remember { mutableStateOf<ApexPeerDetail?>(null) }

    val filteredPeers = remember(deck.colleagues, searchQuery, selectedDepartmentId, activeFilter) {
        deck.colleagues.filter { peer ->
            val matchesSearch = searchQuery.isEmpty() ||
                peer.displayName.contains(searchQuery, ignoreCase = true) ||
                peer.departmentName.contains(searchQuery, ignoreCase = true) ||
                peer.roleTitle.contains(searchQuery, ignoreCase = true)

            val matchesDept = selectedDepartmentId == null ||
                deck.departments.find { it.id == selectedDepartmentId }?.name == peer.departmentName

            val matchesFilter = when (activeFilter) {
                "LAN" -> peer.reachability == "LAN"
                "ONLINE" -> peer.isOnline
                "VERIFIED" -> peer.isVerified
                else -> true
            }

            matchesSearch && matchesDept && matchesFilter
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FlashSpacing.space16),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            // --- 1. Search Bar ---
            ApexSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search colleagues, roles, device IDs...",
            )

            // --- 2. Department Filter Carousel ---
            if (deck.departments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    ApexFilterChip(
                        label = "All Departments",
                        selected = selectedDepartmentId == null,
                        count = deck.colleagues.size,
                        onClick = { selectedDepartmentId = null },
                    )
                    deck.departments.forEach { dept ->
                        ApexFilterChip(
                            label = dept.name,
                            selected = selectedDepartmentId == dept.id,
                            count = dept.memberCount,
                            onClick = { selectedDepartmentId = dept.id },
                        )
                    }
                }
            }

            // --- 3. Filter Pills (Reachability & Verification) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                listOf(
                    "ALL" to "All Members",
                    "LAN" to "LAN Direct",
                    "ONLINE" to "Active Now",
                    "VERIFIED" to "Org Verified",
                ).forEach { (key, label) ->
                    ApexFilterChip(
                        label = label,
                        selected = activeFilter == key,
                        onClick = { activeFilter = key },
                    )
                }
            }

            // --- 4. Sync Watermark Banner ---
            ApexCard(contentPadding = FlashSpacing.space12) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Check,
                            tint = colors.textSuccess,
                            size = FlashDimensions.iconSm,
                            contentDescription = null,
                        )
                        Column {
                            FlashText(
                                text = "Directory Snapshot #${deck.syncWatermark.sequenceNumber}",
                                style = FlashTheme.typography.captionEmphasis,
                                color = colors.textPrimary,
                            )
                            FlashText(
                                text = "Status: ${deck.syncWatermark.status} • Updated ${apexFormatAgo(deck.nowMs, deck.syncWatermark.lastSyncedAtMs)}",
                                style = FlashTheme.typography.metadataDefault,
                                color = colors.textTertiary,
                            )
                        }
                    }

                    ApexSecondaryButton(
                        text = "Refresh",
                        icon = FlashIcons.Retry,
                        onClick = onTriggerRefresh,
                    )
                }
            }

            // --- 5. Colleague List ---
            if (filteredPeers.isEmpty()) {
                ApexEmptyState(
                    title = if (deck.colleagues.isEmpty()) "Directory Empty" else "No Colleagues Found",
                    description = if (deck.colleagues.isEmpty())
                        "No directory snapshot available in this scenario."
                    else
                        "No colleagues matched '$searchQuery'. Try adjusting your search query or filters.",
                    icon = FlashIcons.Search,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    filteredPeers.forEach { peer ->
                        ApexPeerRow(
                            peer = peer,
                            onClick = { selectedPeer = peer },
                        )
                    }
                }
            }
        }

        // --- 6. Peer Resolution Drawer / Modal Overlay ---
        if (selectedPeer != null) {
            ApexPeerResolutionDrawer(
                peer = selectedPeer!!,
                onDismiss = { selectedPeer = null },
            )
        }
    }
}

@Composable
private fun ApexPeerRow(
    peer: ApexPeerDetail,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ApexDims.cardRadius))
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(ApexDims.cardRadius))
            .flashPressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        // Avatar with initials
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.accentPrimary.copy(alpha = 0.15f))
                .border(FlashDimensions.borderHairline, colors.accentPrimary.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val initials = peer.displayName
                .split(" ")
                .filter { it.isNotEmpty() }
                .take(2)
                .map { it.first() }
                .joinToString("")
            FlashText(
                text = initials.ifEmpty { "P" },
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.accentPrimary,
            )
            // Presence badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (peer.isOnline) colors.statusOnline else colors.statusOffline)
                    .border(1.dp, colors.backgroundSurface, CircleShape),
            )
        }

        // Name, Department & Role
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                FlashText(
                    text = peer.displayName,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (peer.isSelf) {
                    ApexBadge(text = "You", tone = ApexTone.BUSY)
                }
                if (peer.isAdmin) {
                    ApexBadge(text = "Admin", tone = ApexTone.SPECIAL)
                }
            }
            Spacer(Modifier.height(FlashSpacing.space2))
            FlashText(
                text = "${peer.roleTitle} • ${peer.departmentName}",
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Reachability & Verification Indicators
        Column(horizontalAlignment = Alignment.End) {
            ApexBadge(
                text = peer.reachability,
                tone = when (peer.reachability) {
                    "LAN" -> ApexTone.ONLINE
                    "HUB Relay" -> ApexTone.STANDBY
                    else -> ApexTone.MUTED
                },
                icon = when (peer.reachability) {
                    "LAN" -> FlashIcons.Wifi
                    "HUB Relay" -> FlashIcons.Relay
                    else -> FlashIcons.Connection
                },
            )
            if (peer.isVerified) {
                Spacer(Modifier.height(FlashSpacing.space4))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
                ) {
                    FlashIcon(
                        icon = FlashIcons.Verified,
                        tint = colors.textSuccess,
                        size = 12.dp,
                        contentDescription = null,
                    )
                    FlashText(
                        text = "Verified",
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSuccess,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApexPeerResolutionDrawer(
    peer: ApexPeerDetail,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .width(ApexDims.drawerWidth)
                .fillMaxSize()
                .background(colors.sheetSurface)
                .border(FlashDimensions.borderHairline, colors.borderSubtle)
                .clickable(enabled = false) {}
                .padding(FlashSpacing.space20),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText(
                    text = "Peer Resolution Profile",
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                ApexIconButton(
                    icon = FlashIcons.Close,
                    onClick = onDismiss,
                    contentDescription = "Close drawer",
                )
            }

            ApexDivider()

            // Peer Identity Banner
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(colors.accentPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = peer.displayName.take(2).uppercase(),
                        style = FlashTheme.typography.headingLarge,
                        color = colors.accentPrimary,
                    )
                }
                Column {
                    FlashText(
                        text = peer.displayName,
                        style = FlashTheme.typography.headingSmall,
                        color = colors.textPrimary,
                    )
                    FlashText(
                        text = peer.roleTitle,
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                    FlashText(
                        text = peer.departmentName,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textTertiary,
                    )
                }
            }

            // Transport Reachability (OrgPeerResolver)
            ApexCard {
                ApexSectionHeader(title = "Network Reachability (OrgPeerResolver)")
                Spacer(Modifier.height(FlashSpacing.space8))
                ApexTile(
                    label = "Transport Mode",
                    value = peer.reachability,
                    caption = "Endpoint: ${peer.ipAddress}",
                    tone = if (peer.reachability == "LAN") ApexTone.ONLINE else ApexTone.STANDBY,
                    icon = FlashIcons.Connection,
                )
            }

            // Cryptographic Fingerprint & Attestation
            ApexCard {
                ApexSectionHeader(title = "Cryptographic Trust (DirectoryHubSealing)")
                Spacer(Modifier.height(FlashSpacing.space8))
                ApexCodeBox(
                    label = "Public Key Fingerprint (Ed25519)",
                    code = peer.publicKeyFingerprint,
                )
                Spacer(Modifier.height(FlashSpacing.space8))
                FlashText(
                    text = "Attestation: ${peer.encryptionAlgorithm}",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                )
            }

            // Privacy Controls (DirectoryPrivacyClient)
            ApexCard {
                ApexSectionHeader(title = "Privacy & Visibility Controls")
                Spacer(Modifier.height(FlashSpacing.space4))
                FlashText(
                    text = "This colleague is sharing presence and LAN direct discoverability under Organization Trust Policy v2.",
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textSecondary,
                )
            }

            Spacer(Modifier.weight(1f))

            // Action Buttons
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                ApexButton(
                    text = "Start Direct Secure Chat",
                    icon = FlashIcons.Chat,
                    tone = ApexTone.BUSY,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
                ApexSecondaryButton(
                    text = "Verify Peer Fingerprint",
                    icon = FlashIcons.Verified,
                    tone = ApexTone.STANDBY,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
