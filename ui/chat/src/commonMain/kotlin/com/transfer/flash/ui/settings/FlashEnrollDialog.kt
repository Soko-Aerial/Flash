@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Dialog for manual enrollment into an enterprise organization hub.
 * Allows employees to paste an HTTPS or flash:// enrollment link.
 */
@Composable
public fun FlashEnrollDialog(
    onDismiss: () -> Unit,
    onEnroll: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialLink: String = "",
    isEnrolling: Boolean = false,
    enrollError: String? = null,
) {
    val colors = FlashTheme.colors
    val clipboardManager = LocalClipboardManager.current
    var inputLink by remember(initialLink) { mutableStateOf(initialLink) }
    val parsed = remember(inputLink) { FlashEnrollMath.parseInvite(inputLink) }

    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        modifier = modifier,
        title = {
            FlashText(
                text = "Enrol in Organization",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                FlashText(
                    text = "Paste the invite link from your organisation administrator.",
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )

                OutlinedTextField(
                    value = inputLink,
                    onValueChange = { inputLink = it },
                    textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                    shape = FlashShapes.attachment,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.accentFill,
                        focusedBorderColor = colors.accentFill,
                        unfocusedBorderColor = colors.borderDefault,
                        focusedPlaceholderColor = colors.textTertiary,
                        unfocusedPlaceholderColor = colors.textTertiary,
                        focusedContainerColor = colors.composerInputBackground,
                        unfocusedContainerColor = colors.composerInputBackground,
                    ),
                    placeholder = {
                        FlashText(
                            text = "Invite link",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textTertiary,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )

                // Paste from clipboard button (>= 48.dp touch target)
                Row(
                    modifier = Modifier
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clip(FlashShapes.chip)
                        .background(colors.backgroundSurfaceSubtle)
                        .clickable(role = Role.Button) {
                            clipboardManager.getText()?.text?.let { pasted ->
                                inputLink = pasted.trim()
                            }
                        }
                        .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    FlashIcon(
                        icon = FlashIcons.Attach,
                        contentDescription = null,
                        tint = colors.textLink,
                        size = FlashDimensions.iconSm,
                    )
                    FlashText(
                        text = "Paste from clipboard",
                        style = FlashTheme.typography.metadataEmphasis,
                        color = colors.textLink,
                    )
                }

                if (parsed != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(FlashShapes.chip)
                            .background(colors.backgroundSurfaceSubtle)
                            .padding(FlashSpacing.space12),
                        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                        ) {
                            FlashIcon(
                                icon = FlashIcons.Verified,
                                contentDescription = null,
                                tint = colors.textLink,
                                size = FlashDimensions.iconSm,
                            )
                            FlashText(
                                text = "Valid Organization Invite",
                                style = FlashTheme.typography.metadataEmphasis,
                                color = colors.textLink,
                            )
                        }
                        FlashText(
                            text = "Hub Server: ${parsed.serverUrl}",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textPrimary,
                        )
                        if (parsed.rootFingerprint.isNotBlank()) {
                            FlashText(
                                text = "Organisation key: ${com.transfer.flash.core.enterprise.HubStatusMath.shortFingerprint(parsed.rootFingerprint)}",
                                style = FlashTheme.typography.metadataDefault,
                                color = colors.textSecondary,
                            )
                        } else {
                            // CLI-01: a link without the organisation key cannot be trusted; say so before the tap.
                            FlashText(
                                text = FlashHubStatusMath.enrollFailureText(
                                    com.transfer.flash.core.enterprise.EnrollFailureReason.INVITE_NOT_PINNED,
                                ),
                                style = FlashTheme.typography.metadataEmphasis,
                                color = colors.textError,
                            )
                        }
                    }
                } else if (inputLink.isNotBlank()) {
                    FlashText(
                        text = "Invalid link: must contain server and token parameters",
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textError,
                    )
                }

                if (enrollError != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(FlashShapes.chip)
                            .background(colors.backgroundSurfaceSubtle)
                            .padding(FlashSpacing.space12),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Failed,
                            contentDescription = null,
                            tint = colors.textError,
                            size = FlashDimensions.iconSm,
                        )
                        FlashText(
                            text = enrollError,
                            style = FlashTheme.typography.metadataEmphasis,
                            color = colors.textError,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (parsed != null) {
                val canEnroll = !isEnrolling && parsed.rootFingerprint.isNotBlank()
                FlashSettingsTextButton(
                    label = if (isEnrolling) "Enrolling..." else "Confirm & Enrol",
                    color = if (canEnroll) colors.textLink else colors.textTertiary,
                    enabled = canEnroll,
                    onClick = { onEnroll(inputLink.trim()) },
                    leading = if (isEnrolling) {
                        {
                            FlashSettingsSpinner(
                                size = FlashDimensions.iconSm,
                                tint = colors.textLink,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        },
        dismissButton = {
            FlashSettingsTextButton(
                label = "Cancel",
                color = if (!isEnrolling) colors.textSecondary else colors.textTertiary,
                enabled = !isEnrolling,
                onClick = onDismiss,
            )
        },
    )
}
