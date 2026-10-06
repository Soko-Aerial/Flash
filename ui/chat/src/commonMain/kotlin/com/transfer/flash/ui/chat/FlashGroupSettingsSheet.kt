package com.transfer.flash.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.group.GroupLocalPreferences
import com.transfer.flash.core.messaging.protocol.GroupSettings
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-053: Group settings bottom sheet.
 * Presents signed group rules (admin-only) and device-local preferences (all members).
 */
@Composable
public fun FlashGroupSettingsSheet(
    settings: GroupSettings?,
    preferences: GroupLocalPreferences?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isOwner: Boolean = false,
    isAdmin: Boolean = false,
    canShareInvite: Boolean = false,
    onUpdateSettings: (joinPolicy: String?, inviteSharers: String?, maxMembers: Int?, swarmServing: Boolean?, membersMayAdd: Boolean?) -> Unit = { _, _, _, _, _ -> },
    onUpdatePreferences: (serveToGroup: Boolean?, serveWifiOnly: Boolean?, batteryThreshold: Int?, keepDays: Int?) -> Unit = { _, _, _, _ -> },
    onShareInvite: (() -> Unit)? = null,
    onChangeGroupCode: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val canEdit = remember(isOwner, isAdmin) { FlashGroupSettingsMath.canEditGroupRules(isOwner, isAdmin) }
    val effectiveSettings = settings ?: GroupSettings.defaults("preview")
    val effectivePrefs = preferences ?: GroupLocalPreferences.defaults("preview")

    var showRotateConfirm by remember { mutableStateOf(false) }

    FlashSheetHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FlashSpacing.space12)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.borderSubtle),
            )
        },
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
            // Sheet Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = FlashSpacing.space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText(
                    text = "Group Settings",
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (canEdit) {
                    Box(
                        modifier = Modifier
                            .clip(FlashShapes.chip)
                            .background(colors.accentPrimary.copy(alpha = 0.12f))
                            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                    ) {
                        FlashText(
                            text = if (isOwner) "Owner" else "Admin",
                            style = FlashTheme.typography.metadataEmphasis,
                            color = colors.accentPrimary,
                        )
                    }
                }
            }

            // Section 1: Group Rules (Signed, applies to all)
            SectionHeader(
                title = "Group rules",
                subtitle = "Signed by admins · Applies to all members",
            )

            if (!canEdit) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = FlashSpacing.space12)
                        .clip(FlashShapes.bubbleGrouped)
                        .background(colors.backgroundSurfaceSubtle)
                        .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                ) {
                    FlashText(
                        text = "Only group admins can change group rules.",
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                    )
                }
            }

            // Join Policy
            val isApprovalRequired = effectiveSettings.joinPolicy.equals(GroupSettings.POLICY_APPROVE, ignoreCase = true)
            SettingsSwitchRow(
                title = "Require approval to join",
                description = FlashGroupSettingsMath.joinPolicyDescription(effectiveSettings.joinPolicy),
                checked = isApprovalRequired,
                enabled = canEdit,
                onCheckedChange = { checked ->
                    val newPolicy = if (checked) GroupSettings.POLICY_APPROVE else GroupSettings.POLICY_OPEN
                    onUpdateSettings(newPolicy, null, null, null, null)
                },
            )

            Divider()

            // Invite Sharers
            val isAdminsOnlySharers = effectiveSettings.inviteSharers.equals(GroupSettings.SHARERS_ADMINS, ignoreCase = true)
            SettingsSwitchRow(
                title = "Admins only share invites",
                description = FlashGroupSettingsMath.inviteSharersDescription(effectiveSettings.inviteSharers),
                checked = isAdminsOnlySharers,
                enabled = canEdit,
                onCheckedChange = { checked ->
                    val newSharers = if (checked) GroupSettings.SHARERS_ADMINS else GroupSettings.SHARERS_ALL
                    onUpdateSettings(null, newSharers, null, null, null)
                },
            )

            Divider()

            // Members May Add
            SettingsSwitchRow(
                title = "Allow members to add others",
                description = FlashGroupSettingsMath.membersMayAddDescription(effectiveSettings.membersMayAdd),
                checked = effectiveSettings.membersMayAdd,
                enabled = canEdit,
                onCheckedChange = { checked ->
                    onUpdateSettings(null, null, null, null, checked)
                },
            )

            Divider()

            // Swarm File Sharing
            SettingsSwitchRow(
                title = "Group file swarming",
                description = FlashGroupSettingsMath.swarmServingDescription(effectiveSettings.swarmServing),
                checked = effectiveSettings.swarmServing,
                enabled = canEdit,
                onCheckedChange = { checked ->
                    onUpdateSettings(null, null, null, checked, null)
                },
            )

            Divider()

            // Capacity Stepper
            CapacityStepperRow(
                maxMembers = effectiveSettings.maxMembers,
                enabled = canEdit,
                onDelta = { delta ->
                    val updated = FlashGroupSettingsMath.clampMaxMembers(effectiveSettings.maxMembers, delta)
                    if (updated != effectiveSettings.maxMembers) {
                        onUpdateSettings(null, null, updated, null, null)
                    }
                },
            )

            Spacer(modifier = Modifier.height(FlashSpacing.space24))

            // Section 2: This device (Local preferences)
            SectionHeader(
                title = "This device",
                subtitle = "Stored locally · Applies only to this device",
            )

            // Help share files
            SettingsSwitchRow(
                title = "Help share group files",
                description = FlashGroupSettingsMath.serveToGroupDescription(effectivePrefs.serveToGroup),
                checked = effectivePrefs.serveToGroup,
                enabled = true,
                onCheckedChange = { checked ->
                    onUpdatePreferences(checked, null, null, null)
                },
            )

            Divider()

            // Wi-Fi only
            SettingsSwitchRow(
                title = "Share over Wi-Fi only",
                description = FlashGroupSettingsMath.serveWifiOnlyDescription(effectivePrefs.serveWifiOnly),
                checked = effectivePrefs.serveWifiOnly,
                enabled = true,
                onCheckedChange = { checked ->
                    onUpdatePreferences(null, checked, null, null)
                },
            )

            Divider()

            // Battery threshold
            ValueStepperRow(
                title = "Battery pause threshold",
                valueLabel = FlashGroupSettingsMath.batteryThresholdLabel(effectivePrefs.batteryThresholdPercent),
                description = FlashGroupSettingsMath.batteryThresholdDescription(effectivePrefs.batteryThresholdPercent),
                onDecrement = {
                    val newVal = (effectivePrefs.batteryThresholdPercent - 5).coerceIn(5, 50)
                    onUpdatePreferences(null, null, newVal, null)
                },
                onIncrement = {
                    val newVal = (effectivePrefs.batteryThresholdPercent + 5).coerceIn(5, 50)
                    onUpdatePreferences(null, null, newVal, null)
                },
            )

            Divider()

            // Keep available days
            ValueStepperRow(
                title = "Keep files available",
                valueLabel = FlashGroupSettingsMath.keepAvailableDaysLabel(effectivePrefs.keepAvailableDays),
                description = FlashGroupSettingsMath.keepAvailableDaysDescription(effectivePrefs.keepAvailableDays),
                onDecrement = {
                    val newVal = (effectivePrefs.keepAvailableDays - 1).coerceIn(1, 30)
                    onUpdatePreferences(null, null, null, newVal)
                },
                onIncrement = {
                    val newVal = (effectivePrefs.keepAvailableDays + 1).coerceIn(1, 30)
                    onUpdatePreferences(null, null, null, newVal)
                },
            )

            Spacer(modifier = Modifier.height(FlashSpacing.space24))

            // Section 3: Actions (Invites & Group Code)
            if (canShareInvite || canEdit) {
                SectionHeader(
                    title = "Group code & invites",
                    subtitle = "Manage entry points to this group",
                )

                if (canShareInvite && onShareInvite != null) {
                    ActionRow(
                        title = "Share invite link",
                        icon = FlashIcons.Share,
                        onClick = onShareInvite,
                    )
                }

                if (canEdit && onChangeGroupCode != null) {
                    ActionRow(
                        title = "Change group code",
                        icon = FlashIcons.Retry,
                        isDestructive = true,
                        onClick = { showRotateConfirm = true },
                    )
                }
            }
        }
    }

    if (showRotateConfirm && onChangeGroupCode != null) {
        FlashRotateCodeDialog(
            onConfirm = {
                showRotateConfirm = false
                onChangeGroupCode()
            },
            onDismiss = { showRotateConfirm = false },
        )
    }
}

