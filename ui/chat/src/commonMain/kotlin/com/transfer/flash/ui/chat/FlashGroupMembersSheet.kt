package com.transfer.flash.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashGroupJoinRequestUi
import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.messaging.model.FlashMemberRole
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Pure sorting/copy logic for the group members sheet (UI-029).
 * Unit-testable without instrumentation (see [FlashGroupMembersLogicTest]).
 */
object FlashGroupMembersMath {
    /** Hard cap on rendered member rows (sheet scrolls, but huge meshes stay bounded). */
    const val MAX_VISIBLE_ROWS = 50

    /** Online before offline, then Owner/Admin by rank, then alphabetical by name. */
    fun sortMembers(members: List<FlashGroupMemberUi>): List<FlashGroupMemberUi> =
        members.sortedWith(
            compareByDescending<FlashGroupMemberUi> { it.isOnline }
                .thenBy { roleRank(it.role) }
                .thenBy { it.name.lowercase() },
        )

    private fun roleRank(role: FlashMemberRole): Int = when (role) {
        FlashMemberRole.Owner -> 0
        FlashMemberRole.Admin -> 1
        FlashMemberRole.Member -> 2
    }

    /** "4 of 15 online" title summary; singular-safe. */
    fun onlineSummaryLabel(total: Int, online: Int): String =
        "${online.coerceAtLeast(0)} of ${total.coerceAtLeast(0)} online"

    /** Pill copy for privileged roles; null for plain members (no badge rendered). */
    fun roleBadgeLabel(role: FlashMemberRole): String? = when (role) {
        FlashMemberRole.Owner -> "Creator"
        FlashMemberRole.Admin -> "Admin"
        FlashMemberRole.Member -> null
    }

    /** Rendered-row count cap helper. */
    fun visibleRowCount(requested: Int, max: Int = MAX_VISIBLE_ROWS): Int = requested.coerceIn(0, max)

    /**
     * "Added by Ada · not verified" for a member the group owner introduced (ADR-044 V2); null for a paired member and
     * for yourself, so no line is drawn. A blank name falls back to "the group owner".
     */
    fun introducedByLabel(introducedBy: String?): String? =
        introducedBy?.let { "Added by ${ownerName(it)} · not verified" }

    /** The spoken form of [introducedByLabel], appended to the row's accessibility description. */
    fun introducedByDescription(introducedBy: String?): String? =
        introducedBy?.let { "added by ${ownerName(it)}, not verified" }

    private fun ownerName(name: String): String = name.ifBlank { "the group owner" }

    /** Owner can remove anyone except the owner; Admin can remove regular members only. */
    fun canRemove(member: FlashGroupMemberUi, isOwner: Boolean = true): Boolean =
        if (isOwner) member.role != FlashMemberRole.Owner
        else member.role == FlashMemberRole.Member

    /** Title of the remove confirmation. */
    fun removeTitle(name: String): String = "Remove ${name.ifBlank { "this member" }}?"

    /** Body of the remove confirmation: honest that a removed member keeps what they already received. */
    fun removeMessage(name: String): String =
        "${name.ifBlank { "This member" }} will be removed for everyone in the group and stop receiving new messages. " +
            "Messages they already received stay on their device."
}

/**
 * UI-029 Group members sheet — custom member rows (avatar, online dot, transport,
 * role badge) inside a ModalBottomSheet. No stock list-item components.
 */
