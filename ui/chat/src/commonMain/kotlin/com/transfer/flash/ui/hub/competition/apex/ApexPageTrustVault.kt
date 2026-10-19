package com.transfer.flash.ui.hub.competition.apex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale

/**
 * APEX ZENITH — Page 4: Enterprise Enrollment, Certificates & Trust Vault.
 *
 * Implements:
 * - Onboarding and invite redemption flow for unenrolled devices (EnrollmentInvite, EnterpriseEnrollmentClient, EnrollmentResult)
 * - Interactive Certificate Hierarchy Inspector (DeviceCert, DelegationCert, OrgMemberStatement)
 * - Security step-up & role elevation simulator (HubRoles, HubStepUp)
 * - Revocation status & CRL inspector (RevocationList)
 */
@Composable
internal fun ApexPageTrustVault(
    deck: ApexHubDeck,
    modifier: Modifier = Modifier,
    onSimulateEnroll: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    var selectedCertId by remember { mutableStateOf<String?>("cert-device") }
    var inviteTokenInput by remember { mutableStateOf("APEX-INV-9941-X7K2") }
    var stepUpRequested by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
    ) {
        // --- If Unenrolled: Onboarding & Invite Redemption Flow ---
        if (deck.scenario == ApexScenario.UNENROLLED) {
            ApexUnenrolledOnboardingCard(
                inviteToken = inviteTokenInput,
                onInviteTokenChange = { inviteTokenInput = it },
                onEnroll = onSimulateEnroll,
            )
            return@Column
        }

        // --- 1. Trust Vault Status Header ---
        ApexCard(
            tone = if (deck.scenario == ApexScenario.CERT_REVOKED) ApexTone.DANGER else ApexTone.SEALED,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    ) {
                        FlashIcon(
                            icon = if (deck.scenario == ApexScenario.CERT_REVOKED) FlashIcons.Failed else FlashIcons.Verified,
                            tint = if (deck.scenario == ApexScenario.CERT_REVOKED) colors.textError else colors.statusOnline,
                            size = FlashDimensions.iconMd,
                        )
                        FlashText(
                            text = if (deck.scenario == ApexScenario.CERT_REVOKED) "Trust Chain Compromised / Revoked" else "Cryptographic Trust Vault",
                            style = FlashTheme.typography.headingMedium,
                            color = colors.textPrimary,
                        )
                    }
                    Spacer(Modifier.height(FlashSpacing.space4))
                    FlashText(
                        text = if (deck.scenario == ApexScenario.CERT_REVOKED)
                            "Device certificate revoked by security policy in CRL seq #49102"
                        else
                            "Hardware-bound cryptographic identity verified against Root CA",
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }

                ApexBadge(
                    text = if (deck.scenario == ApexScenario.CERT_REVOKED) "REVOKED" else "CHAIN VALID",
                    tone = if (deck.scenario == ApexScenario.CERT_REVOKED) ApexTone.DANGER else ApexTone.ONLINE,
                )
            }
        }

        // --- 2. Interactive Certificate Chain Inspector ---
        ApexCard {
            ApexSectionHeader(
                title = "Certificate Hierarchy Chain",
                actionText = "Verify CRL",
                onAction = {},
            )
            Spacer(Modifier.height(FlashSpacing.space8))

            deck.certChain.forEachIndexed { index, cert ->
                val isSelected = selectedCertId == cert.id
                ApexCertChainNode(
                    cert = cert,
                    isSelected = isSelected,
                    isLast = index == deck.certChain.lastIndex,
                    onClick = { selectedCertId = cert.id },
                )
            }
        }

        // --- 3. Selected Certificate Details ---
        val activeCert = deck.certChain.find { it.id == selectedCertId } ?: deck.certChain.firstOrNull()
        if (activeCert != null) {
            ApexCard {
                ApexSectionHeader(title = "Certificate Details (${activeCert.level})")
                Spacer(Modifier.height(FlashSpacing.space8))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    ApexTile(
                        label = "Status",
                        value = if (activeCert.isRevoked) "REVOKED" else "VALID",
                        caption = activeCert.algorithm,
                        tone = if (activeCert.isRevoked) ApexTone.DANGER else ApexTone.ONLINE,
                        icon = if (activeCert.isRevoked) FlashIcons.Failed else FlashIcons.Check,
                        modifier = Modifier.weight(1f),
                    )
                    ApexTile(
                        label = "Serial Number",
                        value = activeCert.serialNumber,
                        caption = "Issuer: ${activeCert.issuerName.take(20)}...",
                        tone = ApexTone.STANDBY,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(FlashSpacing.space12))
                ApexCodeBox(
                    label = "Public Key Fingerprint",
                    code = activeCert.fingerprint,
                )
            }
        }

        // --- 4. Role Step-Up Simulator (HubRoles, HubStepUp) ---
        ApexCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FlashText(
                        text = "Security Role Step-Up Elevation",
                        style = FlashTheme.typography.headingSmall,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(FlashSpacing.space2))
                    FlashText(
                        text = if (stepUpRequested)
                            "Challenge completed: Elevated to Temporary Security Officer for 60m"
                        else
                            "Current: Staff Cryptographer. Request hardware challenge for elevated admin access.",
                        style = FlashTheme.typography.captionDefault,
                        color = if (stepUpRequested) colors.textSuccess else colors.textSecondary,
                    )
                }

                Spacer(Modifier.width(FlashSpacing.space12))

                ApexButton(
                    text = if (stepUpRequested) "Elevated" else "Step-Up",
                    tone = if (stepUpRequested) ApexTone.ONLINE else ApexTone.BUSY,
                    icon = FlashIcons.Verified,
                    onClick = { stepUpRequested = !stepUpRequested },
                )
            }
        }
    }
}

