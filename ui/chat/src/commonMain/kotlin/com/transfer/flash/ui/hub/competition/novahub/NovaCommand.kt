package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). Page 1: the Command Center.
 *
 * One glance for "is my organisation link healthy and what is it doing right now": the org header
 * with its link pill, the live link line, telemetry cards with spark history, the enterprise
 * session list, the sealing-key state and the offline colleague-trust lease. Every value is a
 * presenting value from [NovaWorld] — the page owns no data and no clock beyond the demo ticker.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

@Composable
internal fun NovaCommandPage(
    world: NovaWorld,
    onDemoAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NovaPageScaffold(page = NovaPage.Command, world = world, onDemoAction = onDemoAction, modifier = modifier) {
        NovaOrgHeader(world)
        NovaLiveLine(world)
        NovaTelemetrySection(world)
        NovaSessionsSection(world, onDemoAction)
        NovaSealingSection(world)
        NovaLeaseSection(world)
    }
}

// --- Org header ----------------------------------------------------------------------------------------

@Composable
private fun NovaOrgHeader(world: NovaWorld) {
    val org = world.org
    val linkTone = world.link.tone()
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaMonogramTile(
                initials = org.name.split(' ').take(2).mapNotNull { it.firstOrNull() }.joinToString(""),
                seed = org.name,
                size = 52.dp,
                shape = NovaHeroShape,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(
                    text = org.name,
                    style = FlashTheme.typography.headingMedium,
                    color = FlashTheme.colors.textPrimary,
                )
                NovaMetaLine(text = org.host, icon = FlashIcons.Relay)
                NovaMetaLine(text = org.roleLine, icon = FlashIcons.Device)
                NovaMetaLine(
                    text = "${org.memberCount} colleagues · ${org.departmentCount} departments · ${org.groupCount} groups",
                    icon = FlashIcons.Group,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                NovaPill(text = world.link.label, tone = linkTone)
                if (org.canPost) {
                    NovaPill(text = "Can post", tone = NovaTone.INFO, withIcon = false)
                }
            }
        }
    }
}

