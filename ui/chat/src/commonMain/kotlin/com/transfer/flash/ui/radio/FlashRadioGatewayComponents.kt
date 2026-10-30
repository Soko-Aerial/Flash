@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.radio.connect.RadioCallsign
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.hub.FlashHubButton
import com.transfer.flash.ui.hub.FlashHubChip
import com.transfer.flash.ui.hub.FlashHubSwitch
import com.transfer.flash.ui.hub.FlashHubTextAction
import com.transfer.flash.ui.hub.HubLiveDot
import com.transfer.flash.ui.hub.HubShapes
import com.transfer.flash.ui.hub.HubTone
import com.transfer.flash.ui.hub.color
import com.transfer.flash.ui.hub.hubCapsStyle
import com.transfer.flash.ui.hub.hubClickable
import com.transfer.flash.ui.hub.hubHeroBrush
import com.transfer.flash.ui.hub.hubRuleBelow
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/*
 * Redesign v2 (docs/ui/radio-gateway.md "Redesign v2", 2026-10-28).
 *
 * The rule this file follows: a border and a fill are information, not decoration. The page is ONE station plate
 * (the radio itself) plus two attention strips (permission, candidate found); every other section is a small-caps
 * label with rows separated by hairlines. The language is the Hub tab's (UI-058) - HubShapes, hubCapsStyle,
 * FlashHubSwitch / Button / TextAction / Chip, HubTone - so the app keeps one visual system instead of two.
 *
 * Nothing in the wiring changed: the same controller calls, the same FlashRadioCopy sentences and the same
 * FlashRadioMath decisions (FlashRadioNoDeadActionsTest / FlashRadioCopyA11ySourceTest still guard this file).
 */

private val RadioCardShape = HubShapes.card
private val RadioControlShape = HubShapes.control

// --- Shared pieces ----------------------------------------------------------------------------------------

/** The colour a connection state means. Always paired with a word at the call site, never colour alone. */
internal fun radioStatusTone(status: RadioConnectionStatus, reason: RadioFailureReason): HubTone {
    if (reason != RadioFailureReason.NONE) {
        return when (reason) {
            RadioFailureReason.NO_ADAPTER,
            RadioFailureReason.BLUETOOTH_OFF,
            RadioFailureReason.PAIRING_REFUSED,
            RadioFailureReason.WRONG_PIN,
            RadioFailureReason.PORT_IN_USE -> HubTone.DANGER
            RadioFailureReason.PERMISSION_MISSING,
            RadioFailureReason.PERMISSION_DENIED,
            RadioFailureReason.RADIO_NOT_FOUND,
            RadioFailureReason.KISS_NOT_ENABLED,
            RadioFailureReason.RADIO_CLOSED_LINK,
            RadioFailureReason.LINK_LOST -> HubTone.WARNING
            RadioFailureReason.NONE -> HubTone.NEUTRAL
        }
    }
    return when (status) {
        RadioConnectionStatus.CONNECTED -> HubTone.SUCCESS
        RadioConnectionStatus.PAIRING,
        RadioConnectionStatus.CONNECTING,
        RadioConnectionStatus.DETECTING,
        RadioConnectionStatus.SCANNING,
        RadioConnectionStatus.CONFIRMING_CANDIDATE,
        RadioConnectionStatus.CHECKLIST_READY -> HubTone.ACCENT
        RadioConnectionStatus.FAILED,
        RadioConnectionStatus.LINK_LOST -> HubTone.DANGER
        RadioConnectionStatus.OFF -> HubTone.NEUTRAL
    }
}

/** The short profile name used in the station facts ("M · Encrypted"). */
internal fun shortProfileName(profile: RadioSecurityProfile): String = when (profile) {
    RadioSecurityProfile.PROFILE_M -> FlashRadioCopy.PROFILE_M_SHORT
    RadioSecurityProfile.PROFILE_A -> FlashRadioCopy.PROFILE_A_SHORT
}