@Composable
public fun FlashRotateCodeDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        title = {
            FlashText(
                text = "Change group code?",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
        },
        text = {
            FlashText(
                text = "Existing invite links will stop working immediately. Current group members stay in the group and receive the new code automatically.",
                style = FlashTheme.typography.bodyDefault,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                androidx.compose.material3.Text("Change code", color = colors.textError)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    val colors = FlashTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
    ) {
        FlashText(
            text = title,
            style = FlashTheme.typography.headingSmall,
            color = colors.textPrimary,
        )
        FlashText(
            text = subtitle,
            style = FlashTheme.typography.metadataDefault,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                } else {
                    Modifier
                },
            )
            .padding(vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
        ) {
            FlashText(
                text = title,
                style = FlashTheme.typography.bodyEmphasis,
                color = if (enabled) colors.textPrimary else colors.textTertiary,
            )
            FlashText(
                text = description,
                style = FlashTheme.typography.metadataDefault,
                color = if (enabled) colors.textSecondary else colors.textTertiary,
            )
        }
        Spacer(modifier = Modifier.width(FlashSpacing.space12))
        CustomSwitch(
            checked = checked,
            enabled = enabled,
        )
    }
}

@Composable
private fun CapacityStepperRow(
    maxMembers: Int,
    enabled: Boolean,
    onDelta: (Int) -> Unit,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
        ) {
            FlashText(
                text = "Maximum group size",
                style = FlashTheme.typography.bodyEmphasis,
                color = if (enabled) colors.textPrimary else colors.textTertiary,
            )
            FlashText(
                text = FlashGroupSettingsMath.maxMembersLabel(maxMembers),
                style = FlashTheme.typography.metadataDefault,
                color = if (enabled) colors.textSecondary else colors.textTertiary,
            )
        }
        if (enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                StepperButton(
                    text = "−",
                    onClick = { onDelta(-5) },
                )
                StepperButton(
                    text = "+",
                    onClick = { onDelta(5) },
                )
            }
        }
    }
}