/** The live link line: a breathing dot while connected, one honest sentence always. */
@Composable
private fun NovaLiveLine(world: NovaWorld) {
    val tick = rememberNovaTick()
    val online = world.link == NovaLinkState.Online
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        modifier = Modifier.padding(horizontal = FlashSpacing.space4),
    ) {
        NovaDot(tone = world.link.tone(), breathe = online)
        FlashText(
            text = if (online) {
                "Link live for ${world.liveTickBaseSeconds + tick} s · TLS pinned to the organisation root"
            } else {
                "The root pin ${world.org.rootShort.takeLast(9)} is checked at every connect attempt"
            },
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Telemetry -----------------------------------------------------------------------------------------

@Composable
private fun NovaTelemetrySection(world: NovaWorld) {
    Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
        NovaSectionHeader(title = "Live telemetry", trailing = "per frame")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val twoColumns = maxWidth >= 560.dp
            if (twoColumns) {
                Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                    world.telemetry.chunked(2).forEach { rowOf ->
                        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                            rowOf.forEach { card ->
                                NovaTelemetryCard(card, Modifier.weight(1f), index = world.telemetry.indexOf(card), key = world.scenario)
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                    world.telemetry.forEachIndexed { index, card ->
                        NovaTelemetryCard(card, Modifier.fillMaxWidth(), index = index, key = world.scenario)
                    }
                }
            }
        }
    }
}

@Composable
private fun NovaTelemetryCard(
    telemetry: NovaTelemetry,
    modifier: Modifier,
    index: Int,
    key: Any,
) {
    val colors = FlashTheme.colors
    Column(
        modifier = modifier
            .novaStagger(index, key)
            .clip(NovaCardShape)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, NovaCardShape)
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            NovaIconTile(icon = novaTelemetryIcon(telemetry.icon), tone = telemetry.tone, size = 32.dp, iconSize = 15.dp)
            FlashText(
                text = telemetry.label,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FlashText(
                text = telemetry.value,
                style = FlashTheme.typography.headingSmall,
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
        NovaSpark(values = telemetry.spark, tone = telemetry.tone, modifier = Modifier.fillMaxWidth().height(22.dp))
        NovaMetaLine(text = telemetry.subline)
    }
}

// --- Sessions ------------------------------------------------------------------------------------------

@Composable
private fun NovaSessionsSection(world: NovaWorld, onDemoAction: (String) -> Unit) {
    NovaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlashText(
                text = "Enterprise sessions",
                style = FlashTheme.typography.captionEmphasis,
                color = FlashTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            FlashText(
                text = "${world.sessions.size} live",
                style = FlashTheme.typography.numericDefault,
                color = FlashTheme.colors.textTertiary,
            )
        }
        if (world.sessions.isEmpty()) {
            Spacer(Modifier.height(FlashSpacing.space8))
            NovaMetaLine(text = "No organisation session right now — nothing here is pretending.", icon = FlashIcons.Clock)
        } else {
            world.sessions.forEachIndexed { index, session ->
                if (index > 0) {
                    Spacer(Modifier.height(FlashSpacing.space12))
                    NovaDivider()
                    Spacer(Modifier.height(FlashSpacing.space12))
                }
                NovaSessionRow(session, index = index, key = world.scenario)
            }
            Spacer(Modifier.height(FlashSpacing.space4))
            FlashText(
                text = "Tap a session for its wire detail (demo).",
                style = FlashTheme.typography.metadataDefault,
                color = FlashTheme.colors.textTertiary,
                modifier = Modifier
                    .padding(top = FlashSpacing.space4)
                    .novaClick(onClick = { onDemoAction("Wire detail for ${world.sessions.firstOrNull()?.peerLabel ?: "session"} (demo)") }),
            )
        }
    }
}

@Composable
private fun NovaSessionRow(session: NovaSession, index: Int, key: Any) {
    val colors = FlashTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .novaStagger(index, key),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            NovaKindTile(text = novaKindLabel(session.kind), tone = session.stateTone, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(
                    text = session.peerLabel,
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashText(
                    text = session.detail,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NovaPill(text = session.stateLabel, tone = session.stateTone)
        }
        session.progress?.let { progress ->
            NovaMeter(progress = progress, tone = session.stateTone, flowing = true)
        }
    }
}

// --- Sealing -------------------------------------------------------------------------------------------

@Composable
private fun NovaSealingSection(world: NovaWorld) {
    val sealing = world.sealing
    val tone = sealing.state.tone()
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = FlashIcons.Encryption, tone = tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = "Sealing key",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                FlashText(
                    text = sealing.sentence,
                    style = FlashTheme.typography.captionDefault,
                    color = FlashTheme.colors.textSecondary,
                )
                if (sealing.unverified > 0) {
                    NovaMetaLine(
                        text = "${sealing.unverified} sealed messages could not be opened (unverified drops)",
                        icon = FlashIcons.Clock,
                    )
                }
                if (sealing.readableOff > 0) {
                    NovaMetaLine(
                        text = "${sealing.readableOff} readable messages refused — \"Messages through the hub\" is off",
                        icon = FlashIcons.Clock,
                    )
                }
            }
            NovaPill(text = sealing.state.label, tone = tone)
        }
        if (sealing.fingerprintShort.isNotBlank()) {
            Spacer(Modifier.height(FlashSpacing.space8))
            NovaCopyRow(value = sealing.fingerprintShort, icon = FlashIcons.Encryption, copyLabel = "Copy fingerprint")
            FlashText(
                text = "This is the public fingerprint only — the sealing key itself never leaves this phone.",
                style = FlashTheme.typography.metadataDefault,
                color = FlashTheme.colors.textTertiary,
                modifier = Modifier.padding(top = FlashSpacing.space4),
            )
        }
    }
}

// --- Lease ---------------------------------------------------------------------------------------------

/** Plain words for the lease state — enum names are for code, not for people. */
private fun novaLeaseLabel(kind: NovaLeaseKind): String = when (kind) {
    NovaLeaseKind.Valid -> "Valid"
    NovaLeaseKind.Expiring -> "Expiring"
    NovaLeaseKind.Expired -> "Expired"
    NovaLeaseKind.NeverSynced -> "Never synced"
    NovaLeaseKind.ClockWrong -> "Clock wrong"
    NovaLeaseKind.IdentityMismatch -> "Key mismatch"
}

@Composable
private fun NovaLeaseSection(world: NovaWorld) {
    val lease = world.lease
    val tone = lease.kind.tone()
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = FlashIcons.Clock, tone = tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = "Offline colleague trust",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                FlashText(
                    text = lease.sentence,
                    style = FlashTheme.typography.captionDefault,
                    color = FlashTheme.colors.textSecondary,
                )
            }
            NovaPill(text = novaLeaseLabel(lease.kind), tone = tone, withIcon = false)
        }
        Spacer(Modifier.height(FlashSpacing.space8))
        NovaMeter(
            progress = if (lease.kind == NovaLeaseKind.Valid || lease.kind == NovaLeaseKind.Expiring) {
                (lease.daysLeft / 30f).coerceIn(0.04f, 1f)
            } else {
                0f
            },
            tone = tone,
        )
        NovaMetaLine(
            text = when (lease.kind) {
                NovaLeaseKind.Valid -> "${lease.daysLeft} days left before you reconnect to refresh it"
                NovaLeaseKind.Expiring -> "${lease.daysLeft} days left — reconnect soon to refresh the lease"
                else -> "Colleague trust refreshes on the next successful sync"
            },
            icon = FlashIcons.Clock,
        )
    }
}
