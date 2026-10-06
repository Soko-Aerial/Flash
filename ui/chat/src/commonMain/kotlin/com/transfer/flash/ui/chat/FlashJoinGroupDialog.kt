package com.transfer.flash.ui.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * UI-054: Join group confirmation dialog and manual link paste entry point.
 */
@Composable
public fun FlashJoinGroupDialog(
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialInviteUrl: String? = null,
    pendingStatusSentence: String? = null,
    onCancelPendingJoin: (() -> Unit)? = null,
    /** The name this device knows the inviter by, or null when it is a stranger (the dialog then says so). */
    resolveInviterName: (com.transfer.flash.core.security.group.GroupInvite) -> String? = { null },
) {
    val colors = FlashTheme.colors
    val clipboardManager = LocalClipboardManager.current
    var inputUrl by remember { mutableStateOf(initialInviteUrl.orEmpty()) }
    val parsedInvite = remember(inputUrl) {
        if (inputUrl.isNotBlank()) FlashGroupInviteJoinMath.parseInviteUrl(inputUrl) else null
    }

    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        modifier = modifier,
        title = {
            if (pendingStatusSentence != null) {
                FlashText(
                    text = "Joining Group",
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
            } else if (parsedInvite != null) {
                FlashText(
                    text = FlashGroupInviteJoinMath.joinConfirmationTitle(parsedInvite.groupName),
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
            } else {
                FlashText(
                    text = "Join with invite link",
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = FlashSpacing.space8),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                if (pendingStatusSentence != null) {
                    // Pending state
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(colors.accentPrimary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Clock,
                            contentDescription = "Pending",
                            tint = colors.accentPrimary,
                        )
                    }
                    FlashText(
                        text = pendingStatusSentence,
                        style = FlashTheme.typography.bodyDefault,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                } else if (parsedInvite != null) {
                    // Decoded preview state
                    FlashAvatar(
                        initials = parsedInvite.groupName.take(2).uppercase().ifEmpty { "GP" },
                        seed = parsedInvite.groupId,
                        size = 56.dp,
                    )
                    FlashText(
                        text = FlashGroupInviteJoinMath.joinConfirmationMessage(
                            parsedInvite.groupName,
                            resolveInviterName(parsedInvite),
                        ),
                        style = FlashTheme.typography.bodyDefault,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    if (parsedInvite.addressHints.isNotEmpty()) {
                        FlashText(
                            text = "${parsedInvite.addressHints.size} network address hint(s) provided",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textTertiary,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    // Link input state
                    FlashText(
                        text = "Paste a Flash group invite link (flash://g/1/...) to join:",
                        style = FlashTheme.typography.bodyDefault,
                        color = colors.textSecondary,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(FlashShapes.bubbleGrouped)
                            .background(colors.backgroundSurfaceSubtle)
                            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.bubbleGrouped)
                            .padding(FlashSpacing.space8),
                    ) {
                        TextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            placeholder = {
                                FlashText(
                                    text = "flash://g/1/...",
                                    style = FlashTheme.typography.bodyDefault,
                                    color = colors.textTertiary,
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Paste Button
                    Row(
                        modifier = Modifier
                            .clip(FlashShapes.chip)
                            .background(colors.backgroundSurfaceSubtle)
                            .clickable {
                                clipboardManager.getText()?.text?.let { pasted ->
                                    if (FlashGroupInviteJoinMath.isInviteUrl(pasted)) {
                                        inputUrl = FlashGroupInviteJoinMath.extractInviteUrl(pasted) ?: pasted
                                    } else {
                                        inputUrl = pasted
                                    }
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
                    if (inputUrl.isNotBlank() && parsedInvite == null) {
                        FlashText(
                            text = "Invalid link: must be a valid flash://g/1/... invite",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textError,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (pendingStatusSentence != null) {
                if (onCancelPendingJoin != null) {
                    TextButton(onClick = onCancelPendingJoin) {
                        androidx.compose.material3.Text("Cancel request", color = colors.textError)
                    }
                }
            } else if (parsedInvite != null) {
                TextButton(onClick = { onJoin(inputUrl) }) {
                    androidx.compose.material3.Text("Join group", color = colors.accentPrimary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Close", color = colors.textSecondary)
            }
        },
    )
}
