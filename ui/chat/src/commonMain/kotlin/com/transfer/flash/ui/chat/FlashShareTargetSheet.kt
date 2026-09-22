package com.transfer.flash.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * One item in an inbound share payload (from Android ACTION_SEND / SEND_MULTIPLE or Desktop drop).
 */
data class FlashShareItemUi(
    val uri: String,
    val name: String,
    val sizeBytes: Long = 0L,
    val mimeType: String = "*/*",
)

/**
 * Inbound share payload containing files, text, or both.
 */
data class FlashSharePayloadUi(
    val items: List<FlashShareItemUi> = emptyList(),
    val text: String? = null,
)

/**
 * A candidate recipient shown in the share target sheet (paired device, active chat, or nearby device).
 */
data class FlashShareRecipientUi(
    val id: String,
    val name: String,
    val initials: String,
    val subtitle: String? = null,
    val isOnline: Boolean = false,
    val isGroup: Boolean = false,
    val isPaired: Boolean = true,
    val transport: FlashNetworkTransport = FlashNetworkTransport.Lan,
    val deviceKind: FlashDeviceKind = FlashDeviceKind.UNKNOWN,
)

/** Pure helpers backing share target formatting and calculations (JVM-testable). */
object FlashShareTargetMath {

    fun formatItemSummary(payload: FlashSharePayloadUi): String {
        val count = payload.items.size
        val totalBytes = payload.items.sumOf { it.sizeBytes }
        val hasText = !payload.text.isNullOrBlank()

        return when {
            count == 0 && hasText -> "Shared Text"
            count == 1 && !hasText -> payload.items.first().name
            count == 1 && hasText -> "${payload.items.first().name} + message"
            count > 1 && !hasText -> "$count files (${formatBytes(totalBytes)})"
            count > 1 && hasText -> "$count files (${formatBytes(totalBytes)}) + message"
            else -> "Share"
        }
    }

    fun formatItemSubtitle(payload: FlashSharePayloadUi): String {
        val count = payload.items.size
        val hasText = !payload.text.isNullOrBlank()

        return when {
            count == 0 && hasText -> {
                val snippet = payload.text.orEmpty().replace("\n", " ").trim()
                if (snippet.length > 60) snippet.take(57) + "…" else snippet
            }
            count == 1 -> {
                val item = payload.items.first()
                if (item.sizeBytes > 0) formatBytes(item.sizeBytes) else item.mimeType
            }
            count > 1 -> {
                val names = payload.items.take(3).joinToString(", ") { it.name }
                if (count > 3) "$names +${count - 3} more" else names
            }
            else -> ""
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> {
            val kb = bytes / 1024.0
            "${((kb * 10).toLong() / 10.0)} KB"
        }
        bytes < 1024L * 1024L * 1024L -> {
            val mb = bytes / (1024.0 * 1024.0)
            "${((mb * 10).toLong() / 10.0)} MB"
        }
        else -> {
            val gb = bytes / (1024.0 * 1024.0 * 1024.0)
            "${((gb * 10).toLong() / 10.0)} GB"
        }
    }

    fun initialsFor(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val parts = trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
            else -> trimmed.take(2).uppercase()
        }
    }
}

/**
 * Unified Share Target Sheet (Android System Share Target / Desktop File Drop).
 *
 * Displays a summary of the shared content (file count, size, names) and provides an
 * actionable list of existing paired devices, active chats, and discovered nearby devices
 * to immediately initiate a transfer.
 */
