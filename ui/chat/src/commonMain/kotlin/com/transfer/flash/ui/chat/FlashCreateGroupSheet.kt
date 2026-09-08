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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

/** One selectable trusted peer in the create-group sheet. */
public data class FlashCreateGroupPeerUi(
    val id: String,
    val name: String,
    val initials: String,
)

/**
 * Pure creation rules for the group sheet. The local device always counts as one member, so at
 * most [MAX_REMOTE_MEMBERS] peers may be selected — Flash groups cap at six devices total.
 */
public object FlashCreateGroupMath {
    public const val MAX_MEMBERS: Int = 6
    public const val MAX_REMOTE_MEMBERS: Int = MAX_MEMBERS - 1

    public fun normalizedTitle(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && it.length <= 80 }

    public fun canCreate(name: String, selectedPeers: Set<String>): Boolean =
        normalizedTitle(name) != null &&
            selectedPeers.isNotEmpty() &&
            selectedPeers.size <= MAX_REMOTE_MEMBERS

    public fun selectionAllowed(selectedCount: Int): Boolean = selectedCount < MAX_REMOTE_MEMBERS

    /** "3 of 5 members chosen" — the local device is the sixth slot. */
    public fun countLabel(selectedCount: Int): String =
        "${(selectedCount + 1).coerceIn(1, MAX_MEMBERS)} of $MAX_MEMBERS members chosen"
}

/**
 * Group creation surface (group Phase 1A): a Flash-styled bottom sheet with a title field and a
 * trusted-peer multi-select. Only paired peers are offered by the host, so membership is
 * fail-closed by construction. Calls [onCreate] once with the normalized title and the selected
 * peer ids (the local device is implicit and never listed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun FlashCreateGroupSheet(
    peers: List<FlashCreateGroupPeerUi>,
    onDismiss: () -> Unit,
    onCreate: (title: String, memberIds: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    var title by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val canCreate = FlashCreateGroupMath.canCreate(title, selected)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.backgroundSurface,
        shape = RoundedCornerShape(
            topStart = FlashShapes.radius24,
            topEnd = FlashShapes.radius24,
        ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FlashSpacing.space16)
                .padding(bottom = FlashSpacing.space24),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            FlashText(text = "New group", style = FlashTheme.typography.headingMedium)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Group name") },
                modifier = Modifier.fillMaxWidth(),
            )

            FlashText(
                text = FlashCreateGroupMath.countLabel(selected.size),
                style = FlashTheme.typography.metadataEmphasis,
            )

            if (peers.isEmpty()) {
                FlashText(
                    text = "Pair a device first — groups are built from trusted peers only.",
                    style = FlashTheme.typography.bodyDefault,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(peers, key = { it.id }) { peer ->
                        val isSelected = peer.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = isSelected || FlashCreateGroupMath.selectionAllowed(selected.size),
                                ) {
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
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Cancel", color = colors.textSecondary)
                }
                androidx.compose.material3.TextButton(
                    enabled = canCreate,
                    onClick = {
                        FlashCreateGroupMath.normalizedTitle(title)?.let { normalized ->
                            onCreate(normalized, selected)
                        }
                    },
                ) {
                    Text("Create", color = if (canCreate) colors.accentPrimary else colors.textTertiary)
                }
            }
        }
    }
}
