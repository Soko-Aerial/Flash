@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.radio

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.transfer.flash.core.radio.connect.RadioCallsign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

private val RadioCardShape = RoundedCornerShape(FlashShapes.radius16)
private val RadioPillShape = RoundedCornerShape(FlashShapes.radiusFull)

// --- Master Switch Card (UI-070) -------------------------------------------------------------------------

@Composable
public fun FlashRadioMasterSwitchCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurface)
            .border(1.dp, colors.borderDefault, RadioCardShape)
            .padding(FlashSpacing.space16)
            .semantics {
                contentDescription = FlashRadioCopy.A11Y_MASTER_SWITCH
                stateDescription = if (enabled) FlashRadioCopy.VALUE_ON else FlashRadioCopy.VALUE_OFF
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = FlashSpacing.space12)) {
                FlashText(
                    text = FlashRadioCopy.MASTER_SWITCH_TITLE,
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(FlashSpacing.space4))
                FlashText(
                    text = FlashRadioCopy.MASTER_SWITCH_SUBTITLE,
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textSecondary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.accentPrimary,
                    uncheckedThumbColor = colors.textSecondary,
                    uncheckedTrackColor = colors.backgroundSurfaceSubtle,
                ),
            )
        }
    }
}

// --- Connection Status Card (UI-073) ---------------------------------------------------------------------

@Composable
public fun FlashRadioStatusCard(
    state: RadioGatewayUiState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val statusColor = when (state.connectionStatus) {
        RadioConnectionStatus.CONNECTED -> Color(0xFF3FB950)
        RadioConnectionStatus.CONNECTING,
        RadioConnectionStatus.PAIRING,
        RadioConnectionStatus.DETECTING,
        RadioConnectionStatus.SCANNING -> Color(0xFFD29922)
        RadioConnectionStatus.FAILED,
        RadioConnectionStatus.LINK_LOST -> Color(0xFFF85149)
        RadioConnectionStatus.CHECKLIST_READY -> colors.accentPrimary
        RadioConnectionStatus.OFF,
        RadioConnectionStatus.CONFIRMING_CANDIDATE -> colors.textSecondary
    }

    val sentence = FlashRadioMath.statusSentence(
        state.connectionStatus,
        state.failureReason,
        state.candidateRadio?.name ?: state.rememberedRadioName,
    )
    val nextStep = FlashRadioMath.statusNextStep(state.connectionStatus, state.failureReason)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurface)
            .border(1.dp, colors.borderDefault, RadioCardShape)
            .padding(FlashSpacing.space16)
            .semantics {
                contentDescription = FlashRadioCopy.A11Y_STATUS_CHIP
                stateDescription = sentence
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(modifier = Modifier.width(FlashSpacing.space8))
                FlashText(
                    text = sentence,
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
            }
            if (nextStep.isNotBlank()) {
                Spacer(modifier = Modifier.height(FlashSpacing.space4))
                FlashText(
                    text = nextStep,
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textSecondary,
                )
            }
            if (FlashRadioMath.canReconnect(state.connectionStatus, state.rememberedRadioAddress != null)) {
                Spacer(modifier = Modifier.height(FlashSpacing.space12))
                FlashRadioOutlineButton(
                    text = FlashRadioCopy.ACTION_RECONNECT,
                    icon = FlashIcons.Retry,
                    onClick = onReconnect,
                    a11yLabel = FlashRadioCopy.A11Y_RECONNECT_RADIO,
                )
            }
        }
    }
}

// --- Readiness Checklist Card (UI-070) -------------------------------------------------------------------

@Composable
public fun FlashRadioChecklistCard(
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurface)
            .border(1.dp, colors.borderDefault, RadioCardShape)
            .padding(FlashSpacing.space16),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            FlashText(
                text = FlashRadioCopy.CHECKLIST_TITLE,
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space4))
            FlashText(
                text = FlashRadioCopy.CHECKLIST_SUBTITLE,
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space12))
            FlashChecklistItem(text = FlashRadioCopy.CHECKLIST_STEP_1)
            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            FlashChecklistItem(text = FlashRadioCopy.CHECKLIST_STEP_2)
            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            FlashChecklistItem(text = FlashRadioCopy.CHECKLIST_STEP_3)
        }
    }
}

