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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.unit.sp
import com.transfer.flash.core.radio.connect.RadioCallsign
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.chat.FlashSheetHost
import com.transfer.flash.ui.hub.FlashHubButton
import com.transfer.flash.ui.hub.FlashHubChip
import com.transfer.flash.ui.hub.FlashHubIconButton
import com.transfer.flash.ui.hub.FlashHubSwitch
import com.transfer.flash.ui.hub.FlashHubTextAction
import com.transfer.flash.ui.hub.HubLiveDot
import com.transfer.flash.ui.hub.HubShapes
import com.transfer.flash.ui.hub.HubTone
import com.transfer.flash.ui.hub.color
import com.transfer.flash.ui.hub.hubClickable
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/*
 * Redesign v3 (docs/ui/radio-gateway.md, "Redesign v3", 2026-11-05).
 *
 * The rule this file follows: a border and a fill are information, not decoration. The page is ONE station plate
 * (the radio and its link), one audio panel (listen + hold to talk), a list of the radios the operator owns with the
 * one pairing action, and the readiness steps. Security profile, operational mode and the RF test frame moved into
 * the Radio settings sheet, so the page answers "can I talk now, and how do I add a radio?" and nothing else.
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

/** The short profile name used in the link facts ("M · Encrypted"). */
internal fun shortProfileName(profile: RadioSecurityProfile): String = when (profile) {
    RadioSecurityProfile.PROFILE_M -> FlashRadioCopy.PROFILE_M_SHORT
    RadioSecurityProfile.PROFILE_A -> FlashRadioCopy.PROFILE_A_SHORT
}

/** A section label: sentence case, 13 sp, just above its rows. No caps, no rule, no box. */
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
            .padding(top = FlashSpacing.space8, bottom = FlashSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashText(
            text = text,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = FlashTheme.typography.metadataEmphasis.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action?.invoke()
    }
}

/** A switch with its 48 dp touch area and a spoken name; the only toggle control on this page. */
@Composable
internal fun RadioSwitch(on: Boolean, a11yLabel: String, onToggle: (Boolean) -> Unit) {
    val colors = FlashTheme.colors
    // The track alone is one tone step from the surface (WCAG 1.4.11 wants 3:1 for a control boundary), so the
    // hit area carries a hairline capsule in a tone that is actually visible on both the plate and the audio panel.
    Box(
        modifier = Modifier
            .size(FlashDimensions.minTouchTarget)
            .hubClickable { onToggle(!on) }
            .semantics {
                contentDescription = a11yLabel
                stateDescription = if (on) FlashRadioCopy.VALUE_ON else FlashRadioCopy.VALUE_OFF
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(HubShapes.capsule)
                .border(
                    width = FlashDimensions.borderHairline,
                    color = if (on) colors.accentPrimary else colors.textTertiary,
                    shape = HubShapes.capsule,
                )
                .padding(2.dp),
        ) {
            FlashHubSwitch(on = on)
        }
    }
}

/** One "label — value" row of the link facts. */
@Composable
internal fun RadioFactRow(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = label,
            modifier = Modifier.widthIn(min = 92.dp),
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.width(FlashSpacing.space12))
        FlashText(
            text = value,
            modifier = Modifier.weight(1f),
            style = FlashTheme.typography.captionDefault,
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
                color = if (selected) colors.accentPrimary else colors.textTertiary,
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
 * A settings choice row (security profile, operational mode): ring, title, optional chip, subtitle. Rows, not
 * cards: the selected state is the ring and the title colour.
 */
@Composable
internal fun RadioChoiceRow(
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null && badge.isNotBlank()) {
                    FlashHubChip(text = badge, emphasised = badgeEmphasised)
                }
            }
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.metadataDefault.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = if (enabled) colors.textSecondary else colors.textTertiary,
            )
        }
    }
}

// --- Station plate (UI-070 / UI-073) ----------------------------------------------------------------------