/** A small-caps section label followed by a hairline that runs to the edge, with an optional compact action. */
@Composable
internal fun FlashRadioSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = FlashSpacing.space8, bottom = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashText(
            text = text.uppercase(),
            style = hubCapsStyle(),
            color = colors.textSecondary,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.borderSubtle))
        if (action != null) action()
    }
}

/** A compact capsule action that sits in a section header: 48 dp touch area, ~30 dp of visible pill. */
@Composable
private fun RadioHeaderAction(
    label: String,
    onClick: () -> Unit,
    a11yLabel: String? = null,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .hubClickable(onClick = onClick)
            .semantics { if (a11yLabel != null) contentDescription = a11yLabel },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(HubShapes.capsule)
                .background(colors.accentPrimary.copy(alpha = 0.14f))
                .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText(label, style = FlashTheme.typography.metadataEmphasis, color = colors.accentPrimary, maxLines = 1)
        }
    }
}

/** One "label — value" row of the station facts. */
@Composable
internal fun RadioFactRow(label: String, value: String) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .hubRuleBelow()
            .padding(vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = label,
            modifier = Modifier.widthIn(min = 88.dp),
            style = FlashTheme.typography.metadataDefault,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.width(FlashSpacing.space12))
        FlashText(
            text = value,
            modifier = Modifier.weight(1f),
            style = FlashTheme.typography.bodyDefault,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The selection mark of a choice row: a ring, filled when chosen. */
@Composable
private fun RadioRing(selected: Boolean) {
    val colors = FlashTheme.colors
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 1.5.dp else FlashDimensions.borderHairline,
                color = if (selected) colors.accentPrimary else colors.borderStrong,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.accentPrimary))
        }
    }
}

/**
 * A choice row (security profile, operational mode): ring, title, optional chip, subtitle. Rows, not cards: the
 * selected state is carried by the ring and the title colour, so the page keeps a single visual weight.
 */
@Composable
private fun RadioChoiceRow(
    title: String,
    subtitle: String,
    badge: String?,
    selected: Boolean,
    a11yLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    badgeEmphasised: Boolean = false,
) {
    val colors = FlashTheme.colors
    val titleColor = when {
        !enabled -> colors.textTertiary
        selected -> colors.accentPrimary
        else -> colors.textPrimary
    }
    val badgeText = if (selected) FlashRadioCopy.BADGE_ACTIVE else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .hubRuleBelow()
            .hubClickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = a11yLabel
                stateDescription = badgeText
            }
            .padding(vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        RadioRing(selected = selected && enabled)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashText(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = FlashTheme.typography.bodyEmphasis,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null && badge.isNotBlank()) {
                    FlashHubChip(text = badge, emphasised = badgeEmphasised)
                }
            }
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.metadataDefault,
                color = if (enabled) colors.textSecondary else colors.textTertiary,
            )
        }
    }
}

// --- Station plate (UI-070 / UI-073) ----------------------------------------------------------------------

/**
 * The one hero of the page: the radio, its live state and the master switch. Everything else on the screen is a
 * section of rows, so this block is the only filled surface with a tone-tinted edge.
 */