@Composable
private fun FlashChecklistItem(text: String) {
    val colors = FlashTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        FlashIcon(
            icon = FlashIcons.Check,
            contentDescription = null,
            tint = colors.accentPrimary,
            size = FlashDimensions.iconSm,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.width(FlashSpacing.space8))
        FlashText(
            text = text,
            style = FlashTheme.typography.captionDefault,
            color = colors.textPrimary,
        )
    }
}

// --- Candidate Confirmation Card (UI-070) ----------------------------------------------------------------

@Composable
public fun FlashRadioCandidateCard(
    candidate: DiscoveredRadioDevice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurfaceSubtle)
            .border(1.dp, colors.accentPrimary, RadioCardShape)
            .padding(FlashSpacing.space16),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlashIcon(
                    icon = FlashIcons.Device,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    size = FlashDimensions.iconMd,
                )
                Spacer(modifier = Modifier.width(FlashSpacing.space8))
                FlashText(
                    text = FlashRadioCopy.CANDIDATE_TITLE,
                    style = FlashTheme.typography.headingMedium,
                    color = colors.accentPrimary,
                )
            }
            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            FlashText(
                text = FlashRadioCopy.candidateQuestion(candidate.name),
                style = FlashTheme.typography.bodyDefault,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space12))
            Row(
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashRadioPrimaryButton(
                    text = FlashRadioCopy.ACTION_USE_CANDIDATE,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
                FlashRadioOutlineButton(
                    text = FlashRadioCopy.ACTION_DIFFERENT_RADIO,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// --- Remembered Radio Card (UI-070) ----------------------------------------------------------------------

@Composable
public fun FlashRadioRememberedCard(
    name: String,
    address: String,
    onForget: () -> Unit,
    onReconnect: () -> Unit,
    canReconnect: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurface)
            .border(1.dp, colors.borderDefault, RadioCardShape)
            .padding(FlashSpacing.space16),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlashIcon(
                        icon = FlashIcons.Bluetooth,
                        contentDescription = null,
                        tint = colors.accentPrimary,
                        size = FlashDimensions.iconMd,
                    )
                    Spacer(modifier = Modifier.width(FlashSpacing.space8))
                    Column {
                        FlashText(
                            text = name,
                            style = FlashTheme.typography.headingMedium,
                            color = colors.textPrimary,
                        )
                        FlashText(
                            text = FlashRadioCopy.addressHint(address),
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textSecondary,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    if (canReconnect) {
                        FlashRadioOutlineButton(
                            text = FlashRadioCopy.ACTION_RECONNECT,
                            onClick = onReconnect,
                            a11yLabel = FlashRadioCopy.A11Y_RECONNECT_RADIO,
                        )
                    }
                    FlashRadioOutlineButton(
                        text = FlashRadioCopy.ACTION_FORGET,
                        onClick = onForget,
                        a11yLabel = FlashRadioCopy.A11Y_FORGET_RADIO,
                    )
                }
            }
        }
    }
}

// --- Live Scan Section ("Find my radio", UI-071) ----------------------------------------------------------

