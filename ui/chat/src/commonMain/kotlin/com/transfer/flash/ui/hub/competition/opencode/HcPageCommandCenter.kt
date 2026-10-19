package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlin.math.roundToInt

/**
 * **Page 1 — Hub Command Center.**
 *
 * The one page that answers "is the hub healthy, and am I still trusted?" without a tap. It is a single scrolling
 * column on purpose: a dashboard is read, not navigated. On the desktop the two lower bands sit side by side, because
 * the same page has the room; on a phone they stack, in the same order.
 *
 * Everything is derived from the immutable [HcDeck]. The only live behaviour is the Refresh affordance, which runs a
 * mock progress cycle (`rememberHcBusy`) so the deck demonstrates its own busy state, and the four jump rows, which the
 * showcase wires to the page picker.
 */
@Composable
internal fun HcPageCommandCenter(deck: HcDeck, widthDp: Float, onOpenPage: (HcPage) -> Unit) {
    val wide = hcIsWide(widthDp)
    val (refreshing, refresh) = rememberHcBusy()
    HcScrollColumn {
        HcPageHeader(
            title = if (deck.enrolled) deck.orgName else "Flash Hub",
            subtitle = "${deck.hubName} · ${deck.hubHost} · hub v${deck.hubVersion}",
            glyph = deck.scenario.icon,
            tone = deck.scenario.tone,
            statusLabel = "${deck.link.label} · ${deck.phase.label}",
            statusPulse = deck.link.isLive,
            trailing = { HcIconAction(HcGlyph.RETRY, "Refresh hub status", refresh, enabled = !refreshing) },
        )

        HcRefreshRow(
            sentence = deck.syncSentence,
            versionLine = "directory ${hcEpochLine(deck)}",
            refreshing = refreshing,
            failed = deck.syncStatus == HubSyncStatus.FAILED_OFFLINE ||
                deck.syncStatus == HubSyncStatus.FAILED_OTHER,
            onRefresh = refresh,
            modifier = Modifier.padding(bottom = FlashSpacing.space12),
        )

        deck.banners.forEach { banner ->
            HcBannerCard(banner, Modifier.padding(bottom = FlashSpacing.space12))
        }

        HcBand("This device") { HcIdentityCard(deck) }

        if (deck.enrolled) {
            HcBand("Live telemetry", trailing = if (deck.telemetry.liveSessions > 0) "${deck.telemetry.liveSessions} sessions" else null) {
                HcTileGrid(columns = hcColumns(widthDp, 2, 4), count = 8) { index -> HcTelemetryTile(deck, index) }
            }
            HcBand("Hub link") { HcLinkChartCard(deck) }
        } else {
            HcBand("Live telemetry") {
                HcEmptyState(
                    glyph = HcGlyph.LINK_OFF,
                    headline = "Nothing to report yet",
                    body = "Latency, sealed envelopes and sessions appear here as soon as this device is signed in to a hub.",
                    actionLabel = "Enter an invitation code",
                    onAction = { onOpenPage(HcPage.TRUST) },
                )
            }
        }

        if (deck.enrollment.isNotEmpty()) {
            HcBand("Enrolment", trailing = deck.enrollment.count { it.state == HcStepState.DONE }.let { "$it of ${deck.enrollment.size} done" }) {
                HcCard(glyph = HcGlyph.CLOCK, tone = deck.scenario.tone) { HcStepRail(deck.enrollment) }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                Box(Modifier.weight(1f)) { HcBand("Trust") { HcTrustCard(deck) } }
                Box(Modifier.weight(1f)) { HcBand("What the hub can see") { HcPrivacyCard(deck, onOpenPage) } }
            }
        } else {
            HcBand("Trust") { HcTrustCard(deck) }
            HcBand("What the hub can see") { HcPrivacyCard(deck, onOpenPage) }
        }

        HcBand("Go deeper") { HcJumpCard(deck, onOpenPage) }
    }
}

private fun hcEpochLine(deck: HcDeck): String {
    if (deck.directoryVersion == 0L) return "never synced"
    val epoch = deck.directoryEpoch.ifBlank { "—" }
    return "$epoch · v${hcCount(deck.directoryVersion.toInt())} · ${hcCount(deck.directoryTotal)} colleagues"
}