@Composable
fun FlashShareTargetSheet(
    payload: FlashSharePayloadUi,
    pairedDevices: List<FlashShareRecipientUi>,
    nearbyDevices: List<FlashShareRecipientUi>,
    recentChats: List<FlashShareRecipientUi> = emptyList(),
    isScanning: Boolean = true,
    onSelectRecipient: (FlashShareRecipientUi) -> Unit,
    onDismiss: () -> Unit,
    onManualConnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors

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
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.accentPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Share,
                            tint = colors.accentPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    FlashText(
                        text = "Share with Flash",
                        style = FlashTheme.typography.headingMedium,
                    )
                }
                IconButton(onClick = onDismiss) {
                    FlashIcon(
                        icon = FlashIcons.Close,
                        contentDescription = "Cancel",
                        tint = colors.textSecondary,
                    )
                }
            }

            // Shared Content Card Preview
            SharedContentPreviewCard(payload = payload)

            // Recipient Sections
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                // Section 1: Paired Devices
                if (pairedDevices.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Paired Devices",
                            count = pairedDevices.size,
                        )
                    }
                    items(pairedDevices, key = { "paired_${it.id}" }) { device ->
                        ShareRecipientRow(
                            recipient = device,
                            onClick = { onSelectRecipient(device) },
                        )
                    }
                }

                // Section 2: Recent Chats
                if (recentChats.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Recent Chats",
                            count = recentChats.size,
                        )
                    }
                    items(recentChats, key = { "chat_${it.id}" }) { chat ->
                        ShareRecipientRow(
                            recipient = chat,
                            onClick = { onSelectRecipient(chat) },
                        )
                    }
                }

                // Section 3: Nearby Discovered Devices
                if (nearbyDevices.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Available Nearby",
                            count = nearbyDevices.size,
                        )
                    }
                    items(nearbyDevices, key = { "nearby_${it.id}" }) { device ->
                        ShareRecipientRow(
                            recipient = device,
                            onClick = { onSelectRecipient(device) },
                        )
                    }
                }

                // Empty / Discovery State
                if (pairedDevices.isEmpty() && nearbyDevices.isEmpty() && recentChats.isEmpty()) {
                    item {
                        EmptyDiscoveryCard(isScanning = isScanning)
                    }
                } else if (isScanning) {
                    item {
                        ScanningIndicatorRow()
                    }
                }

                // Manual Connect Option
                if (onManualConnect != null) {
                    item {
                        ManualConnectRow(onClick = onManualConnect)
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedContentPreviewCard(payload: FlashSharePayloadUi) {
    val colors = FlashTheme.colors
    val icon = when {
        payload.items.any { it.mimeType.startsWith("image/") } -> FlashIcons.Gallery
        payload.items.any { it.mimeType.startsWith("video/") } -> FlashIcons.VideoCall
        payload.items.any { it.mimeType.startsWith("audio/") } -> FlashIcons.Microphone
        payload.items.isNotEmpty() -> FlashIcons.Attach
        else -> FlashIcons.Chat
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp)),
        color = colors.backgroundSurfaceStrong,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FlashSpacing.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.accentPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = icon,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FlashText(
                    text = FlashShareTargetMath.formatItemSummary(payload),
                    style = FlashTheme.typography.bodyDefault,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = FlashShareTargetMath.formatItemSubtitle(payload)
                if (subtitle.isNotBlank()) {
                    FlashText(
                        text = subtitle,
                        style = FlashTheme.typography.metadataEmphasis,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FlashSpacing.space8, bottom = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        FlashText(
            text = title.uppercase(),
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.textSecondary,
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.borderSubtle)
                .padding(horizontal = 6.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = count.toString(),
                style = FlashTheme.typography.metadataEmphasis,
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun ShareRecipientRow(
    recipient: FlashShareRecipientUi,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.backgroundSurfaceStrong)
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            FlashAvatar(
                initials = recipient.initials,
                seed = recipient.name,
                size = FlashDimensions.avatarMd,
            )
            if (recipient.isOnline) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.backgroundSurface)
                        .padding(1.5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(colors.accentPrimary),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FlashText(
                text = recipient.name,
                style = FlashTheme.typography.bodyDefault,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (recipient.isOnline) {
                    val transportText = if (recipient.transport == FlashNetworkTransport.WifiDirect) {
                        "Online · Wi-Fi Direct"
                    } else {
                        "Online · LAN"
                    }
                    FlashText(
                        text = transportText,
                        style = FlashTheme.typography.metadataEmphasis,
                        color = colors.accentPrimary,
                    )
                } else if (!recipient.isPaired) {
                    FlashText(
                        text = "Available nearby · Tap to pair & send",
                        style = FlashTheme.typography.metadataEmphasis,
                        color = colors.textSecondary,
                    )
                } else {
                    FlashText(
                        text = recipient.subtitle ?: "Offline",
                        style = FlashTheme.typography.metadataEmphasis,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        // Action Chip
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (recipient.isOnline) colors.accentPrimary.copy(alpha = 0.15f) else colors.borderSubtle,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlashIcon(
                    icon = if (recipient.isPaired) FlashIcons.Send else FlashIcons.Share,
                    tint = if (recipient.isOnline) colors.accentPrimary else colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
                FlashText(
                    text = if (recipient.isPaired) "Send" else "Pair & Send",
                    style = FlashTheme.typography.metadataEmphasis,
                    color = if (recipient.isOnline) colors.accentPrimary else colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryCard(isScanning: Boolean) {
    val colors = FlashTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.backgroundSurfaceStrong)
            .padding(FlashSpacing.space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashIcon(
            icon = FlashIcons.Nearby,
            tint = colors.textSecondary,
            modifier = Modifier.size(32.dp),
        )
        FlashText(
            text = if (isScanning) "Searching for nearby devices…" else "No devices found",
            style = FlashTheme.typography.bodyDefault,
        )
        FlashText(
            text = "Make sure Flash is open on the target device and connected to the same Wi-Fi network.",
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun ScanningIndicatorRow() {
    val colors = FlashTheme.colors
    val transition = rememberInfiniteTransition()
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space8),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.accentPrimary)
                .alpha(pulseAlpha),
        )
        Spacer(modifier = Modifier.width(8.dp))
        FlashText(
            text = "Scanning local network for devices…",
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun ManualConnectRow(onClick: () -> Unit) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashIcon(
            icon = FlashIcons.Connection,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        FlashText(
            text = "Connect directly by IP address…",
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.accentPrimary,
        )
    }
}