@Composable
public fun FlashRadioFindSection(
    isScanning: Boolean,
    countdownSeconds: Int,
    devices: List<DiscoveredRadioDevice>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPair: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val sorted = remember(devices) { FlashRadioMath.sortDiscoveredDevices(devices) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurface)
            .border(1.dp, colors.borderDefault, RadioCardShape)
            .padding(FlashSpacing.space16)
            .animateContentSize(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FlashText(
                        text = FlashRadioCopy.FIND_SECTION_TITLE,
                        style = FlashTheme.typography.headingMedium,
                        color = colors.textPrimary,
                    )
                    FlashText(
                        text = FlashRadioCopy.FIND_SECTION_SUBTITLE,
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }
                if (isScanning) {
                    FlashRadioOutlineButton(
                        text = FlashRadioCopy.actionStopScan(countdownSeconds),
                        onClick = onStopScan,
                        a11yLabel = FlashRadioCopy.A11Y_STOP_SCAN,
                    )
                } else {
                    FlashRadioPrimaryButton(
                        text = FlashRadioCopy.ACTION_SCAN_RADIOS,
                        onClick = onStartScan,
                    )
                }
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space12))

            if (sorted.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = FlashSpacing.space12),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = FlashRadioCopy.SCAN_NO_DEVICES,
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }
            } else {
                sorted.forEach { device ->
                    FlashDeviceRow(
                        device = device,
                        onPair = { onPair(device.id) },
                    )
                    Spacer(modifier = Modifier.height(FlashSpacing.space8))
                }
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlashText(
                    text = FlashRadioCopy.SCAN_BATTERY_NOTE,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSettings) {
                    FlashText(
                        text = FlashRadioCopy.ACTION_OPEN_BT_SETTINGS,
                        style = FlashTheme.typography.captionDefault,
                        color = colors.accentPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlashDeviceRow(
    device: DiscoveredRadioDevice,
    onPair: () -> Unit,
) {
    val colors = FlashTheme.colors
    val categoryText = FlashRadioMath.categoryBadgeText(device.category)
    val categoryColor = when (device.category) {
        RadioDeviceCategory.KNOWN_RADIO -> colors.accentPrimary
        RadioDeviceCategory.SERIAL_SPP -> Color(0xFFD29922)
        RadioDeviceCategory.OTHER -> colors.textSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurfaceSubtle)
            .border(0.5.dp, colors.borderDefault, RadioCardShape)
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = FlashSpacing.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlashText(
                    text = device.name,
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.width(FlashSpacing.space8))
                Box(
                    modifier = Modifier
                        .clip(RadioPillShape)
                        .background(categoryColor.copy(alpha = 0.15f))
                        .padding(horizontal = FlashSpacing.space8, vertical = 2.dp),
                ) {
                    FlashText(
                        text = categoryText,
                        style = FlashTheme.typography.metadataDefault,
                        color = categoryColor,
                    )
                }
            }
            Spacer(modifier = Modifier.height(FlashSpacing.space4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlashText(
                    text = device.id,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                )
                if (device.rssi != null) {
                    FlashText(
                        text = " · ${device.rssi} dBm",
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textTertiary,
                    )
                }
            }
        }

        if (device.isPaired) {
            FlashIcon(
                icon = FlashIcons.Verified,
                contentDescription = null,
                tint = Color(0xFF3FB950),
                size = FlashDimensions.iconMd,
            )
        } else if (device.isPairing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = colors.accentPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            FlashRadioOutlineButton(
                text = FlashRadioCopy.ACTION_PAIR,
                onClick = onPair,
                a11yLabel = FlashRadioCopy.a11yPairDevice(device.name),
            )
        }
    }
}

// --- Security Profile Card & Authorisation (UI-072) -------------------------------------------------------

@Composable
public fun FlashRadioSecurityCard(
    selectedProfile: RadioSecurityProfile,
    isAuthorised: Boolean,
    onSelectProfile: (RadioSecurityProfile) -> Unit,
    onOpenAuthorisation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurface)
            .border(1.dp, colors.borderDefault, RadioCardShape)
            .padding(FlashSpacing.space16),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlashText(
                    text = FlashRadioCopy.SECURITY_SECTION_TITLE,
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                if (isAuthorised) {
                    Box(
                        modifier = Modifier
                            .clip(RadioPillShape)
                            .background(Color(0xFF3FB950).copy(alpha = 0.15f))
                            .padding(horizontal = FlashSpacing.space8, vertical = 2.dp),
                    ) {
                        FlashText(
                            text = FlashRadioCopy.BADGE_AUTHORISED,
                            style = FlashTheme.typography.metadataDefault,
                            color = Color(0xFF3FB950),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space12))

            // Profile M (Encrypted) — Primary Option
            FlashProfileRow(
                title = FlashRadioCopy.PROFILE_M_TITLE,
                subtitle = FlashRadioCopy.PROFILE_M_SUBTITLE,
                badge = FlashRadioCopy.PROFILE_M_RECOMMENDED_BADGE,
                isSelected = selectedProfile == RadioSecurityProfile.PROFILE_M,
                onClick = {
                    if (isAuthorised) {
                        onSelectProfile(RadioSecurityProfile.PROFILE_M)
                    } else {
                        onOpenAuthorisation()
                    }
                },
                a11yLabel = FlashRadioCopy.A11Y_SELECT_PROFILE_M,
            )

            Spacer(modifier = Modifier.height(FlashSpacing.space8))

            // Profile A (Cleartext) — Amateur Fallback
            FlashProfileRow(
                title = FlashRadioCopy.PROFILE_A_TITLE,
                subtitle = FlashRadioCopy.PROFILE_A_SUBTITLE,
                badge = null,
                isSelected = selectedProfile == RadioSecurityProfile.PROFILE_A,
                onClick = { onSelectProfile(RadioSecurityProfile.PROFILE_A) },
                a11yLabel = FlashRadioCopy.A11Y_SELECT_PROFILE_A,
            )
        }
    }
}