/**
 * The one hero: the radio, its live link and the master switch. Everything else is a section of rows, so this is
 * the only filled surface that carries identity.
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
    val hasRadio = state.rememberedRadioName != null || state.candidateRadio != null
    val sentence = FlashRadioMath.statusSentence(
        state.connectionStatus,
        state.failureReason,
        state.candidateRadio?.name ?: state.rememberedRadioName,
    )
    val nextStep = FlashRadioMath.statusNextStep(state.connectionStatus, state.failureReason)
    val stationName = state.rememberedRadioName ?: state.candidateRadio?.name ?: FlashRadioCopy.STATION_NO_RADIO
    val live = state.connectionStatus == RadioConnectionStatus.CONNECTED
    val canReconnect = state.enabled &&
        FlashRadioMath.canReconnect(state.connectionStatus, state.rememberedRadioAddress != null)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadioCardShape)
            .background(if (state.enabled) colors.backgroundSurface else colors.backgroundSurfaceSubtle)
            .padding(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlashIcon(
                icon = if (hasRadio) FlashIcons.Device else FlashIcons.Bluetooth,
                contentDescription = null,
                tint = if (state.enabled) tint else colors.textTertiary,
                size = 22.dp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlashText(
                    text = stationName,
                    modifier = Modifier.semantics { heading() },
                    style = FlashTheme.typography.headingSmall.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    HubLiveDot(
                        color = if (state.enabled) tint else colors.textTertiary,
                        live = live,
                        size = 7.dp,
                    )
                    FlashText(
                        text = sentence,
                        style = FlashTheme.typography.captionDefault,
                        color = if (state.enabled) colors.textSecondary else colors.textTertiary,
                        maxLines = 2,
                    )
                }
            }
            RadioSwitch(
                on = state.enabled,
                a11yLabel = FlashRadioCopy.A11Y_MASTER_SWITCH,
                onToggle = onToggle,
            )
        }

        if (state.enabled && nextStep.isNotBlank()) {
            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            FlashText(
                text = nextStep,
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary,
            )
        }

        val facts = listOfNotNull(
            state.rememberedRadioAddress?.takeIf { it.isNotBlank() },
        )
        if (facts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            FlashText(
                text = FlashRadioCopy.linkFacts(facts.joinToString(" · ")),
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!state.enabled) {
            // A first-timer should not have to find a 40 dp switch in the corner: the plate carries the one action.
            Spacer(modifier = Modifier.height(FlashSpacing.space16))
            FlashHubButton(
                label = FlashRadioCopy.ACTION_TURN_ON,
                onClick = { onToggle(true) },
                modifier = Modifier.fillMaxWidth(),
                icon = FlashIcons.Bluetooth,
            )
        }

        if (canReconnect) {
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

// --- Attention strips -------------------------------------------------------------------------------------

/**
 * The permission strip. It is a strip (tone wash, no border), not a box: it only appears when something is
 * actually blocked, and then it is the loudest thing on the page.
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
            FlashHubButton(
                label = FlashRadioCopy.ACTION_GRANT_PERMISSION,
                onClick = onRequestPermission,
            )
        }
    }
}

/** "<name> is paired already, use it?" as a strip: the one question that needs an answer before anything else. */
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

// --- Your radios (UI-071) ---------------------------------------------------------------------------------

/**
 * The radios this device already knows, plus the one filled action that starts pairing. Each row is one device;
 * the radio in use is marked with a chip, the others offer "Use".
 */
