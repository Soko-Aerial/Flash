package com.transfer.flash.ui.nearby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.theme.FlashSpacing
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
) {
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }

    val parsedPort = portText.trim().toIntOrNull()
    val isPortValid = portText.isBlank() || (parsedPort != null && parsedPort in 1..65535)
    val canConnect = host.isNotBlank() && isPortValid

    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = FlashTheme.colors.backgroundSurface,
        title = {
            Text(
                text = "Connect by IP",
                style = FlashTheme.typography.headingMedium,
                color = FlashTheme.colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                Text(
                    text = "Enter the IP address and port of the target Flash device on your local network.",
                    style = FlashTheme.typography.metadataDefault,
                    color = FlashTheme.colors.textSecondary,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    singleLine = true,
                    label = { Text("IP Address / Host") },
                    placeholder = { Text("192.168.1.100") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    singleLine = true,
                    label = { Text("Port (default 45822)") },
                    placeholder = { Text("45822") },
                    isError = !isPortValid,
                    supportingText = if (!isPortValid) {
                        { Text("Port must be between 1 and 65535", color = FlashTheme.colors.textError) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConnect,
                onClick = {
                    val targetPort = portText.trim().toIntOrNull() ?: 45822
                    onConnect(host.trim(), targetPort)
                },
            ) {
                Text(
                    text = "Connect",
                    color = if (canConnect) FlashTheme.colors.accentPrimary else FlashTheme.colors.textTertiary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = FlashTheme.colors.textSecondary)
            }
        },
    )
}