/** Identity, role and the trust lease, with the lease as a ring so its remaining days read instantly. */
@Composable
private fun HcIdentityCard(deck: HcDeck) {
    val colors = FlashTheme.colors
    val scenarioTone = deck.scenario.tone
    val leaseTone = hcLeaseTone(deck)
    val pinRowTone = if (deck.lease.clockTrusted) HcTone.NEUTRAL else HcTone.DANGER
    HcCard(eyebrow = "This device", title = deck.myName, glyph = HcGlyph.DEVICE, tone = scenarioTone) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            HcAvatar(
                name = deck.myName,
                seed = deck.myDeviceId,
                diameter = FlashDimensions.avatarLg,
                online = deck.link.isLive,
                ringTone = scenarioTone,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(deck.myName, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1)
                FlashText(
                    "device ${deck.myDeviceId}",
                    style = hcMono(FlashTheme.typography.numericDefault),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HcChip(deck.myRoleLabel, tone = if (deck.myRole == HubPersonRole.PERSON) HcTone.NEUTRAL else HcTone.ACCENT, compact = true)
        }

        HcChipFlow {
            HcChip(deck.myDepartment.ifBlank { "No department" }, glyph = HcGlyph.DEPARTMENT, compact = true)
            HcChip(deck.link.label, tone = deck.link.tone, glyph = HcGlyph.RELAY, compact = true)
            HcChip(deck.phase.label, tone = HcTone.NEUTRAL, glyph = HcGlyph.CLOCK, compact = true)
        }

        HcDivider()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space16)) {
            HcRingGauge(
                progress = hcLeaseFraction(deck),
                tone = leaseTone,
                value = if (deck.lease.daysLeft > 0) "${deck.lease.daysLeft}" else "0",
                caption = "days left",
                diameter = 84.dp,
                strokeWidth = 8.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                HcKeyValue("Trust lease", hcLeaseSentence(deck), tone = leaseTone)
                HcKeyValue("Lease source", deck.lease.source)
                HcKeyValue(
                    "Device clock",
                    if (deck.lease.clockTrusted) "Trusted" else "Backwards jump seen",
                    tone = pinRowTone,
                    glyph = HcGlyph.CLOCK,
                )
                HcKeyValue("Offline grace", "${deck.offlineGraceDays} days, then re-verify")
            }
        }

        HcDivider()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            HcPulseDot(deck.sealingKey.state.tone, pulsing = deck.sealingKey.state == HcSealing.SEALED, diameter = 9.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(deck.sealingKey.state.label, style = FlashTheme.typography.metadataEmphasis, color = hcTone(deck.sealingKey.state.tone).on, maxLines = 1)
                FlashText(
                    "key ${hcFingerprint(deck.sealingKey.fingerprint, 2)}",
                    style = hcMono(FlashTheme.typography.numericDefault),
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
            if (deck.sealingKey.pendingFingerprint != null) {
                HcChip("New code", tone = HcTone.WARNING, glyph = HcGlyph.WARNING, compact = true)
            }
            HcChip("${deck.sealingKey.recipientsSealed} sealed", tone = HcTone.SUCCESS, compact = true)
        }
    }
}

