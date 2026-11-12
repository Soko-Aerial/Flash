package com.transfer.flash.ui.settings

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Rename this device, as other devices see it.
 *
 * Built on [FlashConfirmHost] rather than Material3's `AlertDialog` so it works on Compose Desktop:
 * that component is window-backed there (`DialogWindow`) and would open outside the app window. On
 * Android `FlashConfirmHost` **is** the real `AlertDialog`, so this renders the same either way.
 */
@Composable
fun FlashDisplayNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = FlashTheme.colors
    var text by remember { mutableStateOf(initial) }
    val canSave = text.isNotBlank()

    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        title = {
            FlashText(
                text = "Display name",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                shape = FlashShapes.attachment,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    cursorColor = colors.accentFill,
                    focusedBorderColor = colors.accentFill,
                    unfocusedBorderColor = colors.borderDefault,
                    focusedLabelColor = colors.textLink,
                    unfocusedLabelColor = colors.textSecondary,
                    focusedContainerColor = colors.composerInputBackground,
                    unfocusedContainerColor = colors.composerInputBackground,
                ),
                label = {
                    FlashText(
                        text = "Visible to nearby devices",
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                },
            )
        },
        confirmButton = {
            FlashSettingsTextButton(
                label = "Save",
                // A blank name would make this device unidentifiable in every peer's list, which is
                // worse than the name it already has — so Save is unavailable rather than trimming
                // to an empty string.
                color = if (canSave) colors.textLink else colors.textTertiary,
                enabled = canSave,
                onClick = { onConfirm(text.trim()) },
            )
        },
        dismissButton = {
            FlashSettingsTextButton(
                label = "Cancel",
                color = colors.textSecondary,
                onClick = onDismiss,
            )
        },
    )
}