@Composable
public fun FlashRadioStationBlock(
    state: RadioGatewayUiState,
    onToggle: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val tone = radioStatusTone(state.connectionStatus, state.failureReason)
    val tint = tone.color()
    val sentence = FlashRadioMath.statusSentence(
        state.connectionStatus,
        state.failureReason,
        state.candidateRadio?.name ?: state.rememberedRadioName,
    )
    val nextStep = FlashRadioMath.statusNextStep(state.connectionStatus, state.failureReason)
    val stationName = state.rememberedRadioName ?: state.candidateRadio?.name ?: FlashRadioCopy.STATION_NO_RADIO
    val live = state.connectionStatus == RadioConnectionStatus.CONNECTED

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .then(
                if (state.enabled) {
                    Modifier
                        .background(hubHeroBrush(strong = 0.16f, soft = 0.04f))
                        .border(FlashDimensions.borderHairline, tint.copy(alpha = 0.35f), RadioCardShape)
                } else {
                    Modifier.background(colors.backgroundSurfaceSubtle)
                },
            )
            .padding(FlashSpacing.space16),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(HubShapes.mark(44.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = FlashIcons.Connection,
                    contentDescription = null,
                    tint = tint,
                    size = FlashDimensions.iconMd,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = stationName,
                    modifier = Modifier.semantics { heading() },
                    style = FlashTheme.typography.headingSmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    HubLiveDot(color = tint, live = live, size = 7.dp)
                    FlashText(
                        text = sentence,
                        style = FlashTheme.typography.metadataDefault,
                        color = if (state.enabled) colors.textSecondary else colors.textTertiary,
                        maxLines = 2,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(FlashDimensions.minTouchTarget)
                    .hubClickable { onToggle(!state.enabled) }
                    .semantics {
                        contentDescription = FlashRadioCopy.A11Y_MASTER_SWITCH
                        stateDescription = if (state.enabled) FlashRadioCopy.VALUE_ON else FlashRadioCopy.VALUE_OFF
                    },
                contentAlignment = Alignment.Center,
            ) {
                FlashHubSwitch(on = state.enabled)
            }
        }
        if (state.enabled) {
            if (nextStep.isNotBlank()) {
                Spacer(modifier = Modifier.height(FlashSpacing.space8))
                FlashText(
                    text = nextStep,
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
            }
            if (FlashRadioMath.canReconnect(state.connectionStatus, state.rememberedRadioAddress != null)) {
                Spacer(modifier = Modifier.height(FlashSpacing.space12))
                FlashHubButton(
                    label = FlashRadioCopy.ACTION_RECONNECT,
                    onClick = onReconnect,
                    icon = FlashIcons.Retry,
                    primary = false,
                )
            }
        }
    }
}

// --- Attention strips -------------------------------------------------------------------------------------

/**
 * The permission strip. It is a strip (tone wash plus a hairline under the text), not a box: it only ever appears
 * when something is actually blocked, and then it is the loudest thing on the page.
 */
@Composable
public fun FlashRadioPermissionBanner(
    isDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val tint = colors.accentSecondary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(tint.copy(alpha = 0.10f))
            .border(FlashDimensions.borderHairline, tint.copy(alpha = 0.35f), RadioCardShape)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashIcon(
            icon = FlashIcons.Bluetooth,
            contentDescription = null,
            tint = tint,
            size = FlashDimensions.iconSm,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(
                text = FlashRadioCopy.PERMISSION_CARD_TITLE,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
            )
            FlashText(
                text = FlashRadioCopy.PERMISSION_REASON_TEXT,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
        if (isDenied) {
            FlashHubTextAction(FlashRadioCopy.ACTION_OPEN_BT_SETTINGS, onOpenSettings)
        } else {
            FlashHubTextAction(FlashRadioCopy.ACTION_GRANT_PERMISSION, onRequestPermission)
        }
    }
}

/** "A paired radio found, use it?" as a strip: the one question that needs an answer before anything else works. */
@Composable
public fun FlashRadioCandidateBanner(
    candidate: DiscoveredRadioDevice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val tint = colors.accentPrimary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(tint.copy(alpha = 0.10f))
            .border(FlashDimensions.borderHairline, tint.copy(alpha = 0.40f), RadioCardShape)
            .padding(FlashSpacing.space16),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            FlashIcon(
                icon = FlashIcons.Device,
                contentDescription = null,
                tint = tint,
                size = FlashDimensions.iconSm,
            )
            FlashText(
                text = FlashRadioCopy.CANDIDATE_TITLE,
                style = FlashTheme.typography.bodyEmphasis,
                color = tint,
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            FlashHubButton(
                label = FlashRadioCopy.ACTION_USE_CANDIDATE,
                onClick = onConfirm,
                icon = FlashIcons.Check,
            )
            FlashHubTextAction(FlashRadioCopy.ACTION_DIFFERENT_RADIO, onDismiss)
        }
    }
}

// --- This link (UI-070 remembered radio, UI-073 details) ---------------------------------------------------

/** The facts of the remembered link plus the one destructive-looking action ("Forget"), as plain rows. */
@Composable
public fun FlashRadioLinkSection(
    state: RadioGatewayUiState,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FlashRadioSectionHeader(FlashRadioCopy.SECTION_LINK)
        RadioFactRow(
            label = FlashRadioCopy.FACT_RADIO,
            value = state.rememberedRadioName ?: FlashRadioCopy.FACT_NONE,
        )
        RadioFactRow(
            label = FlashRadioCopy.FACT_ADDRESS,
            value = state.rememberedRadioAddress ?: FlashRadioCopy.FACT_NONE,
        )
        RadioFactRow(
            label = FlashRadioCopy.FACT_PROFILE,
            value = shortProfileName(state.selectedProfile),
        )
        RadioFactRow(
            label = FlashRadioCopy.FACT_CALLSIGN,
            value = state.operatorCallsign ?: FlashRadioCopy.FACT_NONE,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FlashDimensions.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            FlashHubTextAction(FlashRadioCopy.ACTION_FORGET, onForget)
        }
    }
}