@Composable
private fun HcTelemetryTile(deck: HcDeck, index: Int) {
    val t = deck.telemetry
    val latency = t.latencyMs.lastOrNull()?.roundToInt() ?: 0
    val throughput = t.throughputKbps.lastOrNull()?.roundToInt() ?: 0
    when (index) {
        0 -> HcStatTile(
            label = "Round trip",
            value = "$latency",
            unit = "ms",
            glyph = HcGlyph.CLOCK,
            tone = when {
                t.latencyMs.isEmpty() -> HcTone.MUTED
                latency > 200 -> HcTone.WARNING
                else -> HcTone.SUCCESS
            },
            series = t.latencyMs.ifEmpty { null },
            caption = if (t.latencyMs.isEmpty()) "No samples yet" else "last ${t.latencyMs.size} samples",
        )
        1 -> HcStatTile(
            label = "Throughput",
            value = "$throughput",
            unit = "kbps",
            glyph = HcGlyph.UPLOAD,
            tone = if (t.throughputKbps.isEmpty()) HcTone.MUTED else HcTone.ACCENT,
            series = t.throughputKbps.ifEmpty { null },
            caption = "organisation traffic",
        )
        2 -> HcStatTile(
            label = "Sealed envelopes",
            value = hcCount(t.sealedCount),
            glyph = HcGlyph.SHIELD,
            tone = if (t.sealedCount == 0) HcTone.MUTED else HcTone.SUCCESS,
            caption = "${hcCount(t.readableCount)} are still readable on the hub",
        )
        3 -> HcStatTile(
            label = "Live sessions",
            value = "${t.liveSessions}",
            glyph = HcGlyph.DEVICE,
            tone = if (t.liveSessions == 0) HcTone.MUTED else HcTone.ACCENT,
            caption = "devices signed in now",
        )
        4 -> HcStatTile(
            label = "Held by the hub",
            value = "${t.mailboxHeld}",
            glyph = HcGlyph.MAILBOX,
            tone = if (t.mailboxHeld == 0) HcTone.MUTED else HcTone.WARNING,
            caption = "delivered when the recipient signs in",
        )
        5 -> HcStatTile(
            label = "Dropped unverified",
            value = "${t.droppedUnverified}",
            glyph = HcGlyph.WARNING,
            tone = if (t.droppedUnverified == 0) HcTone.MUTED else HcTone.DANGER,
            caption = "never stored, never delivered",
        )
        6 -> HcStatTile(
            label = "Directory",
            value = hcCount(deck.directoryTotal),
            glyph = HcGlyph.DEPARTMENT,
            tone = if (deck.directoryTotal == 0) HcTone.MUTED else HcTone.NEUTRAL,
            caption = hcEpochLine(deck),
        )
        else -> HcStatTile(
            label = "Session uptime",
            value = hcElapsed(t.uptimeMs),
            glyph = HcGlyph.BOLT,
            tone = HcTone.NEUTRAL,
            caption = "${hcCount(t.envelopesOut)} out · ${hcCount(t.envelopesIn)} in",
        )
    }
}

/** Latency and throughput over the retained sample window. Both charts share one card so they are read together. */
@Composable
private fun HcLinkChartCard(deck: HcDeck) {
    val colors = FlashTheme.colors
    val t = deck.telemetry
    val retryLine = if (t.retryAttempt > 0) {
        "Attempt ${t.retryAttempt} refused; the next try is in ${t.retryInSeconds} s."
    } else {
        "The socket is live; no attempt has been refused."
    }
    HcCard(eyebrow = "Last ${t.latencyMs.size.coerceAtLeast(1)} samples", title = "Hub link", glyph = HcGlyph.RELAY, tone = deck.link.tone) {
        if (t.latencyMs.isEmpty()) {
            FlashText(
                "No samples yet. The hub has not answered since this device started.",
                style = FlashTheme.typography.bodyDefault,
                color = colors.textSecondary,
            )
        } else {
            HcChartLegend("Round trip", "${t.latencyMs.last().roundToInt()} ms", HcTone.ACCENT)
            HcSparkline(t.latencyMs, hcTone(HcTone.ACCENT).strong, Modifier.fillMaxWidth().height(52.dp))
            HcChartLegend("Throughput", "${t.throughputKbps.lastOrNull()?.roundToInt() ?: 0} kbps", HcTone.SUCCESS, Modifier.padding(top = FlashSpacing.space8))
            HcSparkline(t.throughputKbps, hcTone(HcTone.SUCCESS).strong, Modifier.fillMaxWidth().height(52.dp))
        }
        HcDivider()
        HcKeyValue("Envelopes", "${hcCount(t.envelopesOut)} sent · ${hcCount(t.envelopesIn)} received")
        HcKeyValue("Retry", retryLine, tone = if (t.retryAttempt > 0) HcTone.WARNING else HcTone.NEUTRAL)
    }
}

