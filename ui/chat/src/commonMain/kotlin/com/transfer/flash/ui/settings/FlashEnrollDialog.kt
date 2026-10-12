package com.transfer.flash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Dialog for manual enrollment into an enterprise organization hub.
 * Allows employees to paste or type a flash://enroll link.
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
    var inputLink by remember { mutableStateOf(initialLink) }
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
                    text = "Paste the invitation link (flash://enroll) provided by your company administrator:",
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )

                OutlinedTextField(
                    value = inputLink,
                    onValueChange = { inputLink = it },
                    placeholder = {
                        Text(
                            text = "flash://enroll?server=...&token=...",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textTertiary,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )

                // Paste from clipboard button
                Row(
                    modifier = Modifier
                        .clip(FlashShapes.chip)
                        .background(colors.backgroundSurfaceSubtle)
                        .clickable {
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
                        contentDescription = "Paste",
                        tint = colors.accentPrimary,
                    )
                    FlashText(
                        text = "Paste from clipboard",
                        style = FlashTheme.typography.metadataEmphasis,
                        color = colors.accentPrimary,
                    )
                }

                if (parsed != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.backgroundSurfaceSubtle)
                            .padding(FlashSpacing.space12),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FlashText(
                            text = "✓ Valid Organization Invite",
                            style = FlashTheme.typography.metadataEmphasis,
                            color = colors.accentPrimary,
                        )
                        FlashText(
                            text = "Hub Server: ${parsed.serverUrl}",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textPrimary,
                        )
                        if (parsed.rootFingerprint.isNotBlank()) {
                            FlashText(
                                text = "Root Fingerprint: ${parsed.rootFingerprint.take(16)}...",
                                style = FlashTheme.typography.metadataDefault,
                                color = colors.textSecondary,
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.backgroundSurfaceSubtle)
                            .padding(FlashSpacing.space12),
                    ) {
                        FlashText(
                            text = "⚠ $enrollError",
                            style = FlashTheme.typography.metadataEmphasis,
                            color = colors.textError,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (parsed != null) {
                TextButton(
                    enabled = !isEnrolling,
                    onClick = { onEnroll(inputLink.trim()) },
                ) {
                    if (isEnrolling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enrolling...", color = colors.accentPrimary)
                    } else {
                        Text("Confirm & Enrol", color = colors.accentPrimary)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isEnrolling,
                onClick = onDismiss,
            ) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}