// --- Set up your radio (UI-070 readiness checklist) -------------------------------------------------------

/** The three readiness steps as rows. Shown while nothing is set up, and while the gateway is off. */
@Composable
public fun FlashRadioChecklistSection(
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        FlashRadioSectionHeader(FlashRadioCopy.CHECKLIST_TITLE)
        FlashText(
            text = FlashRadioCopy.CHECKLIST_SUBTITLE,
            modifier = Modifier.padding(bottom = FlashSpacing.space8),
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
        ChecklistStep(FlashRadioCopy.CHECKLIST_STEP_1)
        ChecklistStep(FlashRadioCopy.CHECKLIST_STEP_2)
        ChecklistStep(FlashRadioCopy.CHECKLIST_STEP_3)
    }
}

@Composable
private fun ChecklistStep(text: String) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hubRuleBelow()
            .padding(vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashIcon(
            icon = FlashIcons.Check,
            contentDescription = null,
            tint = colors.accentPrimary,
            size = FlashDimensions.iconSm,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlashText(
            text = text,
            modifier = Modifier.weight(1f),
            style = FlashTheme.typography.bodyDefault,
            color = colors.textPrimary,
        )
    }
}

// --- Find my radio (UI-071) -------------------------------------------------------------------------------

/** Live scan and pairing as rows: the scan action lives in the section header, so it never scrolls away first. */
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
    Column(modifier = modifier.fillMaxWidth()) {
        FlashRadioSectionHeader(
            text = FlashRadioCopy.FIND_SECTION_TITLE,
            action = {
                if (isScanning) {
                    RadioHeaderAction(
                        label = FlashRadioCopy.actionStopScan(countdownSeconds),
                        onClick = onStopScan,
                        a11yLabel = FlashRadioCopy.A11Y_STOP_SCAN,
                    )
                } else {
                    RadioHeaderAction(
                        label = FlashRadioCopy.ACTION_SCAN_RADIOS,
                        onClick = onStartScan,
                        a11yLabel = FlashRadioCopy.A11Y_START_SCAN,
                    )
                }
            },
        )
        FlashText(
            text = FlashRadioCopy.FIND_SECTION_SUBTITLE,
            modifier = Modifier.padding(bottom = FlashSpacing.space4),
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
        if (sorted.isEmpty()) {
            FlashText(
                text = FlashRadioCopy.SCAN_NO_DEVICES,
                modifier = Modifier.padding(vertical = FlashSpacing.space8),
                style = FlashTheme.typography.bodyDefault,
                color = colors.textSecondary,
            )
        } else {
            sorted.forEach { device ->
                FlashRadioDeviceRow(device = device, onPair = { onPair(device.id) })
            }
        }
        Spacer(modifier = Modifier.height(FlashSpacing.space4))
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = FlashDimensions.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            FlashText(
                text = FlashRadioCopy.SCAN_BATTERY_NOTE,
                modifier = Modifier.weight(1f),
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
            FlashHubTextAction(FlashRadioCopy.ACTION_OPEN_BT_SETTINGS, onOpenSettings)
        }
    }
}

