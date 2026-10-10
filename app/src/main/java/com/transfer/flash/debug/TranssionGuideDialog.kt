package com.transfer.flash.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One-time set-up helper for Transsion phones (HIB-03). Lists [TranssionSetupGuide.pending]
 * steps, each with an "Open" button where a screen exists,.
 *
 * Plain Material 3 on purpose: it is a rarely seen set-up helper, like the display-name dialog,
 * not part of the premium chat UI (AGENTS.md section 34 covers chat components).
 *
 * @param ignoringBatteryOptimizations the live state of Android's own exemption; the dialog
 * hides the first step once it is true. The other steps cannot be read, so they stay listed.
 * @param onOpen opens the screen behind a step (the host calls [OemBatteryOptimizationHelper.openTarget]).
 */
@Composable
fun TranssionGuideDialog(
    ignoringBatteryOptimizations: Boolean,
    onOpen: (TranssionSetupGuide.Target) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasWidget = com.transfer.flash.widget.FlashAppWidgetProvider.hasActiveWidget(context)
    val steps = TranssionSetupGuide.pending(ignoringBatteryOptimizations, hasWidget)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep Flash connected with the screen off") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Your phone pauses apps a few seconds after the screen turns off. " +
                        "Do these once so Flash can keep receiving messages and calls.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                steps.forEachIndexed { index, step ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${index + 1}. ${step.title}", style = MaterialTheme.typography.titleSmall)
                        Text(step.body, style = MaterialTheme.typography.bodySmall)
                        if (step.target != TranssionSetupGuide.Target.NONE) {
                            val buttonLabel = if (step.target == TranssionSetupGuide.Target.WIDGET_PIN) "Add" else "Open"
                            TextButton(onClick = { onOpen(step.target) }) { Text(buttonLabel) }
                        }
                    }
                }
                Text(
                    "Menu names differ between phone models. Flash cannot check the steps after the first, " +
                        "so it will not tell you they are done.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
