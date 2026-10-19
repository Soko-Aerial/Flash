package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). Page 4: Vault — enrolment, certificates & trust.
 *
 * The invite card (masked token, root pin, TLS promise), the enrolment step rail, the trust-chain
 * inspector (device leaf → relay delegation → organisation root, the TOFU pin), and the vault
 * details: device line, roles and scopes, the step-up approval code and the revocation list state.
 * Tokens and fingerprints render masked or shortened only — the honest-display rule.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

@Composable
internal fun NovaVaultPage(
    world: NovaWorld,
    onDemoAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NovaPageScaffold(page = NovaPage.Vault, world = world, onDemoAction = onDemoAction, modifier = modifier) {
        world.invite?.let { invite ->
            NovaInviteCard(invite = invite, onDemoAction = onDemoAction)
        }
        if (world.enrolSteps.isNotEmpty()) {
            NovaEnrolSection(world.enrolSteps)
        }
        if (world.chain.isNotEmpty()) {
            NovaChainSection(world.chain, world.scenario)
        }
        NovaVaultDetails(world, onDemoAction)
    }
}

// --- Invite --------------------------------------------------------------------------------------------

@Composable
private fun NovaInviteCard(invite: NovaInvite, onDemoAction: (String) -> Unit) {
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = FlashIcons.Hub, tone = NovaTone.INFO)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = "Enrolment invite · ${invite.organisationName}",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                NovaMetaLine(text = invite.host, icon = FlashIcons.Relay)
                NovaMetaLine(
                    text = if (invite.usesTls) {
                        "Opens over TLS — the root pin is checked before anything is stored"
                    } else {
                        "No TLS advertised — Flash refuses to enrol over an open line"
                    },
                    icon = FlashIcons.Wifi,
                )
                NovaMetaLine(text = "Single-use token expires ${invite.expires}", icon = FlashIcons.Clock)
            }
        }
        Spacer(Modifier.height(FlashSpacing.space8))
        NovaCopyRow(value = invite.tokenMasked, icon = FlashIcons.Encryption, copyLabel = "Copy masked token")
        NovaCopyRow(value = invite.rootShort, icon = FlashIcons.Verified, copyLabel = "Copy root pin")
        FlashText(
            text = "The token shows masked because the demo never holds a real one; the root pin is the fingerprint you compare out-of-band before you trust the link.",
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textTertiary,
            modifier = Modifier.padding(top = FlashSpacing.space4),
        )
        Spacer(Modifier.height(FlashSpacing.space8))
        NovaPrimaryButton(
            label = "Redeem invite",
            onClick = { onDemoAction("Redeem invite (demo — enrolment is owned by the enrol dialog)") },
        )
    }
}

// --- Enrolment steps -----------------------------------------------------------------------------------

@Composable
private fun NovaEnrolSection(steps: List<NovaEnrolStep>) {
    NovaCard {
        FlashText(
            text = "Enrolment",
            style = FlashTheme.typography.captionEmphasis,
            color = FlashTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(FlashSpacing.space8))
        steps.forEachIndexed { index, step ->
            NovaEnrolStepRow(step, isLast = index == steps.lastIndex)
        }
    }
}

@Composable
private fun NovaEnrolStepRow(step: NovaEnrolStep, isLast: Boolean) {
    val tone: NovaTone = when (step.state) {
        NovaStepState.Done -> NovaTone.OK
        NovaStepState.Current -> NovaTone.INFO
        NovaStepState.Pending -> NovaTone.NEUTRAL
    }
    val icon: FlashIconSpec = when (step.state) {
        NovaStepState.Done -> FlashIcons.Check
        NovaStepState.Current -> FlashIcons.Clock
        NovaStepState.Pending -> FlashIcons.Clock
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (step.state == NovaStepState.Pending) {
                // Hollow circle for the not-yet steps: outline only, no fill.
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .height(24.dp)
                        .width(24.dp)
                        .clip(CircleShape)
                        .border(FlashDimensions.borderHairline, FlashTheme.colors.borderStrong, CircleShape),
                )
            } else {
                NovaIconTile(icon = icon, tone = tone, size = 24.dp, iconSize = 13.dp, shape = CircleShape)
            }
            if (!isLast) {
                Box(
                    Modifier
                        .padding(vertical = 2.dp)
                        .width(2.dp)
                        .height(28.dp)
                        .clip(NovaHairShape)
                        .background(FlashTheme.colors.borderSubtle),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(
                text = step.headline,
                style = FlashTheme.typography.captionEmphasis,
                color = if (step.state == NovaStepState.Pending) FlashTheme.colors.textSecondary else FlashTheme.colors.textPrimary,
            )
            FlashText(
                text = step.body,
                style = FlashTheme.typography.metadataDefault,
                color = FlashTheme.colors.textSecondary,
            )
        }
        NovaPill(
            text = when (step.state) {
                NovaStepState.Done -> "Done"
                NovaStepState.Current -> "Now"
                NovaStepState.Pending -> "Queued"
            },
            tone = tone,
        )
    }
}