@Composable
private fun FlashRadioDeviceRow(
    device: DiscoveredRadioDevice,
    onPair: () -> Unit,
) {
    val colors = FlashTheme.colors
    val categoryText = FlashRadioMath.categoryBadgeText(device.category)
    val emphasised = device.category == RadioDeviceCategory.KNOWN_RADIO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .hubRuleBelow()
            .padding(vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(HubShapes.mark(36.dp))
                .background(
                    if (emphasised) colors.accentPrimary.copy(alpha = 0.14f) else colors.backgroundSurfaceStrong,
                ),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = if (emphasised) FlashIcons.Device else FlashIcons.Bluetooth,
                contentDescription = null,
                tint = if (emphasised) colors.accentPrimary else colors.textSecondary,
                size = FlashDimensions.iconSm,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
        ) {
            FlashText(
                text = device.name,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashHubChip(text = categoryText, emphasised = emphasised)
                if (device.rssi != null) {
                    FlashText(
                        text = FlashRadioCopy.signalHint(device.rssi),
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textTertiary,
                    )
                }
            }
        }
        when {
            device.isPairing -> FlashText(
                text = FlashRadioCopy.STATUS_PAIRING_IN_PROGRESS,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
            device.isPaired -> Box(
                modifier = Modifier.semantics { contentDescription = FlashRadioCopy.a11yUseDevice(device.name) },
            ) {
                FlashHubTextAction(FlashRadioCopy.ACTION_USE_RADIO, onPair)
            }
            else -> Box(
                modifier = Modifier.semantics { contentDescription = FlashRadioCopy.a11yPairDevice(device.name) },
            ) {
                FlashHubTextAction(FlashRadioCopy.ACTION_PAIR, onPair)
            }
        }
    }
}

// --- Security profile (UI-072) ----------------------------------------------------------------------------

/** Profile M / Profile A as choice rows. Selecting M without the authorisation still opens the sheet. */
@Composable
public fun FlashRadioSecuritySection(
    selectedProfile: RadioSecurityProfile,
    isAuthorised: Boolean,
    onSelectProfile: (RadioSecurityProfile) -> Unit,
    onOpenAuthorisation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSelectM = FlashRadioMath.isProfileMSelectable(isAuthorised)
    Column(modifier = modifier.fillMaxWidth()) {
        FlashRadioSectionHeader(FlashRadioCopy.SECURITY_SECTION_TITLE)
        RadioChoiceRow(
            title = FlashRadioCopy.PROFILE_M_TITLE,
            subtitle = FlashRadioCopy.PROFILE_M_SUBTITLE,
            badge = if (isAuthorised) FlashRadioCopy.BADGE_AUTHORISED else FlashRadioCopy.PROFILE_M_NEEDS_AUTH_BADGE,
            badgeEmphasised = isAuthorised,
            selected = selectedProfile == RadioSecurityProfile.PROFILE_M,
            a11yLabel = FlashRadioCopy.A11Y_SELECT_PROFILE_M,
            onClick = {
                if (canSelectM) {
                    onSelectProfile(RadioSecurityProfile.PROFILE_M)
                } else {
                    onOpenAuthorisation()
                }
            },
        )
        RadioChoiceRow(
            title = FlashRadioCopy.PROFILE_A_TITLE,
            subtitle = FlashRadioCopy.PROFILE_A_SUBTITLE,
            badge = null,
            selected = selectedProfile == RadioSecurityProfile.PROFILE_A,
            a11yLabel = FlashRadioCopy.A11Y_SELECT_PROFILE_A,
            onClick = { onSelectProfile(RadioSecurityProfile.PROFILE_A) },
        )
    }
}

// --- Operational mode (UI-074) ----------------------------------------------------------------------------

