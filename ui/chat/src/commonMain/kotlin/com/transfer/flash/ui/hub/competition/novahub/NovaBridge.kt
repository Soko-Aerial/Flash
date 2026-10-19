package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). Page 5: Bridge — group rosters & the relay path.
 *
 * The mode card, a small animated path diagram (phone ↔ colleague on the LAN, phone ↔ hub ↔
 * colleague on the relay) whose packets only flow when the ornament budget allows, the relay stat
 * grid, the honesty block about what the hub can and cannot read, and the group roster cards with
 * their signed-statement provenance.
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

@Composable
internal fun NovaBridgePage(
    world: NovaWorld,
    onDemoAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NovaPageScaffold(page = NovaPage.Bridge, world = world, onDemoAction = onDemoAction, modifier = modifier) {
        NovaBridgeModeCard(world)
        NovaJourneyCard(world)
        NovaStatsSection(world)
        NovaHonestCard(world)
        NovaRostersSection(world, onDemoAction)
    }
}

// --- Mode ----------------------------------------------------------------------------------------------

@Composable
private fun NovaBridgeModeCard(world: NovaWorld) {
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = FlashIcons.Connection, tone = world.bridge.modeTone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = world.bridge.modeLabel,
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                FlashText(
                    text = world.bridge.note,
                    style = FlashTheme.typography.captionDefault,
                    color = FlashTheme.colors.textSecondary,
                )
            }
            NovaPill(text = "Bridge", tone = world.bridge.modeTone)
        }
    }
}

// --- Journey --------------------------------------------------------------------------------------------

/**
 * The path diagram. Layout (in a fixed-height canvas):
 *   phone bottom-left · colleague bottom-right · hub top-centre.
 * The direct LAN edge is the straight bottom line; the relay path is the two segments via the hub.
 * Packets ride the edges only when the budget allows; reduce-motion and low tiers draw the same
 * edges and nodes statically, so the picture degrades, never disappears.
 */