// --- Trust chain ---------------------------------------------------------------------------------------

private fun novaChainIcon(row: NovaChainRow): FlashIconSpec = when {
    row.kindLabel.startsWith("Device") -> FlashIcons.Device
    row.kindLabel.startsWith("Delegation") -> FlashIcons.Encryption
    else -> FlashIcons.Verified
}

@Composable
private fun NovaChainSection(chain: List<NovaChainRow>, key: Any) {
    NovaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlashText(
                text = "Trust chain",
                style = FlashTheme.typography.captionEmphasis,
                color = FlashTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            FlashText(
                text = "leaf → delegation → root",
                style = FlashTheme.typography.metadataDefault,
                color = FlashTheme.colors.textTertiary,
            )
        }
        Spacer(Modifier.height(FlashSpacing.space8))
        chain.forEachIndexed { index, row ->
            NovaChainRowItem(row, index = index, key = key)
            if (index != chain.lastIndex) {
                Spacer(Modifier.height(FlashSpacing.space8))
                NovaDivider()
                Spacer(Modifier.height(FlashSpacing.space8))
            }
        }
    }
}

@Composable
private fun NovaChainRowItem(row: NovaChainRow, index: Int, key: Any) {
    val colors = FlashTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .novaStagger(index, key)
            .then(if (row.revoked) Modifier.alpha(0.62f) else Modifier),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        NovaIconTile(icon = novaChainIcon(row), tone = row.tone, size = 36.dp, iconSize = 16.dp, shape = CircleShape)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(
                text = row.name,
                style = FlashTheme.typography.captionEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(
                text = row.kindLabel,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NovaMetaLine(text = "${row.metaLeft} · ${row.metaRight}", icon = FlashIcons.Clock)
            FlashText(
                text = row.idShort,
                style = FlashTheme.typography.numericDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.note?.let { note ->
                FlashText(
                    text = note,
                    style = FlashTheme.typography.metadataDefault,
                    color = if (row.revoked) colors.textError else colors.textSecondary,
                )
            }
        }
        NovaPill(text = row.stateWord, tone = row.tone)
    }
}

// --- Vault details -------------------------------------------------------------------------------------

@Composable
private fun NovaVaultDetails(world: NovaWorld, onDemoAction: (String) -> Unit) {
    val vault = world.vault
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = FlashIcons.Encryption, tone = NovaTone.NEUTRAL)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = "This device in the organisation",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                FlashText(
                    text = vault.deviceLine,
                    style = FlashTheme.typography.captionDefault,
                    color = FlashTheme.colors.textSecondary,
                )
            }
        }
        if (vault.roles.isNotEmpty()) {
            NovaChipLine(label = "Roles", values = vault.roles)
        }
        if (vault.scopes.isNotEmpty()) {
            NovaChipLine(label = "Scopes", values = vault.scopes)
        }
        Spacer(Modifier.height(FlashSpacing.space8))
        NovaDivider()
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashText(
            text = vault.stepUpTitle,
            style = FlashTheme.typography.captionEmphasis,
            color = FlashTheme.colors.textPrimary,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(NovaTileShape)
                .background(FlashTheme.colors.backgroundSurfaceSubtle)
                .border(FlashDimensions.borderHairline, FlashTheme.colors.borderDefault, NovaTileShape)
                .padding(FlashSpacing.space16),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = vault.stepUpCode,
                style = FlashTheme.typography.headingLarge,
                color = FlashTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
        }
        FlashText(
            text = vault.stepUpBody,
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textTertiary,
            modifier = Modifier.padding(top = FlashSpacing.space4),
        )
        Spacer(Modifier.height(FlashSpacing.space8))
        NovaDivider()
        Spacer(Modifier.height(FlashSpacing.space8))
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = if (vault.revocationTone == NovaTone.OK) FlashIcons.Verified else FlashIcons.Failed, tone = vault.revocationTone, size = 32.dp, iconSize = 14.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(
                    text = vault.revocationTitle,
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                FlashText(
                    text = vault.revocationBody,
                    style = FlashTheme.typography.metadataDefault,
                    color = FlashTheme.colors.textSecondary,
                )
            }
            NovaPill(text = if (vault.revocationTone == NovaTone.OK) "Clear" else "Listed", tone = vault.revocationTone, withIcon = false)
        }
    }
}

/** "Roles: member" / "Scopes: chat:send …" — a labelled wrapped chip line. */
@Composable
private fun NovaChipLine(label: String, values: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
        FlashText(
            text = label,
            style = FlashTheme.typography.metadataEmphasis,
            color = FlashTheme.colors.textSecondary,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        ) {
            values.forEach { value ->
                NovaPill(text = value, tone = NovaTone.NEUTRAL, withIcon = false)
            }
        }
    }
}