@Composable
private fun FlashProfileRow(
    title: String,
    subtitle: String,
    badge: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    a11yLabel: String,
) {
    val colors = FlashTheme.colors
    val borderColor = if (isSelected) colors.accentPrimary else colors.borderDefault
    val bgColor = if (isSelected) colors.accentPrimary.copy(alpha = 0.08f) else colors.backgroundSurfaceSubtle

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(bgColor)
            .border(if (isSelected) 1.5.dp else 0.5.dp, borderColor, RadioCardShape)
            .clickable(onClick = onClick)
            .padding(FlashSpacing.space12)
            .semantics {
                role = Role.RadioButton
                contentDescription = a11yLabel
                stateDescription = if (isSelected) FlashRadioCopy.BADGE_ACTIVE else ""
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FlashText(
                    text = title,
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .clip(RadioPillShape)
                            .background(colors.accentPrimary.copy(alpha = 0.15f))
                            .padding(horizontal = FlashSpacing.space8, vertical = 2.dp),
                    ) {
                        FlashText(
                            text = badge,
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.accentPrimary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(FlashSpacing.space4))
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
public fun FlashRadioAuthorisationDialog(
    onConfirm: (callsign: String) -> Unit,
    onDismiss: () -> Unit,
    initialCallsign: String = "",
) {
    val colors = FlashTheme.colors
    var acknowledged by remember { mutableStateOf(false) }
    var callsignText by remember { mutableStateOf(initialCallsign) }
    val normalisedCallsign = RadioCallsign.normalise(callsignText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            FlashText(
                text = FlashRadioCopy.AUTHORISATION_DIALOG_TITLE,
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
        },
        text = {
            Column {
                FlashText(
                    text = FlashRadioCopy.AUTHORISATION_DIALOG_BODY,
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(FlashSpacing.space16))
                OutlinedTextField(
                    value = callsignText,
                    onValueChange = { raw ->
                        callsignText = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' }.take(9)
                    },
                    label = { Text(FlashRadioCopy.CALLSIGN_LABEL) },
                    supportingText = {
                        Text(
                            if (callsignText.isNotEmpty() && normalisedCallsign == null) {
                                FlashRadioCopy.CALLSIGN_INVALID
                            } else {
                                FlashRadioCopy.CALLSIGN_SUPPORTING
                            },
                        )
                    },
                    isError = callsignText.isNotEmpty() && normalisedCallsign == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(FlashSpacing.space12))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RadioCardShape)
                        .background(colors.backgroundSurfaceSubtle)
                        .clickable { acknowledged = !acknowledged }
                        .padding(FlashSpacing.space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.accentPrimary,
                        ),
                    )
                    Spacer(modifier = Modifier.width(FlashSpacing.space12))
                    FlashText(
                        text = FlashRadioCopy.AUTHORISATION_STATEMENT,
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textPrimary,
                    )
                }
            }
        },
        confirmButton = {
            FlashRadioPrimaryButton(
                text = FlashRadioCopy.ACTION_CONFIRM_AUTHORISATION,
                onClick = { normalisedCallsign?.let(onConfirm) },
                enabled = acknowledged && normalisedCallsign != null,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                FlashText(
                    text = FlashRadioCopy.ACTION_CANCEL_AUTHORISATION,
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
            }
        },
        containerColor = colors.backgroundSurface,
    )
}

// --- Mode Selector Card (UI-074) --------------------------------------------------------------------------

@Composable
public fun FlashRadioModesCard(
    selectedMode: RadioGatewayMode,
    availableModes: Set<RadioGatewayMode>,
    onSelectMode: (RadioGatewayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val modes = remember { FlashRadioMath.visibleModes() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurface)
            .border(1.dp, colors.borderDefault, RadioCardShape)
            .padding(FlashSpacing.space16),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            FlashText(
                text = FlashRadioCopy.MODES_SECTION_TITLE,
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space12))
            modes.forEach { mode ->
                val (title, subtitle) = when (mode) {
                    RadioGatewayMode.FIELD_COMPANION -> FlashRadioCopy.MODE_1_TITLE to FlashRadioCopy.MODE_1_SUBTITLE
                    RadioGatewayMode.BASE_STATION_RELAY -> FlashRadioCopy.MODE_2_TITLE to FlashRadioCopy.MODE_2_SUBTITLE
                    RadioGatewayMode.TACTICAL_RADAR -> FlashRadioCopy.MODE_4_TITLE to FlashRadioCopy.MODE_4_SUBTITLE
                    RadioGatewayMode.ALL_IN_ONE -> FlashRadioCopy.MODE_5_TITLE to FlashRadioCopy.MODE_5_SUBTITLE
                    RadioGatewayMode.WALKIE_TALKIE_BRIDGE -> "" to ""
                }
                val isSelectable = FlashRadioMath.isModeSelectable(mode, availableModes)
                val badgeText = FlashRadioMath.modeBadgeText(mode, selectedMode, availableModes)

                FlashModeRow(
                    title = title,
                    subtitle = subtitle,
                    badgeText = badgeText,
                    isSelected = selectedMode == mode,
                    isSelectable = isSelectable,
                    onClick = { if (isSelectable) onSelectMode(mode) },
                    a11yLabel = FlashRadioCopy.a11ySelectMode(title),
                )
                Spacer(modifier = Modifier.height(FlashSpacing.space8))
            }
        }
    }
}