@Composable
public fun FlashRadioRadiosSection(
    state: RadioGatewayUiState,
    onPair: (String) -> Unit,
    onPairNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val otherPaired = remember(state.discoveredDevices, state.rememberedRadioAddress) {
        state.discoveredDevices.filter { it.isPaired && it.id != state.rememberedRadioAddress }
    }
    val count = (if (state.rememberedRadioName != null) 1 else 0) + otherPaired.size
    val connected = state.connectionStatus == RadioConnectionStatus.CONNECTED

    Column(modifier = modifier.fillMaxWidth()) {
        FlashRadioSectionHeader(
            text = FlashRadioCopy.SECTION_RADIOS,
            action = {
                FlashText(
                    text = if (count == 0) FlashRadioCopy.RADIOS_NONE else FlashRadioCopy.radiosCount(count),
                    style = FlashTheme.typography.metadataDefault,
                    color = FlashTheme.colors.textSecondary,
                )
            },
        )

        state.rememberedRadioName?.let { name ->
            RadioKnownDeviceRow(
                name = name,
                address = state.rememberedRadioAddress.orEmpty(),
                chip = if (connected) FlashRadioCopy.CHIP_IN_USE else FlashRadioCopy.CHIP_LINK_LOST,
                chipTone = if (connected) HubTone.SUCCESS else HubTone.WARNING,
            )
        }

        otherPaired.forEach { device ->
            RadioKnownDeviceRow(
                name = device.name,
                address = device.id,
                action = {
                    Box(
                        modifier = Modifier.semantics {
                            contentDescription = FlashRadioCopy.a11yUseDevice(device.name)
                        },
                    ) {
                        FlashHubTextAction(FlashRadioCopy.ACTION_USE_RADIO, onClick = { onPair(device.id) })
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(FlashSpacing.space16))
        FlashHubButton(
            label = FlashRadioCopy.ACTION_PAIR_NEW_RADIO,
            onClick = onPairNew,
            modifier = Modifier.fillMaxWidth(),
            icon = FlashIcons.Bluetooth,
            // One filled control per state: once a radio is set up, pairing steps back to a quiet button.
            primary = state.rememberedRadioName == null,
        )
    }
}

@Composable
private fun RadioKnownDeviceRow(
    name: String,
    address: String,
    chip: String? = null,
    chipTone: HubTone = HubTone.NEUTRAL,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashIcon(
            icon = FlashIcons.Device,
            contentDescription = null,
            tint = colors.textSecondary,
            size = 20.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
        ) {
            FlashText(
                text = name,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashText(
                    text = address,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chip != null) {
                    FlashHubChip(text = chip, emphasised = chipTone == HubTone.SUCCESS)
                }
            }
        }
        action?.invoke()
    }
}

// --- Readiness steps --------------------------------------------------------------------------------------

/**
 * The three preparation steps. Shown expanded before anything is paired (and while the gateway is off), and as a
 * disclosure once a radio is set up, so it never competes with the live link.
 */
@Composable
public fun FlashRadioReadinessSection(
    title: String,
    expanded: Boolean,
    alwaysExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        if (alwaysExpanded) {
            FlashRadioSectionHeader(text = title)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .hubClickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashText(
                    text = FlashRadioCopy.HELP_NOT_SHOWING,
                    modifier = Modifier.weight(1f),
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textSecondary,
                )
                FlashIcon(
                    icon = FlashIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    size = FlashDimensions.iconSm,
                    modifier = Modifier.rotate(if (expanded) 0f else -90f),
                )
            }
        }
        if (alwaysExpanded || expanded) {
            if (alwaysExpanded) {
                Spacer(modifier = Modifier.height(FlashSpacing.space4))
            }
            FlashRadioChecklistStep(FlashRadioCopy.CHECKLIST_STEP_1)
            FlashRadioChecklistStep(FlashRadioCopy.CHECKLIST_STEP_2)
            FlashRadioChecklistStep(FlashRadioCopy.CHECKLIST_STEP_3)
        }
    }
}

@Composable
private fun FlashRadioChecklistStep(text: String) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space4),
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
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
        )
    }
}

// --- This link (UI-073) -----------------------------------------------------------------------------------

/** The operating facts of the live link: what is actually on the air when the operator speaks. */
@Composable
public fun FlashRadioLinkFactsSection(
    state: RadioGatewayUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FlashRadioSectionHeader(FlashRadioCopy.SECTION_LINK)
        RadioFactRow(FlashRadioCopy.FACT_PROFILE, shortProfileName(state.selectedProfile))
        RadioFactRow(
            label = FlashRadioCopy.FACT_CALLSIGN,
            value = state.operatorCallsign ?: FlashRadioCopy.FACT_NONE,
        )
        RadioFactRow(FlashRadioCopy.FACT_MODE, FlashRadioMath.modeTitle(state.selectedMode))
    }
}

// --- Pair sheet (UI-071) ----------------------------------------------------------------------------------

/**
 * The pairing sheet. It exists so the page can offer one button: opening it starts the scan (the existing
 * [RadioGatewayController.startScan]), closing it stops the scan again.
 */