@Composable
private fun ValueStepperRow(
    title: String,
    valueLabel: String,
    description: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashText(
                    text = title,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                )
                FlashText(
                    text = "($valueLabel)",
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.accentPrimary,
                )
            }
            FlashText(
                text = description,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            StepperButton(
                text = "−",
                onClick = onDecrement,
            )
            StepperButton(
                text = "+",
                onClick = onIncrement,
            )
        }
    }
}

@Composable
private fun StepperButton(
    text: String,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.backgroundSurfaceSubtle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = text,
            style = FlashTheme.typography.bodyEmphasis,
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    icon: com.transfer.flash.ui.icons.FlashIconSpec,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FlashShapes.bubbleGrouped)
            .clickable(onClick = onClick)
            .padding(vertical = FlashSpacing.space12, horizontal = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashIcon(
            icon = icon,
            contentDescription = title,
            tint = if (isDestructive) colors.textError else colors.accentPrimary,
        )
        FlashText(
            text = title,
            style = FlashTheme.typography.bodyEmphasis,
            color = if (isDestructive) colors.textError else colors.accentPrimary,
        )
    }
}

@Composable
private fun Divider() {
    val colors = FlashTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FlashDimensions.borderHairline)
            .background(colors.borderSubtle),
    )
}

/** Flash-drawn custom switch token: track + animated spring thumb. */
@Composable
private fun CustomSwitch(checked: Boolean, enabled: Boolean) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val thumbOffset = animateDpAsState(
        targetValue = if (checked) 22.dp else FlashSpacing.space2,
        animationSpec = motion.springSnappySpec(),
        label = "customSwitchThumb",
    )
    val trackTint by animateColorAsState(
        targetValue = when {
            !enabled -> colors.borderSubtle
            checked -> colors.accentPrimary
            else -> colors.borderStrong
        },
        animationSpec = motion.tweenFastSpec(),
        label = "customSwitchTrack",
    )
    val thumbTint by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textTertiary
            checked -> colors.textOnAccent
            else -> colors.backgroundSurface
        },
        animationSpec = motion.tweenFastSpec(),
        label = "customSwitchThumbTint",
    )
    Box(
        Modifier
            .width(44.dp)
            .height(24.dp)
            .clip(FlashShapes.bubbleGrouped)
            .background(trackTint),
    ) {
        Box(
            Modifier
                .offset {
                    IntOffset(thumbOffset.value.roundToPx(), FlashSpacing.space2.roundToPx())
                }
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbTint),
        )
    }
}
