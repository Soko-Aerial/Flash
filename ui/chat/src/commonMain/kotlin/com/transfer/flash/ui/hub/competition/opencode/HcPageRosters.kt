package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * **Page 5 — Verified Group Rosters and the Relay Bridge.**
 *
 * Two halves of the same story, which is why they share a page. A roster is what the organisation *asserts* about a
 * group; the bridge is what the hub actually *carries* for the conversations on this device. Reading them together
 * makes the split obvious: membership is verified from a signed statement, delivery is not verified at all — it is
 * carried, and the page says exactly how much of it the hub can read.
 *
 * Approvals and refusals are live here (they change the list), because a roster with a pending request is the one place
 * on this page where a decision is owed.
 */
@Composable
internal fun HcPageRosters(deck: HcDeck, widthDp: Float) {
    val wide = hcIsWide(widthDp)
    var segment by remember(deck.scenario) { mutableStateOf(0) }
    var selectedKey by remember(deck.scenario) { mutableStateOf<String?>(null) }
    var decided by remember(deck.scenario) { mutableStateOf(emptySet<String>()) }
    var joined by remember(deck.scenario) { mutableStateOf(emptySet<String>()) }

    val rosterKey = if (segment == 0) deck.rosters.firstOrNull()?.groupId else deck.bridges.firstOrNull()?.conversationId
    val highlighted = selectedKey ?: if (wide) rosterKey else null
    val roster = deck.rosters.firstOrNull { it.groupId == highlighted }
    val bridge = deck.bridges.firstOrNull { it.conversationId == highlighted }

    HcSplitPage(
        widthDp = widthDp,
        detailOpen = roster != null || bridge != null,
        onCloseDetail = { selectedKey = null },
        detailTitle = roster?.name ?: bridge?.title ?: "Detail",
        detailPlaceholder = {
            HcEmptyState(
                glyph = HcGlyph.ROSTER,
                headline = "Choose a roster or a bridge",
                body = "A roster is signed membership the organisation asserts. A bridge is a conversation the hub carries for this device.",
            )
        },
        listPane = {
            HcScrollColumn {
                HcPageHeader(
                    title = "Rosters and bridge",
                    subtitle = "${deck.orgName} · ${deck.rosters.size} rosters · ${deck.bridges.size} conversations",
                    glyph = HcGlyph.ROSTER,
                    tone = HcTone.ACCENT,
                    statusLabel = "${deck.rosters.count { it.adoption == "ADOPTED" }} signed rosters in force · ${deck.bridges.count { it.viaHub }} through the hub",
                )
                HcSegmented(
                    options = listOf("Rosters", "Relay bridge"),
                    selectedIndex = segment,
                    onSelect = { segment = it; selectedKey = null },
                    modifier = Modifier.padding(bottom = FlashSpacing.space8),
                )
                if (segment == 0) {
                    if (deck.rosters.isEmpty()) {
                        HcEmptyState(
                            glyph = HcGlyph.ROSTER,
                            headline = if (deck.groups.isEmpty()) "No groups on this device" else "No signed rosters yet",
                            body = if (deck.groups.isEmpty()) {
                                "Join or create a group to see its signed roster here. Membership is asserted by a statement, not by the hub."
                            } else {
                                "The device knows these groups but has no signed roster for them. A roster arrives when a member is online."
                            },
                        )
                    } else {
                        HcBand("Signed rosters", trailing = "${deck.rosters.size}") {
                            deck.rosters.forEach { item ->
                                HcRosterRow(
                                    item = item,
                                    nowMs = deck.nowMs,
                                    pending = item.pendingRequests - if (item.groupId in decided) 1 else 0,
                                    highlighted = item.groupId == highlighted,
                                    onSelect = { selectedKey = item.groupId },
                                )
                            }
                        }
                        HcHint("A roster is signed by the group's owner. It names the members and their keys, so a device the organisation removed can be refused even when it reconnects.")
                    }
                } else {
                    if (deck.bridges.isEmpty()) {
                        HcEmptyState(
                            glyph = HcGlyph.RELAY,
                            headline = "No conversation uses the hub",
                            body = "Every conversation on this device is currently direct. Nothing is waiting at the hub.",
                        )
                    } else {
                        HcBand("Conversations", trailing = "${deck.bridges.size}") {
                            deck.bridges.forEach { item ->
                                HcBridgeRow(item, item.conversationId == highlighted) { selectedKey = item.conversationId }
                            }
                        }
                        HcHint("The hub sees that a message moved from one device to another, when, and how large it was. It does not see what was inside, and this page never claims otherwise.")
                    }
                }
            }
        },
        detailPane = {
            if (roster != null) {
                HcRosterDetail(
                    deck = deck,
                    roster = roster,
                    decided = roster.groupId in decided,
                    joined = roster.groupId in joined,
                    onApprove = { decided = decided + roster.groupId; joined = joined + roster.groupId },
                    onDecline = { decided = decided + roster.groupId },
                )
            } else if (bridge != null) {
                HcBridgeDetail(deck, bridge)
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Roster
// ---------------------------------------------------------------------------------------------------------------

private fun hcRosterDaysLeft(roster: HcRoster, nowMs: Long): Int =
    (((roster.validUntilMs - nowMs) / 86_400_000L).coerceAtLeast(0L)).toInt()

private fun hcAdoptionTone(adoption: String): HcTone = when {
    adoption.startsWith("ADOPTED") -> HcTone.SUCCESS
    adoption.startsWith("STALE") -> HcTone.WARNING
    else -> HcTone.MUTED
}

@Composable
private fun HcRosterRow(item: HcRoster, nowMs: Long, pending: Int, highlighted: Boolean, onSelect: () -> Unit) {
    val colors = FlashTheme.colors
    val tone = when {
        highlighted -> HcTone.ACCENT
        pending > 0 -> HcTone.WARNING
        else -> HcTone.NEUTRAL
    }
    Row(
        Modifier
            .fillMaxWidth()
            .hcCardSurface(tone, RoundedCornerShape(HcDims.tileRadius))
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        HcAvatar(item.name, item.groupId, diameter = FlashDimensions.avatarMd, ringTone = if (pending > 0) HcTone.WARNING else null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            FlashText(item.name, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlashText(
                "${item.department} · v${item.version} · ${item.memberCount} members",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                HcChip(item.adoption.substringBefore(' '), tone = hcAdoptionTone(item.adoption), compact = true)
                if (item.onlineCount > 0) HcChip("${item.onlineCount} online", tone = HcTone.SUCCESS, compact = true)
                if (pending > 0) HcChip("$pending waiting", tone = HcTone.WARNING, glyph = HcGlyph.CLOCK, compact = true)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText("${hcRosterDaysLeft(item, nowMs)} d", style = hcMono(FlashTheme.typography.numericEmphasis), color = colors.textSecondary, maxLines = 1)
            FlashText("roster left", style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun HcRosterDetail(
    deck: HcDeck,
    roster: HcRoster,
    decided: Boolean,
    joined: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = FlashTheme.colors
    val days = hcRosterDaysLeft(roster, deck.nowMs)
    val stale = roster.adoption.startsWith("STALE")
    HcCard(
        eyebrow = "${roster.department} · statement #${roster.statementSerial}",
        title = roster.name,
        glyph = HcGlyph.ROSTER,
        tone = hcAdoptionTone(roster.adoption),
        action = { HcChip(roster.adoption.substringBefore(' '), tone = hcAdoptionTone(roster.adoption), compact = true) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space16)) {
            HcRingGauge(
                progress = (days / 30f).coerceIn(0f, 1f),
                tone = if (days > 7) HcTone.SUCCESS else HcTone.WARNING,
                value = "$days",
                caption = "days left",
                diameter = 80.dp,
                strokeWidth = 8.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                HcKeyValue("Roster version", "v${roster.version}", mono = true)
                HcKeyValue("Members", "${roster.memberCount}")
                HcKeyValue("Administrators", "${roster.adminCount}")
                HcKeyValue("Online right now", "${roster.onlineCount}", tone = if (roster.onlineCount > 0) HcTone.SUCCESS else HcTone.MUTED)
            }
        }
        HcDivider()
        HcKeyValue("Signed by", "${deck.orgName}'s group owner", glyph = HcGlyph.SHIELD)
        HcKeyValue("Key of this device", if (roster.members.any { it.deviceId == deck.myDeviceId }) "Listed in this roster" else "Not listed yet")
        if (stale) {
            HcBannerCard(
                HcBanner(
                    id = "roster-stale",
                    tone = HcTone.WARNING,
                    glyph = HcGlyph.WARNING,
                    title = "A newer roster was offered and refused",
                    body = "The offer did not verify against the owner's key, so this device kept the older roster it already trusts. The members below are the ones still in force.",
                    action = "Explain the refusal",
                ),
            )
        }
        HcDivider()
        FlashText("Members", style = FlashTheme.typography.metadataEmphasis, color = colors.textTertiary)
        roster.members.forEach { member -> HcRosterMemberRow(member) }
        if (roster.pendingRequests > 0) {
            HcDivider()
            HcPendingRequestRow(roster, decided, joined, onApprove, onDecline)
        }
        HcDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            HcButton("Share the group code", {}, glyph = HcGlyph.SHARE, modifier = Modifier.weight(1f))
            HcButton("Open the group", {}, glyph = HcGlyph.GROUP, primary = false, modifier = Modifier.weight(1f))
        }
        HcSoftRule()
        FlashText(
            "A member who is removed loses the group's next key, not what it already received. Flash says so rather than pretending a removal reaches backwards.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun HcRosterMemberRow(member: HcRosterMember) {
    val colors = FlashTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        HcAvatar(member.name, member.deviceId, diameter = FlashDimensions.avatarXs, online = member.online)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(member.name, style = FlashTheme.typography.captionEmphasis, color = colors.textPrimary, maxLines = 1)
            FlashText(
                "${member.role.replaceFirstChar { it.uppercase() }} · ${member.department}",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!member.verified) HcChip("Key unverified", tone = HcTone.DANGER, glyph = HcGlyph.WARNING, compact = true)
        if (member.paired) HcChip("Paired", tone = HcTone.SUCCESS, compact = true)
        HcChip(
            when {
                member.online == null -> "Presence hidden"
                member.online -> "Online"
                else -> "Offline"
            },
            tone = if (member.online == true) HcTone.SUCCESS else HcTone.MUTED,
            compact = true,
        )
    }
}

@Composable
private fun HcPendingRequestRow(roster: HcRoster, decided: Boolean, joined: Boolean, onApprove: () -> Unit, onDecline: () -> Unit) {
    val colors = FlashTheme.colors
    Box(
        Modifier.fillMaxWidth().hcCardSurface(HcTone.WARNING, RoundedCornerShape(HcDims.tileRadius)).padding(FlashSpacing.space12),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                HcPulseDot(HcTone.WARNING, pulsing = !decided, diameter = 9.dp)
                FlashText(
                    if (decided) "Request handled" else "${roster.pendingRequests} join request waiting",
                    Modifier.weight(1f),
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
            FlashText(
                if (decided && joined) {
                    "Approved. The applicant is offered the group's current key and appears in the roster's next version."
                } else if (decided) {
                    "Refused. The applicant is told, and the key material it was offered is dropped."
                } else {
                    "An unpaired colleague asked to join. Approving offers the group's current key; refusing tells them plainly."
                },
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary,
            )
            if (!decided) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    HcButton("Approve", onApprove, glyph = HcGlyph.CHECK, modifier = Modifier.weight(1f))
                    HcButton("Decline", onDecline, primary = false, tone = HcTone.NEUTRAL, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Bridge
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HcBridgeRow(item: HcBridge, highlighted: Boolean, onSelect: () -> Unit) {
    val colors = FlashTheme.colors
    val tone = when {
        highlighted -> HcTone.ACCENT
        item.plaintextHonesty -> HcTone.WARNING
        item.queueHeld > 0 -> HcTone.WARNING
        else -> HcTone.NEUTRAL
    }
    Row(
        Modifier
            .fillMaxWidth()
            .hcCardSurface(tone, RoundedCornerShape(HcDims.tileRadius))
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(FlashShapes.radius8)).background(colors.backgroundSurfaceStrong),
            contentAlignment = Alignment.Center,
        ) { HcGlyphIcon(if (item.viaHub) HcGlyph.RELAY else HcGlyph.WIFI, tint = hcTone(tone).strong, size = FlashDimensions.iconSm) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            FlashText(item.title, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlashText(item.kind, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                HcChip(item.sealed.label, tone = item.sealed.tone, compact = true)
                if (item.queueHeld > 0) HcChip("${item.queueHeld} held", tone = HcTone.WARNING, compact = true)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText("${item.delivered}", style = hcMono(FlashTheme.typography.numericEmphasis), color = colors.textSecondary, maxLines = 1)
            FlashText("delivered", style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun HcBridgeDetail(deck: HcDeck, item: HcBridge) {
    val colors = FlashTheme.colors
    HcCard(
        eyebrow = item.kind,
        title = item.title,
        glyph = HcGlyph.RELAY,
        tone = item.sealed.tone,
        action = { HcChip(if (item.viaHub) "Through the hub" else "Direct", tone = if (item.viaHub) HcTone.ACCENT else HcTone.SUCCESS, compact = true) },
    ) {
        HcChipFlow {
            HcChip(item.sealed.label, tone = item.sealed.tone, glyph = HcGlyph.SHIELD, compact = true)
            HcChip("${item.delivered} delivered", tone = HcTone.NEUTRAL, compact = true)
            if (item.queueHeld > 0) HcChip("${item.queueHeld} waiting at the hub", tone = HcTone.WARNING, glyph = HcGlyph.MAILBOX, compact = true)
        }
        HcDivider()
        HcKeyValue("Last acknowledgement", item.lastAck)
        HcKeyValue("Path", if (item.viaHub) "${deck.hubName} (${deck.hubHost})" else "Local network, no hub", mono = false, glyph = if (item.viaHub) HcGlyph.RELAY else HcGlyph.WIFI)
        HcKeyValue("What the hub can read", if (item.sealed == HcSealing.SEALED) "Nothing of the content" else "The content of this conversation")
        if (item.plaintextHonesty) {
            HcBannerCard(
                HcBanner(
                    id = "bridge-plain",
                    tone = HcTone.WARNING,
                    glyph = HcGlyph.WARNING,
                    title = "Messages here are not sealed",
                    body = "There is no per-sender group key yet, so group content travels readable through the hub. Direct messages with each member are still sealed.",
                ),
            )
        }
        FlashText(item.note, style = FlashTheme.typography.bodyDefault, color = colors.textSecondary)
        HcSoftRule()
        FlashText("What the hub knows either way", style = FlashTheme.typography.metadataEmphasis, color = colors.textTertiary)
        HcKeyValue("Who the message is for", "The hub routes it, so it knows", tone = HcTone.WARNING)
        HcKeyValue("When it moved and how large", "Metadata carried with the envelope", tone = HcTone.WARNING)
        HcKeyValue(
            "What was inside",
            if (item.sealed == HcSealing.SEALED) "Nothing — sealed before it left this device" else "Everything — readable at the hub",
            tone = if (item.sealed == HcSealing.SEALED) HcTone.SUCCESS else HcTone.DANGER,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            HcButton("Open conversation", {}, glyph = HcGlyph.CHAT, modifier = Modifier.weight(1f))
            HcButton("Prefer the LAN", {}, glyph = HcGlyph.WIFI, primary = false, modifier = Modifier.weight(1f), enabled = item.sealed != HcSealing.PLAINTEXT_ONLY)
        }
    }
}

@Composable
private fun HcHint(text: String) {
    val colors = FlashTheme.colors
    FlashText(
        text,
        Modifier.fillMaxWidth().padding(top = FlashSpacing.space4),
        style = FlashTheme.typography.metadataDefault,
        color = colors.textTertiary,
    )
}
