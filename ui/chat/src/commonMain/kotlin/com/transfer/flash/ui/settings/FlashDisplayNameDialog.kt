package com.transfer.flash.ui.settings

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Rename this device, as other devices see it.
 *
 * Built on [FlashConfirmHost] rather than Material3's `AlertDialog` so it works on Compose Desktop:
 * that component is window-backed there (`DialogWindow`) and would open outside the app window. On
 * Android `FlashConfirmHost` **is** the real `AlertDialog`, so this renders the same either way.
 *
 * ## Why this lives here and not in `:app`
 *
 * `:app` already has a private `DisplayNameDialog` doing exactly this with Material3 directly. It is
 * not reachable from `:desktop`, which is why the desktop's Identity row — and
 * `DesktopIdentityStore.updateFriendlyName`, which has always worked and persisted — were
 * unreachable: tapping the row did nothing, so every desktop install was named "Flash Desktop"
 * forever.
 *
 * The `:app` copy is deliberately left in place rather than switched over, because that would change
 * Android-visible chrome that nobody has run on a device. Folding it into this one is a one-line
 * change once someone can verify it there; until then the duplication is recorded here rather than
 * hidden.
 *
 * The field is Material3's `OutlinedTextField`, matching what `:app`'s dialog already uses — it is
 * not window-backed, so it composes correctly inside the overlay layer.
 */
@Composable
fun FlashDisplayNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = FlashTheme.colors.backgroundSurface,
        title = {
            Text(
                text = "Display name",
                style = FlashTheme.typography.headingMedium,
                color = FlashTheme.colors.textPrimary,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Visible to nearby devices") },
            )
        },
        confirmButton = {
            TextButton(
                // A blank name would make this device unidentifiable in every peer's list, which is
                // worse than the name it already has — so Save is unavailable rather than trimming
                // to an empty string.
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) {
                Text("Save", color = FlashTheme.colors.accentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = FlashTheme.colors.textSecondary)
            }
        },
    )
}
