package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * **Page 4 — Enterprise Enrolment, Certificates and Trust Vault.**
 *
 * This is the page the shipped Hub tab has no room for: the whole trust story, shown as the chain it is. It is a
 * scrolling vault rather than a list/detail pair, because the objects are few and each one is *read* rather than
 * opened.
 *
 * Three things it deliberately does:
 *
 * 1. **Never claims secrecy it does not have.** The sealing card says what the hub can and cannot see, in plain words.
 * 2. **Shows validity as a window**, not just a date, so "expires in 304 days" and "expired 2 days ago" read the same way.
 * 3. **Ends with the exit.** Every trust state has a way out — renew, request an invitation, or leave — so a person is
 *    never stuck on a screen that only reports a problem.
 */
@Composable
internal fun HcPageTrust(deck: HcDeck, widthDp: Float) {
    val wide = hcIsWide(widthDp)
    var pendingKeyConfirmed by remember(deck.scenario) { mutableStateOf(false) }
    var leaving by remember(deck.scenario) { mutableStateOf(false) }
    val (fetching, fetch) = rememberHcBusy(1100)
    val certTone = hcCertificateTone(deck)

    HcScrollColumn {
        HcPageHeader(
            title = "Trust vault",
            subtitle = "Certificate, delegation, sealing keys and the revocation list",
            glyph = HcGlyph.SHIELD,
            tone = certTone,
            statusLabel = hcCertificateSentence(deck),
            statusPulse = certTone == HcTone.SUCCESS,
            trailing = { HcIconAction(HcGlyph.RETRY, "Check the certificate again", fetch, enabled = !fetching) },
        )

        deck.banners.forEach { banner -> HcBannerCard(banner, Modifier.padding(bottom = FlashSpacing.space12)) }

        if (deck.enrollment.isNotEmpty()) {
            HcBand(
                title = if (deck.enrolled) "Renewal steps" else "Enrolment steps",
                trailing = "${deck.enrollment.count { it.state == HcStepState.DONE }} of ${deck.enrollment.size}",
            ) {
                HcCard(glyph = HcGlyph.CLOCK, tone = deck.scenario.tone) { HcStepRail(deck.enrollment) }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                Box(Modifier.weight(1f)) { HcBand("Device certificate") { HcCertificateCard(deck, fetching) } }
                Box(Modifier.weight(1f)) { HcBand("Enrolment delegation") { HcDelegationCard(deck) } }
            }
        } else {
            HcBand("Device certificate") { HcCertificateCard(deck, fetching) }
            HcBand("Enrolment delegation") { HcDelegationCard(deck) }
        }

        HcBand("Sealing keys", trailing = if (deck.sealingKey.pendingFingerprint != null) "one to confirm" else null) {
            HcSealingCard(deck, pendingKeyConfirmed, onConfirm = { pendingKeyConfirmed = true })
        }

        HcBand("Revocation list") { HcRevocationCard(deck, fetching, fetch) }

        HcBand("Offline trust") { HcOfflineTrustCard(deck) }

        HcBand("Leaving") { HcLeaveCard(deck, leaving) { leaving = !leaving } }

        if (deck.delegation == null && deck.certificate == null && deck.enrollment.isEmpty()) {
            HcEmptyState(
                glyph = HcGlyph.LINK_OFF,
                headline = "No credentials on this device",
                body = "There is nothing to show in the vault yet. Once an invitation is redeemed, the certificate, its delegation and the sealing key appear here.",
                actionLabel = "Enter an invitation code",
                onAction = {},
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Certificate
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HcCertificateCard(deck: HcDeck, checking: Boolean) {
    val colors = FlashTheme.colors
    val cert = deck.certificate
    if (cert == null) {
        HcEmptyState(
            glyph = HcGlyph.CERTIFICATE,
            headline = "No device certificate",
            body = "This device has never received one. The vault fills after an invitation is redeemed.",
            tone = HcTone.MUTED,
        )
        return
    }
    val nowMs = deck.nowMs
    val expired = cert.validToMs <= nowMs
    val lifetime = (cert.validToMs - cert.validFromMs).coerceAtLeast(1L)
    val used = (nowMs - cert.validFromMs).coerceIn(0L, lifetime)
    val fraction = used.toFloat() / lifetime.toFloat()
    HcCard(
        eyebrow = "Signed by ${deck.orgName}",
        title = "Device certificate",
        glyph = HcGlyph.CERTIFICATE,
        tone = hcCertificateTone(deck),
        action = { HcChip(if (expired) "Expired" else "Valid", tone = if (expired) HcTone.DANGER else HcTone.SUCCESS, compact = true) },
    ) {
        HcMeter(
            label = if (expired) "Certificate lifetime used" else "Certificate lifetime",
            fraction = fraction,
            valueText = if (expired) "expired ${hcAgo(nowMs, cert.validToMs)}" else hcUntil(nowMs, cert.validToMs),
            tone = hcCertificateTone(deck),
            glyph = HcGlyph.CLOCK,
        )
        HcDivider()
        HcKeyValue("Role in the organisation", cert.role.replaceFirstChar { it.uppercase() })
        HcKeyValue("Department", deck.departments.firstOrNull { it.id == cert.department }?.name ?: cert.department)
        HcKeyValue("Serial", cert.serialHex, mono = true, glyph = HcGlyph.KEY)
        HcKeyValue("Algorithm", cert.algorithm)
        HcKeyValue("Valid from", "${hcAgo(nowMs, cert.validFromMs)}", mono = false)
        HcKeyValue("Valid to", "${hcUntil(nowMs, cert.validToMs)}", tone = hcCertificateTone(deck))
        HcDivider()
        HcVerdictRow("Signature by the issuing delegation", ok = cert.signatureVerified, detail = "issuer ${cert.issuerFingerprint}")
        HcVerdictRow("Public key of this device", ok = true, detail = cert.spkiFingerprint)
        HcVerdictRow(
            "In date",
            ok = !expired,
            detail = if (expired) "expired ${hcAgo(nowMs, cert.validToMs)}" else hcUntil(nowMs, cert.validToMs),
        )
        HcFingerprint(cert.spkiFingerprint, groups = 4, tone = hcCertificateTone(deck))
        HcSoftRule()
        if (expired || checking) {
            HcButton(
                label = if (checking) "Checking…" else "Request a renewal",
                onClick = {},
                glyph = HcGlyph.RETRY,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            )
            FlashText(
                "A renewal needs the hub to sign again, so it goes through the organisation. Nothing is sent from this page without a tap.",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        } else {
            HcKeyValue("Renews", "Automatically, 14 days before it expires")
        }
    }
}

@Composable
private fun HcDelegationCard(deck: HcDeck) {
    val colors = FlashTheme.colors
    val delegation = deck.delegation
    if (delegation == null) {
        HcEmptyState(
            glyph = HcGlyph.VERIFIED,
            headline = "No delegation held",
            body = "This device holds a certificate but not the delegation that issued it. That is normal for a member; only administrators keep one.",
            tone = HcTone.MUTED,
        )
        return
    }
    val budget = (delegation.issuedDevices.toFloat() / delegation.maxDevices.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    HcCard(eyebrow = "Administrator credential", title = "Enrolment delegation", glyph = HcGlyph.KEY, tone = HcTone.ACCENT) {
        HcMeter(
            label = "Devices issued under this delegation",
            fraction = budget,
            valueText = "${delegation.issuedDevices} of ${delegation.maxDevices}",
            tone = if (budget > 0.9f) HcTone.WARNING else HcTone.ACCENT,
            glyph = HcGlyph.DEVICE,
        )
        HcDivider()
        HcKeyValue("Serial", "#${delegation.serial}", mono = true)
        HcKeyValue("Valid from", hcAgo(deck.nowMs, delegation.validFromMs))
        HcKeyValue("Valid to", hcUntil(deck.nowMs, delegation.validToMs), tone = HcTone.ACCENT)
        HcKeyValue("Delegate", delegation.delegateFingerprint, mono = true)
        HcDivider()
        FlashText("Scopes", style = FlashTheme.typography.metadataEmphasis, color = colors.textTertiary)
        HcChipFlow {
            delegation.scopes.forEach { scope -> HcChip(hcScopeLabel(scope), tone = HcTone.NEUTRAL, compact = true) }
        }
        HcVerdictRow("Signature by the organisation root", ok = delegation.signatureVerified, detail = deck.orgRootFingerprint)
        HcSoftRule()
        FlashText(
            "A delegation can only do what its scopes allow. Devices are counted against its budget as they are enrolled.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

private fun hcScopeLabel(scope: String): String = when (scope) {
    "enrol:member" -> "Enrol members"
    "enrol:dept_admin" -> "Enrol department heads"
    "enrol:admin" -> "Enrol administrators"
    "revoke:member" -> "Revoke members"
    "revoke:any" -> "Revoke any device"
    else -> scope.replace(':', ' ')
}

// ---------------------------------------------------------------------------------------------------------------
// Sealing
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HcSealingCard(deck: HcDeck, confirmed: Boolean, onConfirm: () -> Unit) {
    val colors = FlashTheme.colors
    val key = deck.sealingKey
    val pending = key.pendingFingerprint
    HcCard(
        eyebrow = "Content key",
        title = "Sealing this device's content",
        glyph = HcGlyph.SHIELD,
        tone = key.state.tone,
        action = { HcChip(key.state.label, tone = key.state.tone, compact = true) },
    ) {
        HcKeyValue("Current key", hcFingerprint(key.fingerprint, 4), mono = true, glyph = HcGlyph.KEY)
        HcKeyValue("Rotated", if (key.rotatedAtMs == 0L) "Never" else hcAgo(deck.nowMs, key.rotatedAtMs))
        HcDivider()
        HcMeter(
            label = "Colleagues who can read what you send",
            fraction = key.recipientsSealed.toFloat() / (key.recipientsSealed + key.recipientsWaiting).coerceAtLeast(1).toFloat(),
            valueText = "${key.recipientsSealed} of ${key.recipientsSealed + key.recipientsWaiting}",
            tone = if (key.recipientsWaiting > 0) HcTone.WARNING else HcTone.SUCCESS,
            glyph = HcGlyph.DEVICE,
        )
        if (pending != null && !confirmed) {
            HcBannerCard(
                HcBanner(
                    id = "seal-pending",
                    tone = HcTone.DANGER,
                    glyph = HcGlyph.WARNING,
                    title = "A new sealing code needs your confirmation",
                    body = "Messages to the colleague who published this code are paused until you confirm it matches what they read out.",
                    action = "Confirm ${hcFingerprint(pending, 2)}",
                ),
                onAction = onConfirm,
            )
        } else if (pending != null) {
            HcVerdictRow("New sealing code confirmed", ok = true, detail = hcFingerprint(pending, 4))
        } else {
            HcVerdictRow("Every colleague's code matches", ok = true, detail = "no rotation is pending")
        }
        HcSoftRule()
        FlashText(
            "Flash seals direct messages with the recipient's published key before they reach the hub. The hub carries the sealed envelope; it cannot open it. Group content is the exception, and the app says so on the conversation itself.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Revocations
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HcRevocationCard(deck: HcDeck, fetching: Boolean, onFetch: () -> Unit) {
    val colors = FlashTheme.colors
    val revocations = deck.revocations
    if (revocations == null) {
        HcEmptyState(
            glyph = HcGlyph.WARNING,
            headline = "No revocation list held",
            body = "This device has not fetched the organisation's revocation list. Nothing can be checked against it until it has.",
            actionLabel = "Fetch it now",
            onAction = onFetch,
            tone = HcTone.WARNING,
        )
        return
    }
    val listsMe = revocations.listsThisDevice
    HcCard(
        eyebrow = "Signed by the organisation",
        title = "Revocation list v${revocations.version}",
        glyph = HcGlyph.WARNING,
        tone = if (listsMe) HcTone.DANGER else HcTone.SUCCESS,
        action = { HcIconAction(HcGlyph.RETRY, "Fetch the revocation list", onFetch, enabled = !fetching) },
    ) {
        HcKeyValue("Entries", "${revocations.entries} revoked device${if (revocations.entries == 1) "" else "s"}")
        HcKeyValue("Fetched", hcAgo(deck.nowMs, revocations.fetchedAtMs))
        HcKeyValue("Form", if (revocations.hashed) "Hashed (v3)" else "Plain")
        HcDivider()
        HcVerdictRow("Organisation root pin", ok = revocations.rootPinOk, detail = deck.orgRootFingerprint)
        HcVerdictRow(
            "This device is not listed",
            ok = !listsMe,
            detail = if (listsMe) "this certificate is in the list" else "checked against the current list",
        )
        if (listsMe) {
            HcBannerCard(
                HcBanner(
                    id = "rev-self",
                    tone = HcTone.DANGER,
                    glyph = HcGlyph.WARNING,
                    title = "The organisation removed this device",
                    body = "Chat on the local network still works between paired devices. The hub path stays closed until a new invitation is redeemed.",
                    action = "Request an invitation",
                    secondaryAction = "Keep using the LAN",
                ),
            )
        }
        HcSoftRule()
        FlashText(
            "The list is fetched and pinned before it is trusted, so a hostile hub cannot invent a revocation for another device.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Offline trust and exit
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HcOfflineTrustCard(deck: HcDeck) {
    val colors = FlashTheme.colors
    HcCard(eyebrow = "Plan B trust lease", title = "Working without the hub", glyph = HcGlyph.CLOCK, tone = hcLeaseTone(deck)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space16)) {
            HcRingGauge(
                progress = hcLeaseFraction(deck),
                tone = hcLeaseTone(deck),
                value = "${deck.lease.daysLeft}",
                caption = "days",
                diameter = 76.dp,
                strokeWidth = 8.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                HcKeyValue("Lease", hcLeaseSentence(deck), tone = hcLeaseTone(deck))
                HcKeyValue("Source", deck.lease.source)
                HcKeyValue("Grace after that", "${deck.offlineGraceDays} days offline, then a re-check")
            }
        }
        HcDivider()
        HcVerdictRow("Clock is trustworthy", ok = deck.lease.clockTrusted, detail = "a backwards jump voids the lease")
        HcVerdictRow("Directory snapshot usable offline", ok = deck.directoryVersion > 0L, detail = "epoch ${deck.directoryEpoch.ifBlank { "—" }} · v${hcCount(deck.directoryVersion.toInt())}")
        FlashText(
            "Everything on this page is verified with keys already on the device, so it stays true when the hub is unreachable.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun HcLeaveCard(deck: HcDeck, confirming: Boolean, onToggle: () -> Unit) {
    val colors = FlashTheme.colors
    HcCard(eyebrow = "Not a trap", title = "Sign out or leave", glyph = HcGlyph.LINK_OFF, tone = HcTone.NEUTRAL) {
        HcKeyValue("Chat with paired devices", "Keeps working without an organisation")
        HcKeyValue("Directory and announcements", "Stop arriving while signed out")
        HcKeyValue("Underlying credential", "Stays on the device until it is removed here")
        HcDivider()
        if (confirming) {
            HcBannerCard(
                HcBanner(
                    id = "leave-confirm",
                    tone = HcTone.DANGER,
                    glyph = HcGlyph.WARNING,
                    title = "Sign out of ${deck.hubName}?",
                    body = "The organisation's directory, announcements and hub messaging stop on this device. Paired devices and local transfers are untouched. Enrolling again needs a fresh invitation.",
                    action = "Yes, sign out",
                    secondaryAction = "Keep it",
                ),
                onAction = onToggle,
                onSecondaryAction = onToggle,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                HcButton("Sign out", onToggle, glyph = HcGlyph.LINK_OFF, primary = false, tone = HcTone.DANGER, modifier = Modifier.weight(1f))
                HcButton("Keep my access", {}, primary = true, modifier = Modifier.weight(1f))
            }
        }
        FlashText(
            if (deck.posting == HubPostingRights.None) {
                "This device posts no announcements, so signing out costs it nothing but the feed."
            } else {
                "This device holds posting rights; signing out ends them with the credential."
            },
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Small derivations
// ---------------------------------------------------------------------------------------------------------------

internal fun hcCertificateTone(deck: HcDeck): HcTone {
    val cert = deck.certificate ?: return HcTone.MUTED
    val now = deck.nowMs
    val left = cert.validToMs - now
    return when {
        left <= 0L -> HcTone.DANGER
        deck.link == HcLink.REVOKED -> HcTone.DANGER
        left < 30L * 86_400_000L -> HcTone.WARNING
        else -> HcTone.SUCCESS
    }
}

internal fun hcCertificateSentence(deck: HcDeck): String {
    val cert = deck.certificate ?: return "No certificate on this device"
    return when {
        cert.validToMs <= deck.nowMs -> "Expired ${hcAgo(deck.nowMs, cert.validToMs)}"
        deck.link == HcLink.REVOKED -> "Removed by the organisation"
        else -> "Valid, ${hcUntil(deck.nowMs, cert.validToMs)} · serial ${cert.serialHex}"
    }
}
