@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

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
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.EnterpriseDirectoryGroup
import com.transfer.flash.core.enterprise.EnterpriseJoinRequest
import com.transfer.flash.core.enterprise.EnterpriseJoinResult
import com.transfer.flash.core.enterprise.EnterpriseJoinStatus
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.settings.FlashHubStatusMath
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.launch

/**
 * Bottom sheet displaying existing groups in the user's department on the Enterprise Hub.
 * Allows newly added organization members to discover and join existing channels.
 */
@Composable
public fun FlashBrowseOrgGroupsSheet(
    departmentName: String?,
    onDismiss: () -> Unit,
    onLoadGroups: suspend () -> FlashResult<List<EnterpriseDirectoryGroup>>,
    /** Asks to join; the sheet turns the answer into text and a state (CLI-04). The host navigates only on JOINED. */
    onJoinGroup: suspend (EnterpriseDirectoryGroup) -> FlashResult<EnterpriseJoinResult>,
    joinedGroupIds: Set<String> = emptySet(),
    /** Group ids this device already asked to join and is waiting on. */
    pendingGroupIds: Set<String> = emptySet(),
    /** Called once after a request was answered JOINED so the host can open the new conversation. */
    onJoined: (EnterpriseDirectoryGroup) -> Unit = {},
    /** Admin only: the requests waiting for [groupId]. Null hides the admin controls. */
    onLoadJoinRequests: (suspend (String) -> FlashResult<List<EnterpriseJoinRequest>>)? = null,
    /** Admin only: approve or decline one request. */
    onDecideJoinRequest: (suspend (EnterpriseJoinRequest, Boolean) -> FlashResult<EnterpriseJoinResult>)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var allGroups by remember { mutableStateOf<List<EnterpriseDirectoryGroup>>(emptyList()) }
    var localPending by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localJoined by remember { mutableStateOf<Set<String>>(emptySet()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var openRequestsFor by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var joiningGroupId by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        isLoading = true
        errorMessage = null
        when (val res = onLoadGroups()) {
            is FlashResult.Success -> allGroups = res.value
            is FlashResult.Failure -> errorMessage = FlashHubStatusMath.hubErrorText(res.error)
        }
        isLoading = false
    }

    LaunchedEffect(Unit) { load() }

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

            feedback?.let { text ->
                FlashText(
                    text = text,
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.accentPrimary,
                )
            }

            // CLI-16 (ERROR-266): said plainly until group trust through the hub directory exists.
            FlashText(
                text = FlashHubStatusMath.GROUP_LIMITATION_TEXT,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
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
                            text = errorMessage.orEmpty(),
                            style = FlashTheme.typography.metadataEmphasis,
                            color = colors.textError,
                        )
                        TextButton(
                            onClick = { scope.launch { load() } },
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
                        val isJoined = joinedGroupIds.contains(group.groupId) || localJoined.contains(group.groupId)
                        val isPending = !isJoined && (pendingGroupIds.contains(group.groupId) || localPending.contains(group.groupId))
                        val isJoining = joiningGroupId == group.groupId
                        val badges = FlashHubStatusMath.groupBadges(group, departmentName)
                        val canReview = onLoadJoinRequests != null && onDecideJoinRequest != null && badges.adminPendingText != null

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.backgroundSurfaceSubtle)
                                .padding(FlashSpacing.space12),
                            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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
                                    FlashText(
                                        text = listOfNotNull(badges.memberText, badges.approvalText, badges.departmentText).joinToString("  -  "),
                                        style = FlashTheme.typography.metadataDefault,
                                        color = colors.textSecondary,
                                        maxLines = 2,
                                    )
                                }

                                when {
                                    isJoined -> Box(
                                        modifier = Modifier
                                            .clip(FlashShapes.chip)
                                            .background(colors.accentPrimary.copy(alpha = 0.15f))
                                            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                                    ) {
                                        FlashText(
                                            text = FlashHubStatusMath.joinButtonLabel(group.requiresApproval, joined = true, pending = false),
                                            style = FlashTheme.typography.metadataEmphasis,
                                            color = colors.accentPrimary,
                                        )
                                    }
                                    isPending -> Box(
                                        modifier = Modifier
                                            .clip(FlashShapes.chip)
                                            .background(colors.backgroundSurfaceStrong)
                                            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                                    ) {
                                        FlashText(
                                            text = FlashHubStatusMath.joinButtonLabel(group.requiresApproval, joined = false, pending = true),
                                            style = FlashTheme.typography.metadataEmphasis,
                                            color = colors.textSecondary,
                                        )
                                    }
                                    else -> Button(
                                        enabled = joiningGroupId == null,
                                        onClick = {
                                            joiningGroupId = group.groupId
                                            feedback = null
                                            scope.launch {
                                                val result = onJoinGroup(group)
                                                val note = FlashHubStatusMath.joinFeedback(group.name, result)
                                                feedback = note.text
                                                when (note.kind) {
                                                    FlashHubStatusMath.JoinFeedbackKind.JOINED -> {
                                                        localJoined = localJoined + group.groupId
                                                        onJoined(group)
                                                    }
                                                    FlashHubStatusMath.JoinFeedbackKind.PENDING -> localPending = localPending + group.groupId
                                                    else -> Unit
                                                }
                                                joiningGroupId = null
                                            }
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
                                                text = FlashHubStatusMath.joinButtonLabel(group.requiresApproval, joined = false, pending = false),
                                                style = FlashTheme.typography.metadataEmphasis,
                                            )
                                        }
                                    }
                                }
                            }

                            if (canReview && onLoadJoinRequests != null && onDecideJoinRequest != null) {
                                val open = openRequestsFor == group.groupId
                                FlashText(
                                    text = if (open) "Hide requests" else badges.adminPendingText.orEmpty(),
                                    style = FlashTheme.typography.metadataEmphasis,
                                    color = colors.accentPrimary,
                                    modifier = Modifier
                                        .clip(FlashShapes.chip)
                                        .clickable(onClickLabel = "Review requests") { openRequestsFor = if (open) null else group.groupId }
                                        .padding(vertical = FlashSpacing.space4),
                                )
                                if (open) {
                                    FlashJoinRequestsPanel(
                                        groupId = group.groupId,
                                        onLoad = onLoadJoinRequests,
                                        onDecide = onDecideJoinRequest,
                                    )
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


/** The requests waiting for one group, with Approve and Decline (admin only). */
@Composable
private fun FlashJoinRequestsPanel(
    groupId: String,
    onLoad: suspend (String) -> FlashResult<List<EnterpriseJoinRequest>>,
    onDecide: suspend (EnterpriseJoinRequest, Boolean) -> FlashResult<EnterpriseJoinResult>,
) {
    val colors = FlashTheme.colors
    val scope = rememberCoroutineScope()
    var requests by remember(groupId) { mutableStateOf<List<EnterpriseJoinRequest>?>(null) }
    var error by remember(groupId) { mutableStateOf<String?>(null) }
    var busyId by remember(groupId) { mutableStateOf<String?>(null) }

    LaunchedEffect(groupId) {
        when (val res = onLoad(groupId)) {
            is FlashResult.Success -> requests = res.value
            is FlashResult.Failure -> error = FlashHubStatusMath.hubErrorText(res.error)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
        error?.let { FlashText(text = it, style = FlashTheme.typography.metadataDefault, color = colors.textError) }
        val list = requests
        if (list == null && error == null) {
            FlashText(text = "Loading requests", style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
        } else if (list != null && list.isEmpty()) {
            FlashText(text = "No requests waiting.", style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
        }
        list?.forEach { request ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashText(
                    text = FlashHubStatusMath.joinRequestTitle(request.displayName),
                    style = FlashTheme.typography.bodyDefault,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                val busy = busyId == request.requestId
                for ((label, approve) in listOf("Approve" to true, "Decline" to false)) {
                    TextButton(
                        enabled = busyId == null,
                        onClick = {
                            busyId = request.requestId
                            scope.launch {
                                when (val res = onDecide(request, approve)) {
                                    is FlashResult.Success -> requests = requests?.filterNot { it.requestId == request.requestId }
                                    is FlashResult.Failure -> error = FlashHubStatusMath.hubErrorText(res.error)
                                }
                                busyId = null
                            }
                        },
                    ) {
                        Text(
                            text = if (busy) "..." else label,
                            color = if (approve) colors.accentPrimary else colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}