@Composable
fun FlashGroupMembersSheet(
    members: List<FlashGroupMemberUi>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isOwner: Boolean = false,
    isAdmin: Boolean = false,
    canShareInvite: Boolean = false,
    onInviteClick: (() -> Unit)? = null,
    pendingJoinRequests: List<FlashGroupJoinRequestUi> = emptyList(),
    onApproveJoinRequest: ((FlashGroupJoinRequestUi) -> Unit)? = null,
    onRefuseJoinRequest: ((FlashGroupJoinRequestUi) -> Unit)? = null,
    /**
     * ADR-044 V2: verify a member the owner introduced by running ordinary pairing with them. Null hides the action
     * (previews, hosts without pairing); it is only ever offered for a member that has `introducedBy`.
     */
    onVerifyMember: ((FlashGroupMemberUi) -> Unit)? = null,
    /**
     * ADR-044 V2 (E5) / ADR-063: owner or admin removes a member. Null hides the action.
     */
    onRemoveMember: ((FlashGroupMemberUi) -> Unit)? = null,
    /** ADR-063: promote a member to admin (owner only). */
    onPromoteAdmin: ((FlashGroupMemberUi) -> Unit)? = null,
    /** ADR-063: demote an admin to member (owner only). */
    onDemoteAdmin: ((FlashGroupMemberUi) -> Unit)? = null,
    onOpenSharedContent: (() -> Unit)? = null,
    /** UI-053: opens the group settings sheet from Group Info. Null hides the row (legacy groups, previews). */
    onOpenGroupSettings: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val sorted = remember(members) { FlashGroupMembersMath.sortMembers(members) }
    val rowCount = remember(sorted.size) { FlashGroupMembersMath.visibleRowCount(sorted.size) }
    val summary = remember(members) {
        FlashGroupMembersMath.onlineSummaryLabel(
            total = members.size,
            online = members.count { it.isOnline },
        )
    }
    var pendingRoleChange by remember { mutableStateOf<Pair<FlashGroupMemberUi, Boolean>?>(null) }
    FlashSheetHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FlashSpacing.space12)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.borderSubtle),
            )
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = FlashSpacing.space20,
                    end = FlashSpacing.space20,
                    bottom = FlashSpacing.space32,
                ),
        ) {
            // Shared Media & Files action row
            if (onOpenSharedContent != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FlashShapes.bubbleGrouped)
                        .clickable {
                            onDismiss()
                            onOpenSharedContent()
                        }
                        .padding(vertical = FlashSpacing.space8, horizontal = FlashSpacing.space4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.accentFill.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Gallery,
                            contentDescription = null,
                            tint = colors.accentFill,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        FlashText(
                            text = "Shared Media & Files",
                            style = FlashTheme.typography.bodyEmphasis,
                            color = colors.textPrimary,
                        )
                        FlashText(
                            text = "Photos, videos, files, and links",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textSecondary,
                        )
                    }
                    FlashIcon(
                        icon = FlashIcons.Forward,
                        contentDescription = null,
                        size = FlashDimensions.iconSm,
                        tint = colors.textSecondary,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = FlashSpacing.space8)
                        .height(FlashDimensions.borderHairline)
                        .background(colors.borderSubtle),
                )
            }

            // UI-053: Group settings action row (v2 groups, active members)
            if (onOpenGroupSettings != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FlashShapes.bubbleGrouped)
                        .clickable {
                            onDismiss()
                            onOpenGroupSettings()
                        }
                        .padding(vertical = FlashSpacing.space8, horizontal = FlashSpacing.space4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.accentFill.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Settings,
                            contentDescription = null,
                            tint = colors.accentFill,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        FlashText(
                            text = "Group settings",
                            style = FlashTheme.typography.bodyEmphasis,
                            color = colors.textPrimary,
                        )
                        FlashText(
                            text = "Join rules, file sharing, group code",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textSecondary,
                        )
                    }
                    FlashIcon(
                        icon = FlashIcons.Forward,
                        contentDescription = null,
                        size = FlashDimensions.iconSm,
                        tint = colors.textSecondary,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = FlashSpacing.space8)
                        .height(FlashDimensions.borderHairline)
                        .background(colors.borderSubtle),
                )
            }

            // UI-054: Invite People action row (shown only when this device may share it)
            if (canShareInvite && onInviteClick != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FlashShapes.bubbleGrouped)
                        .clickable(onClick = onInviteClick)
                        .padding(vertical = FlashSpacing.space8, horizontal = FlashSpacing.space4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.accentFill.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Share,
                            contentDescription = "Invite people",
                            tint = colors.accentFill,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        FlashText(
                            text = "Invite people",
                            style = FlashTheme.typography.bodyEmphasis,
                            color = colors.textLink,
                        )
                        FlashText(
                            text = "Share link or copy to clipboard",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textSecondary,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = FlashSpacing.space8)
                        .height(FlashDimensions.borderHairline)
                        .background(colors.borderSubtle),
                )
            }

            // UI-054: Join Requests section for admins/owners
            if ((isOwner || isAdmin) && pendingJoinRequests.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = FlashSpacing.space12),
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    FlashText(
                        text = "Join requests (${pendingJoinRequests.size})",
                        style = FlashTheme.typography.headingSmall,
                        color = colors.textPrimary,
                    )
                    pendingJoinRequests.forEach { req ->
                        FlashJoinRequestRow(
                            request = req,
                            onApprove = onApproveJoinRequest?.let { { it(req) } },
                            onRefuse = onRefuseJoinRequest?.let { { it(req) } },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = FlashSpacing.space12)
                        .height(FlashDimensions.borderHairline)
                        .background(colors.borderSubtle),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Members. $summary"
                    }
                    .padding(bottom = FlashSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                FlashText(
                    text = "Members",
                    modifier = Modifier.weight(1f),
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashText(
                    text = summary,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }

            sorted.take(rowCount).forEachIndexed { index, member ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FlashDimensions.borderHairline)
                            .background(colors.borderSubtle),
                    )
                }
                FlashMemberRow(
                    member = member,
                    onVerify = onVerifyMember?.takeIf { member.introducedBy != null }?.let { verify -> { verify(member) } },
                    onRemove = onRemoveMember?.takeIf { FlashGroupMembersMath.canRemove(member, isOwner) }?.let { remove -> { remove(member) } },
                    onPromote = onPromoteAdmin?.takeIf { isOwner && member.role == FlashMemberRole.Member }?.let { { pendingRoleChange = member to true } },
                    onDemote = onDemoteAdmin?.takeIf { isOwner && member.role == FlashMemberRole.Admin }?.let { { pendingRoleChange = member to false } },
                )
            }
        }
    }

    pendingRoleChange?.let { (member, isPromote) ->
        FlashConfirmHost(
            onDismiss = { pendingRoleChange = null },
            containerColor = colors.backgroundSurface,
            title = {
                FlashText(
                    text = if (isPromote) "Make ${member.name} an admin?" else "Dismiss ${member.name} as admin?",
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
            },
            text = {
                FlashText(
                    text = if (isPromote) {
                        "${member.name} will be able to approve join requests, remove members, and change group settings."
                    } else {
                        "${member.name} will become a regular member and will no longer be able to manage the group."
                    },
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRoleChange = null
                        if (isPromote) {
                            onPromoteAdmin?.invoke(member)
                        } else {
                            onDemoteAdmin?.invoke(member)
                        }
                    },
                ) {
                    Text(
                        text = if (isPromote) "Make admin" else "Dismiss admin",
                        color = if (isPromote) colors.textLink else colors.textError,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRoleChange = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }
}

/**
 * Single member row: avatar + online dot, name/transport subtitle, transport glyph, role badge, and an overflow
 * menu for Verify / Make admin / Dismiss admin / Remove so member names never crush on 360dp phones.
 */
@Composable
private fun FlashMemberRow(
    member: FlashGroupMemberUi,
    onVerify: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onPromote: (() -> Unit)? = null,
    onDemote: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    var menuExpanded by remember { mutableStateOf(false) }
    val hasActions = onVerify != null || onPromote != null || onDemote != null || onRemove != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    contentDescription = memberRowDescription(member)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            MemberAvatar(member = member)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
            ) {
                FlashText(
                    text = member.name,
                    style = typography.bodyDefault.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashText(
                    text = memberSubtitle(member),
                    style = typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashGroupMembersMath.introducedByLabel(member.introducedBy)?.let { label ->
                    FlashText(
                        text = label,
                        style = typography.metadataDefault,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            MemberTransportGlyph(member.transport)

            FlashGroupMembersMath.roleBadgeLabel(member.role)?.let { badge ->
                RoleBadge(label = badge)
            }
        }

        if (hasActions) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(FlashDimensions.minTouchTarget)
                        .semantics { contentDescription = "Actions for ${member.name}" },
                ) {
                    FlashIcon(
                        icon = FlashIcons.More,
                        contentDescription = null,
                        tint = colors.textSecondary,
                    )
                }
                val menuShape = RoundedCornerShape(FlashShapes.radius16)
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    offset = DpOffset(x = 0.dp, y = FlashSpacing.space4),
                    shape = menuShape,
                    containerColor = colors.backgroundSurface,
                    border = BorderStroke(FlashDimensions.borderHairline, colors.borderSubtle),
                ) {
                    if (onVerify != null) {
                        DropdownMenuItem(
                            text = {
                                FlashText(
                                    text = "Verify",
                                    style = typography.bodyDefault,
                                    color = colors.textLink,
                                )
                            },
                            leadingIcon = {
                                FlashIcon(
                                    icon = FlashIcons.Verified,
                                    contentDescription = null,
                                    tint = colors.textLink,
                                    size = FlashDimensions.iconSm,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onVerify()
                            },
                        )
                    }
                    if (onPromote != null) {
                        DropdownMenuItem(
                            text = {
                                FlashText(
                                    text = "Make admin",
                                    style = typography.bodyDefault,
                                    color = colors.textPrimary,
                                )
                            },
                            leadingIcon = {
                                FlashIcon(
                                    icon = FlashIcons.Group,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    size = FlashDimensions.iconSm,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onPromote()
                            },
                        )
                    }
                    if (onDemote != null) {
                        DropdownMenuItem(
                            text = {
                                FlashText(
                                    text = "Dismiss admin",
                                    style = typography.bodyDefault,
                                    color = colors.textPrimary,
                                )
                            },
                            leadingIcon = {
                                FlashIcon(
                                    icon = FlashIcons.Group,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    size = FlashDimensions.iconSm,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDemote()
                            },
                        )
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = {
                                FlashText(
                                    text = "Remove",
                                    style = typography.bodyDefault,
                                    color = colors.textError,
                                )
                            },
                            leadingIcon = {
                                FlashIcon(
                                    icon = FlashIcons.Delete,
                                    contentDescription = null,
                                    tint = colors.textError,
                                    size = FlashDimensions.iconSm,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Row representing a pending join request in the members sheet (UI-054).
 * Shows applicant initials, name, removed badge if applicable, and Approve / Decline buttons.
 */
@Composable
fun FlashJoinRequestRow(
    request: FlashGroupJoinRequestUi,
    onApprove: (() -> Unit)? = null,
    onRefuse: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val initials = request.label.take(2).uppercase().ifEmpty { "??" }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(FlashShapes.bubbleGrouped)
            .background(colors.backgroundSurfaceSubtle)
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashAvatar(
            initials = initials,
            seed = request.subjectId,
            size = 36.dp,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashText(
                    text = request.label,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (request.isPreviouslyRemoved) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accentSecondary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        FlashText(
                            text = "Previously removed",
                            style = FlashTheme.typography.metadataEmphasis,
                            color = colors.accentSecondary,
                        )
                    }
                }
            }
            FlashText(
                text = FlashGroupInviteJoinMath.joinRequestSubtitle(
                    request.requestedAtMs,
                    com.transfer.flash.core.common.time.SystemTimeSource.nowMs(),
                    request.isKnownDevice,
                ),
                style = FlashTheme.typography.metadataDefault,
                color = if (request.isKnownDevice) colors.textTertiary else colors.accentSecondary,
            )
        }

        if (onRefuse != null) {
            FlashText(
                text = "Decline",
                modifier = Modifier
                    .clip(FlashShapes.chip)
                    .clickable(role = Role.Button, onClick = onRefuse)
                    .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space8),
                style = FlashTheme.typography.captionEmphasis,
                color = colors.textSecondary,
            )
        }

        if (onApprove != null) {
            FlashText(
                text = "Approve",
                modifier = Modifier
                    .clip(FlashShapes.chip)
                    .background(colors.accentFill.copy(alpha = 0.12f))
                    .clickable(role = Role.Button, onClick = onApprove)
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                style = FlashTheme.typography.captionEmphasis,
                color = colors.textLink,
            )
        }
    }
}

/** Seeded 32dp avatar with a 10dp statusOnline dot (surface ring) overlaid bottom-end when online. */
@Composable
private fun MemberAvatar(member: FlashGroupMemberUi) {
    Box(modifier = Modifier.size(32.dp)) {
        FlashAvatar(
            initials = member.initials,
            seed = member.id,
            size = 32.dp,
        )
        if (member.isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(FlashTheme.colors.statusOnline)
                    .border(2.dp, FlashTheme.colors.backgroundSurface, CircleShape),
            )
        }
    }
}

@Composable
private fun MemberTransportGlyph(transport: FlashNetworkTransport) {
    val spec = transport.iconSpec() ?: return
    FlashIcon(
        icon = spec,
        contentDescription = null,
        size = FlashDimensions.iconSm,
        tint = FlashTheme.colors.textTertiary,
    )
}

private fun FlashNetworkTransport.iconSpec(): FlashIconSpec? = when (this) {
    FlashNetworkTransport.Lan -> FlashIcons.Wifi
    FlashNetworkTransport.WifiDirect -> FlashIcons.WifiDirect
    FlashNetworkTransport.Relay -> FlashIcons.Relay
    // ADR-228: group members are never radio-only (the radio carries 1:1 text only), but the enum is
    // shared, and a Bluetooth mark is the honest glyph if that ever changes.
    FlashNetworkTransport.Radio -> FlashIcons.Bluetooth
    FlashNetworkTransport.Unknown -> null
}

@Composable
private fun RoleBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(FlashShapes.chip)
            .background(FlashTheme.colors.accentFill.copy(alpha = 0.12f))
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.metadataEmphasis,
            color = FlashTheme.colors.textLink,
            maxLines = 1,
        )
    }
}

private fun memberSubtitle(member: FlashGroupMemberUi): String =
    if (!member.isOnline || member.transport == FlashNetworkTransport.Unknown) {
        "Offline"
    } else {
        when (member.transport) {
            FlashNetworkTransport.Lan -> "LAN"
            FlashNetworkTransport.WifiDirect -> "Wi-Fi Direct"
            FlashNetworkTransport.Relay -> "Relay"
            FlashNetworkTransport.Radio -> "Radio"
            FlashNetworkTransport.Unknown -> "Offline"
        }
    }

private fun memberRowDescription(member: FlashGroupMemberUi): String {
    val presence = if (member.isOnline) "online" else "offline"
    return buildString {
        append(member.name)
        append(", ")
        append(presence)
        FlashGroupMembersMath.roleBadgeLabel(member.role)?.let {
            append(", ")
            append(it)
        }
        FlashGroupMembersMath.introducedByDescription(member.introducedBy)?.let {
            append(", ")
            append(it)
        }
    }
}

/**
 * Build member rows from the real group header (UI-029). The header only carries
 * per-member initials plus aggregate online/total counts — no names, roles, or
 * per-member transport — so rows use the initials as a neutral label and mark the
 * first [FlashChatHeaderUiState.onlineCount] members online. Returns an empty list
 * when there is no roster (the current 1:1-only build), so no fabricated identities
 * ever reach production; [sampleGroupMembers] stays confined to @Preview.
 */
fun groupMembersFromHeader(header: FlashChatHeaderUiState): List<FlashGroupMemberUi> =
    header.memberInitials.mapIndexed { index, initials ->
        val online = index < header.onlineCount
        FlashGroupMemberUi(
            id = "member_$index",
            name = initials.uppercase(),
            initials = initials,
            isOnline = online,
            role = FlashMemberRole.Member,
            transport = if (online) header.transport else FlashNetworkTransport.Unknown,
        )
    }

/** Sample data: owner + admin + members with mixed transports/online states. */
fun sampleGroupMembers(): List<FlashGroupMemberUi> = listOf(
    FlashGroupMemberUi("m1", "You", "YO", isOnline = true, role = FlashMemberRole.Owner, transport = FlashNetworkTransport.Lan),
    FlashGroupMemberUi("m2", "Alex Rivera", "AR", isOnline = true, role = FlashMemberRole.Admin, transport = FlashNetworkTransport.WifiDirect),
    FlashGroupMemberUi("m3", "Sam Chen", "SC", isOnline = true, role = FlashMemberRole.Member, transport = FlashNetworkTransport.Lan),
    FlashGroupMemberUi("m4", "Lina Farid", "LF", isOnline = false, role = FlashMemberRole.Member, transport = FlashNetworkTransport.Relay),
    FlashGroupMemberUi("m5", "Ben Kato", "BK", isOnline = true, role = FlashMemberRole.Member, transport = FlashNetworkTransport.Relay),
    FlashGroupMemberUi("m6", "Dana Wolfe", "DW", isOnline = false, role = FlashMemberRole.Member),
    FlashGroupMemberUi("m7", "Kai Moreno", "KM", isOnline = true, role = FlashMemberRole.Member, transport = FlashNetworkTransport.Lan, introducedBy = "Alex Rivera"),
)

@Preview(name = "Members — small group", showBackground = true, widthDp = 390)
@Composable
private fun FlashGroupMembersSmallPreview() {
    FlashTheme {
        FlashGroupMembersSheet(
            members = sampleGroupMembers().take(5),
            onDismiss = {},
        )
    }
}

@Preview(name = "Members — large group", showBackground = true, widthDp = 390)
@Composable
private fun FlashGroupMembersLargePreview() {
    FlashTheme {
        FlashGroupMembersSheet(
            members = sampleGroupMembers() + (1..6).map { i ->
                FlashGroupMemberUi(
                    id = "extra$i",
                    name = "Extra Member $i",
                    initials = "EM",
                    isOnline = i % 2 == 0,
                    transport = FlashNetworkTransport.values()[i % 4],
                )
            },
            onDismiss = {},
            onVerifyMember = {},
            onRemoveMember = {},
        )
    }
}