@Composable
private fun NovaJourneyCard(world: NovaWorld) {
    val tone = world.bridge.modeTone
    val directTone = NovaTone.OK
    val hubClosed = tone == NovaTone.FAIL || tone == NovaTone.NEUTRAL
    val directDown = world.link == NovaLinkState.Offline
    val colors = FlashTheme.colors
    val decor = novaDecorAllowed()
    val sweep = rememberNovaSweep()
    val hubWash = tone.wash()
    val directWash = directTone.wash()

    NovaCard {
        Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            FlashText(
                text = "Where your messages ride",
                style = FlashTheme.typography.captionEmphasis,
                color = FlashTheme.colors.textPrimary,
            )
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val w = size.width
                val h = size.height
                val phone = Offset(w * 0.10f, h * 0.78f)
                val peer = Offset(w * 0.90f, h * 0.78f)
                val hub = Offset(w * 0.50f, h * 0.20f)
                val stroke = 5f

                // Direct edge: phone → colleague.
                val directColor = if (directDown) colors.borderStrong else directWash.strong
                drawLine(directColor, phone, peer, strokeWidth = stroke, cap = StrokeCap.Round)

                // Relay edges: phone → hub → colleague. Dashed when closed, solid when alive.
                val hubColor = if (hubClosed) colors.borderStrong else hubWash.strong
                val pathEffect = if (hubClosed && decor) {
                    PathEffect.dashPathEffect(floatArrayOf(18f, 14f))
                } else {
                    null
                }
                drawLine(hubColor, phone, hub, strokeWidth = stroke, cap = StrokeCap.Round, pathEffect = pathEffect)
                drawLine(hubColor, hub, peer, strokeWidth = stroke, cap = StrokeCap.Round, pathEffect = pathEffect)

                // Nodes: phone + colleague as filled discs, hub as a ring.
                drawCircle(directWash.strong, radius = 14f, center = phone)
                drawCircle(directWash.strong, radius = 14f, center = peer)
                if (hubClosed) {
                    // The X of a closed path: two short strokes through the hub node.
                    drawLine(hubColor, hub + Offset(-22f, -22f), hub + Offset(22f, 22f), strokeWidth = 6f, cap = StrokeCap.Round)
                    drawLine(hubColor, hub + Offset(-22f, 22f), hub + Offset(22f, -22f), strokeWidth = 6f, cap = StrokeCap.Round)
                }
                drawCircle(hubWash.strong, radius = 18f, center = hub, style = Stroke(width = 6f))

                // Packets: three per edge, offset around the sweep. Decor only — static frames otherwise.
                if (decor && !directDown) {
                    listOf(0f, 1f / 3f, 2f / 3f).forEach { phase ->
                        val t = (sweep.value + phase) % 1f
                        drawCircle(directWash.strong, radius = 5f, center = lerp(phone, peer, t), alpha = 0.9f)
                    }
                }
                if (decor && !hubClosed) {
                    listOf(0.15f, 0.5f, 0.85f).forEach { phase ->
                        val t = (sweep.value + phase) % 1f
                        if (t < 0.5f) {
                            drawCircle(hubWash.strong, radius = 5f, center = lerp(phone, hub, t * 2f), alpha = 0.9f)
                        } else {
                            drawCircle(hubWash.strong, radius = 5f, center = lerp(hub, peer, (t - 0.5f) * 2f), alpha = 0.9f)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FlashText("This phone", style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
                FlashText(
                    text = if (hubClosed) "Hub path closed" else "Hub (company server)",
                    style = FlashTheme.typography.metadataDefault,
                    color = if (hubClosed) colors.textTertiary else tone.wash().strong,
                )
                FlashText("Colleague", style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
            }
            FlashText(
                text = when {
                    directDown -> "No network: nothing leaves the phone. Nearby transfers resume when Wi-Fi returns."
                    hubClosed -> "The hub refuses this device; the direct line to paired colleagues is untouched."
                    else -> "Nearby colleagues take the direct line; the hub carries only what direct cannot reach."
                },
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float): Offset = Offset(
    a.x + (b.x - a.x) * t,
    a.y + (b.y - a.y) * t,
)

// --- Stats ---------------------------------------------------------------------------------------------

@Composable
private fun NovaStatsSection(world: NovaWorld) {
    if (world.bridge.stats.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
        NovaSectionHeader(title = "Relay accounting", trailing = "this device")
        Box(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                world.bridge.stats.chunked(2).forEach { rowOf ->
                    Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                        rowOf.forEach { stat ->
                            NovaStatTile(
                                label = stat.label,
                                value = stat.value,
                                tone = stat.tone,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowOf.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// --- Honesty block -------------------------------------------------------------------------------------

@Composable
private fun NovaHonestCard(world: NovaWorld) {
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = FlashIcons.Encryption, tone = NovaTone.NEUTRAL)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(
                    text = "What the hub can read",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                FlashText(
                    text = world.bridge.honestLine,
                    style = FlashTheme.typography.captionDefault,
                    color = FlashTheme.colors.textSecondary,
                )
            }
        }
    }
}

// --- Rosters -------------------------------------------------------------------------------------------

@Composable
private fun NovaRostersSection(world: NovaWorld, onDemoAction: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
        NovaSectionHeader(title = "Group rosters", trailing = if (world.rosters.isEmpty()) "" else "${world.rosters.size} groups")
        if (world.rosters.isEmpty()) {
            NovaCard {
                NovaEmptyPanel(
                    icon = FlashIcons.Group,
                    title = "No group rosters",
                    body = "Groups leave with your membership. Chats and transfers with people you paired with directly are untouched.",
                    tone = NovaTone.FAIL,
                )
            }
        } else {
            world.rosters.forEachIndexed { index, roster ->
                NovaRosterCard(roster, people = world.people, index = index, key = world.scenario, onDemoAction = onDemoAction)
            }
        }
    }
}

@Composable
private fun NovaRosterCard(
    roster: NovaRoster,
    people: List<NovaMember>,
    index: Int,
    key: Any,
    onDemoAction: (String) -> Unit,
) {
    val tone = roster.state.tone()
    val colors = FlashTheme.colors
    NovaCard(
        modifier = Modifier
            .novaStagger(index, key)
            .novaClick(onClick = { onDemoAction("Roster ${roster.name}: signed statement v${roster.version} (demo)") }),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = FlashIcons.Group, tone = tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(
                    text = roster.name,
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NovaMetaLine(
                    text = "${roster.department} · v${roster.version} · valid ${roster.validUntilShort} · ${roster.members} members",
                    icon = FlashIcons.Clock,
                )
                NovaMetaLine(text = roster.origin, icon = FlashIcons.Verified)
            }
            NovaPill(text = roster.state.label, tone = tone)
        }
        Spacer(Modifier.height(FlashSpacing.space8))
        // Member avatar cluster: overlapping monograms of the first few roster members, initials
        // resolved from the directory the device can already see (ids it cannot resolve show "?").
        Row(verticalAlignment = Alignment.CenterVertically) {
            roster.memberSeeds.take(5).forEachIndexed { i, seed ->
                val initials = people.firstOrNull { it.id == seed }?.initialsText ?: "?"
                Box(Modifier.offset(x = (-8 * i).dp)) {
                    NovaMonogramTile(
                        initials = initials,
                        seed = seed,
                        size = 28.dp,
                        shape = CircleShape,
                    )
                }
            }
            if (roster.members > 5) {
                Spacer(Modifier.width(FlashSpacing.space8))
                FlashText(
                    text = "+${roster.members - 5}",
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