/** The modes, with "Coming later" as a chip rather than a disabled card. */
@Composable
public fun FlashRadioModesSection(
    selectedMode: RadioGatewayMode,
    availableModes: Set<RadioGatewayMode>,
    onSelectMode: (RadioGatewayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = remember { FlashRadioMath.visibleModes() }
    Column(modifier = modifier.fillMaxWidth()) {
        FlashRadioSectionHeader(FlashRadioCopy.MODES_SECTION_TITLE)
        modes.forEach { mode ->
            val selectable = FlashRadioMath.isModeSelectable(mode, availableModes)
            val title = when (mode) {
                RadioGatewayMode.FIELD_COMPANION -> FlashRadioCopy.MODE_1_TITLE
                RadioGatewayMode.BASE_STATION_RELAY -> FlashRadioCopy.MODE_2_TITLE
                RadioGatewayMode.TACTICAL_RADAR -> FlashRadioCopy.MODE_4_TITLE
                RadioGatewayMode.ALL_IN_ONE -> FlashRadioCopy.MODE_5_TITLE
                RadioGatewayMode.WALKIE_TALKIE_BRIDGE -> FlashRadioCopy.MODE_5_TITLE
            }
            val subtitle = when (mode) {
                RadioGatewayMode.FIELD_COMPANION -> FlashRadioCopy.MODE_1_SUBTITLE
                RadioGatewayMode.BASE_STATION_RELAY -> FlashRadioCopy.MODE_2_SUBTITLE
                RadioGatewayMode.TACTICAL_RADAR -> FlashRadioCopy.MODE_4_SUBTITLE
                RadioGatewayMode.ALL_IN_ONE -> FlashRadioCopy.MODE_5_SUBTITLE
                RadioGatewayMode.WALKIE_TALKIE_BRIDGE -> FlashRadioCopy.MODE_5_SUBTITLE
            }
            RadioChoiceRow(
                title = title,
                subtitle = subtitle,
                badge = FlashRadioMath.modeBadgeText(mode, selectedMode, availableModes),
                badgeEmphasised = mode == selectedMode,
                selected = mode == selectedMode,
                a11yLabel = FlashRadioCopy.a11ySelectMode(title),
                onClick = { onSelectMode(mode) },
                enabled = selectable,
            )
        }
    }
}

// --- Radio diagnostics (UI-073) ---------------------------------------------------------------------------

/** One row: the transmit test, its result line, and why it is unavailable when there is no link. */
@Composable
public fun FlashRadioDiagnosticsSection(
    canSend: Boolean,
    isSending: Boolean,
    lastSuccess: Boolean?,
    onRequestSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val detail: String
    val detailColor: androidx.compose.ui.graphics.Color
    when {
        isSending -> {
            detail = FlashRadioCopy.TEST_MESSAGE_SENDING
            detailColor = colors.textSecondary
        }
        lastSuccess == true -> {
            detail = FlashRadioCopy.TEST_MESSAGE_SENT_SUCCESS
            detailColor = colors.textSuccess
        }
        lastSuccess == false -> {
            detail = FlashRadioCopy.TEST_MESSAGE_SENT_FAILED
            detailColor = colors.textError
        }
        !canSend -> {
            detail = FlashRadioCopy.TEST_MESSAGE_NEEDS_LINK
            detailColor = colors.textTertiary
        }
        else -> {
            detail = FlashRadioCopy.TRANSMIT_WARNING_BODY
            detailColor = colors.textTertiary
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        FlashRadioSectionHeader(FlashRadioCopy.DIAGNOSTICS_SECTION_TITLE)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .hubRuleBelow()
                .padding(vertical = FlashSpacing.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
            ) {
                FlashText(
                    text = FlashRadioCopy.ACTION_SEND_TEST_MESSAGE,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = if (canSend) colors.textPrimary else colors.textTertiary,
                )
                FlashText(
                    text = detail,
                    style = FlashTheme.typography.metadataDefault,
                    color = detailColor,
                )
            }
            FlashHubTextAction(
                label = FlashRadioCopy.ACTION_SEND,
                onClick = onRequestSend,
                enabled = canSend,
            )
        }
    }
}

// --- Sheets (UI-072 authorisation, UI-073 transmit warning) ------------------------------------------------

/** Regulatory authorisation: the statement, the station ID, then the confirm. Same validation as before. */
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

    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.sheetSurface,
        title = {
            FlashText(
                text = FlashRadioCopy.AUTHORISATION_DIALOG_TITLE,
                style = FlashTheme.typography.headingSmall,
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
                RadioCallsignField(
                    value = callsignText,
                    isError = callsignText.isNotEmpty() && normalisedCallsign == null,
                    supporting = if (callsignText.isNotEmpty() && normalisedCallsign == null) {
                        FlashRadioCopy.CALLSIGN_INVALID
                    } else {
                        FlashRadioCopy.CALLSIGN_SUPPORTING
                    },
                    onValueChange = { raw ->
                        callsignText = raw.uppercase()
                            .filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' }
                            .take(9)
                    },
                )
                Spacer(modifier = Modifier.height(FlashSpacing.space12))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RadioControlShape)
                        .background(colors.backgroundSurfaceSubtle)
                        .hubClickable { acknowledged = !acknowledged }
                        .padding(FlashSpacing.space12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    FlashHubSwitch(on = acknowledged)
                    FlashText(
                        text = FlashRadioCopy.AUTHORISATION_STATEMENT,
                        modifier = Modifier.weight(1f),
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textPrimary,
                    )
                }
            }
        },
        confirmButton = {
            FlashHubButton(
                label = FlashRadioCopy.ACTION_CONFIRM_AUTHORISATION,
                onClick = { normalisedCallsign?.let(onConfirm) },
                enabled = acknowledged && normalisedCallsign != null,
            )
        },
        dismissButton = {
            FlashHubTextAction(FlashRadioCopy.ACTION_CANCEL_AUTHORISATION, onDismiss)
        },
    )
}