@Composable
private fun HcChartLegend(label: String, value: String, tone: HcTone, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
        HcPulseDot(tone, diameter = 7.dp)
        FlashText(label, Modifier.weight(1f), style = FlashTheme.typography.metadataEmphasis, color = colors.textSecondary, maxLines = 1)
        FlashText(value, style = hcMono(FlashTheme.typography.numericEmphasis), color = colors.textPrimary, maxLines = 1)
    }
}

/** The credential chain plus the five checks that decide whether the hub path may be used at all. */
@Composable
private fun HcTrustCard(deck: HcDeck) {
    val cert = deck.certificate
    val delegation = deck.delegation
    val statement = deck.statement
    val revocations = deck.revocations
    val chainRow = if (cert != null) HcTone.SUCCESS else HcTone.MUTED
    HcCard(eyebrow = "Verified without the hub", title = "Credential chain", glyph = HcGlyph.SHIELD, tone = chainRow) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HcChainNode(
                eyebrow = "This device",
                title = cert?.role?.replaceFirstChar { it.uppercase() } ?: "No certificate",
                fingerprint = cert?.spkiFingerprint ?: "—",
                glyph = HcGlyph.DEVICE,
                tone = if (cert != null) HcTone.SUCCESS else HcTone.MUTED,
            )
            HcChainLink(chainRow)
            HcChainNode(
                eyebrow = "Issued by",
                title = deck.orgName,
                fingerprint = deck.orgRootFingerprint,
                glyph = HcGlyph.HUB,
                tone = if (delegation != null || cert != null) HcTone.SUCCESS else HcTone.MUTED,
            )
            HcChainLink(if (revocations?.rootPinOk == true) HcTone.SUCCESS else HcTone.MUTED)
            HcChainNode(
                eyebrow = "Revocation",
                title = revocations?.let { "List v${it.version}" } ?: "Not held",
                fingerprint = revocations?.let { "${it.entries} entries" } ?: "—",
                glyph = HcGlyph.WARNING,
                tone = if (revocations != null) HcTone.ACCENT else HcTone.MUTED,
            )
        }
        HcDivider()
        HcVerdictRow(
            "Certificate signature",
            ok = cert?.signatureVerified == true,
            detail = cert?.let { "serial ${it.serialHex} · ${it.algorithm}" },
        )
        HcVerdictRow(
            "Member statement matches this device's key",
            ok = statement?.bindingOk == true,
            detail = statement?.let { "statement ${it.serial} · ${hcAgo(deck.nowMs, it.issuedAtMs)}" },
        )
        HcVerdictRow(
            "Certificates are in date",
            ok = cert != null && cert.validToMs > deck.nowMs,
            detail = cert?.let { "${hcUntil(deck.nowMs, it.validToMs)} · issued ${hcAgo(deck.nowMs, it.validFromMs)}" },
        )
        HcVerdictRow(
            "Not on the revocation list",
            ok = revocations != null && !revocations.listsThisDevice,
            detail = revocations?.let { "fetched ${hcAgo(deck.nowMs, it.fetchedAtMs)}" },
        )
        HcVerdictRow(
            "Organisation root pin",
            ok = revocations?.rootPinOk == true,
            detail = when {
                revocations == null -> null
                revocations.hashed -> "hashed list v3, root pin matched"
                else -> "root pin matched"
            },
        )
        HcFingerprint(deck.orgRootFingerprint, groups = 8, tone = if (cert != null) HcTone.SUCCESS else HcTone.MUTED)
    }
}

/**
 * What leaves this device. Written as settings, not as pass/fail: "presence is visible" is a privacy *cost* that a
 * person chose, so a green tick would be a lie. The chip word carries the meaning.
 */
