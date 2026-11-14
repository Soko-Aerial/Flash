package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.protocol.GroupPolicy
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
    val department: String? = null,
    val isDirectoryMember: Boolean = false,
)

/**
 * Pure creation rules for the group sheet. The local device always counts as one member, so at
 * most [MAX_REMOTE_MEMBERS] peers may be selected. The cap is the signed (v2) group limit, 20 devices (ADR-044 V2);
 * the repository refuses a group above six whose invitees are not all on a build that supports signed groups, and
 * names the reason, so the sheet does not need to know which peers are on which build.
 */
public object FlashCreateGroupMath {
    public const val MAX_MEMBERS: Int = GroupPolicy.MAX_MEMBERS_V2
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
@Composable
public fun FlashCreateGroupSheet(
    peers: List<FlashCreateGroupPeerUi>,
    onDismiss: () -> Unit,
    onCreate: (title: String, memberIds: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    isEnterpriseEnrolled: Boolean = false,
    initialUseHub: Boolean = false,
    onCreateWithHub: ((title: String, memberIds: Set<String>, linkToHub: Boolean) -> Unit)? = null,
    onFindDevicesClick: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    var title by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var linkToHub by rememberSaveable { mutableStateOf(initialUseHub) }
    val canCreate = FlashCreateGroupMath.canCreate(title, selected)
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
            FlashText(text = "New group", style = typography.headingMedium)

            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = "Group name",
                    style = typography.metadataEmphasis,
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FlashShapes.composerInput)
                        .background(colors.composerInputBackground)
                        .border(
                            width = FlashDimensions.borderHairline,
                            color = colors.borderDefault,
                            shape = FlashShapes.composerInput,
                        )
                        .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = FlashSpacing.space4),
                        singleLine = true,
                        textStyle = typography.bodyDefault.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accentFill),
                        decorationBox = { innerTextField ->
                            if (title.isEmpty()) {
                                FlashText(
                                    text = "Enter a group name",
                                    style = typography.bodyDefault,
                                    color = colors.textTertiary,
                                    maxLines = 1,
                                )
                            }
                            innerTextField()
                        },
                    )
                    if (title.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { title = "" }
                                .semantics { role = Role.Button; contentDescription = "Clear group name" },
                            contentAlignment = Alignment.Center,
                        ) {
                            FlashIcon(
                                icon = FlashIcons.Close,
                                contentDescription = null,
                                size = FlashDimensions.iconSm,
                                tint = colors.textSecondary,
                            )
                        }
                    }
                }
            }

            if (isEnterpriseEnrolled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { linkToHub = !linkToHub }
                        .padding(vertical = FlashSpacing.space4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        FlashText(
                            text = "Link to Organization Hub",
                            style = typography.bodyDefault,
                        )
                        FlashText(
                            text = "Relays new messages to remote & offline coworkers",
                            style = typography.metadataDefault,
                            color = colors.textSecondary,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = linkToHub,
                        onCheckedChange = { linkToHub = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onAccentFill,
                            checkedTrackColor = colors.accentFill,
                        ),
                    )
                }
            }

            FlashText(
                text = FlashCreateGroupMath.countLabel(selected.size),
                style = typography.metadataEmphasis,
            )

            if (peers.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    FlashText(
                        text = "Pair a device first — groups are built from trusted peers only.",
                        style = typography.bodyDefault,
                        color = colors.textSecondary,
                    )
                    if (onFindDevicesClick != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = FlashDimensions.minTouchTarget)
                                .clip(FlashShapes.chip)
                                .background(colors.accentFill)
                                .clickable(onClick = onFindDevicesClick)
                                .semantics { role = Role.Button; contentDescription = "Find devices" },
                            contentAlignment = Alignment.Center,
                        ) {
                            FlashText(
                                text = "Find devices",
                                style = typography.bodyEmphasis,
                                color = colors.onAccentFill,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 280.dp),
                ) {
                    items(peers, key = { it.id }) { peer ->
                        val isSelected = peer.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = FlashDimensions.minTouchTarget)
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
                            Column(modifier = Modifier.weight(1f)) {
                                FlashText(
                                    text = peer.name,
                                    style = typography.bodyDefault,
                                    maxLines = 1,
                                )
                                if (peer.department != null) {
                                    FlashText(
                                        text = peer.department,
                                        style = typography.metadataDefault,
                                        color = colors.textLink,
                                    )
                                }
                            }
                            if (isSelected) {
                                FlashIcon(
                                    icon = FlashIcons.Check,
                                    contentDescription = "Selected",
                                    tint = colors.accentFill,
                                )
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
                            if (onCreateWithHub != null) {
                                onCreateWithHub(normalized, selected, linkToHub)
                            } else {
                                onCreate(normalized, selected)
                            }
                        }
                    },
                ) {
                    Text("Create", color = if (canCreate) colors.textLink else colors.textTertiary)
                }
            }
        }
    }
}
