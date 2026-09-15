package com.transfer.flash.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Group Phase D: add trusted peers to an existing group. Shares [FlashCreateGroupPeerUi] and the
 * six-member cap math with creation; the host pre-filters peers who are already members, so the
 * sheet counts only the NEW additions against the cap. Calls [onAdd] once with the selection.
 */
@Composable
public fun FlashAddMembersSheet(
    availablePeers: List<FlashCreateGroupPeerUi>,
    onDismiss: () -> Unit,
    onAdd: (memberIds: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val canAdd = selected.isNotEmpty()
    FlashSheetHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FlashSpacing.space16)
                .padding(bottom = FlashSpacing.space24),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            FlashText(text = "Add members", style = FlashTheme.typography.headingMedium)
            FlashText(
                text = "Only paired devices can be added.",
                style = FlashTheme.typography.metadataEmphasis,
            )
            if (availablePeers.isEmpty()) {
                FlashText(
                    text = "No paired devices available to add.",
                    style = FlashTheme.typography.bodyDefault,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(availablePeers, key = { it.id }) { peer ->
                        val isSelected = peer.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (isSelected) selected - peer.id else selected + peer.id
                                }
                                .padding(vertical = FlashSpacing.space4)
                                .semantics { role = Role.Checkbox },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                        ) {
                            FlashAvatar(
                                initials = peer.initials,
                                seed = peer.name,
                                size = FlashDimensions.avatarSm,
                            )
                            FlashText(
                                text = peer.name,
                                style = FlashTheme.typography.bodyDefault,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            if (isSelected) {
                                FlashIcon(icon = FlashIcons.Check, contentDescription = "Selected")
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = colors.textSecondary)
                }
                TextButton(
                    enabled = canAdd,
                    onClick = {
                        onAdd(selected)
                        onDismiss()
                    },
                ) {
                    Text("Add", color = if (canAdd) colors.accentPrimary else colors.textTertiary)
                }
            }
        }
    }
}

/**
 * Group Phase D: leave-group confirmation. Leaving is explicit and destructive to membership
 * (history stays), so it asks once. The host performs the leave and navigates back on success.
 */
@Composable
public fun FlashLeaveGroupDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        title = { FlashText(text = "Leave group?", style = FlashTheme.typography.headingMedium) },
        text = {
            FlashText(
                text = "You will stop receiving messages from this group. Your chat history stays on this device.",
                style = FlashTheme.typography.bodyDefault,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Leave", color = colors.accentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}