@Composable
public fun FlashRadioPairSheet(
    state: RadioGatewayUiState,
    onDismiss: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPair: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val sorted = remember(state.discoveredDevices) {
        FlashRadioMath.sortDiscoveredDevices(state.discoveredDevices)
    }

    LaunchedEffect(Unit) {
        if (!state.isScanning) onStartScan()
    }

    FlashSheetHost(
        onDismiss = {
            onStopScan()
            onDismiss()
        },
        containerColor = colors.backgroundSurface,
        dragHandle = { RadioSheetHandle() },
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
                modifier = Modifier.fillMaxWidth().padding(bottom = FlashSpacing.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText(
                    text = FlashRadioCopy.PAIR_SHEET_TITLE,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                FlashHubIconButton(FlashIcons.Close, FlashRadioCopy.A11Y_PAIR_SHEET_CLOSE, onDismiss)
            }
            FlashText(
                text = FlashRadioCopy.PAIR_SHEET_SUBTITLE,
                style = FlashTheme.typography.metadataDefault.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space12))

            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                HubLiveDot(color = colors.accentPrimary, live = state.isScanning, size = 7.dp)
                FlashText(
                    text = FlashRadioMath.scanStatusText(state.isScanning),
                    modifier = Modifier.weight(1f),
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textSecondary,
                )
                if (state.isScanning) {
                    FlashHubTextAction(
                        label = FlashRadioCopy.actionStopScan(state.scanCountdownSeconds),
                        onClick = onStopScan,
                    )
                } else {
                    FlashHubTextAction(
                        label = FlashRadioCopy.ACTION_SCAN_RADIOS,
                        onClick = onStartScan,
                    )
                }
            }

            val permissionNeeded = !state.nearbyDevicesPermissionGranted ||
                state.failureReason == RadioFailureReason.PERMISSION_MISSING ||
                state.failureReason == RadioFailureReason.PERMISSION_DENIED
            if (permissionNeeded) {
                Spacer(modifier = Modifier.height(FlashSpacing.space8))
                FlashRadioPermissionBanner(
                    isDenied = state.failureReason == RadioFailureReason.PERMISSION_DENIED,
                    onRequestPermission = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                )
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            if (sorted.isEmpty()) {
                FlashText(
                    text = FlashRadioCopy.SCAN_NO_DEVICES,
                    modifier = Modifier.padding(vertical = FlashSpacing.space12),
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textSecondary,
                )
            } else {
                sorted.forEach { device ->
                    RadioDiscoveredRow(
                        device = device,
                        onPair = { onPair(device.id) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space12))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = FlashDimensions.minTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                FlashText(
                    text = FlashRadioCopy.SCAN_BATTERY_NOTE,
                    modifier = Modifier.weight(1f),
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                )
                FlashHubTextAction(FlashRadioCopy.ACTION_OPEN_BT_SETTINGS, onOpenSettings)
            }
        }
    }
}

@Composable
private fun RadioDiscoveredRow(device: DiscoveredRadioDevice, onPair: () -> Unit) {
    val colors = FlashTheme.colors
    val emphasised = device.category == RadioDeviceCategory.KNOWN_RADIO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashIcon(
            icon = if (emphasised) FlashIcons.Device else FlashIcons.Bluetooth,
            contentDescription = null,
            tint = if (emphasised) colors.textPrimary else colors.textSecondary,
            size = 20.dp,
        )
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
                FlashHubChip(text = FlashRadioMath.categoryBadgeText(device.category), emphasised = emphasised)
                device.rssi?.let { rssi ->
                    FlashText(
                        text = FlashRadioCopy.signalHint(rssi),
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
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
                modifier = Modifier.semantics {
                    contentDescription = FlashRadioCopy.a11yUseDevice(device.name)
                },
            ) {
                FlashHubTextAction(FlashRadioCopy.ACTION_USE_RADIO, onPair)
            }
            else -> Box(
                modifier = Modifier.semantics {
                    contentDescription = FlashRadioCopy.a11yPairDevice(device.name)
                },
            ) {
                FlashHubTextAction(FlashRadioCopy.ACTION_PAIR, onPair)
            }
        }
    }
}

// --- Settings sheet (UI-072 / UI-074 / diagnostics) -------------------------------------------------------

/**
 * The less-used controls, off the page: security profile, operational mode, the RF test frame and Forget. Same
 * behaviour, validation and copy as before; only the container moved.
 */
