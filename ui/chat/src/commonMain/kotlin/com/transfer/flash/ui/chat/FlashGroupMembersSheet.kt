package com.transfer.flash.ui.chat

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
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

    /** Owner-only removal is offered on every row except the creator's own: the owner cannot remove itself. */
    fun canRemove(member: FlashGroupMemberUi): Boolean = member.role != FlashMemberRole.Owner

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
    /**
     * ADR-044 V2: verify a member the owner introduced by running ordinary pairing with them. Null hides the action
     * (previews, hosts without pairing); it is only ever offered for a member that has `introducedBy`.
     */
    onVerifyMember: ((FlashGroupMemberUi) -> Unit)? = null,
    /**
     * ADR-044 V2 (E5): the owner removes a member. Null hides the action; the host passes it only for the owner of a v2
     * group, and it is never offered on the Owner row (see [FlashGroupMembersMath.canRemove]).
     */
    onRemoveMember: ((FlashGroupMemberUi) -> Unit)? = null,
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
                    onRemove = onRemoveMember?.takeIf { FlashGroupMembersMath.canRemove(member) }?.let { remove -> { remove(member) } },
                )
            }
        }
    }
}

/**
 * Single member row: avatar + online dot, name/transport subtitle, transport glyph, role badge, and for a member the
 * owner introduced an "Added by" line and a Verify action; the owner of a v2 group also gets a Remove action. The action
 * buttons sit outside the merged description so each stays a separate accessibility target.
 */
@Composable
private fun FlashMemberRow(member: FlashGroupMemberUi, onVerify: (() -> Unit)?, onRemove: (() -> Unit)?) {
    val colors = FlashTheme.colors

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
                    style = FlashTheme.typography.bodyDefault.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashText(
                    text = memberSubtitle(member),
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashGroupMembersMath.introducedByLabel(member.introducedBy)?.let { label ->
                    FlashText(
                        text = label,
                        style = FlashTheme.typography.metadataDefault,
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

        if (onVerify != null) {
            FlashText(
                text = "Verify",
                modifier = Modifier
                    .semantics { contentDescription = "Verify ${member.name}" }
                    .clickable(role = Role.Button, onClick = onVerify)
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space12),
                style = FlashTheme.typography.bodyDefault,
                color = colors.accentPrimary,
                maxLines = 1,
            )
        }

        if (onRemove != null) {
            FlashText(
                text = "Remove",
                modifier = Modifier
                    .semantics { contentDescription = "Remove ${member.name} from the group" }
                    .clickable(role = Role.Button, onClick = onRemove)
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space12),
                style = FlashTheme.typography.bodyDefault,
                color = colors.textError,
                maxLines = 1,
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
    FlashNetworkTransport.Unknown -> null
}

@Composable
private fun RoleBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(FlashShapes.chip)
            .background(FlashTheme.colors.accentPrimary.copy(alpha = 0.12f))
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.metadataEmphasis,
            color = FlashTheme.colors.accentPrimary,
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
