package com.transfer.flash.ui.nearby

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Dialog allowing the user to manually enter an IP address and port to connect to a Flash peer on the LAN.
 *
 * Uses [FlashConfirmHost] for multiplatform overlay compatibility across Android and Compose Desktop.
 */
@Composable
fun FlashManualConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String, port: Int) -> Unit,
    isConnecting: Boolean = false,
    errorMessage: String? = null,
    onClearError: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }

    val parsedPort = portText.trim().toIntOrNull()
    val isPortValid = portText.isBlank() || (parsedPort != null && parsedPort in 1..65535)
    val canConnect = host.isNotBlank() && isPortValid && !isConnecting

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        disabledTextColor = colors.textTertiary,
        cursorColor = colors.accentFill,
        focusedBorderColor = colors.accentFill,
        unfocusedBorderColor = colors.borderDefault,
        errorBorderColor = colors.textError,
        focusedLabelColor = colors.textLink,
        unfocusedLabelColor = colors.textSecondary,
        focusedContainerColor = colors.composerInputBackground,
        unfocusedContainerColor = colors.composerInputBackground,
    )

    FlashConfirmHost(
        onDismiss = { if (!isConnecting) onDismiss() },
        containerColor = colors.backgroundSurface,
        title = {
            FlashText(
                text = "Connect by IP",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                FlashText(
                    text = "Enter the IP address and port shown on the other device's Nearby screen.",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        onClearError()
                    },
                    enabled = !isConnecting,
                    singleLine = true,
                    textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                    shape = FlashShapes.attachment,
                    colors = fieldColors,
                    label = { FlashText("IP Address / Host", style = FlashTheme.typography.captionDefault, color = colors.textSecondary) },
                    placeholder = { FlashText("192.168.1.100", style = FlashTheme.typography.bodyDefault, color = colors.textTertiary) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { next ->
                        portText = next.filter { it.isDigit() }
                        onClearError()
                    },
                    enabled = !isConnecting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                    shape = FlashShapes.attachment,
                    colors = fieldColors,
                    label = { FlashText("Port (default 45822)", style = FlashTheme.typography.captionDefault, color = colors.textSecondary) },
                    placeholder = { FlashText("45822", style = FlashTheme.typography.bodyDefault, color = colors.textTertiary) },
                    isError = !isPortValid,
                    supportingText = if (!isPortValid) {
                        { FlashText("Port must be between 1 and 65535", style = FlashTheme.typography.metadataDefault, color = colors.textError) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!errorMessage.isNullOrBlank()) {
                    FlashText(
                        text = errorMessage,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textError,
                    )
                }
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(FlashShapes.bubbleGrouped)
                    .background(if (canConnect) colors.accentFill else colors.backgroundSurfaceStrong)
                    .clickable(enabled = canConnect) {
                        val targetPort = portText.trim().toIntOrNull() ?: 45822
                        onConnect(host.trim(), targetPort)
                    }
                    .semantics { role = Role.Button }
                    .padding(horizontal = FlashSpacing.space16),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = if (isConnecting) "Connecting…" else "Connect",
                    style = FlashTheme.typography.captionEmphasis,
                    color = if (canConnect) colors.onAccentFill else colors.textTertiary,
                )
            }
        },
        dismissButton = {
            Box(
                modifier = Modifier
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(FlashShapes.bubbleGrouped)
                    .clickable(enabled = !isConnecting, onClick = onDismiss)
                    .semantics { role = Role.Button }
                    .padding(horizontal = FlashSpacing.space12),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Cancel",
                    style = FlashTheme.typography.captionEmphasis,
                    color = if (isConnecting) colors.textTertiary else colors.textSecondary,
                )
            }
        },
    )
}