@Composable
public fun FlashRadioSettingsSheet(
    state: RadioGatewayUiState,
    canSendTestMessage: Boolean,
    onDismiss: () -> Unit,
    onSelectProfile: (RadioSecurityProfile) -> Unit,
    onOpenAuthorisation: () -> Unit,
    onSelectMode: (RadioGatewayMode) -> Unit,
    onRequestSendTest: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val canSelectM = FlashRadioMath.isProfileMSelectable(state.isAuthorisedForEncryption)
    val modes = remember { FlashRadioMath.visibleModes() }

    FlashSheetHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        dragHandle = { RadioSheetHandle() },
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
                modifier = Modifier.fillMaxWidth().padding(bottom = FlashSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText(
                    text = FlashRadioCopy.SETTINGS_SHEET_TITLE,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                FlashHubIconButton(FlashIcons.Close, FlashRadioCopy.A11Y_SETTINGS_SHEET_CLOSE, onDismiss)
            }

            FlashRadioSectionHeader(FlashRadioCopy.SECURITY_SECTION_TITLE)
            Column(modifier = Modifier.selectableGroup()) {
                RadioChoiceRow(
                    title = FlashRadioCopy.PROFILE_M_TITLE,
                    subtitle = FlashRadioCopy.PROFILE_M_SUBTITLE,
                    badge = if (state.isAuthorisedForEncryption) {
                        FlashRadioCopy.BADGE_AUTHORISED
                    } else {
                        FlashRadioCopy.PROFILE_M_NEEDS_AUTH_BADGE
                    },
                    badgeEmphasised = state.isAuthorisedForEncryption,
                    selected = state.selectedProfile == RadioSecurityProfile.PROFILE_M,
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
                    selected = state.selectedProfile == RadioSecurityProfile.PROFILE_A,
                    a11yLabel = FlashRadioCopy.A11Y_SELECT_PROFILE_A,
                    onClick = { onSelectProfile(RadioSecurityProfile.PROFILE_A) },
                )
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space20))
            FlashRadioSectionHeader(FlashRadioCopy.MODES_SECTION_TITLE)
            Column(modifier = Modifier.selectableGroup()) {
                modes.forEach { mode ->
                    val selectable = FlashRadioMath.isModeSelectable(mode, state.availableModes)
                    RadioChoiceRow(
                        title = FlashRadioMath.modeTitle(mode),
                        subtitle = FlashRadioMath.modeSubtitle(mode),
                        badge = FlashRadioMath.modeBadgeText(mode, state.selectedMode, state.availableModes),
                        badgeEmphasised = mode == state.selectedMode,
                        selected = mode == state.selectedMode,
                        a11yLabel = FlashRadioCopy.a11ySelectMode(FlashRadioMath.modeTitle(mode)),
                        onClick = { onSelectMode(mode) },
                        enabled = selectable,
                    )
                }
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space20))
            FlashRadioSectionHeader(FlashRadioCopy.SECTION_THIS_RADIO)
            RadioFactRow(FlashRadioCopy.FACT_RADIO, state.rememberedRadioName ?: FlashRadioCopy.FACT_NONE)
            RadioFactRow(FlashRadioCopy.FACT_ADDRESS, state.rememberedRadioAddress ?: FlashRadioCopy.FACT_NONE)
            RadioFactRow(
                label = FlashRadioCopy.FACT_CALLSIGN,
                value = state.operatorCallsign ?: FlashRadioCopy.FACT_NONE,
            )

            Spacer(modifier = Modifier.height(FlashSpacing.space8))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
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
                        color = if (canSendTestMessage) colors.textPrimary else colors.textTertiary,
                    )
                    FlashText(
                        text = FlashRadioCopy.TEST_MESSAGE_NEEDS_LINK,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                    )
                }
                FlashHubTextAction(
                    label = FlashRadioCopy.ACTION_SEND,
                    onClick = onRequestSendTest,
                    enabled = canSendTestMessage,
                )
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space12))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
                ) {
                    FlashText(
                        text = FlashRadioCopy.ACTION_FORGET_RADIO,
                        style = FlashTheme.typography.bodyEmphasis,
                        color = colors.textError,
                    )
                    FlashText(
                        text = FlashRadioCopy.forgetConsequence(
                            state.rememberedRadioName ?: FlashRadioCopy.STATION_NO_RADIO,
                        ),
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                    )
                }
                FlashHubTextAction(
                    label = FlashRadioCopy.ACTION_FORGET,
                    onClick = onForget,
                    enabled = state.rememberedRadioAddress != null,
                )
            }
        }
    }
}

@Composable
private fun RadioSheetHandle() {
    Box(
        modifier = Modifier
            .padding(vertical = FlashSpacing.space12)
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(FlashTheme.colors.borderSubtle),
    )
}

// --- Sheets (UI-072 authorisation, diagnostics transmit warning) -------------------------------------------

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