@Composable
private fun ApexCertChainNode(
    cert: ApexCertNode,
    isSelected: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    val bg = if (isSelected) colors.accentPrimary.copy(alpha = 0.12f) else colors.backgroundSurfaceStrong.copy(alpha = 0.4f)
    val border = if (isSelected) colors.accentPrimary else colors.borderSubtle

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ApexDims.tileRadius))
                .background(bg)
                .border(FlashDimensions.borderHairline, border, RoundedCornerShape(ApexDims.tileRadius))
                .flashPressScale(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(FlashSpacing.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (cert.isRevoked) colors.textError.copy(alpha = 0.2f) else colors.statusOnline.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = if (cert.isRevoked) FlashIcons.Failed else FlashIcons.Verified,
                    tint = if (cert.isRevoked) colors.textError else colors.statusOnline,
                    size = FlashDimensions.iconSm,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                FlashText(
                    text = cert.subjectName,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                )
                FlashText(
                    text = "${cert.level} • Serial: ${cert.serialNumber}",
                    style = apexMono(FlashTheme.typography.captionDefault),
                    color = colors.textSecondary,
                )
            }

            ApexBadge(
                text = if (cert.isRevoked) "REVOKED" else "VALID",
                tone = if (cert.isRevoked) ApexTone.DANGER else ApexTone.ONLINE,
            )
        }

        if (!isLast) {
            Box(
                modifier = Modifier
                    .padding(start = 28.dp)
                    .width(2.dp)
                    .height(14.dp)
                    .background(colors.borderDefault),
            )
        }
    }
}

@Composable
private fun ApexUnenrolledOnboardingCard(
    inviteToken: String,
    onInviteTokenChange: (String) -> Unit,
    onEnroll: () -> Unit,
) {
    val colors = FlashTheme.colors

    ApexCard(contentPadding = FlashSpacing.space24) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.accentPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = FlashIcons.Encryption,
                    tint = colors.accentPrimary,
                    size = 32.dp,
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FlashText(
                    text = "Enterprise Device Enrollment",
                    style = FlashTheme.typography.headingLarge,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(FlashSpacing.space4))
                FlashText(
                    text = "Enter an invitation token from your Organization Administrator to securely bind this device.",
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
            }

            // Invite code input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ApexDims.tileRadius))
                    .background(colors.composerInputBackground)
                    .border(FlashDimensions.borderHairline, colors.borderDefault, RoundedCornerShape(ApexDims.tileRadius))
                    .padding(FlashSpacing.space12),
            ) {
                BasicTextField(
                    value = inviteToken,
                    onValueChange = onInviteTokenChange,
                    textStyle = apexMono(FlashTheme.typography.bodyEmphasis.copy(color = colors.textPrimary)),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Onboarding Steps
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ApexDims.tileRadius))
                    .background(colors.backgroundSurfaceStrong.copy(alpha = 0.5f))
                    .padding(FlashSpacing.space12),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashText(
                    text = "ENROLLMENT SECURITY VERIFICATION STEPS",
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.textTertiary,
                )
                FlashText("1. Verify Organization Root Certificate fingerprint pinning", style = FlashTheme.typography.captionDefault, color = colors.textSecondary)
                FlashText("2. Generate device-bound Ed25519 key pair in Hardware Keystore", style = FlashTheme.typography.captionDefault, color = colors.textSecondary)
                FlashText("3. Submit signed invitation token to Hub Enrollment endpoint", style = FlashTheme.typography.captionDefault, color = colors.textSecondary)
                FlashText("4. Receive signed Member Identity Statement and Department Lease", style = FlashTheme.typography.captionDefault, color = colors.textSecondary)
            }

            ApexButton(
                text = "Redeem Invite & Enroll Device",
                icon = FlashIcons.Verified,
                onClick = onEnroll,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
