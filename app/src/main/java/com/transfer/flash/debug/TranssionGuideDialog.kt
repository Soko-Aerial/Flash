package com.transfer.flash.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * One-time set-up helper for Transsion phones (HIB-03). Lists [TranssionSetupGuide.pending]
 * steps, each with an "Open" button where a screen exists.
 *
 * Styled with Flash theme tokens ([FlashTheme], [FlashShapes], [FlashText]) so it inherits the
 * app's light/dark palette and dynamic accent without Material 3 default purple leakage.
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
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasWidget = com.transfer.flash.widget.FlashAppWidgetProvider.hasActiveWidget(context)
    val steps = TranssionSetupGuide.pending(ignoringBatteryOptimizations, hasWidget)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(FlashShapes.radius24),
        containerColor = colors.backgroundSurface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = {
            FlashText(
                text = "Keep Flash connected with the screen off",
                style = typography.headingMedium,
                color = colors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                FlashText(
                    text = "Your phone pauses apps a few seconds after the screen turns off. " +
                        "Do these once so Flash can keep receiving messages and calls.",
                    style = typography.bodyDefault,
                    color = colors.textSecondary,
                )
                steps.forEachIndexed { index, step ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(FlashShapes.attachment)
                            .background(colors.backgroundSurfaceSubtle)
                            .border(
                                width = FlashDimensions.borderHairline,
                                color = colors.borderSubtle,
                                shape = FlashShapes.attachment,
                            )
                            .padding(FlashSpacing.space12),
                        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                    ) {
                        FlashText(
                            text = "${index + 1}. ${step.title}",
                            style = typography.bodyEmphasis,
                            color = colors.textPrimary,
                        )
                        FlashText(
                            text = step.body,
                            style = typography.captionDefault,
                            color = colors.textSecondary,
                        )
                        if (step.target != TranssionSetupGuide.Target.NONE) {
                            val buttonLabel = if (step.target == TranssionSetupGuide.Target.WIDGET_PIN) "Add" else "Open"
                            Spacer(modifier = Modifier.height(FlashSpacing.space4))
                            TranssionGuideButton(
                                label = buttonLabel,
                                description = "$buttonLabel: ${step.title}",
                                onClick = { onOpen(step.target) },
                            )
                        }
                    }
                }
                FlashText(
                    text = "Menu names differ between phone models. Flash cannot check the steps after the first, " +
                        "so it will not tell you they are done.",
                    style = typography.metadataDefault,
                    color = colors.textTertiary,
                )
            }
        },
        confirmButton = {
            TranssionGuideButton(
                label = "Done",
                description = "Done",
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun TranssionGuideButton(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Box(
        modifier = Modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.button)
            .background(colors.accentFill)
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = label,
            style = typography.captionEmphasis,
            color = colors.onAccentFill,
        )
    }
}