/** The RF warning before a test transmission. Warning tone, one destructive-looking action, no hidden transmit. */
@Composable
public fun FlashRadioTestMessageWarningDialog(
    onTransmitNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    val tint = colors.accentSecondary
    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.sheetSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashIcon(
                    icon = FlashIcons.Bolt,
                    contentDescription = null,
                    tint = tint,
                    size = FlashDimensions.iconSm,
                )
                FlashText(
                    text = FlashRadioCopy.TRANSMIT_WARNING_TITLE,
                    style = FlashTheme.typography.headingSmall,
                    color = tint,
                )
            }
        },
        text = {
            FlashText(
                text = FlashRadioCopy.TRANSMIT_WARNING_BODY,
                style = FlashTheme.typography.bodyDefault,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            FlashHubButton(label = FlashRadioCopy.ACTION_TRANSMIT_NOW, onClick = onTransmitNow)
        },
        dismissButton = {
            FlashHubTextAction(FlashRadioCopy.ACTION_CANCEL_TRANSMIT, onDismiss)
        },
    )
}

/** A Flash text field: hairline box, composer fill, label above, supporting line below. */
@Composable
private fun RadioCallsignField(
    value: String,
    isError: Boolean,
    supporting: String,
    onValueChange: (String) -> Unit,
) {
    val colors = FlashTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        FlashText(
            text = FlashRadioCopy.CALLSIGN_LABEL,
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(FlashSpacing.space4))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RadioControlShape)
                .background(colors.composerInputBackground)
                .border(
                    width = FlashDimensions.borderHairline,
                    color = if (isError) colors.textError else colors.borderSubtle,
                    shape = RadioControlShape,
                )
                .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space12),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accentPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = FlashRadioCopy.CALLSIGN_LABEL },
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        FlashText(
                            text = FlashRadioCopy.CALLSIGN_LABEL,
                            style = FlashTheme.typography.bodyDefault,
                            color = colors.textTertiary,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(modifier = Modifier.height(FlashSpacing.space4))
        FlashText(
            text = supporting,
            style = FlashTheme.typography.metadataDefault,
            color = if (isError) colors.textError else colors.textTertiary,
        )
    }
}