@Composable
private fun HcPrivacyCard(deck: HcDeck, onOpenPage: (HcPage) -> Unit) {
    val p = deck.privacy
    HcCard(eyebrow = "Device setting", title = "What the hub can see", glyph = HcGlyph.SLIDERS) {
        HcPrivacyRow("Presence is visible to colleagues", on = p.presenceVisible, onText = "Visible", offText = "Hidden", goodWhenOn = false)
        HcPrivacyRow("This device is listed in the directory", on = p.directoryVisible, onText = "Listed", offText = "Restricted", goodWhenOn = false)
        HcPrivacyRow("Hidden departments are honoured", on = p.hiddenDepartments, onText = "Honoured", offText = "Not set", goodWhenOn = true)
        HcPrivacyRow("Group content is sealed end to end", on = p.sealableOnly, onText = "Sealed", offText = "Readable", goodWhenOn = true)
        HcPrivacyRow("Hub content is allowed on this device", on = p.hubContentAllowed, onText = "Allowed", offText = "Device only", goodWhenOn = true)
        HcSoftRule()
        FlashText(
            "Announcements are stored readable on the hub by design. Nothing else here is.",
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textTertiary,
        )
        HcTextAction("Open the trust vault", { onOpenPage(HcPage.TRUST) }, tone = HcTone.ACCENT)
    }
}

@Composable
private fun HcPrivacyRow(label: String, on: Boolean, onText: String, offText: String, goodWhenOn: Boolean) {
    val colors = FlashTheme.colors
    val settled = on == goodWhenOn
    Row(
        Modifier.fillMaxWidth().padding(vertical = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(label, Modifier.weight(1f), style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 2)
        HcChip(if (on) onText else offText, tone = if (settled) HcTone.SUCCESS else HcTone.WARNING, compact = true)
    }
}

/** Four rows that move the judge to another page — the only cross-page navigation the deck has. */
@Composable
private fun HcJumpCard(deck: HcDeck, onOpenPage: (HcPage) -> Unit) {
    HcCard(glyph = HcGlyph.CHEVRON, eyebrow = "Five pages", title = "Where to go next") {
        HcJumpRow(
            glyph = HcGlyph.PERSON,
            title = "Organisation directory",
            subtitle = "${hcCount(deck.visiblePeople.size)} colleagues in ${deck.departments.size} departments",
            onClick = { onOpenPage(HcPage.DIRECTORY) },
        )
        HcJumpRow(
            glyph = HcGlyph.ANNOUNCEMENT,
            title = "Announcements",
            subtitle = if (deck.unreadAnnouncements > 0) "${deck.unreadAnnouncements} unread" else "All read",
            onClick = { onOpenPage(HcPage.ANNOUNCEMENTS) },
        )
        HcJumpRow(
            glyph = HcGlyph.KEY,
            title = "Trust vault",
            subtitle = "Certificate, sealing keys and revocations",
            onClick = { onOpenPage(HcPage.TRUST) },
        )
        HcJumpRow(
            glyph = HcGlyph.ROSTER,
            title = "Rosters and relay bridge",
            subtitle = "${deck.rosters.size} verified rosters · ${deck.bridges.count { it.viaHub }} conversations through the hub",
            onClick = { onOpenPage(HcPage.ROSTERS) },
        )
    }
}

@Composable
private fun HcJumpRow(glyph: HcGlyph, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = FlashTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(FlashShapes.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(FlashShapes.radius8)).background(colors.backgroundSurfaceStrong),
            contentAlignment = Alignment.Center,
        ) { HcGlyphIcon(glyph, tint = colors.textSecondary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(title, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1)
            FlashText(subtitle, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HcChevron(colors.textTertiary)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Lease helpers (shared with the trust page through this file's `internal` visibility)
// ---------------------------------------------------------------------------------------------------------------

internal fun hcLeaseTone(deck: HcDeck): HcTone = when {
    deck.lease.untilMs <= 0L -> HcTone.MUTED
    deck.lease.daysLeft >= 14 -> HcTone.SUCCESS
    deck.lease.daysLeft >= 5 -> HcTone.WARNING
    else -> HcTone.DANGER
}

internal fun hcLeaseFraction(deck: HcDeck): Float {
    if (deck.lease.untilMs <= 0L) return 0f
    return (deck.lease.daysLeft / 30f).coerceIn(0f, 1f)
}

internal fun hcLeaseSentence(deck: HcDeck): String = when {
    deck.lease.untilMs <= 0L -> "No snapshot"
    deck.lease.daysLeft >= 1 -> "${deck.lease.daysLeft} days · ${hcUntil(deck.nowMs, deck.lease.untilMs)}"
    else -> hcUntil(deck.nowMs, deck.lease.untilMs)
}