@Composable
private fun FlashModeRow(
    title: String,
    subtitle: String,
    badgeText: String,
    isSelected: Boolean,
    isSelectable: Boolean,
    onClick: () -> Unit,
    a11yLabel: String,
) {
    val colors = FlashTheme.colors
    val borderColor = if (isSelected) colors.accentPrimary else colors.borderDefault
    val alpha = if (isSelectable) 1f else 0.5f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.08f) else colors.backgroundSurfaceSubtle)
            .border(if (isSelected) 1.5.dp else 0.5.dp, borderColor, RadioCardShape)
            .clickable(enabled = isSelectable, onClick = onClick)
            .padding(FlashSpacing.space12)
            .semantics {
                role = Role.RadioButton
                contentDescription = a11yLabel
                stateDescription = badgeText
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlashText(
                    text = title,
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary.copy(alpha = alpha),
                )
                if (badgeText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RadioPillShape)
                            .background(
                                if (isSelected) colors.accentPrimary.copy(alpha = 0.15f)
                                else colors.backgroundSurface
                            )
                            .padding(horizontal = FlashSpacing.space8, vertical = 2.dp),
                    ) {
                        FlashText(
                            text = badgeText,
                            style = FlashTheme.typography.metadataDefault,
                            color = if (isSelected) colors.accentPrimary else colors.textSecondary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(FlashSpacing.space4))
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary.copy(alpha = alpha),
            )
        }
    }
}

// --- Diagnostic Test Message Card (UI-070, Rule 4) -------------------------------------------------------

