package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.launch

/**
 * UI representation of a group in the organization directory.
 */
public data class FlashBrowseOrgGroupUi(
    val groupId: String,
    val name: String,
    val department: String,
    val memberCount: Int = 0,
    val requiresApproval: Boolean = false,
    val description: String = "",
)

/**
 * Bottom sheet displaying existing groups in the user's department on the Enterprise Hub.
 * Allows newly added organization members to discover and join existing channels.
 */
@Composable
public fun FlashBrowseOrgGroupsSheet(
    departmentName: String?,
    onDismiss: () -> Unit,
    onLoadGroups: suspend () -> List<FlashBrowseOrgGroupUi>,
    onJoinGroup: (FlashBrowseOrgGroupUi) -> Unit,
    joinedGroupIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var allGroups by remember { mutableStateOf<List<FlashBrowseOrgGroupUi>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var joiningGroupId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        try {
            allGroups = onLoadGroups()
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load department groups"
        } finally {
            isLoading = false
        }
    }

    val filteredGroups = remember(allGroups, searchQuery) {
        if (searchQuery.isBlank()) {
            allGroups
        } else {
            allGroups.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.groupId.contains(searchQuery, ignoreCase = true)
            }
        }
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    FlashText(
                        text = "Department Channels",
                        style = FlashTheme.typography.headingMedium,
                    )
                    FlashText(
                        text = departmentName?.let { "Unit: $it" } ?: "Organization Directory",
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(FlashShapes.chip)
                        .background(colors.accentPrimary.copy(alpha = 0.15f))
                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                ) {
                    FlashText(
                        text = "HUB DIRECTORY",
                        style = FlashTheme.typography.metadataEmphasis,
                        color = colors.accentPrimary,
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text("Search groups by name...", style = FlashTheme.typography.bodyDefault) },
                leadingIcon = { FlashIcon(FlashIcons.Search, contentDescription = "Search") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    ) {
                        CircularProgressIndicator(
                            color = colors.accentPrimary,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                        )
                        FlashText(
                            text = "Querying organization hub...",
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textSecondary,
                        )
                    }
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.backgroundSurfaceSubtle)
                        .padding(FlashSpacing.space16),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                        FlashText(
                            text = "⚠ $errorMessage",
                            style = FlashTheme.typography.metadataEmphasis,
                            color = colors.textError,
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    errorMessage = null
                                    try {
                                        allGroups = onLoadGroups()
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Failed to load department groups"
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                        ) {
                            Text("Retry", color = colors.accentPrimary)
                        }
                    }
                }
            } else if (filteredGroups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = if (searchQuery.isNotBlank()) "No groups match '$searchQuery'" else "No groups registered in this department yet.",
                        style = FlashTheme.typography.bodyDefault,
                        color = colors.textSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    items(filteredGroups, key = { it.groupId }) { group ->
                        val isJoined = joinedGroupIds.contains(group.groupId)
                        val isJoining = joiningGroupId == group.groupId

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.backgroundSurfaceSubtle)
                                .padding(FlashSpacing.space12),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                        ) {
                            FlashAvatar(
                                initials = group.name.take(2).uppercase(),
                                seed = group.name,
                                size = FlashDimensions.avatarSm,
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                FlashText(
                                    text = group.name,
                                    style = FlashTheme.typography.bodyEmphasis,
                                    maxLines = 1,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FlashText(
                                        text = "${group.memberCount} members",
                                        style = FlashTheme.typography.metadataDefault,
                                        color = colors.textSecondary,
                                    )
                                    if (group.requiresApproval) {
                                        FlashText(
                                            text = "• Approval Req.",
                                            style = FlashTheme.typography.metadataDefault,
                                            color = colors.accentSecondary,
                                        )
                                    }
                                }
                            }

                            if (isJoined) {
                                Box(
                                    modifier = Modifier
                                        .clip(FlashShapes.chip)
                                        .background(colors.accentPrimary.copy(alpha = 0.15f))
                                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                                ) {
                                    FlashText(
                                        text = "✓ Joined",
                                        style = FlashTheme.typography.metadataEmphasis,
                                        color = colors.accentPrimary,
                                    )
                                }
                            } else {
                                Button(
                                    enabled = !isJoining,
                                    onClick = {
                                        joiningGroupId = group.groupId
                                        onJoinGroup(group)
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.accentPrimary,
                                        contentColor = colors.backgroundSurface,
                                    ),
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    if (isJoining) {
                                        CircularProgressIndicator(
                                            color = colors.backgroundSurface,
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text(
                                            text = if (group.requiresApproval) "Request" else "Join",
                                            style = FlashTheme.typography.metadataEmphasis,
                                        )
                                    }
                                }
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
                    Text("Close", color = colors.textSecondary)
                }
            }
        }
    }
}