@Composable
public fun FlashRadioDiagnosticsCard(
    canSend: Boolean,
    isSending: Boolean,
    lastSuccess: Boolean?,
    onRequestSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(colors.backgroundSurface)
            .border(1.dp, colors.borderDefault, RadioCardShape)
            .padding(FlashSpacing.space16),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            FlashText(
                text = FlashRadioCopy.DIAGNOSTICS_SECTION_TITLE,
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            FlashText(
                text = FlashRadioCopy.TRANSMIT_WARNING_BODY,
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space12))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FlashRadioPrimaryButton(
                    text = if (isSending) FlashRadioCopy.TEST_MESSAGE_SENDING else FlashRadioCopy.ACTION_SEND_TEST_MESSAGE,
                    onClick = onRequestSend,
                    enabled = canSend,
                    a11yLabel = FlashRadioCopy.A11Y_TEST_MESSAGE_BTN,
                )
                if (lastSuccess == true) {
                    FlashText(
                        text = FlashRadioCopy.TEST_MESSAGE_SENT_SUCCESS,
                        style = FlashTheme.typography.metadataDefault,
                        color = Color(0xFF3FB950),
                    )
                } else if (lastSuccess == false) {
                    FlashText(
                        text = FlashRadioCopy.TEST_MESSAGE_SENT_FAILED,
                        style = FlashTheme.typography.metadataDefault,
                        color = Color(0xFFF85149),
                    )
                }
            }
        }
    }
}

@Composable
public fun FlashRadioTestMessageWarningDialog(
    onTransmitNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            FlashText(
                text = FlashRadioCopy.TRANSMIT_WARNING_TITLE,
                style = FlashTheme.typography.headingMedium,
                color = Color(0xFFF0883E),
            )
        },
        text = {
            FlashText(
                text = FlashRadioCopy.TRANSMIT_WARNING_BODY,
                style = FlashTheme.typography.bodyDefault,
                color = colors.textPrimary,
            )
        },
        confirmButton = {
            FlashRadioPrimaryButton(
                text = FlashRadioCopy.ACTION_TRANSMIT_NOW,
                onClick = onTransmitNow,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                FlashText(
                    text = FlashRadioCopy.ACTION_CANCEL_TRANSMIT,
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
            }
        },
        containerColor = colors.backgroundSurface,
    )
}

// --- Permissions Card ------------------------------------------------------------------------------------

@Composable
public fun FlashRadioPermissionCard(
    isDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(Color(0xFFD29922).copy(alpha = 0.1f))
            .border(1.dp, Color(0xFFD29922), RadioCardShape)
            .padding(FlashSpacing.space16),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            FlashText(
                text = FlashRadioCopy.PERMISSION_CARD_TITLE,
                style = FlashTheme.typography.headingMedium,
                color = Color(0xFFD29922),
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space4))
            FlashText(
                text = if (isDenied) FlashRadioCopy.SENTENCE_PERMISSION_DENIED else FlashRadioCopy.PERMISSION_REASON_TEXT,
                style = FlashTheme.typography.captionDefault,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space12))
            if (isDenied) {
                FlashRadioOutlineButton(
                    text = FlashRadioCopy.ACTION_OPEN_BT_SETTINGS,
                    onClick = onOpenSettings,
                )
            } else {
                FlashRadioPrimaryButton(
                    text = FlashRadioCopy.ACTION_GRANT_PERMISSION,
                    onClick = onRequestPermission,
                )
            }
        }
    }
}

// --- Styled Button Helpers -------------------------------------------------------------------------------

@Composable
public fun FlashRadioPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    a11yLabel: String? = null,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .clip(RadioCardShape)
            .background(if (enabled) colors.accentPrimary else colors.backgroundSurfaceSubtle)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12)
            .semantics {
                role = Role.Button
                if (a11yLabel != null) contentDescription = a11yLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = text,
            style = FlashTheme.typography.bodyEmphasis,
            color = if (enabled) Color.White else colors.textTertiary,
        )
    }
}

@Composable
public fun FlashRadioOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: com.transfer.flash.ui.icons.FlashIconSpec? = null,
    a11yLabel: String? = null,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .clip(RadioCardShape)
            .border(1.dp, colors.borderDefault, RadioCardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .semantics {
                role = Role.Button
                if (a11yLabel != null) contentDescription = a11yLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                FlashIcon(
                    icon = icon,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    size = FlashDimensions.iconSm,
                )
                Spacer(modifier = Modifier.width(FlashSpacing.space4))
            }
            FlashText(
                text = text,
                style = FlashTheme.typography.captionDefault,
                color = colors.textPrimary,
            )
        }
    }
}
